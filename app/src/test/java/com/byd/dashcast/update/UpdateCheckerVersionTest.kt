package com.byd.dashcast.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
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

    @Test
    fun `release tags use a strict grammar`() {
        assertTrue(OtaVersionPolicy.isValidReleaseVersion("1.8.47-beta"))
        assertTrue(OtaVersionPolicy.isValidReleaseVersion("1.7.0"))
        assertFalse(OtaVersionPolicy.isValidReleaseVersion("1-not-a-version"))
        assertFalse(OtaVersionPolicy.isValidReleaseVersion("1.8.x"))
    }

    @Test
    fun `semantic maximum does not depend on release creation order`() {
        assertTrue(OtaVersionPolicy.compareVersions("1.8.47-beta", "1.8.9-beta") > 0)
        assertTrue(OtaVersionPolicy.compareVersions("1.8.47", "1.8.47-beta") > 0)
        assertTrue(OtaVersionPolicy.compareVersions("1.8.47-rc2", "1.8.47-beta") > 0)
    }

    @Test
    fun `stable promotion supersedes the beta with the same base version`() {
        assertTrue(OtaVersionPolicy.isNewer("1.8.47", "1.8.47-beta", 637))
        assertFalse(OtaVersionPolicy.isNewer("1.8.47-beta", "1.8.47", 638))
    }

    @Test
    fun `signer comparison is order independent and rejects a different signer`() {
        val first = byteArrayOf(1, 2, 3)
        val second = byteArrayOf(4, 5, 6)

        assertTrue(UpdateChecker.sameByteSet(
            arrayOf(first, second), arrayOf(second, first)))
        assertFalse(UpdateChecker.sameByteSet(
            arrayOf(first), arrayOf(second)))
        assertFalse(UpdateChecker.sameByteSet(null, arrayOf(first)))
    }
}