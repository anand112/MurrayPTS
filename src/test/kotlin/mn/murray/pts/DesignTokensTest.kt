package mn.murray.pts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Stage A's design-token layer, read out of the two stylesheets rather than off a screen.
 *
 * A token layer is a promise about *text*: `--sp-3` exists, every rule that wants 12px of padding asks for it, and
 * nothing between the two has drifted. None of that is visible in rendered markup — `appPage()` links `base.css`
 * and then `app.css` and the browser resolves the variables — so this test reads the files the way a browser
 * would, with the same rule about ties: equal specificity settles by source order, and `base.css` is first.
 *
 * What is checked, and why each check is the one worth having:
 *
 *  1. **A reference without a definition is invisible breakage.** `var(--sp-9)` where nothing defines `--sp-9`
 *     makes that declaration invalid at computed-value time, so the property falls back to its initial value —
 *     padding to nothing, radius to square — on every page at once. That is the failure mode a token layer
 *     invents for itself and it cannot be seen by eye.
 *  2. **The pixels live in one table.** Every ramp step is asserted at its exact value, so a later re-typing is a
 *     deliberate edit to `base.css` and not an accident in one of four hundred rules. And no rule may write out a
 *     pixel the table already names — that is the claim that keeps the layer from rotting back into literals.
 *  3. **The face stack asks for Inter first and downloads nothing.** The reader chose a stack over a file; a
 *     machine without Inter renders exactly what it rendered before. The test is what stops someone quietly
 *     adding an `@font-face` later.
 *  4. **Accent text is measured, not admired.** `--accent-ink` is resolved through the cascade for all six
 *     accent/theme combinations and compared against the four surfaces it is actually painted on, with the WCAG
 *     relative-luminance ratio and a 4.5:1 floor. The numbers are the ones that found the defect: dark green text
 *     on a dark panel was 1.45:1. Reordering the accent table fails this check, which is the point — that ordering
 *     is the trap this codebase has fallen into four times.
 *  5. **One focus ring, one token, drawn on `:focus-visible`** in `base.css`, the file both the login card and the
 *     app load, and weak enough that a control naming its own ring keeps its own offset.
 */
class DesignTokensTest {

    private val baseCss = File("src/main/resources/static/css/base.css").readText()
    private val appCss = File("src/main/resources/static/css/app.css").readText()

    /** The two files in the order a page loads them, so "source order" below means what a browser means. */
    private val css = listOf("base.css" to baseCss, "app.css" to appCss)

