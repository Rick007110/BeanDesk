package nl.beandesk.app.icon

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The AppIcon enum and the launcher aliases in the manifest must describe the same icons. */
class AppIconManifestTest {
    private val android = "http://schemas.android.com/apk/res/android"

    // Unit tests run with the app module as working directory.
    private val aliases: List<Element> = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(File("src/main/AndroidManifest.xml"))
        .getElementsByTagName("activity-alias")
        .let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    private fun Element.attr(name: String) = getAttributeNS(android, name)

    @Test
    fun `every icon has an alias and every alias an icon`() {
        assertEquals(
            AppIcon.entries.map { ".${it.aliasName}" }.toSet(),
            aliases.map { it.attr("name") }.toSet(),
        )
    }

    @Test
    fun `only the default icon is enabled in the manifest`() {
        val enabled = aliases.filter { it.attr("enabled") == "true" }.map { it.attr("name") }
        assertEquals(listOf(".${AppIcon.DEFAULT.aliasName}"), enabled)
    }

    @Test
    fun `each alias is a launcher entry with its own icon`() {
        AppIcon.entries.forEach { icon ->
            val alias = aliases.single { it.attr("name") == ".${icon.aliasName}" }
            val mipmap = "ic_launcher_${icon.name.lowercase()}"
            assertEquals("@mipmap/$mipmap", alias.attr("icon"))
            assertTrue(File("src/main/res/mipmap-anydpi/$mipmap.xml").exists(), "Missing $mipmap.xml")
            val categories = alias.getElementsByTagName("category")
            assertTrue(
                (0 until categories.length).any {
                    (categories.item(it) as Element).attr("name") == "android.intent.category.LAUNCHER"
                },
                "${icon.aliasName} is no launcher entry",
            )
        }
    }
}
