package mn.murray.pts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The shared text hierarchy, read from the stylesheets rather than from a screenshot.
 *
 * Stage 132 supplied the font stack and type scale. Stage 148 makes the components use them consistently:
 * one readable body line box, compact controls, clear heading levels, quieter metadata and empty states
 * without italics. Colours remain theme tokens so these rules apply unchanged in light and dark mode.
 */
class TypographyTest {

    private val baseCss = File("src/main/resources/static/css/base.css").readText()
    private val appCss = File("src/main/resources/static/css/app.css").readText()
    private val comments = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)

    private fun code(text: String): String = comments.replace(text, " ")

    /** The declarations on one exact selector; the surrounding brace keeps a prefix from matching. */
    private fun rule(css: String, selector: String): Map<String, String> {
        val match = Regex("(?:^|[{}])\\s*" + Regex.escape(selector) + "\\s*\\{([^{}]*)\\}")
            .find(code(css))
        assertTrue(match != null, "missing typography rule: $selector")
        return Regex("([\\w-]+)\\s*:\\s*([^;]+)")
            .findAll(match!!.groupValues[1])
            .associate { it.groupValues[1] to it.groupValues[2].trim() }
    }

    @Test
    fun `the page and controls share one readable baseline`() {
        val body = rule(baseCss, "body")
        assertEquals("var(--font-ui)", body["font-family"])
        assertEquals("var(--fs-11)", body["font-size"])
        assertEquals("1.5", body["line-height"])
        assertEquals("var(--title)", body["color"])

        val controls = rule(baseCss, "button, input, select, textarea")
        assertEquals("inherit", controls["font-family"], "a control must not fall back to the browser's face")
        assertEquals("1.4", controls["line-height"], "text inside controls needs its own compact line box")
        assertEquals("inherit", controls["color"], "control text must follow the active theme")
    }

    @Test
    fun `headings and descriptions keep a deliberate hierarchy`() {
        val loginTitle = rule(baseCss, "h1")
        assertEquals("var(--fs-15)", loginTitle["font-size"])
        assertEquals("800", loginTitle["font-weight"])
        assertEquals("1.2", loginTitle["line-height"])
        assertEquals("-.025em", loginTitle["letter-spacing"])
        assertEquals("1.3", rule(baseCss, "h2, h3")["line-height"])

        val pageTitle = rule(appCss, ".page-head h1")
        assertEquals("var(--fs-15)", pageTitle["font-size"])
        assertEquals("800", pageTitle["font-weight"])
        assertEquals("var(--title)", pageTitle["color"])

        val sub = rule(appCss, ".page-head h1 .page-sub")
        assertEquals("var(--fs-7)", sub["font-size"])
        assertEquals("500", sub["font-weight"])
        assertEquals("1.4", sub["line-height"])
        assertEquals("var(--muted)", sub["color"])

        val section = rule(appCss, ".card-panel h3, .fc-completed-head h3")
        assertEquals("var(--fs-12)", section["font-size"])
        assertEquals("700", section["font-weight"])
        assertEquals("-.01em", section["letter-spacing"])
        assertEquals("var(--fs-14)", rule(appCss, ".modal-wide h3")["font-size"])
    }

    @Test
    fun `labels placeholders help and field errors read as one form system`() {
        val label = rule(baseCss, "label")
        assertEquals("var(--sp-2)", label["margin-bottom"])
        assertEquals("var(--fs-5)", label["font-size"])
        assertEquals("1.35", label["line-height"])
        assertEquals("uppercase", label["text-transform"])
        assertEquals("var(--label)", label["color"])

        val placeholder = rule(baseCss, "input::placeholder")
        assertEquals("var(--note)", placeholder["color"])
        assertEquals("1", placeholder["opacity"], "browser placeholder opacity must not mute the hint twice")

        val hint = rule(appCss, ".field-hint")
        assertEquals("var(--fs-6)", hint["font-size"])
        assertEquals("1.45", hint["line-height"])
        assertEquals("var(--muted)", hint["color"])
        assertEquals("1.45", rule(appCss, ".field-err")["line-height"])
    }

    @Test
    fun `names identifiers tables and metadata have consistent line boxes`() {
        assertEquals("1.45", rule(appCss, "table.grid")["line-height"])
        assertEquals("1.35", rule(appCss, "table.grid th")["line-height"])

        for (selector in listOf(".pname", ".task-title", ".search-title")) {
            val title = rule(appCss, selector)
            assertEquals("600", title["font-weight"], "$selector should lead without becoming a heading")
            assertEquals("1.4", title["line-height"], "$selector lost its readable wrapped-title line box")
        }
        for (selector in listOf(".panel-caption", ".task-meta", ".search-detail", ".note-by")) {
            val meta = rule(appCss, selector)
            assertEquals("1.45", meta["line-height"], "$selector lost its secondary-text rhythm")
            assertEquals("var(--muted)", meta["color"], "$selector must follow the theme's secondary ink")
        }

        val code = rule(appCss, ".code")
        assertEquals("var(--font-mono)", code["font-family"])
        assertEquals("1.35", code["line-height"])
        assertEquals(".02em", code["letter-spacing"])
    }

    @Test
    fun `empty and informational messages are readable rather than decorative italics`() {
        assertEquals("1.4", rule(appCss, ".board-empty-line")["line-height"])
        assertEquals("1.5", rule(appCss, ".board-empty-sub")["line-height"])

        for (selector in listOf(".assignee.none", ".kcol-empty", ".section-empty", ".mtd-empty", ".mpop-empty")) {
            val empty = rule(appCss, selector)
            assertEquals("normal", empty["font-style"], "$selector should read as information, not an aside")
            assertEquals("var(--muted)", empty["color"], "$selector must stay legible in both themes")
        }
        assertEquals("1.5", rule(appCss, ".mpop-empty")["line-height"])
        assertEquals("1.55", rule(appCss, ".notif-empty")["line-height"])
        assertEquals("1.5", rule(appCss, ".flash")["line-height"])
    }
}
