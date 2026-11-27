package mn.murray.pts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Stage B's motion layer, read out of the two stylesheets and the one script it touches.
 *
 * Motion is the hardest thing in this app to test, because none of it is a state a browser remembers: a fade is
 * twenty frames, and a screenshot catches whichever one it likes. So the checks are about the claims the cascade
 * makes rather than about a frame — which length of time a rule asks for, which moment an entrance is attached
 * to, which event a script has to be the one answering. Each case is the pair a later edit could otherwise
 * split in half: a hover rule with no transition behind it, a `.kcard-drag` class nobody adds, an entrance hung
 * on the box a live search replaces.
 *
 * What is checked, and why each check is the one worth having:
 *
 *  1. **The times live in one table** — the original dim/drawer lengths and the kanban landing length, and the
 *     literals they replaced are gone rather than shadowed by a token nobody uses.
 *  2. **No rule writes out a length of time the table names** — the same rot Stage A fenced against, one family
 *     later: a `150ms` typed beside `var(--dur-1)` makes the table decoration.
 *  3. **A popup's overlay fades and blurs, and still hides the way the scripts say** — those scripts set
 *     `display`, so the entrance has to be an `animation` and the hide has to stay `display: none`.
 *  4. **The card lift is a class, and the script is what puts it on** — CSS cannot know a card is in the hand,
 *     and a class added after `setData` would miss the drag image the browser paints.
 *  5. **The one-shot banner arrives**, because a sentence that appears between two frames reads as a glitch.
 *  6. **A page fades in once and a re-rendered board does not** — the difference between a view change and a
 *     render, which is the exact mistake the checklist warns about.
 *  7. **Reduced motion is honoured in the file every page loads**, and the only widths app.css answers to are
 *     the two it has — the fold's 900px and the 520px Stage 134 gave a row of fields that stopped fitting.
 *  8. **Every control that answers a pointer answers it over time**, and nothing sits in that list without a
 *     hover state to travel to.
 */
class MotionTest {

    private val baseCss = File("src/main/resources/static/css/base.css").readText()
    private val appCss = File("src/main/resources/static/css/app.css").readText()
    private val kanbanJs = File("src/main/resources/static/js/kanban.js").readText()

