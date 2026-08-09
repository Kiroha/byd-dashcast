package com.byd.dashcast.report

import com.byd.dashcast.report.ReportDelivery.Route
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ReportDelivery.route — the transport decision, pinned as a pure function.
 *
 * Six screens used to make this choice themselves and drifted apart doing it. These cases are the
 * contract that replaces that drift; they are also the only part of the funnel that can be verified
 * without a configured build machine, which is exactly why the decision was extracted.
 */
class ReportDeliveryTest {

    private val small = 1024L
    private val huge = ReportDelivery.TELEGRAM_MAX_BYTES + 1

    @Test
    fun `the bot is preferred while the artefact fits`() {
        // It lands in the topic the maintainer already watches and it carries the caption; Azure
        // has neither. So size, not availability, is what demotes it.
        assertEquals(Route.TELEGRAM, ReportDelivery.route(true, true, small))
        assertEquals(Route.TELEGRAM, ReportDelivery.route(true, false, small))
    }

    @Test
    fun `an artefact over the ceiling goes to the container`() {
        assertEquals(Route.AZURE, ReportDelivery.route(true, true, huge))
    }

    @Test
    fun `the container takes over when the bot is not configured`() {
        assertEquals(Route.AZURE, ReportDelivery.route(false, true, small))
        assertEquals(Route.AZURE, ReportDelivery.route(false, true, huge))
    }

    @Test
    fun `no exit when nothing is configured`() {
        assertEquals(Route.NONE, ReportDelivery.route(false, false, small))
    }

    @Test
    fun `no exit for an oversized artefact with no container`() {
        // The important one: the bot IS configured, so a naive check would send it and get a
        // rejection after the whole upload. The caller must be told up front instead.
        assertEquals(Route.NONE, ReportDelivery.route(true, false, huge))
    }

    @Test
    fun `the ceiling is exclusive at the boundary`() {
        assertEquals(Route.TELEGRAM,
            ReportDelivery.route(true, false, ReportDelivery.TELEGRAM_MAX_BYTES - 1))
        assertEquals(Route.NONE,
            ReportDelivery.route(true, false, ReportDelivery.TELEGRAM_MAX_BYTES))
    }

    @Test
    fun `the ceiling stays under Telegram's real limit`() {
        // ApkExtractionPolicy is dimensioned around the same assumption; if this ever drifts above
        // 50 MB the bot starts rejecting uploads after transferring them in full.
        org.junit.Assert.assertTrue(ReportDelivery.TELEGRAM_MAX_BYTES < 50L * 1024 * 1024)
    }
}
