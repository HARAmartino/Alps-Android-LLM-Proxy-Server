package com.llmproxy.server

import com.llmproxy.util.SslCertGenerator
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

class SslContextLoader(
    private val sslCertGenerator: SslCertGenerator,
) {
    fun loadSslContext(): Pair<SSLContext, KeyStore> {
        val material = sslCertGenerator.ensureCertificateFiles()
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(material.keyStore, material.password)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(keyManagerFactory.keyManagers, null, null)
        return sslContext to material.keyStore
    }
}