    private val comments = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)

    /** Prose is not a rule, and these files quote their own rules in comments constantly. */
    private fun code(text: String): String = comments.replace(text, " ")

    private fun occurrences(haystack: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(haystack).count()

    private val all: String get() = code(baseCss) + "\n" + code(appCss)

    /**
     * The body of the one rule whose selector is exactly this one. Anchored at a rule boundary, because
     * `html[data-theme="dark"] .kcard.kcard-drag` also *contains* `.kcard.kcard-drag` and would otherwise be
     * counted as a second copy of the rule the assertion is about.
     */
    private fun body(selector: String, text: String = appCss): String {
        val rule = Regex("(?:^|\\})\\s*" + Regex.escape(selector) + "\\s*\\{([^}]*)\\}")
            .findAll(code(text)).toList()
        assertEquals(1, rule.size, "\"$selector\" should be exactly one rule; found ${rule.size}")
        return rule[0].groupValues[1].trim()
    }

    /**
     * The body of one `@keyframes` block, found by walking its braces rather than by a pattern: a regex
     * that asks for "everything up to the last two braces" is greedy, and `pts-view-in` is the last block
     * in the file with only `.content` after it, so a greedy capture hands back the rest of the stylesheet
     * and a claim about the fade quietly becomes a claim about the drawer and the fold as well.
     */
    private fun frames(name: String, text: String = code(appCss)): String {
        val head = Regex("@keyframes\\s+" + Regex.escape(name) + "\\s*\\{").find(text)
        assertTrue(head != null, "$name is not defined")
        val from = head!!.range.last + 1
        var depth = 1
        var i = from
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        assertTrue(depth == 0, "$name is never closed")
        return text.substring(from, i - 1)
    }

    /** The declarations inside base.css's `:root` — the table every page gets before any other rule. */
    private fun scale(): Map<String, String> {
        val root = Regex(":root\\s*\\{([^}]*)\\}").find(code(baseCss))?.groupValues?.get(1).orEmpty()
        return Regex("(--[\\w-]+)\\s*:\\s*([^;]+)").findAll(root)
            .associate { it.groupValues[1] to it.groupValues[2].trim() }
    }

    /**
     * The selector list of the one rule that gives the app its pointer transitions. It spans several lines, so
     * it is found by its body and read backwards — which is also the only way to prove the list is *one* rule
     * rather than the same five properties spelled out per control.
     */
    private fun transitionSet(): Set<String> {
        val app = code(appCss)
        val marker = "transition: background-color var(--dur-1) var(--ease-std)"
        assertEquals(1, occurrences(app, marker), "the pointer transitions are not the one rule they are meant to be")
        val brace = app.lastIndexOf("{", app.indexOf(marker))
        val from = app.lastIndexOf("}", brace) + 1
        return app.substring(from, brace).split(",")
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /** Each control in that list, and the hover rule its transition travels *to*. */
    private val pointerControls = listOf(
        ".nav-item" to ".nav-item:hover",
        ".ghost-btn" to ".ghost-btn:hover",
        ".modal-btn" to ".modal-btn:hover",
        ".count-btn" to ".count-btn:hover",
        ".quick-add" to ".quick-add:hover",
        ".tasks-toggle" to ".tasks-toggle:hover",
        ".todo-move" to ".todo-move:hover",
        ".kmove-btn" to ".kmove-btn:hover:not(:disabled)",
        ".kcol-menu-btn" to ".kcol-menu-btn:hover",
        ".kcard-open-btn" to ".kcard-open-btn:hover",
        ".notif-btn" to ".notif-btn:hover",
        ".mpop-x" to ".mpop-x:hover",
        ".x-btn" to ".x-btn:hover",
        ".delete-btn" to ".delete-btn:hover",
        ".kcard" to ".kcard[data-task-card]:hover",
        ".close" to ".close:hover",
        "a.logout" to "a.logout:hover",
        ".ll-open" to ".ll-open:hover",
        ".row-link" to ".row-link:hover",
        ".crumb-link" to ".crumb-link:hover",
        ".back-link" to ".back-link:hover",
        ".ftab" to ".ftab:hover",
        ".board-toolbar .board-reset" to ".board-toolbar .board-reset:hover",
        ".fc-num" to ".fc-num:hover",
        ".kcol-add-btn" to ".kcol-add-btn:hover",
        ".kdone-btn" to ".kdone-btn:hover",
        ".lane-add-box-btn" to ".lane-add-box-btn:hover",
        // Stage 134 put the phone's bottom bar on this list: a tab is a control, and a tab that changes colour
        // the instant a cursor lands on it is the one control on that bar the reader would notice skipping.
        ".mtab" to ".mtab:hover",
        ".team-table tbody tr[data-emp] td" to ".team-table tbody tr[data-emp]:hover td",
        "table.projects-table tbody tr td" to "table.projects-table tbody tr:hover td",
        "table.projects-table .pin-btn" to "table.projects-table .pin-btn:hover",
    )

    /** The boxes a live swap replaces; an entrance on one of them would replay on every render. */
    private val swappedBoxes = listOf("board-results", "other-groups", "live-region", "ll-board", "kbody")

    // ---- 1 and 2. one table of times, and no second copy of it -----------------------------------------

    @Test
    fun `the time the app moves in is one table, including the kanban landing`() {
        val t = scale()
        assertEquals("120ms", t["--dur-1"], "--dur-1 must stay the length the board's dim already used")
        assertEquals("200ms", t["--dur-2"], "--dur-2 must stay the drawer's slide")
        assertEquals("150ms", t["--dur-3"], "--dur-3 is the dropped card's short settle")
        assertEquals("cubic-bezier(.22, .61, .36, 1)", t["--ease-out"], "--ease-out is the entrance curve")
        assertEquals("cubic-bezier(.4, 0, .2, 1)", t["--ease-std"], "--ease-std is the state-change curve")
        // The claim is that these are the app's own numbers, so the literals they replaced are gone.
        for (literal in listOf("transition: opacity 120ms ease", "transition: transform .2s ease")) {
            assertFalse(literal in all, "\"$literal\" is still written out; the table is decoration")
        }
    }

    @Test
    fun `no rule writes out a length of time the table names`() {
        // One rule deliberately takes motion away: the captured drag image must not inherit the card's hover
        // transition. Pin the exception to that exact selector, then account for every other declaration below.
        assertEquals(1, Regex("transition\\s*:\\s*none").findAll(all).count(),
            "only the captured drag image may have transition:none")
        val stableDrag = Regex("(?:^|\\})\\s*\\.kcard\\.kcard-drag\\s*\\{[^}]*transition:\\s*none\\s*;")
            .find(all)
        assertTrue(stableDrag != null, "the stable drag image lost its exact transition:none exception")
        val moved = Regex("(?<![\\w-])(transition|animation)\\s*:\\s*(?<v>[^{}]*)[;}]").findAll(all).toList()
        assertTrue(moved.size >= 8,
            "only ${moved.size} transition/animation declarations — the app has more motion than that")
        val offenders = moved.filter { m ->
            val v = m.groups["v"]!!.value
            if (m.range.first in stableDrag!!.range) return@filter false
            // Every one of them asks the table, and none of them writes a time or the keyword the curves replaced.
            !v.contains("var(--dur-") || !v.contains("var(--ease-") ||
                Regex("[\\d.]+m?s(?![\\w-])").containsMatchIn(v) ||
                Regex("(^|[\\s,])ease($|[\\s,;])").containsMatchIn(v)
        }
        assertTrue(offenders.isEmpty(), "these declarations do not read the motion table:\n" +
            offenders.joinToString("\n") { it.value.trim() })
        // A bare time is allowed in exactly two places: the table that defines it, and base.css's
        // reduced-motion block, which is a *property* (`animation-duration`) the scan above cannot see.
        val base = code(baseCss)
        val root = Regex(":root\\s*\\{([^}]*)\\}").find(base)!!.range
        val reduceAt = base.indexOf("@media (prefers-reduced-motion")
        assertTrue(reduceAt >= 0, "base.css has no reduced-motion block to excuse a bare duration")
        val reduce = reduceAt..base.lastIndex
        val outside = Regex("[\\d.]+m?s(?![\\w-])").findAll(base)
            .filter { m -> !(m.range.first in root && m.range.last in root) &&
                !(m.range.first in reduce && m.range.last in reduce) }
            .toList() + Regex("[\\d.]+m?s(?![\\w-])").findAll(code(appCss)).toList()
        assertTrue(outside.isEmpty(),
            "a duration lives outside the table and the reduced-motion block: " + outside.map { it.value })
        assertEquals(6, occurrences(base, "ms"), "120ms + 200ms + 150ms in the table, three .01ms in the block")
    }

    // ---- 3. the overlay that is not there yet ---------------------------------------------------------

    @Test
    fun `a popup comes over a page that has gone soft, and still hides the way the scripts say`() {
        val scrim = body(".modal")
        assertTrue("display: none" in scrim, "the scripts open this by setting `display`; a fade cannot replace it")
        assertTrue("backdrop-filter: blur(3px)" in scrim, "the page behind a popup still competes with it")
        assertTrue("animation: pts-overlay-in var(--dur-2) var(--ease-out)" in scrim,
            "the scrim arrives all at once instead of fading to its half-black")
        assertTrue("animation: pts-card-in var(--dur-2) var(--ease-out)" in body(".modal-content"),
            "the popup's card appears flatly rather than rising into place")
        assertTrue("opacity: 0" in frames("pts-overlay-in"), "pts-overlay-in no longer starts from nothing")
        assertTrue("transform: translateY(10px) scale(.98)" in frames("pts-card-in"),
            "the popup's rise lost the travel that makes it a rise")
        // One blur in both files: the overlay's. `.mpop` is a panel beside a row rather than an overlay over
        // the page, so it fades without blurring, and a second blur would cost a compositor per open popup.
        assertEquals(1, Regex("backdrop-filter").findAll(all).count(),
            "something other than the modal scrim is now blurring the page")
        assertTrue("animation: pts-pop-in var(--dur-1) var(--ease-out)" in body(".mpop"),
            "a row's popup still appears between two frames")
    }

    // ---- 4. the card in the hand ----------------------------------------------------------------------

    @Test
    fun `a card being dragged has a stable image and a separately dimmed original`() {
        val lift = body(".kcard.kcard-drag")
        listOf("opacity: .92", "transform: none", "transition: none", "box-shadow: 0 8px 18px").forEach {
            assertTrue(it in lift, "the drag image is not stable: missing \"$it\"")
        }
        assertTrue("opacity: .38" in body(".kcard.kcard-ghost"), "the original card is not dimmed in place")
        val dark = Regex("(?:^|\\})\\s*html\\[data-theme=\"dark\"\\] \\.kcard\\.kcard-drag\\s*\\{([^}]*)\\}")
            .find(code(appCss))
        assertTrue(dark != null && "box-shadow: 0 8px 18px rgba(0, 0, 0, .48)" in dark!!.groupValues[1],
            "the lift disappears on the dark theme, where a pale shadow is no shadow at all")
        // The stylesheet cannot know a drag has started. The drag-image class must be added before setData, while
        // the original's ghost class waits for the next frame so it cannot dim the captured image.
        val start = code(kanbanJs).substringAfter("addEventListener('dragstart'").substringBefore("});")
        val end = kanbanJs.substringAfter("addEventListener('dragend'").substringBefore("});")
        assertTrue("card.classList.add('kcard-drag')" in start, "nothing ever puts the card in the hand")
        assertTrue("card.classList.remove('kcard-drag')" in end, "a dropped card stays lifted forever")
        assertTrue(start.indexOf("card.classList.add('kcard-drag')") < start.indexOf("e.dataTransfer.setData"),
            "the lift arrives after the drag image was painted, so the card being carried looks untouched")
        assertTrue("requestAnimationFrame(function" in start && "card.classList.add('kcard-ghost')" in start,
            "the original is dimmed in the same paint as the drag image")
        assertEquals(1, occurrences(kanbanJs, "classList.add('kcard-drag')"), "two handlers both lifting is two shapes")
        assertEquals(1, occurrences(kanbanJs, "classList.remove('kcard-drag')"), "the lift comes off in one place")
        assertEquals(2, Regex("kcard-drag").findAll(code(appCss)).count(), "one rule and its dark twin")
        val settle = body(".kcard.kcard-settle")
        assertTrue("animation: pts-kanban-settle var(--dur-3) var(--ease-out)" in settle,
            "the dropped card has no short settle")
        assertTrue("transform: scale(.98)" in frames("pts-kanban-settle"), "the settle no longer starts just below rest")
    }

    // ---- 5 and 6. arriving once -----------------------------------------------------------------------

    @Test
    fun `the one-shot banner arrives rather than appearing`() {
        assertTrue("animation: pts-flash-in var(--dur-2) var(--ease-out)" in body(".flash"),
            "a save's answer pops into the page between two frames")
        val f = frames("pts-flash-in")
        assertTrue("opacity: 0" in f && "translateY(-4px)" in f, "pts-flash-in no longer slides down into place")
        // The banner is the app's one-shot answer, and there is no toast system to animate: the checklist's
        // "toast entrance" lands on this rule, on the two colours Stage A left it with.
        listOf(".flash.ok", ".flash.err").forEach {
            assertTrue(it in code(appCss), "the banner lost $it, so the entrance would paint the wrong sentence")
        }
    }

    @Test
    fun `a page fades in once, and a board re-rendered inside it does not`() {
        assertTrue("animation: pts-view-in var(--dur-2) var(--ease-out)" in body(".content"),
            "the page has no entrance, or the fade is on the wrong box")
        assertEquals(2, occurrences(code(appCss), "pts-view-in"),
            "the view fade is attached more than once, so a re-render would restart it")
        assertFalse("animation" in body("[data-live-region]"),
            "a live search restarts the page's fade, which is the mistake the checklist names")
        assertTrue("transition: opacity var(--dur-1) var(--ease-std)" in body("[data-live-region]"),
            "the refresh lost the tokenised dim")
        assertTrue("body.pts-refreshing [data-live-region] { opacity: .55; }" in code(appCss),
            "a refresh is no longer a dim, or the class moved off <body>")
        assertFalse("transform" in frames("pts-view-in"),
            "the page's entrance moves the layout; an opacity fade is the whole claim")
        val animated = Regex("([^{}]*)\\{([^}]*animation[^}]*)\\}").findAll(code(appCss))
            .map { it.groupValues[1].trim().replace(Regex("\\s+"), " ") }
            .filter { sel -> swappedBoxes.any { sel.contains(it) } }
            .toList()
        assertTrue(animated.isEmpty(), "a box a live swap replaces has an entrance on it: $animated")
    }

    // ---- 7. the courtesy ------------------------------------------------------------------------------

    @Test
    fun `reduced motion takes the time out, in the file every page loads`() {
        val base = code(baseCss)
        assertEquals(1, Regex("@media\\s*\\(").findAll(base).count(), "base.css grew a second media rule")
        assertTrue("@media (prefers-reduced-motion: reduce)" in base,
            "the setting is not honoured, or it moved to the file the login page does not load")
        val block = base.substringAfter("@media (prefers-reduced-motion: reduce)")
        assertTrue("*, *::before, *::after" in block, "the block picks which elements lose their motion")
        listOf("animation-duration", "transition-duration").forEach {
            assertTrue(Regex("$it\\s*:\\s*\\.01ms\\s*!important").containsMatchIn(block),
                "the reduced-motion block no longer takes the time out of $it")
        }
        // A short duration does not stop a looping animation, it only makes each loop short: the count
        // has to come down to one as well, or a spinner keeps spinning with the setting on.
        assertTrue(Regex("animation-iteration-count\\s*:\\s*1\\s*!important").containsMatchIn(block),
            "the reduced-motion block no longer stops an animation that would otherwise loop")
        // .01ms and not 0s, so a transitionend or animationend still fires for anything waiting on one.
        assertFalse(Regex("duration:\\s*0s").containsMatchIn(block),
            "a zero duration strands every animationend listener in the app")
        assertTrue(base.lastIndexOf("prefers-reduced-motion") > base.lastIndexOf(":focus-visible"),
            "the reduced-motion block is no longer the last rule in base.css")
        // The fold's widths are app.css's business, and a motion query is not a width. Stage 134 added the second
        // of them — 520px, where a row of fields stops having room for two — so the pin is no longer "one
        // breakpoint" but "exactly the widths this app has, and both of them named": a count alone would pass
        // on a stylesheet that had traded the drawer's 900px for somebody's 768px.
        assertEquals(2, Regex("@media\\s*\\(").findAll(code(appCss)).count(),
            "app.css grew or lost a width; the reduced-motion block belongs in base.css and is not one of these")
        assertTrue("@media (max-width: 900px)" in code(appCss), "the drawer lost its breakpoint")
        assertTrue("@media (max-width: 520px)" in code(appCss), "the detail strip lost its narrowest row")
        assertEquals(3, occurrences(base, "!important"), "!important is this block's alone, three times")
        assertEquals(0, occurrences(code(appCss), "!important"), "app.css has never needed it and does not start")
    }

    // ---- 8. the set -----------------------------------------------------------------------------------

    @Test
    fun `every control that answers a pointer answers it over time`() {
        val list = transitionSet()
        assertEquals(pointerControls.map { it.first }.toSet(), list,
            "the pointer transition list and the controls this test knows about have drifted apart")
        for ((control, hover) in pointerControls) {
            assertTrue(control in list, "$control is not in the transition rule")
            assertTrue(hover in all, "\"$hover\" is gone, so $control has a transition with nowhere to travel")
        }
        // The app's own submit button is in base.css, because the login page is the one that most needs it.
        val submit = body("button.submit, a.submit", baseCss)
        assertTrue("transition: background-color var(--dur-1) var(--ease-std)" in submit,
            "the login button changes colour instantly under a cursor")
        assertEquals(1, occurrences(code(baseCss), "button.submit:active"), "one press rule, not a pile of them")
        assertTrue(
            "button.submit:active:not(:disabled), a.submit:active { transform: translateY(1px); }" in code(baseCss),
            "a press is not answered before the request it starts, or a disabled button is allowed to move",
        )
        // The drawer's slide is tokenised too, which is what lets the reduced-motion block reach it.
        assertTrue("transition: transform var(--dur-2) var(--ease-std)" in all,
            "the drawer slides on its own clock again, outside the table")
    }
}
