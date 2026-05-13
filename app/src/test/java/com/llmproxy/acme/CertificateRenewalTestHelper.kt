package com.llmproxy.acme

import kotlinx.coroutines.delay
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Test helper for deterministic validation of the certificate renewal flow.
 *
 * Provides:
 * - [FakeDnsProvider]: configurable DNS provider with success / failure / timeout modes.
 * - [CertExpiryScenario]: preset expiry states to drive renewal-trigger logic.
 * - [expiryForScenario]: converts a [CertExpiryScenario] into a concrete [Instant].
 * - Factory helpers for building preset [com.llmproxy.acme.AcmeCertManager.AcmeResult] values
 *   so that test assertions can be written against known outcomes.
 *
 * **Usage (unit tests only — never instantiated in production code):**
 * ```
 * val helper = CertificateRenewalTestHelper()
 * val dns = helper.FakeDnsProvider(mode = CertificateRenewalTestHelper.DnsMode.SUCCESS)
 * // Wire dns into an AcmeCertManager and call requestCertificate(…)
 * ```
 *
 * All test helpers live in `test/` and must never be referenced from `main/`.
 */
class CertificateRenewalTestHelper {

    // -------------------------------------------------------------------------
    // DNS provider modes
    // -------------------------------------------------------------------------

    /** Controls how [FakeDnsProvider] responds to TXT-record operations. */
    enum class DnsMode {
        /** All operations succeed immediately. */
        SUCCESS,
        /** [addTxtRecord] throws an [IOException], simulating a DNS API error. */
        FAILURE,
        /** [addTxtRecord] suspends indefinitely, simulating a network timeout. */
        TIMEOUT,
    }

    /**
     * A [DnsProvider] implementation for unit and integration tests.
     *
     * Records every add/remove call so assertions can verify that ACME challenge
     * records are created and cleaned up correctly.
     */
    inner class FakeDnsProvider(var mode: DnsMode = DnsMode.SUCCESS) : DnsProvider {

        /** All TXT records added via [addTxtRecord] since the last [reset]. */
        val addedRecords = mutableListOf<Pair<String, String>>()

        /** All TXT records removed via [removeTxtRecord] since the last [reset]. */
        val removedRecords = mutableListOf<Pair<String, String>>()

        override suspend fun addTxtRecord(name: String, content: String) {
            when (mode) {
                DnsMode.FAILURE -> throw IOException("Simulated DNS API failure for addTxtRecord($name)")
                DnsMode.TIMEOUT -> delay(Long.MAX_VALUE) // suspends until test coroutine is cancelled
                DnsMode.SUCCESS -> addedRecords += name to content
            }
        }

        override suspend fun removeTxtRecord(name: String, content: String) {
            // Always record removals regardless of mode — cleanup must run even after failures.
            removedRecords += name to content
        }

        /** Resets recorded calls. Useful for multi-scenario tests. */
        fun reset() {
            addedRecords.clear()
            removedRecords.clear()
        }
    }

    // -------------------------------------------------------------------------
    // Certificate expiry scenarios
    // -------------------------------------------------------------------------

    /**
     * Canonical expiry states used to drive renewal-trigger tests.
     *
     * | Scenario       | Description                                             |
     * |----------------|---------------------------------------------------------|
     * | VALID          | 90 days remaining — well outside the renewal threshold. |
     * | EXPIRING_SOON  | 20 days remaining — within the 30-day renewal window.   |
     * | EXPIRED        | Already expired 1 day ago.                              |
     */
    enum class CertExpiryScenario {
        VALID,
        EXPIRING_SOON,
        EXPIRED,
    }

    /**
     * Returns an [Instant] corresponding to [scenario].
     *
     * Inject this value into [com.llmproxy.util.SslCertGenerator] test doubles to control
     * what [com.llmproxy.acme.AcmeCertManager.shouldRenewWithin] returns without hitting
     * real ACME endpoints.
     *
     * ```
     * val expiry = helper.expiryForScenario(CertExpiryScenario.EXPIRING_SOON)
     * // assert: shouldRenewWithin(30) == true when cert expires in 20 days
     * ```
     */
    fun expiryForScenario(scenario: CertExpiryScenario): Instant = when (scenario) {
        CertExpiryScenario.VALID -> Instant.now().plus(90, ChronoUnit.DAYS)
        CertExpiryScenario.EXPIRING_SOON -> Instant.now().plus(20, ChronoUnit.DAYS)
        CertExpiryScenario.EXPIRED -> Instant.now().minus(1, ChronoUnit.DAYS)
    }

    /**
     * Evaluates whether a certificate with the given [expiresAt] instant should be renewed
     * within [thresholdDays] days, using the same logic as
     * [com.llmproxy.acme.AcmeCertManager.shouldRenewWithin].
     *
     * Use this in unit tests to verify renewal-trigger decisions without instantiating a real
     * [com.llmproxy.acme.AcmeCertManager].
     *
     * ```
     * val expiry = helper.expiryForScenario(CertExpiryScenario.EXPIRING_SOON)
     * assertTrue(helper.shouldRenewWithin(expiry, 30))
     * ```
     */
    fun shouldRenewWithin(expiresAt: Instant?, thresholdDays: Long): Boolean {
        if (expiresAt == null) return true
        val threshold = Instant.now().plus(thresholdDays, ChronoUnit.DAYS)
        return expiresAt.isBefore(threshold)
    }

    // -------------------------------------------------------------------------
    // AcmeResult factories
    // -------------------------------------------------------------------------

    /**
     * Builds a success [AcmeCertManager.AcmeResult] with a configurable expiry.
     * Defaults to 90 days in the future (a freshly issued cert).
     */
    fun successResult(
        expiresAt: Instant = Instant.now().plus(90, ChronoUnit.DAYS),
    ): AcmeCertManager.AcmeResult = AcmeCertManager.AcmeResult(
        success = true,
        expiresAt = expiresAt,
        warning = null,
    )

    /**
     * Builds a failure [AcmeCertManager.AcmeResult] with a configurable warning message.
     * The [expiresAt] field reflects the existing certificate that remains in use.
     */
    fun failureResult(
        warning: String = "Simulated ACME failure.",
        expiresAt: Instant? = Instant.now().plus(20, ChronoUnit.DAYS),
    ): AcmeCertManager.AcmeResult = AcmeCertManager.AcmeResult(
        success = false,
        expiresAt = expiresAt,
        warning = warning,
    )
}
