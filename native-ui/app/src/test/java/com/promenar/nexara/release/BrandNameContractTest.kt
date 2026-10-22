package com.promenar.nexara.release

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import org.junit.Test

/**
 * 品牌名预留：界面文案只经 XML 实体 &brand; 引用品牌名，各资源文件的实体声明必须一致，
 * 改名时只需修改这些声明（字标另由 brandWordmark 实体给出）。
 */
class BrandNameContractTest {
    private val resRoot = Path.of("app/src/main/res")
    private val entity = Regex("""<!ENTITY\s+(\w+)\s+"([^"]*)">""")

    private fun valueFiles(): List<Path> = Files.walk(resRoot).use { paths ->
        paths.filter { it.name.endsWith(".xml") && it.parent.name.startsWith("values") }.toList()
    }

    private fun entities(text: String): Map<String, String> =
        entity.findAll(text).associate { it.groupValues[1] to it.groupValues[2] }

    @Test
    fun `引用品牌实体的文件都声明了相同的品牌名`() {
        val main = entities(resRoot.resolve("values/strings.xml").readText())
        val brand = main.getValue("brand")
        assertThat(main.getValue("brandWordmark")).isNotEmpty()

        val users = valueFiles().filter { it.readText().contains("&brand;") }
        assertThat(users.size).isAtLeast(8)
        users.forEach { file ->
            assertWithMessage(file.toString()).that(entities(file.readText())["brand"]).isEqualTo(brand)
        }
    }

    @Test
    fun `界面文案不直接写品牌名`() {
        val brand = entities(resRoot.resolve("values/strings.xml").readText()).getValue("brand")
        val word = Regex("""(?<![A-Za-z_])${Regex.escape(brand)}(?![A-Za-z_])""")
        valueFiles().forEach { file ->
            val body = file.readText().lines().filterNot { it.contains("<!ENTITY") }.joinToString("\n")
            assertWithMessage(file.toString()).that(word.containsMatchIn(body)).isFalse()
        }
    }

    @Test
    fun `品牌名与字标资源不参与翻译`() {
        val strings = resRoot.resolve("values/strings.xml").readText()
        assertThat(strings).contains("""<string name="brand_name" translatable="false">&brand;</string>""")
        assertThat(strings).contains("""<string name="brand_wordmark" translatable="false">&brandWordmark;</string>""")
        valueFiles().filter { it.parent.name != "values" }.forEach { file ->
            assertWithMessage(file.toString()).that(file.readText()).doesNotContain("name=\"brand_")
        }
    }
}
