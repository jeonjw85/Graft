package kr.jjw.graft.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalizationParityTest {
    private fun loadStrings(path: String): Map<String, String> {
        val file = File(path)
        assertTrue(file.exists(), "missing string resource file: ${file.absolutePath}")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        val map = linkedMapOf<String, String>()
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            val name = node.attributes.getNamedItem("name").nodeValue
            map[name] = node.textContent
        }
        return map
    }

    @Test
    fun `english and korean string resources have matching non-empty keys`() {
        val en = loadStrings("src/main/res/values/strings.xml")
        val ko = loadStrings("src/main/res/values-ko/strings.xml")

        assertFalse(en.isEmpty())
        assertEquals(en.keys, ko.keys, "en and ko strings.xml keys must stay in sync")
        assertEquals("연결", ko["tab_connection"])
        assertEquals("%1\$s에 연결됨", ko["status_connected"])
        for ((key, value) in ko) {
            assertTrue(value.isNotBlank(), "empty Korean translation for $key")
        }
    }
}
