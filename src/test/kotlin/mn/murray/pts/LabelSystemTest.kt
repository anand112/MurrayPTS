package mn.murray.pts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Stage 149's compact label system, read from the stylesheets rather than from a screenshot.
 *
 * Statuses, priorities, departments, categories, roles, counts and existing tags share one geometry. Their
 * meaning lives in semantic ink tokens, while the same low-tint fill is mixed from that ink and the active
 * card surface. The contrast check below resolves every light/dark badge pairing through that arithmetic,
 * including the reader-selected accent used by department headings and active navigation counts.
 */
class LabelSystemTest {

    private val baseCss = File("src/main/resources/static/css/base.css").readText()
    private val appCss = File("src/main/resources/static/css/app.css").readText()
    private val comments = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
    private val badgeSelector = (
        ".chip, .prio, .kstat, .cal-tag, .other-tag, .tcode, .dept-name, .dept-count, .count, .pill, " +
            ".bucket-preset, .bucket-pending-chip"
        )

    private fun code(text: String): String = comments.replace(text, " ")

    private fun normalized(text: String): String = text.replace(Regex("\\s+"), " ")

    /** The declarations on one exact selector, including a selector that shares a comma-separated rule. */
    private fun rule(css: String, selector: String, excluding: String? = null): Map<String, String> {
        val wanted = normalized(selector)
        val omitted = excluding?.let(::normalized)
        val match = Regex("([^{}]+)\\{([^{}]*)\\}").findAll(normalized(code(css)))
            .firstOrNull { entry ->
                val selectorText = normalized(entry.groupValues[1].trim())
                val selectors = selectorText.split(',').map { it.trim() }
                val matches = if (',' in wanted) selectorText == wanted else wanted in selectors
                matches && selectorText != omitted
            }
        assertTrue(match != null, "missing label-system rule: $selector")
        return Regex("([\\w-]+)\\s*:\\s*([^;]+)")
            .findAll(match!!.groupValues[2])
            .associate { it.groupValues[1] to it.groupValues[2].trim() }
    }

    private data class TokenRule(val selector: String, val props: Map<String, String>)

    /** Token tables in page-load order, with prose and component rules left out. */
    private val tokenRules: List<TokenRule> = buildList {
        for (text in listOf(baseCss, appCss)) {
            for (match in Regex("([^{}\\n]+)\\{([^{}]*)\\}").findAll(code(text))) {
                val selector = match.groupValues[1].trim()
                if (selector != ":root" && !selector.startsWith("html")) continue
                val props = Regex("(--[\\w-]+)\\s*:\\s*([^;]+)")
                    .findAll(match.groupValues[2])
                    .associate { it.groupValues[1] to it.groupValues[2].trim() }
                if (props.isNotEmpty()) add(TokenRule(selector, props))
            }
        }
    }

    /** The tokens one theme/accent combination resolves to; source order is the order the pages load them. */
    private fun tokens(theme: String, accent: String): Map<String, String> {
        val themeRe = Regex("""\[data-theme="(\w+)"]""")
        val accentRe = Regex("""\[data-accent="(\w+)"]""")
        val out = LinkedHashMap<String, String>()
        for (entry in tokenRules) {
            val wantedTheme = themeRe.find(entry.selector)?.groupValues?.get(1)
            val wantedAccent = accentRe.find(entry.selector)?.groupValues?.get(1)
            if (wantedTheme != null && wantedTheme != theme) continue
            if (wantedAccent != null && wantedAccent != accent) continue
            out.putAll(entry.props)
        }
        repeat(4) {
            for ((name, value) in out.toList()) {
                val ref = Regex("var\\((--[\\w-]+)\\)").matchEntire(value)?.groupValues?.get(1)
                if (ref != null && out.containsKey(ref)) out[name] = out.getValue(ref)
            }
        }
        return out
    }

    private fun channel(hex: String, index: Int): Int =
        hex.removePrefix("#").substring(index * 2, index * 2 + 2).toInt(16)

    /** The shared fill is 8% ink over the card surface, in sRGB. */
    private fun mixedSurface(ink: String, card: String): String = (0..2).joinToString("") { i ->
        val value = (channel(ink, i) * 0.08 + channel(card, i) * 0.92).roundToInt()
        value.toString(16).padStart(2, '0')
    }.let { "#$it" }

    private fun luminance(hex: String): Double {
        val channels = (0..2).map { i ->
            val value = channel(hex, i) / 255.0
            if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]
    }

    private fun contrast(a: String, b: String): Double {
        val first = luminance(a)
        val second = luminance(b)
        return (max(first, second) + 0.05) / (min(first, second) + 0.05)
    }

    @Test
    fun `every label shares one compact geometry and one theme-aware fill`() {
        assertEquals(1, Regex(Regex.escape(normalized(badgeSelector)) + "\\s*\\{").findAll(normalized(code(appCss))).count())
        val badge = rule(appCss, badgeSelector)
        val expected = mapOf(
            "display" to "inline-flex",
            "align-items" to "center",
            "gap" to "var(--sp-1)",
            "max-width" to "100%",
            "padding" to "2px var(--sp-2)",
            "border" to "1px solid transparent",
            "border-radius" to "var(--r-pill)",
            "background" to "color-mix(in srgb, var(--badge-ink, var(--muted)) 8%, var(--card-bg))",
            "color" to "var(--badge-ink, var(--muted))",
            "font-size" to "var(--fs-5)",
            "font-weight" to "700",
            "line-height" to "1.25",
            "letter-spacing" to ".01em",
            "white-space" to "nowrap",
            "vertical-align" to "middle",
        )
        for ((property, value) in expected) {
            assertEquals(value, badge[property], "$property drifted on the shared label rule")
        }
        assertFalse(badge.values.any { "!important" in it }, "the shared label system must not outrank its variants")
    }

