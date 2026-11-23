package mn.murray.pts

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.pow

/**
 * WCAG 2.2 AA guard rails, light and dark. The colour pairs below are the ones the app paints text and
 * status on; each is resolved from the real token sheets per theme and held to 4.5:1 for text (1.4.3) and
 * 3:1 for focus rings (1.4.11). The markup checks pin the names, landmarks and heading levels the audit added.
 */
class AccessibilityAuditTest {
    private val comments = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
    private val css = (File("src/main/resources/static/css/base.css").readText() + "\n" +
        File("src/main/resources/static/css/app.css").readText()).replace(comments, " ")
    private val kotlinSrc = "src/main/kotlin/mn/murray/pts"

    private fun tokens(selectors: Set<String>): Map<String, String> {
        val out = mutableMapOf<String, String>()
        Regex("([^{}]+)\\{([^{}]*)\\}").findAll(css).forEach { m ->
            if (m.groupValues[1].trim() in selectors)
                Regex("(--[\\w-]+)\\s*:\\s*([^;]+)").findAll(m.groupValues[2]).forEach { out[it.groupValues[1]] = it.groupValues[2].trim() }
        }
        return out
    }
    private val light = tokens(setOf(":root", "html"))
    private val dark = light + tokens(setOf("html[data-theme=\"dark\"]"))

    private fun resolve(v: String, t: Map<String, String>, depth: Int = 0): String {
        val m = Regex("var\\((--[\\w-]+)\\)").matchEntire(v.trim()) ?: return v.trim()
        check(depth < 8) { "token loop at $v" }
        return resolve(t[m.groupValues[1]] ?: error("${m.groupValues[1]} is not defined"), t, depth + 1)
    }
    private fun lum(hex: String): Double {
        val h = hex.removePrefix("#").let { if (it.length == 3) it.map { c -> "$c$c" }.joinToString("") else it }
        require(h.length == 6) { "not a solid colour: $hex" }
        val ch = (0..2).map { h.substring(it * 2, it * 2 + 2).toInt(16) / 255.0 }
            .map { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch[0] + 0.7152 * ch[1] + 0.0722 * ch[2]
    }
    private fun ratio(fg: String, bg: String, t: Map<String, String>): Double {
        val (a, b) = listOf(lum(resolve(fg, t)), lum(resolve(bg, t))).sortedDescending()
        return (a + .05) / (b + .05)
    }

    private val textPairs = listOf(
        "var(--title)" to "var(--card-bg)", "var(--muted)" to "var(--card-bg)", "var(--label)" to "var(--card-bg)",
        "var(--muted)" to "var(--main-bg)", "var(--note)" to "var(--card-bg)", "var(--input-text)" to "var(--input-bg)",
        "#fff" to "var(--btn-bg)", "#fff" to "var(--btn-bg-hover)", "#fff" to "var(--danger)",
        "var(--danger-ink)" to "var(--card-bg)", "var(--danger-ink)" to "var(--danger-soft)",
        "var(--ok-ink)" to "var(--ok-soft)", "var(--info-ink)" to "var(--info-soft)",
        "var(--plan-ink)" to "var(--plan-soft)", "var(--warn-ink)" to "var(--warn-soft)",
        "var(--warn-ink)" to "var(--card-bg)", "var(--ok-ink)" to "var(--card-bg)",
    )

    @Test
    fun `text and status colours meet 4_5 to 1 on both themes`() {
        listOf("light" to light, "dark" to dark).forEach { (name, t) ->
            textPairs.forEach { (fg, bg) ->
                val r = ratio(fg, bg, t)
                assertTrue(r >= 4.5, "$name: $fg on $bg is %.2f:1, under AA".format(r))
            }
        }
    }

    @Test
    fun `focus ring stands out from the card and the page on both themes`() {
        listOf("light" to light, "dark" to dark).forEach { (name, t) ->
            listOf("var(--card-bg)", "var(--main-bg)").forEach { bg ->
                val r = ratio("var(--focus-ring)", bg, t)
                assertTrue(r >= 3.0, "$name: focus ring on $bg is %.2f:1, under 3:1".format(r))
            }
        }
        assertTrue(Regex(":focus-visible[^{]*\\{[^}]*var\\(--focus-ring\\)").containsMatchIn(css), "focus-visible lost its ring")
    }

    @Test
    fun `text never wears a fill token that only clears contrast behind white`() {
        assertFalse(Regex("(?<![\\w-])color:\\s*var\\(--danger(-strong)?\\)").containsMatchIn(css),
            "red text must use --danger-ink, which lightens on the dark card")
    }

    @Test
    fun `reduced motion is honoured`() {
        assertTrue("prefers-reduced-motion: reduce" in css, "no reduced-motion block")
    }

    @Test
    fun `icon-only controls, landmarks and heading levels`() {
        val shell = File("$kotlinSrc/DashboardCommon.kt").readText()
        assertTrue("attributes[\"aria-label\"] = \"Unpin \${project.name}\"" in shell, "the × unpin button has no name")
        assertTrue(Regex("nav \\{\\s*attributes\\[\"aria-label\"] = \"Main\"").containsMatchIn(shell), "the sidebar nav is unnamed")
        assertTrue("main(\"main\")" in shell, "the page lost its main landmark")
        listOf("Settings.kt", "AccessControl.kt").forEach {
            val src = File("$kotlinSrc/$it").readText()
            assertFalse("h3(\"set-section\")" in src, "$it: a section under the page h1 skips h2")
        }
    }
}
