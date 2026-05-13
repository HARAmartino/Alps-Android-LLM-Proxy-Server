package com.llmproxy.util

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.core.content.FileProvider
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit

class SslCertGenerator(
    private val context: Context,
) {
    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private val passwordPreferences: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            KEYSTORE_PASSWORD_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    @Synchronized
    fun ensureCertificateFiles(): SslMaterial {
        val keyStorePassword = getOrCreateKeyStorePassword()
        val sslDirectory = File(context.filesDir, SSL_DIRECTORY).apply { mkdirs() }
        val certificateFile = File(sslDirectory, CERTIFICATE_FILE_NAME)
        val privateKeyFile = File(sslDirectory, PRIVATE_KEY_FILE_NAME)
        val keyStoreFile = File(sslDirectory, KEYSTORE_FILE_NAME)

        if (!certificateFile.exists() || !privateKeyFile.exists() || !keyStoreFile.exists()) {
            generateCertificate(certificateFile, privateKeyFile, keyStoreFile, keyStorePassword)
        }

        val keyStore = KeyStore.getInstance(KEYSTORE_TYPE).apply {
            keyStoreFile.inputStream().use { load(it, keyStorePassword) }
        }

        return SslMaterial(
            keyStore = keyStore,
            certificateFile = certificateFile,
            privateKeyFile = privateKeyFile,
            keyStoreFile = keyStoreFile,
            password = keyStorePassword,
        )
    }

    fun createExportIntent(): Intent {
        val certificateFile = ensureCertificateFiles().certificateFile
        val certificateUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            certificateFile,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/x-x509-ca-cert"
            putExtra(Intent.EXTRA_STREAM, certificateUri)
            putExtra(Intent.EXTRA_SUBJECT, "LLM Proxy self-signed certificate")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun hasCertificateFiles(): Boolean {
        val sslDirectory = File(context.filesDir, SSL_DIRECTORY)
        return File(sslDirectory, CERTIFICATE_FILE_NAME).exists() &&
            File(sslDirectory, PRIVATE_KEY_FILE_NAME).exists() &&
            File(sslDirectory, KEYSTORE_FILE_NAME).exists()
    }

    private fun generateCertificate(
        certificateFile: File,
        privateKeyFile: File,
        keyStoreFile: File,
        keyStorePassword: CharArray,
    ) {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048)
        }.generateKeyPair()

        val now = Date()
        val notAfter = Date(now.time + TimeUnit.DAYS.toMillis(3650))
        val subject = X500Name("CN=Android LLM Proxy Server")
        val builder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(System.currentTimeMillis()),
            now,
            notAfter,
            subject,
            keyPair.public,
        ).apply {
            val extensionUtils = JcaX509ExtensionUtils()
            addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            addExtension(Extension.subjectKeyIdentifier, false, extensionUtils.createSubjectKeyIdentifier(keyPair.public))
            addExtension(Extension.authorityKeyIdentifier, false, extensionUtils.createAuthorityKeyIdentifier(keyPair.public))
            addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
            addExtension(
                Extension.extendedKeyUsage,
                false,
                org.bouncycastle.asn1.x509.ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth),
            )
            addExtension(
                Extension.subjectAlternativeName,
                false,
                GeneralNames(
                    arrayOf(
                        GeneralName(GeneralName.dNSName, "localhost"),
                        GeneralName(GeneralName.iPAddress, "127.0.0.1"),
                    )
                ),
            )
        }

        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(keyPair.private)
        val certificate: X509Certificate = JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(builder.build(signer))

        FileWriter(certificateFile).use { writer ->
            JcaPEMWriter(writer).use { pemWriter ->
                pemWriter.writeObject(certificate)
            }
        }

        FileWriter(privateKeyFile).use { writer ->
            JcaPEMWriter(writer).use { pemWriter ->
                pemWriter.writeObject(keyPair.private)
            }
        }

        val keyStore = KeyStore.getInstance(KEYSTORE_TYPE).apply {
            load(null)
            setKeyEntry(KEY_ALIAS, keyPair.private, keyStorePassword, arrayOf(certificate))
        }

        FileOutputStream(keyStoreFile).use { outputStream ->
            keyStore.store(outputStream, keyStorePassword)
        }
    }

    private fun getOrCreateKeyStorePassword(): CharArray {
        val existing = passwordPreferences.getString(KEYSTORE_PASSWORD_KEY, null)
        if (!existing.isNullOrBlank()) {
            return existing.toCharArray()
        }

        val randomBytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val encoded = java.util.Base64.getEncoder().encodeToString(randomBytes)
        passwordPreferences.edit().putString(KEYSTORE_PASSWORD_KEY, encoded).apply()
        return encoded.toCharArray()
    }

    data class SslMaterial(
        val keyStore: KeyStore,
        val certificateFile: File,
        val privateKeyFile: File,
        val keyStoreFile: File,
        val password: CharArray,
    )

    companion object {
        const val KEY_ALIAS = "proxy"
        private const val KEYSTORE_TYPE = "PKCS12"
        private const val SSL_DIRECTORY = "ssl"
        private const val CERTIFICATE_FILE_NAME = "proxy-cert.crt"
        private const val PRIVATE_KEY_FILE_NAME = "proxy-key.pem"
        private const val KEYSTORE_FILE_NAME = "proxy-keystore.p12"
        private const val KEYSTORE_PASSWORD_FILE = "ssl_keystore_passwords"
        private const val KEYSTORE_PASSWORD_KEY = "proxy_keystore_password"
    }
}
