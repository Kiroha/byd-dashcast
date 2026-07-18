package com.byd.dashcast.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerVersionTest {
    @Test
    fun `same beta version is not offered again`() {
        assertFalse(OtaVersionPolicy.isNewer("1.6.137-beta", "1.6.137-beta", 578))
    }

    @Test
    fun `numeric semantic segments are compared numerically`() {
        assertTrue(OtaVersionPolicy.isNewer("1.6.137-beta", "1.6.99-beta", 500))
        assertFalse(OtaVersionPolicy.isNewer("1.6.99-beta", "1.6.137-beta", 578))
    }

    @Test
    fun `build suffix only wins when base version is equal and code is newer`() {
        assertTrue(OtaVersionPolicy.isNewer("1.6.137-build579", "1.6.137-beta", 578))
        assertFalse(OtaVersionPolicy.isNewer("1.6.137-build578", "1.6.137-beta", 578))
        assertFalse(OtaVersionPolicy.isNewer("1.6.136-build999", "1.6.137-beta", 578))
    }
}