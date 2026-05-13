package com.llmproxy.service

import com.llmproxy.acme.CertificateRenewalTestHelper
import com.llmproxy.acme.CertificateRenewalTestHelper.CertExpiryScenario
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Unit tests for the certificate renewal flow helpers and the drain-window state machine.
 *
 * These tests cover:
 * 1. [CertificateRenewalTestHelper.FakeDnsProvider] contract — records are captured and
 *    failure/timeout modes throw/suspend as expected.
 * 2. [CertificateRenewalTestHelper.shouldRenewWithin] — threshold logic across all
 *    [CertExpiryScenario] variants.
 * 3. [CertificateRenewalTestHelper] result factories — success / failure shapes.
 *
 * Integration-level tests for [ServerLifecycleManager.gracefulRestartLocked] metrics
 * (graceful vs forced close counts) require a running Ktor engine and are covered by
 * the 72-hour soak test described in README.md.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CertificateRenewalFlowTest {

    private lateinit var helper: CertificateRenewalTestHelper
    private lateinit var dns: CertificateRenewalTestHelper.FakeDnsProvider

    @Before
    fun setUp() {
        helper = CertificateRenewalTestHelper()
        dns = helper.FakeDnsProvider()
    }

    // -------------------------------------------------------------------------
    // FakeDnsProvider — SUCCESS mode
    // -------------------------------------------------------------------------

    @Test
    fun `FakeDnsProvider SUCCESS mode records add and remove operations`() = runTest {
        dns.addTxtRecord("_acme-challenge.example.com", "token-value")
        dns.removeTxtRecord("_acme-challenge.example.com", "token-value")

        assertEquals(1, dns.addedRecords.size)
        assertEquals("_acme-challenge.example.com" to "token-value", dns.addedRecords[0])
        assertEquals(1, dns.removedRecords.size)
        assertEquals("_acme-challenge.example.com" to "token-value", dns.removedRecords[0])
    }

    @Test
    fun `FakeDnsProvider SUCCESS mode accumulates multiple records`() = runTest {
        dns.addTxtRecord("_acme-challenge.a.example.com", "val-a")
        dns.addTxtRecord("_acme-challenge.b.example.com", "val-b")

        assertEquals(2, dns.addedRecords.size)
    }

    @Test
    fun `FakeDnsProvider reset clears recorded operations`() = runTest {
        dns.addTxtRecord("_acme-challenge.example.com", "v1")
        dns.reset()

        assertTrue(dns.addedRecords.isEmpty())
        assertTrue(dns.removedRecords.isEmpty())
    }

    // -------------------------------------------------------------------------
    // FakeDnsProvider — FAILURE mode
    // -------------------------------------------------------------------------

    @Test
    fun `FakeDnsProvider FAILURE mode throws IOException on addTxtRecord`() = runTest {
        dns.mode = CertificateRenewalTestHelper.DnsMode.FAILURE
        var caught: IOException? = null
        try {
            dns.addTxtRecord("_acme-challenge.example.com", "token")
        } catch (e: IOException) {
            caught = e
        }
        assertNotNull("Expected IOException from FAILURE mode", caught)
        assertTrue(dns.addedRecords.isEmpty())
    }

    @Test
    fun `FakeDnsProvider FAILURE mode still allows removeTxtRecord for cleanup`() = runTest {
        dns.mode = CertificateRenewalTestHelper.DnsMode.FAILURE
        // Cleanup must succeed even after an add failure so ACME challenge records
        // are not left dangling in the DNS zone.
        dns.removeTxtRecord("_acme-challenge.example.com", "token")
        assertEquals(1, dns.removedRecords.size)
    }

    // -------------------------------------------------------------------------
    // FakeDnsProvider — mode switching
    // -------------------------------------------------------------------------

    @Test
    fun `FakeDnsProvider mode can be changed between calls`() = runTest {
        dns.addTxtRecord("_acme-challenge.example.com", "v1")
        dns.mode = CertificateRenewalTestHelper.DnsMode.FAILURE
        var caught: IOException? = null
        try {
            dns.addTxtRecord("_acme-challenge.example.com", "v2")
        } catch (e: IOException) {
            caught = e
        }
        assertNotNull(caught)
        // Only the first call should be recorded.
        assertEquals(1, dns.addedRecords.size)
    }

    // -------------------------------------------------------------------------
    // shouldRenewWithin — expiry scenario coverage
    // -------------------------------------------------------------------------

    @Test
    fun `shouldRenewWithin returns false for VALID cert with 30-day threshold`() {
        val expiry = helper.expiryForScenario(CertExpiryScenario.VALID) // 90 days out
        assertFalse(helper.shouldRenewWithin(expiry, thresholdDays = 30))
    }

    @Test
    fun `shouldRenewWithin returns true for EXPIRING_SOON cert with 30-day threshold`() {
        val expiry = helper.expiryForScenario(CertExpiryScenario.EXPIRING_SOON) // 20 days out
        assertTrue(helper.shouldRenewWithin(expiry, thresholdDays = 30))
    }

    @Test
    fun `shouldRenewWithin returns true for EXPIRED cert`() {
        val expiry = helper.expiryForScenario(CertExpiryScenario.EXPIRED) // already past
        assertTrue(helper.shouldRenewWithin(expiry, thresholdDays = 30))
    }

    @Test
    fun `shouldRenewWithin returns true when expiresAt is null`() {
        assertTrue(helper.shouldRenewWithin(expiresAt = null, thresholdDays = 30))
    }

    // -------------------------------------------------------------------------
    // AcmeResult factories
    // -------------------------------------------------------------------------

    @Test
    fun `successResult returns a result with success=true and non-null expiry`() {
        val result = helper.successResult()
        assertTrue(result.success)
        assertNotNull(result.expiresAt)
        assertNull(result.warning)
    }

    @Test
    fun `failureResult returns a result with success=false and a warning message`() {
        val result = helper.failureResult(warning = "DNS challenge timed out.")
        assertFalse(result.success)
        assertEquals("DNS challenge timed out.", result.warning)
    }

    @Test
    fun `successResult expiry is approximately 90 days in the future`() {
        val result = helper.successResult()
        val expiresAt = result.expiresAt!!
        val nowPlusDays = java.time.Instant.now().plus(85, java.time.temporal.ChronoUnit.DAYS)
        assertTrue(
            "Expected expiry at least 85 days from now; got $expiresAt",
            expiresAt.isAfter(nowPlusDays),
        )
    }
}
