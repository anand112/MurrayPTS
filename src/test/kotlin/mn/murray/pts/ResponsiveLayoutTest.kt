package mn.murray.pts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** The 375px / 768px audit: two width breakpoints only, 44px touch targets, sideways scroll kept inside its own box. */
class ResponsiveLayoutTest {
    private val css = File("src/main/resources/static/css/app.css").readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
    private fun block(width: Int): String {
        val start = css.indexOf("@media (max-width: ${width}px) {")
        assertTrue(start >= 0, "no ${width}px block")
        var depth = 0
        for (i in css.indexOf('{', start) until css.length) {
            if (css[i] == '{') depth++ else if (css[i] == '}') { depth--; if (depth == 0) return css.substring(start, i) }
        }
        error("unclosed ${width}px block")
    }

    @Test
    fun `only the 900px and 520px width breakpoints exist`() {
        val widths = Regex("@media \\((?:max|min)-width: (\\d+)px\\)").findAll(css).map { it.groupValues[1] }.toList()
        assertEquals(listOf("900", "520"), widths)
    }

    @Test
    fun `touch targets are 44px and nothing scrolls the page sideways`() {
        val tablet = block(900)
        listOf(
            "min-height: 44px; min-width: 44px;", "width: 44px; height: 44px;", "body.app-body { overflow-x: clip; }",
            ".card-panel:has(table), .board-results:has(table) { overflow-x: auto; }",
            "scroll-snap-type: x mandatory", ".todo-board { scroll-snap-type: x mandatory; }",
            "scroll-padding-bottom: calc(76px + env(safe-area-inset-bottom))",
        ).forEach { assertTrue(it in tablet, "the 900px block lost: $it") }
        assertTrue("40px" !in tablet, "a 40px control is under the 44px target")
    }

    @Test
    fun `phone keeps actions under the thumb and honours the safe area`() {
        val phone = block(520)
        listOf(".modal-actions { position: sticky; bottom: 0;", ".page-actions > * { flex: 1 1 auto; }",
            "input, select, textarea { font-size: var(--fs-13); }", "env(safe-area-inset-bottom")
            .forEach { assertTrue(it in phone, "the 520px block lost: $it") }
        listOf("DashboardCommon.kt", "Views.kt").forEach {
            assertTrue("viewport-fit=cover" in File("src/main/kotlin/mn/murray/pts/$it").readText(),
                "$it: without viewport-fit=cover, env(safe-area-inset-bottom) is always 0")
        }
    }
}
