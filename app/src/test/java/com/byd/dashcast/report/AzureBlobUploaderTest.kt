package com.byd.dashcast.report

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.MalformedURLException
import java.net.HttpURLConnection
import java.net.URL

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

    @Test
    fun `transient commit failures retry without reuploading blocks`() {
        val responses = ArrayDeque(listOf(503, 503, 201))
        val attempts = mutableListOf<FakeConnection>()
        val delays = mutableListOf<Long>()

        AzureBlobUploader.commitWithRetry(
            "https://blob.example/container/report.zip?comp=blocklist&sas",
            listOf("block-1"),
            AzureBlobUploader.ConnectionFactory { _, _ ->
                FakeConnection(responses.removeFirst()).also { attempts += it }
            },
            AzureBlobUploader.RetrySleeper { delays += it },
        )

        assertEquals(3, attempts.size)
        assertEquals(listOf(1500L, 3000L), delays)
        assertTrue(attempts.all { it.requestBody().contains("<Latest>block-1</Latest>") })
    }

    @Test
    fun `permanent commit rejection is not retried`() {
        var attempts = 0

        try {
            AzureBlobUploader.commitWithRetry(
                "https://blob.example/container/report.zip?comp=blocklist&sas",
                listOf("block-1"),
                AzureBlobUploader.ConnectionFactory { _, _ ->
                    attempts++
                    FakeConnection(403)
                },
                AzureBlobUploader.RetrySleeper { throw AssertionError("must not sleep") },
            )
            throw AssertionError("403 commit unexpectedly succeeded")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("HTTP 403"))
        }

        assertEquals(1, attempts)
    }

    private class FakeConnection(private val status: Int) :
        HttpURLConnection(URL("https://blob.example")) {
        private val output = ByteArrayOutputStream()

        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getOutputStream() = output
        override fun getResponseCode(): Int = status
        override fun getErrorStream() =
            ByteArrayInputStream("<Error><Code>Transient</Code></Error>".toByteArray())

        fun requestBody(): String = output.toString(Charsets.UTF_8.name())
    }
}