    private val comments = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)

    /** Prose is not a rule: a comment that quotes `padding: 8px` must not be read as one. */
    private fun code(text: String): String = comments.replace(text, " ")

    // ---- the cascade, small ----------------------------------------------------------------------------

    private data class Rule(val selector: String, val props: Map<String, String>)

    /** Every rule that puts a custom property on `html`, in load order. */
    private val tokenRules: List<Rule> = buildList {
        for ((_, text) in css) {
            for (match in Regex("([^{}\\n]+)\\{([^{}]*)\\}").findAll(code(text))) {
                val selector = match.groupValues[1].trim()
                if (selector != ":root" && !selector.startsWith("html")) continue
                val props = Regex("(--[\\w-]+)\\s*:\\s*([^;]+)")
                    .findAll(match.groupValues[2])
                    .associate { it.groupValues[1] to it.groupValues[2].trim() }
                if (props.isNotEmpty()) add(Rule(selector, props))
            }
        }
    }

    private fun specificity(selector: String): Int =
        10 * selector.count { it == '[' } + 10 * Regex(":root").findAll(selector).count() +
            if ("html" in selector.substringBefore('[')) 1 else 0

    private val themeRe = Regex("""\[data-theme="(\w+)"]""")
    private val accentRe = Regex("""\[data-accent="(\w+)"]""")

    /**
     * What `html` carries for one page. A rule matches a (theme, accent) combination when every attribute it names
     * is the one being asked about; among the rules that match, the winner is the most specific, ties going to the
     * later one in the file. `--focus-ring: var(--accent-ink)` is then followed to its value, because a token that
     * reads through another token is the whole reason the ring can be one declaration.
     */
    private fun tokens(theme: String, accent: String): Map<String, String> {
        val matching = tokenRules.mapIndexed { i, r -> Triple(specificity(r.selector), i, r) }
            .filter { (_, _, r) ->
                (themeRe.find(r.selector)?.groupValues?.get(1) ?: theme) == theme &&
                    (accentRe.find(r.selector)?.groupValues?.get(1) ?: accent) == accent
            }
            .sortedBy { (spec, i, _) -> spec * 10000 + i }
        val out = LinkedHashMap<String, String>()
        for ((_, _, r) in matching) out.putAll(r.props)
        repeat(4) {
            for ((k, v) in out.toList()) {
                val ref = Regex("var\\((--[\\w-]+)\\)").matchEntire(v)?.groupValues?.get(1)
                if (ref != null && out.containsKey(ref)) out[k] = out[ref]!!
            }
        }
        return out
    }

    // ---- WCAG 2.x contrast ------------------------------------------------------------------------------

    private fun luminance(hex: String): Double {
        val h = hex.removePrefix("#")
        val full = if (h.length == 3) h.map { "$it$it" }.joinToString("") else h
        val channel = (0..2).map { i ->
            val c = full.substring(i * 2, i * 2 + 2).toInt(16) / 255.0
            if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel[0] + 0.7152 * channel[1] + 0.0722 * channel[2]
    }

    private fun ratio(a: String, b: String): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun channel(hex: String, i: Int) = hex.removePrefix("#").substring(i * 2, i * 2 + 2).toInt(16)

    private val accents = listOf("green", "blue", "amber")
    private val themes = listOf("light", "dark")

    /** The surfaces accent text is really painted on, for one combination, read through the same cascade. */
    private fun surfaces(theme: String, accent: String): Map<String, String> {
        val t = tokens(theme, accent)
        return listOf("card-bg", "main-bg", "input-bg", "accent-soft").associateWith { t.getValue("--$it") }
    }

    // ---- 1. the layer holds together --------------------------------------------------------------------

    @Test
    fun `every custom property the stylesheets reach for is defined in one of them`() {
        val all = code(css.joinToString("\n") { it.second })
        val defined = Regex("(--[\\w-]+)\\s*:").findAll(all).map { it.groupValues[1] }.toSet()
        val used = Regex("var\\((--[\\w-]+)").findAll(all).map { it.groupValues[1] }.toSet()
        val missing = used - defined
        assertTrue(
            missing.isEmpty(),
            "$missing is named in a var() and defined nowhere; such a declaration is invalid at computed-value " +
                "time, so the property falls back to its initial value on every page at once",
        )
        assertTrue(defined.size >= 40, "the token table has only ${defined.size} properties; it is not a layer yet")
        assertTrue(Regex("var\\(").findAll(all).count() > 500, "the files barely reference any token")
    }

    @Test
    fun `the scale is one table of exact pixels in the file both pages load`() {
        val root = Regex(":root\\s*\\{([^}]*)\\}").find(code(baseCss))?.groupValues?.get(1).orEmpty()
        val defined = Regex("(--[\\w-]+)\\s*:\\s*([^;]+)")
            .findAll(root)
            .associate { it.groupValues[1] to it.groupValues[2].trim() }
        val ramp = linkedMapOf(
            "--sp-1" to "4px", "--sp-2" to "8px", "--sp-3" to "12px", "--sp-4" to "16px",
            "--sp-5" to "20px", "--sp-6" to "24px", "--sp-7" to "28px", "--sp-8" to "32px",
            "--r-xs" to "4px", "--r-sm" to "6px", "--r-md" to "8px", "--r-lg" to "12px", "--r-xl" to "16px",
            "--r-pill" to "999px", "--r-round" to "50%",
            "--fs-1" to "9px", "--fs-2" to "9.5px", "--fs-3" to "10px", "--fs-4" to "10.5px", "--fs-5" to "11px",
            "--fs-6" to "11.5px", "--fs-7" to "12px", "--fs-8" to "12.5px", "--fs-9" to "13px",
            "--fs-10" to "13.5px", "--fs-11" to "14px", "--fs-12" to "15px", "--fs-13" to "16px",
            "--fs-14" to "18px", "--fs-15" to "22px",
        )
        for ((token, px) in ramp) {
            assertEquals(px, defined[token], "$token must be exactly $px; the ramp is where its pixels live")
        }
        assertTrue(Regex("var\\(--(?:sp|r|fs)-").findAll(code(appCss)).count() >= 350,
            "app.css does not read the scale; the literals never moved in")
    }

    /**
     * The rot a token layer invites is a second copy of a named value: someone types `12px` again and the table is
     * decoration. Checked per family, because 9px is a type step and a radius of 9px is nothing of the sort -- and
     * the values *between* the steps (6px and 10px of padding, 17px of type) are deliberately still literals, which
     * is what keeps the distance between the app and a clean grid countable.
     */
    @Test
    fun `no rule writes out a pixel the ramp already names`() {
        val space = setOf("4px", "8px", "12px", "16px", "20px", "24px", "28px", "32px")
        val radius = setOf("4px", "6px", "8px", "12px", "16px", "999px", "50%")
        val type = setOf("9px", "9.5px", "10px", "10.5px", "11px", "11.5px", "12px", "12.5px", "13px", "13.5px",
            "14px", "15px", "16px", "18px", "22px")
        val families = listOf(
            "spacing" to Regex(
                "(?:padding|margin|gap|row-gap|column-gap|(?:padding|margin)-(?:top|right|bottom|left))" +
                    "\\s*:\\s*(?<v>[^;{}]*)[;}]",
            ),
            "radius" to Regex("border-radius\\s*:\\s*(?<v>[^;{}]*)[;}]"),
            "type" to Regex("font-size\\s*:\\s*(?<v>[^;{}]*)[;}]"),
        )
        val steps = mapOf("spacing" to space, "radius" to radius, "type" to type)
        val offenders = mutableListOf<String>()
        for ((name, text) in css) {
            for ((family, pattern) in families) {
                for (m in pattern.findAll(code(text))) {
                    // The value is a named group, not group 2: the three patterns carry a different number of
                    // captures, and a bare index reads past the end of the radius and type matches.
                    val value = m.groups["v"]!!.value.trim()
                    val bare = value.split(Regex("\\s+")).filter { it in steps.getValue(family) }
                    if (bare.isNotEmpty()) offenders += "$name: ${m.value.trim()} -> $bare"
                }
            }
        }
        assertTrue(offenders.isEmpty(), "these declarations spell out a value that has a name:\n" +
            offenders.joinToString("\n"))
    }

    // ---- 2. the face stack, without a download ----------------------------------------------------------

    @Test
    fun `the interface asks for Inter first and the stylesheet ships no font`() {
        val ui = Regex("--font-ui\\s*:\\s*([^;]+)").find(baseCss)?.groupValues?.get(1).orEmpty().trim()
        assertTrue(ui.startsWith("Inter,"), "--font-ui does not ask for Inter first: $ui")
        assertTrue("\"Segoe UI\"" in ui, "--font-ui dropped the Windows face it used to lead with: $ui")
        val mono = Regex("--font-mono\\s*:\\s*([^;]+)").find(baseCss)?.groupValues?.get(1).orEmpty().trim()
        assertTrue("Consolas" in mono, "--font-mono is not the code stack the app used: $mono")
        for ((name, text) in css) {
            assertFalse("@font-face" in text, "$name downloads a font; the reader chose a stack, not a file")
            assertFalse(".woff" in text || ".ttf" in text, "$name references a font file; none is shipped")
        }
        assertFalse(File("src/main/resources/static/fonts").exists(), "a fonts directory was added to the app")
        assertTrue("font-family: var(--font-ui);" in code(baseCss), "the body does not ask for the interface stack")
        assertEquals(2, Regex("font-family: var\\(--font-mono\\)").findAll(code(appCss)).count(),
            "the code faces stopped reading --font-mono; the boxes are .code and .email-mode")
        val inheriting = Regex("font-family: (inherit|var\\(--font)")
            .findAll(code(baseCss) + code(appCss)).count()
        assertTrue(inheriting >= 12,
            "controls stopped inheriting the face stack: only $inheriting declarations ask for a face at all")
    }

    // ---- 3. accent text, measured ----------------------------------------------------------------------

    @Test
    fun `accent text clears AA on every surface it can be painted on`() {
        for (theme in themes) {
            for (accent in accents) {
                val ink = tokens(theme, accent)["--accent-ink"]
                    ?: error("$accent/$theme has no --accent-ink, so accent text has no readable colour")
                for ((surface, hex) in surfaces(theme, accent)) {
                    val r = ratio(ink, hex)
                    assertTrue(
                        r >= 4.5,
                        "--accent-ink $ink on --$surface $hex is %.2f:1 in %s %s; normal accent text needs 4.5:1, " +
                            "and this pairing is the one the checklist called low-contrast".format(r, theme, accent),
                    )
                }
            }
        }
    }

    @Test
    fun `text asks for the ink and fills keep the dark end of the accent`() {
        val app = code(appCss)
        assertEquals(0, Regex("(?<![-\\w])color:\\s*var\\(--accent-strong\\)").findAll(app).count(),
            "text still asks for --accent-strong; that is the end of an accent meant to be sat *on*, not read")
        assertEquals(0, Regex("(?<![-\\w])color:\\s*var\\(--accent\\)").findAll(app).count(),
            "text still asks for the mid tone --accent")
        val inked = Regex("(?<![-\\w])color:\\s*var\\(--accent-ink\\)").findAll(app).count()
        assertTrue(inked >= 25, "only $inked rules read accent text from --accent-ink; the marks did not all move")
        // the fills keep it, and white on them still reads -- if someone swaps a fill for the ink, this fails
        assertTrue("background: var(--accent-strong)" in app, "the accent stopped being a fill colour somewhere")
        for (theme in themes) {
            for (accent in accents) {
                val strong = tokens(theme, accent).getValue("--accent-strong")
                val r = ratio("#ffffff", strong)
                assertTrue(r >= 4.5, "white on --accent-strong $strong is %.2f:1 in %s %s".format(r, theme, accent))
            }
        }
    }

    /**
     * The ordering, claimed as arithmetic instead of as six hexes: the dark ink of a hue must be *lighter* than
     * that hue's light ink, and must still be the same hue. Put `html[data-accent="blue"]` after
     * `html[data-accent="blue"][data-theme="dark"]`, or the dark rule after a light one, and the resolution above
     * hands a dark page the light ink -- which is lighter on nothing: it is the same dark green that measured
     * 1.45:1 before this stage.
     */
    @Test
    fun `each accent lifts its ink for the dark theme and keeps its hue doing it`() {
        for (accent in accents) {
            val light = tokens("light", accent).getValue("--accent-ink")
            val dark = tokens("dark", accent).getValue("--accent-ink")
            assertTrue(
                luminance(dark) > luminance(light),
                "$accent's dark ink $dark is no lighter than its light ink $light: on a dark panel the mark has " +
                    "to come up off the background, and a reordered accent table does the opposite",
            )
            val carrier = (0..2).maxOf { i -> channel(light, i) }
            val lightChannel = (0..2).first { i -> channel(light, i) == carrier }
            val darkChannel = (0..2).maxBy { i -> channel(dark, i) }
            assertEquals(lightChannel, darkChannel,
                "$accent's ink moved to another channel -- that is a new colour, not the same one lifted")
        }
    }

    // ---- 4. the focus ring ------------------------------------------------------------------------------

    @Test
    fun `one focus ring comes from one token and appears on keyboard focus`() {
        val base = code(baseCss)
        assertTrue(
            ":focus-visible { outline: 2px solid var(--focus-ring); outline-offset: 2px; }" in base,
            "the app lost its one universal :focus-visible rule, in the file both the login card and the shell load",
        )
        assertEquals(1, Regex("(?m)^:focus-visible\\s*\\{").findAll(base).count(),
            "base.css draws the universal ring more than once, so a later copy silently wins by source order")
        // the login page loads base.css alone, so the token has to have a value there too, in both themes
        for (theme in themes) {
            val t = tokens(theme, "green")
            val ring = t["--focus-ring"]
                ?: error("$theme has no --focus-ring; a var() with no value is a ring drawn in the text colour")
            assertEquals(t["--accent-ink"], ring, "$theme's ring does not read through the ink")
            for (surface in listOf("card-bg", "input-bg")) {
                val r = ratio(ring, t.getValue("--$surface"))
                assertTrue(r >= 3.0,
                    "$theme ring $ring on --$surface is %.2f:1; a focus indicator is a non-text component and " +
                        "may ask for 3:1 -- the login page's old #6b9c1e could not clear it".format(r))
            }
        }
        // …and every accent, not just the one that ships: the ring is a token, so a hue that answers to another
        // page's colour would be a ring drawn in the wrong one. `--focus-ring: var(--accent-ink)` is repeated in
        // all six blocks on purpose — one declaration on `:root` loses to base.css's `html[data-theme="dark"]`.
        for (theme in themes) {
            for (accent in accents) {
                val t = tokens(theme, accent)
                assertEquals(
                    t.getValue("--accent-ink"), t["--focus-ring"],
                    "$accent/$theme resolves a ring that is not its own ink: a single `:root` declaration loses the " +
                        "specificity fight with base.css's theme rule, which is why the token is named in all six",
                )
                for (surface in listOf("card-bg", "main-bg", "input-bg", "accent-soft")) {
                    val r = ratio(t.getValue("--focus-ring"), t.getValue("--$surface"))
                    assertTrue(r >= 3.0,
                        "$accent/$theme's ring ${t["--focus-ring"]} on --$surface is %.2f:1".format(r))
                }
            }
        }
        val app = code(appCss)
        assertEquals(0, Regex("outline:[^;]*var\\(--accent-strong\\)").findAll(app).count(),
            "a hand-drawn ring still hard-codes the fill colour")
        assertTrue(
            ".kcard-open-btn:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: -2px; }" in app,
            "the card opener lost the ring it draws *inside* its own border -- the universal 2px offset would " +
                "sit off the card and be clipped by the column",
        )
        assertTrue(
            ".colour-opt input[type=\"radio\"]:focus-visible + .bucket-dot { outline: 2px solid var(--focus-ring); " +
                "outline-offset: 4px; }" in app,
            "the bucket swatch lost the wide ring that clears the dot's own edge",
        )
    }
}
