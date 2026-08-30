package com.byd.dashcast.report

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.MalformedURLException

class AzureBlobUploaderTest {

    @Test
    fun `upload failure messages never expose the sas`() {
        val sas = "sv=2024&sp=cw&sig=SECRET-SIGNATURE"
        val error = MalformedURLException(
            "no protocol: blob.example/container?comp=block&$sas"
        )

        val message = AzureBlobUploader.safeFailureMessage(error, sas)

        assertTrue(message.contains("MalformedURLException"))
        assertTrue(message.contains("<sas>"))
        assertFalse(message.contains("SECRET-SIGNATURE"))
    }
}