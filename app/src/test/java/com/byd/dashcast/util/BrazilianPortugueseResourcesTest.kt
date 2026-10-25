package com.byd.dashcast.util

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class BrazilianPortugueseResourcesTest {
    private val resources = File("src/main/res").takeIf { it.isDirectory } ?: File("app/src/main/res")
    private val format = Regex("%(?:\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z%]")

    private fun entries(directory: String): Map<String, Element> {
        val result = linkedMapOf<String, Element>()
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        for (file in File(resources, directory).listFiles()!!.filter { it.extension == "xml" }) {
            val children = factory.newDocumentBuilder().parse(file).documentElement.childNodes
            for (index in 0 until children.length) {
                val element = children.item(index) as? Element ?: continue
                if (element.tagName !in setOf("string", "string-array", "plurals") || element.getAttribute("translatable") == "false") continue
                val key = element.tagName + ":" + element.getAttribute("name")
                assertNull("Duplicate resource $key in $directory", result.put(key, element))
            }
        }
        return result
    }

    private fun items(element: Element): List<Element> {
        val children = element.childNodes
        return (0 until children.length).mapNotNull { children.item(it) as? Element }
    }

    private fun formats(text: String) = format.findAll(text).map { it.value }.groupingBy { it }.eachCount()

    @Test fun `every current translatable resource and positional argument has a Brazilian translation`() {
        val base = entries("values")
        val portuguese = entries("values-pt-rBR")
        assertEquals("Missing or obsolete Brazilian resource keys", base.keys, portuguese.keys)
        for ((key, source) in base) {
            val translated = portuguese.getValue(key)
            assertTrue("Empty Brazilian translation: $key", translated.textContent.isNotBlank())
            assertEquals("Formatting arguments: $key", formats(source.textContent), formats(translated.textContent))
            if (source.tagName != "string") {
                val sourceItems = items(source)
                val translatedItems = items(translated)
                assertEquals("Indexed item count: $key", sourceItems.size, translatedItems.size)
                sourceItems.zip(translatedItems).forEachIndexed { index, (a, b) ->
                    assertEquals("Plural quantity $key/$index", a.getAttribute("quantity"), b.getAttribute("quantity"))
                    assertEquals("Indexed format $key/$index", formats(a.textContent), formats(b.textContent))
                    assertTrue("Empty indexed translation $key/$index", b.textContent.isNotBlank())
                }
            }
        }
    }
}
