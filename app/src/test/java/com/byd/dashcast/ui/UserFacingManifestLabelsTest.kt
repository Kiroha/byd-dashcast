package com.byd.dashcast.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class UserFacingManifestLabelsTest {

    @Test
    fun `log and hotspot activities use localized labels`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "app/src/main/AndroidManifest.xml").isFile }
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(File(root, "app/src/main/AndroidManifest.xml"))
        val activities = document.getElementsByTagName("activity")
        val labels = mutableMapOf<String, String>()
        for (index in 0 until activities.length) {
            val activity = activities.item(index) as Element
            labels[activity.getAttributeNS(ANDROID_NAMESPACE, "name")] =
                activity.getAttributeNS(ANDROID_NAMESPACE, "label")
        }

        assertEquals("@string/log_title", labels[".ui.log.LogActivity"])
        assertEquals("@string/hotspot_title", labels[".ui.hotspot.HotspotActivity"])
    }

    companion object {
        private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}