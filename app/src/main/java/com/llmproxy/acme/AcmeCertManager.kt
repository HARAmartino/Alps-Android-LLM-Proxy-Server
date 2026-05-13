package com.llmproxy.acme

import android.content.Context
import com.llmproxy.util.Logger
import com.llmproxy.util.SslCertGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.shredzone.acme4j.Account
import org.shredzone.acme4j.AccountBuilder
import org.shredzone.acme4j.Session
import org.shredzone.acme4j.Status
import org.shredzone.acme4j.challenge.Dns01Challenge
import org.shredzone.acme4j.exception.AcmeConflictException
import org.shredzone.acme4j.util.CSRBuilder
import org.shredzone.acme4j.util.KeyPairUtils
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.time.Instant
import java.time.temporal.ChronoUnit

class AcmeCertManager(
    private val context: Context,
    private val sslCertGenerator: SslCertGenerator,
    private val dnsProvider: DnsProvider,
) {
    suspend fun requestCertificate(domain: String): AcmeResult = withContext(Dispatchers.IO) {
        val normalizedDomain = domain.trim().lowercase().trim('.')
        require(normalizedDomain.isNotBlank()) { "Domain is required for Let's Encrypt." }

        runCatching {
            // ACME state machine:
            // 1) Register account (or reuse existing key/account)
            // 2) Open order for domain
            // 3) Solve DNS-01 authorization(s)
            // 4) Finalize order with CSR
            // 5) Download cert chain and atomically switch server TLS material
            val session = Session(LETS_ENCRYPT_DIRECTORY)
            val account = resolveAccount(session)
            val order = account.newOrder().domain(normalizedDomain).create()
            authorizeDnsChallenges(order.authorizations)
            val domainKeyPair = loadOrCreateKeyPair(File(acmeDirectory(), DOMAIN_KEY_FILE_NAME))
            val csr = CSRBuilder().apply {
                addDomain(normalizedDomain)
                sign(domainKeyPair)
            }
            order.execute(csr.encoded)
            waitForOrderValid(order)
            val certificate = order.certificate ?: error("ACME order completed without certificate")
            val chain = certificate.certificateChain
            sslCertGenerator.installCertificate(
                privateKey = domainKeyPair.private,
                certificateChain = chain,
                source = SslCertGenerator.CERT_SOURCE_LETS_ENCRYPT,
            )
            AcmeResult(
                success = true,
                expiresAt = chain.firstOrNull()?.notAfter?.toInstant(),
                warning = null,
            )
        }.getOrElse { error ->
            Logger.e("AcmeCertManager", "ACME flow failed; reverting to self-signed certificate", error)
            sslCertGenerator.resetToSelfSigned()
            AcmeResult(
                success = false,
                expiresAt = sslCertGenerator.certificateExpiresAt(),
                warning = "Let's Encrypt failed (${error.message ?: "unknown error"}). Using self-signed certificate.",
            )
        }
    }

    suspend fun shouldRenewWithin(days: Long): Boolean = withContext(Dispatchers.IO) {
        val expiresAt = sslCertGenerator.certificateExpiresAt() ?: return@withContext true
        val threshold = Instant.now().plus(days, ChronoUnit.DAYS)
        expiresAt.isBefore(threshold)
    }

    private fun resolveAccount(session: Session): Account {
        val accountKeyPair = loadOrCreateKeyPair(File(acmeDirectory(), ACCOUNT_KEY_FILE_NAME))
        return try {
            AccountBuilder()
                .agreeToTermsOfService()
                .useKeyPair(accountKeyPair)
                .create(session)
        } catch (_: AcmeConflictException) {
            AccountBuilder()
                .onlyExisting()
                .useKeyPair(accountKeyPair)
                .create(session)
        }
    }

    private suspend fun authorizeDnsChallenges(authorizations: List<org.shredzone.acme4j.Authorization>) {
        authorizations.forEach { authorization ->
            if (authorization.status == Status.VALID) return@forEach
            val challenge = authorization.findChallenge(Dns01Challenge.TYPE) as? Dns01Challenge
                ?: error("DNS-01 challenge missing for ${authorization.identifier.domain}")
            val recordName = "_acme-challenge.${authorization.identifier.domain}"
            val recordValue = challenge.digest

            dnsProvider.addTxtRecord(recordName, recordValue)
            try {
                challenge.trigger()
                waitForChallengeValid(challenge)
            } finally {
                runCatching { dnsProvider.removeTxtRecord(recordName, recordValue) }
                    .onFailure { Logger.e("AcmeCertManager", "Failed to cleanup TXT record", it) }
            }
        }
    }

    private suspend fun waitForChallengeValid(challenge: Dns01Challenge) {
        repeat(CHALLENGE_POLL_ATTEMPTS) {
            challenge.update()
            when (challenge.status) {
                Status.VALID -> return
                Status.INVALID -> error("DNS-01 challenge marked invalid by ACME server.")
                else -> delay(CHALLENGE_POLL_DELAY_MS)
            }
        }
        error("DNS propagation timeout for DNS-01 challenge.")
    }

    private suspend fun waitForOrderValid(order: org.shredzone.acme4j.Order) {
        repeat(ORDER_POLL_ATTEMPTS) {
            order.update()
            when (order.status) {
                Status.VALID -> return
                Status.INVALID -> error("ACME order became invalid.")
                else -> delay(ORDER_POLL_DELAY_MS)
            }
        }
        error("Timed out waiting for ACME order finalization.")
    }

    private fun loadOrCreateKeyPair(file: File): KeyPair {
        file.parentFile?.mkdirs()
        if (file.exists()) {
            FileReader(file).use { reader ->
                return KeyPairUtils.readKeyPair(reader)
            }
        }
        val generated = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        FileWriter(file).use { writer ->
            KeyPairUtils.writeKeyPair(generated, writer)
        }
        return generated
    }

    private fun acmeDirectory(): File = File(context.filesDir, "ssl/acme").apply { mkdirs() }

    data class AcmeResult(
        val success: Boolean,
        val expiresAt: Instant?,
        val warning: String?,
    )

    companion object {
        private const val LETS_ENCRYPT_DIRECTORY = "acme://letsencrypt.org"
        private const val ACCOUNT_KEY_FILE_NAME = "account.pem"
        private const val DOMAIN_KEY_FILE_NAME = "domain.pem"
        private const val CHALLENGE_POLL_ATTEMPTS = 30
        private const val CHALLENGE_POLL_DELAY_MS = 5_000L
        private const val ORDER_POLL_ATTEMPTS = 30
        private const val ORDER_POLL_DELAY_MS = 4_000L
    }
}