    @Test
    fun `component variants add meaning without growing a second badge style`() {
        val variants = listOf(
            ".pill", ".prio", ".kstat", ".tcode", ".tcode.m", ".cal-tag", ".other-tag", ".dept-chip",
            ".dept-name", ".dept-count", ".count", ".count.zero", ".bucket-preset", ".bucket-pending-chip",
            ".group-head .chip", "table.projects-table td.c-status .chip", ".chip.perm", ".chip.ts-you",
            ".type-row .chip",
        )
        val geometry = setOf("display", "padding", "font-size", "border-radius")
        for (selector in variants) {
            val overlap = rule(appCss, selector, excluding = badgeSelector).keys intersect geometry
            assertTrue(overlap.isEmpty(), "$selector restates badge geometry: $overlap")
        }
        assertEquals("normal", rule(appCss, ".dept-chip")["white-space"], "legacy department names must be allowed to wrap")
        assertEquals("left", rule(appCss, ".dept-chip")["text-align"])
        val numericBadge = rule(appCss, ".count, .pill")
        assertEquals("24px", numericBadge["min-width"], "count badges lost their shared compact width")
        assertEquals("center", numericBadge["justify-content"], "count badges stopped centring their figures")
        assertEquals("tabular-nums", numericBadge["font-variant-numeric"], "count badges lost aligned digits")
        assertFalse("opacity" in rule(appCss, ".count.zero"), "a zero count is information, not disabled text")
    }

    @Test
    fun `statuses keep their vocabulary and map to one semantic palette`() {
        val statuses = mapOf(
            ".chip.ontrack" to "--badge-amber",
            ".chip.atrisk" to "--badge-red",
            ".chip.done" to "--badge-green",
            ".chip.planning" to "--badge-violet",
            ".chip.onhold" to "--badge-orange",
            ".kstat.s-inprogress" to "--badge-amber",
            ".kstat.s-blocked" to "--badge-red",
            ".kstat.s-completed" to "--badge-green",
            ".kstat.s-notstarted" to "--badge-violet",
            ".kstat.s-onhold" to "--badge-orange",
            ".count.ontrack" to "--badge-amber",
            ".count.atrisk" to "--badge-red",
            ".count.done" to "--badge-green",
            ".count.planning" to "--badge-violet",
            ".count.onhold" to "--badge-orange",
        )
        for ((selector, ink) in statuses) {
            assertEquals("var($ink)", rule(appCss, selector)["--badge-ink"], "$selector lost its status meaning")
        }
        assertEquals("var(--badge-green)", rule(appCss, ".chip.ll-discussed")["--badge-ink"])
        assertEquals("var(--muted)", rule(appCss, ".chip.ll-undiscussed")["--badge-ink"])
        assertEquals("var(--badge-green)", rule(appCss, ".chip.ts-you")["--badge-ink"],
            "the YOU marker must win its specificity tie with ontrack")
    }

    @Test
    fun `priority category and neutral labels keep distinct low-saturation inks`() {
        val priorities = mapOf(
            ".prio.p-high" to "--badge-red",
            ".prio.p-med" to "--badge-orange",
            ".prio.p-low" to "--muted",
        )
        val categories = mapOf(
            ".chip.t-m" to "--badge-blue",
            ".chip.t-r" to "--badge-amber",
            ".chip.t-s" to "--badge-green",
            ".chip.t-l" to "--badge-violet",
            ".chip.t-d" to "--badge-teal",
            ".chip.t-e" to "--badge-slate",
            ".chip.t-f" to "--badge-orange",
            ".chip.t-g" to "--badge-rose",
            ".chip.t-o" to "--muted",
        )
        for ((selector, ink) in priorities + categories) {
            assertEquals("var($ink)", rule(appCss, selector)["--badge-ink"], "$selector lost its meaning")
        }
        for (selector in listOf(".chip.ll-undiscussed, .cal-tag, .other-tag", ".pill", ".count.zero")) {
            assertEquals("var(--muted)", rule(appCss, selector)["--badge-ink"], "$selector should stay neutral")
        }
        assertEquals("var(--badge-blue)", rule(appCss, ".tcode.bucket-chip")["--badge-ink"])
        assertEquals("var(--badge-green)", rule(appCss, ".bucket-pending-chip", excluding = badgeSelector)["--badge-ink"])
        assertEquals("var(--accent-ink)", rule(appCss, ".nav-item.active .pill")["--badge-ink"])
        assertEquals("var(--accent-ink)", rule(appCss, ".ftab.active .pill")["--badge-ink"])
    }

    @Test
    fun `every badge ink clears AA on its actual light and dark fill`() {
        val badgeTokens = listOf(
            "--badge-green", "--badge-amber", "--badge-orange", "--badge-red", "--badge-blue",
            "--badge-violet", "--badge-teal", "--badge-slate", "--badge-rose",
        )
        for (theme in listOf("light", "dark")) {
            for (accent in listOf("green", "blue", "amber")) {
                val table = tokens(theme, accent)
                val card = table.getValue("--card-bg")
                for (token in listOf("--muted", "--accent-ink") + badgeTokens) {
                    val ink = table.getValue(token)
                    val fill = mixedSurface(ink, card)
                    val ratio = contrast(ink, fill)
                    assertTrue(
                        ratio >= 4.5,
                        "$token $ink on $fill is %.2f:1 in $theme/$accent; compact badge text needs 4.5:1"
                            .format(ratio),
                    )
                }
                assertTrue(
                    contrast(table.getValue("--title"), table.getValue("--input-bg")) >= 4.5,
                    "permission chips lost readable title ink on their input surface in $theme/$accent",
                )
            }
        }
    }
}
