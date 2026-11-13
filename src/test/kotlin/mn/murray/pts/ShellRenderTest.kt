package mn.murray.pts

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.html.html
import kotlinx.html.stream.createHTML
import mn.murray.pts.db.Migrations
import mn.murray.pts.domain.Member
import mn.murray.pts.repository.ProjectRepository
import mn.murray.pts.repository.TeamRepository
import mn.murray.pts.repository.UserRepository
import mn.murray.pts.service.ProjectService
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The app shell's small-screen navigation (Stage 119), read as markup and as the two files that make
 * it work.
 *
 * `appPage()` is one function for every page, so this test renders it once and measures the contract
 * the breakpoint depends on: the sidebar answers to `#appSidebar`, the page head carries a real
 * `<button id="navToggle">` whose `aria-controls` names that drawer and whose `aria-expanded` starts
 * false, the backdrop exists but is hidden, and the title still sits in a `.page-title` wrapper so the
 * page's actions keep the row's far edge.
 *
 * The stylesheet half is read because a breakpoint cannot be seen in markup: app.css answers to two widths and
 * only two — 900px, where Stage 119 folds the shell, and the 520px **Stage 134** added — and inside the wider one
 * the drawer is full width and off-canvas under `html.js`, the button is the only visible opener, the backdrop is
 * shown only while `body.nav-open`, and both card boards become one column a thumb pages through. The script half is
 * the mirror image: `app.js` puts the `js` class on the document (so a scripting-off reader gets the
 * stacked sidebar instead of an unopenable drawer), and it owns the toggle, Escape, the backdrop and
 * the resize that closes a drawer the desktop no longer shows.
 *
 * Stage 120's answer to the same shrink is measured here as well: the dense fixed-layout sheets
 * (projects, Team, the two forecast ledgers, Lesson learnt) keep a minimum width and their panels
 * scroll sideways, so a narrow window no longer prints "S 0 1" and "C O D E" one letter a line.
 *
 * The sidebar's own **Pinned projects** panel is measured here too, since **Stage 127** made it everybody's: any
 * signed-in account may pin for itself, and each row now leads with the project's ID — `projects.code`, the same
 * value the Projects sheet's `ID` column prints — so a reader who pins three "Road upgrade"s can tell them apart.
 * The panel is per-account, which the last two tests read from two sidebars in one database.
 *
 * **Stage 128** adds the drawer's second control: a close button printed *inside* `#appSidebar`, which the
 * breakpoint shows only while the drawer is open. Markup cannot say that, so the same three halves are read: the
 * button and its position in the drawer's own order (behind the link a reader is sent to when the drawer opens),
 * the rules that decide when it shows and where it sits, and the wiring that makes it run the close path the
 * backdrop and Escape already run rather than a second copy of it.
 *
 * **Stage 129** gave that button a second drawing of the same glyph — outlined for the light theme, solid for the
 * dark one — so the markup case reads both svgs inside the one button, and a further case reads the stylesheet for
 * the four rules that choose between them and for the absence of any such choice inside the breakpoint: a theme is
 * not a width, so resizing must never swap the glyph.
 *
 * **Stage 130** gives that same corner button a desktop meaning: it hides the sidebar, and a slim tab on the page's
 * left edge brings it back. So the shell now prints a second disclosure *outside* `#appSidebar`, the stylesheet has
 * rules above the breakpoint that the breakpoint answers one for one, and `app.js` reads one `localStorage` key
 * before the first paint. Three more cases read exactly those three files; the drawer's cases above are unchanged,
 * because a phone reader gets precisely what Stage 128 left them with.
 *
 * **Stage 134** finishes the checklist's third group, and it lands here because it lands in the same three files:
 * the phone's bottom tab bar is shell markup with a breakpoint hiding it, the drawer's second opener is the bar's
 * own *Menu* tab (so the "every closer" case now pins two openers on one close path, and which of them a close
 * returns focus to), and the project page's detail strip — the checklist's "KPI row" — takes two fields to a row
 * at 900px and one at 520px, which is why app.css answers to a second width. The Kanban swipe that replaced
 * Stage 119's two-up wrap is measured in the breakpoint case, and the search page the *Search* tab opens has a
 * case of its own in [SearchRenderTest].
 */
class ShellRenderTest {

    private lateinit var ds: HikariDataSource

    private val reader = "EMP0001"

    /** A second signed-in account, for the Stage 127 promise that a pin belongs to whoever pressed it. */
    private val colleague = "EMP0002"

    @BeforeEach
    fun setUp(@TempDir dir: File) {
        ds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${File(dir, "shell.db").absolutePath}"
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            }
        )
        Migrations.migrate(ds)
        Database.connect(ds)
        TeamRepository.upsert(Member(reader, "Munkhtsetseg", "Operations", "Project Manager"))
        UserRepository.insert(reader, "Munkhtsetseg", "x", "Manager", "munkho@murray.mn", null, false, false)
        TeamRepository.upsert(Member(colleague, "Bat-Erdene", "Workshop", "Engineer"))
        UserRepository.insert(colleague, "Bat-Erdene", "x", "Viewer", "bat@murray.mn", null, false, false)
    }

    @AfterEach
    fun tearDown() = ds.close()

    /** The whole document for one page: `createHTML` needs an `HTML` receiver, and `appPage` draws the shell. */
    private fun page(): String = page(reader)

    /** The same shell as [colleague] or anybody else, so a test can compare two accounts' sidebars. */
    private fun page(empNo: String): String = createHTML().html {
        appPage(UserSession(empNo), "projects", "Projects") { }
    }

    private fun appCss(): String = File("src/main/resources/static/css/app.css").readText()
    private fun baseCss(): String = File("src/main/resources/static/css/base.css").readText()
    private fun appJs(): String = File("src/main/resources/static/js/app.js").readText()

    /**
     * That a box is drawn from the scale *and* the scale step is still the pixel the box was measured at. Stage A
     * turned the literals in app.css into `var(--…)`; a test that pinned one of those literals now pins both
     * halves, because otherwise re-typing the ramp in base.css would keep passing a test that claims a size.
     */
    private fun stepIs(token: String, pixel: String) {
        val root = Regex(":root\\s*\\{([^}]*)\\}").find(baseCss())?.groupValues?.get(1).orEmpty()
        val value = Regex(Regex.escape(token) + "\\s*:\\s*([^;]+)")
            .find(root)?.groupValues?.get(1)?.trim()
        assertEquals(pixel, value, "$token is not $pixel in the scale table — the shape these tests claim is that pixel")
    }

    /**
     * One project row, written straight to the table. The sidebar panel reads [ProjectService.all], which is the
     * `projects` table alone, so a pinned project needs no board and no cards to be listed.
     */
    private fun project(code: String, name: String): Int = ProjectRepository.insert(
        code = code, name = name, status = "In progress", progress = 0,
        managerEmpNo = reader, due = "2027-03-30", type = "Site projects",
    )

    /**
     * The sidebar's Pinned projects rows, oldest pin first, each as its own contents. `createHTML` pretty-prints,
     * so the document is first collapsed to one line — and a pinned row holds no nested `div`, so the first
     * `</div>` after its opening tag is the row's own.
     */
    private fun pinnedRows(html: String): List<String> =
        Regex("<div class=\"pinned-row\">(.*?)</div>")
            .findAll(html.replace(Regex("\\s+"), " "))
            .map { it.groupValues[1].trim() }
            .toList()

    @Test
    fun `the page head carries the only opener, and it names the drawer it opens`() {
        val html = page()
        assertTrue(
            Regex("<aside[^>]*class=\"sidebar\"[^>]*id=\"appSidebar\"|<aside[^>]*id=\"appSidebar\"[^>]*class=\"sidebar\"")
                .containsMatchIn(html),
            "the sidebar lost the id the hamburger controls",
        )
        val button = Regex("<button[^>]*id=\"navToggle\"[^>]*>").find(html)?.value
            ?: error("the page head has no hamburger")
        assertTrue(button.contains("class=\"nav-toggle\""), "the hamburger is not styled by the breakpoint: $button")
        assertTrue(button.contains("type=\"button\""), "the hamburger would submit a form it sits in")
        assertTrue(button.contains("aria-controls=\"appSidebar\""), "the hamburger does not name its drawer: $button")
        assertTrue(button.contains("aria-expanded=\"false\""), "the drawer starts open, or says nothing: $button")
        assertTrue(button.contains("aria-label=\"Open navigation\""), "the hamburger has no name: $button")
        assertEquals(3, Regex("<span class=\"nav-bar\"></span>").findAll(html).count(), "the hamburger is not three bars")
        // The button and the title share a wrapper, so .page-head keeps two children and the actions stay right.
        val title = html.substringAfter("<div class=\"page-title\">").substringBefore("</div>")
        assertTrue(title.contains("id=\"navToggle\""), "the hamburger left the title's own line")
        assertTrue(title.contains("<h1>Projects</h1>"), "the page title left its wrapper")
        val backdrop = Regex("<div[^>]*class=\"nav-backdrop\"[^>]*>").find(html)?.value
            ?: error("the drawer has no backdrop to press")
        assertTrue(backdrop.contains("id=\"navBackdrop\""), "the backdrop has no id: $backdrop")
        assertTrue(backdrop.contains("aria-hidden=\"true\""), "the backdrop is announced to a screen reader: $backdrop")
    }

    /**
     * The drawer's own closer (Stage 128), as markup: a real disclosure button inside `#appSidebar`, drawn with
     * the house icon so it needs no font, and sitting behind the logo link so the drawer's opening focus —
     * `querySelector('a, button')` — still lands on somewhere to go rather than on an offer to leave. Since
     * Stage 129 it carries two drawings of one glyph, and this case pins that the button holds both of them —
     * which one is visible is the stylesheet's decision, pinned by the case after it.
     */
    @Test
    fun `the drawer carries its own close button, behind the link it opens on`() {
        val html = page()
        val aside = html.substringAfter("<aside").substringBefore("</aside>")
        val button = Regex("<button[^>]*id=\"navClose\"[^>]*>.*?</button>", RegexOption.DOT_MATCHES_ALL)
            .find(aside)?.value
            ?: error("the drawer has no close button of its own")
        assertTrue(button.contains("class=\"nav-close\""), "the close button is not styled by the breakpoint: $button")
        assertTrue(button.contains("type=\"button\""), "the close button would submit a form it sits in")
        assertTrue(button.contains("aria-controls=\"appSidebar\""), "the close button does not name the drawer it closes: $button")
        assertTrue(button.contains("aria-label=\"Close navigation\""), "the close button has no name: $button")
        // One control owns the state; the closer only ever acts on an open drawer, so it has nothing to report.
        assertFalse(button.contains("aria-expanded"), "the close button repeats the opener's state: $button")
        // The two drawings (Stage 129): the panel with its divider and a left chevron, once in strokes and once
        // solid. The regexes must reach the closing tag — an opening-tag-only match cannot see a glyph.
        val outline = Regex("<svg class=\"glyph-outline\"[^>]*>.*?</svg>", RegexOption.DOT_MATCHES_ALL)
            .find(button)?.value
            ?: error("the closer has no outlined drawing for the light theme")
        val filled = Regex("<svg class=\"glyph-filled\"[^>]*>.*?</svg>", RegexOption.DOT_MATCHES_ALL)
            .find(button)?.value
            ?: error("the closer has no solid drawing for the dark theme")
        assertTrue(
            outline.contains("fill=\"none\"") && outline.contains("stroke=\"currentColor\"") &&
                outline.contains("<rect x=\"3\" y=\"3\" width=\"18\" height=\"18\" rx=\"2\"/>") &&
                outline.contains("<path d=\"M9 3v18\"/>") &&
                outline.contains("<path d=\"m16 15-3-3 3-3\"/>"),
            "the outlined drawing is not the panel-and-chevron glyph: $outline",
        )
        assertTrue(
            filled.contains("fill=\"currentColor\"") && filled.contains("fill-rule=\"evenodd\"") &&
                filled.contains("M9 3h1.6v18H9z") && filled.contains("M15.5 7.5 11 12l4.5 4.5h2L13 12l4.5-4.5z"),
            "the solid drawing does not cut its own divider and chevron out of the block: $filled",
        )
        // Its place in the drawer's own order, not just its presence: the logo link before it, the nav after it.
        val logo = aside.indexOf("mms-logo.png")
        val close = aside.indexOf("id=\"navClose\"")
        val nav = aside.indexOf("<nav>")
        assertTrue(logo in 0 until close, "the close button came before the logo, so it is what a reader is focused on")
        assertTrue(close < nav, "the close button left the top of the drawer and sits among the links")
        // And it is the drawer's control, not a second hamburger: the page head still carries exactly one button.
        val head = html.substringAfter("<div class=\"page-title\">").substringBefore("</div>")
        assertTrue(head.contains("id=\"navToggle\"") && !head.contains("id=\"navClose\""),
            "the close button moved into the page head")
        assertEquals(1, Regex("id=\"navClose\"").findAll(html).count(), "the shell printed the close button twice")
    }

    @Test
    fun `one breakpoint at 900px takes the drawer off-canvas and hands the boards to a swipe`() {
        val css = appCss()
        // At-rules, not the word: the comments above and inside this block name `@media` themselves. Stage 134 made
        // this a count of two — the fold's 900px and the 520px the detail strip's narrowest row needs — and names
        // both, because a bare count would pass on a stylesheet that had traded one of them for somebody's 768px.
        assertEquals(2, Regex("@media\\s*\\(").findAll(css).count(), "app.css grew or lost a width")
        assertTrue(css.contains("@media (max-width: 520px)"), "the detail strip lost the width it drops to one at")
        val block = css.substringAfter("@media (max-width: 900px)").substringBefore("@media (max-width: 520px)")
        assertTrue(css.contains(".nav-toggle, .nav-backdrop, .nav-close, .sidebar-tab { display: none; }"),
            "the hamburger, the backdrop, the closer or the reopen tab prints before a rule has switched it on")
        listOf(
            "html.js .sidebar",
            "transform: translateX(-100%)",
            "width: 100%",
            "html.js body.nav-open .sidebar { transform: none; }",
            "html.js .nav-toggle",
            "display: inline-flex",
            "html.js body.nav-open .nav-backdrop { display: block; }",
            // Stage 134: the boards swipe. `mandatory` is the half that makes a flick settle on a column instead
            // of stopping between two, `start` is the half that puts the next column's left edge under the thumb,
            // and the basis is 88% rather than 100% so the column after it shows — the only affordance a row that
            // scrolls sideways has. The dashed add-a-column box rides the same basis: it is a child of the row.
            "scroll-snap-type: x mandatory",
            "scroll-snap-align: start",
            ".kanban > .kcol, .kanban > .lane-add-box",
            "flex: 0 0 88%",
        ).forEach { rule ->
            assertTrue(block.contains(rule), "the breakpoint lost \"$rule\"")
        }
        // Stage 119's two-up wrap is *gone*, not shadowed by the flex rule that replaced it: a
        // `grid-template-columns` left in the block would be a declaration nothing reads, and the next person to
        // measure this board would be measuring a rule the board no longer obeys.
        assertFalse(block.contains("repeat(2, minmax(0, 1fr))"),
            "the boards still carry the two-up wrap the swipe replaced")
        // Without scripting the drawer is never off-canvas, because the rule that takes it there is scoped to html.js.
        assertFalse(
            Regex("\\n    \\.sidebar \\{[^}]*translateX").containsMatchIn(block),
            "a scripting-off reader gets an unopenable drawer",
        )
        assertTrue(css.contains("translateX(-100%)"), "the drawer does not slide in from the left")
    }

    /**
     * When the drawer's closer is allowed to be seen (Stage 128). Three things have to be true at once for the
     * button to print, and each one is a reader who would otherwise get a control that does nothing: the drawer
     * must actually be a drawer (the breakpoint), it must actually be open (`body.nav-open`), and it must
     * actually be closable by script (`html.js`).
     */
    @Test
    fun `the close button shows only on an open drawer, in its top-right corner`() {
        val css = appCss()
        val block = css.substringAfter("@media (max-width: 900px)")
        val show = Regex("[^\\n]*\\.nav-close[^\\n]*\\{").findAll(block).map { it.value.trim() }.toList()
        assertTrue(show.isNotEmpty(), "the breakpoint stopped styling the drawer's close button")
        // Stage 130 gave the same button a desktop meaning, so the breakpoint now carries two rules for it: the
        // desktop version stood down at this width, and Stage 128's rule that prints it on an open drawer. The gate
        // that matters is the one that did not move — nothing at this width may leave a closer on a page that has no
        // drawer to close.
        val shown = show.filter { it.endsWith(".nav-close {") }
        shown.forEach {
            assertTrue(
                it.startsWith("html.js body.nav-open") || it == "html.js .nav-close {",
                "$it prints a closer where nothing can be closed",
            )
        }
        assertTrue(shown.any { it.startsWith("html.js body.nav-open") }, "the breakpoint stopped showing the closer at all")
        assertTrue(block.contains("html.js .nav-close { display: none; }"),
            "the desktop closer is never stood down at this width")
        assertTrue(
            block.indexOf("html.js .nav-close { display: none; }") < block.indexOf("html.js body.nav-open .nav-close {"),
            "the breakpoint hides the closer after it shows it, so a phone drawer would open without one",
        )
        val box = block.substringAfter("html.js body.nav-open .nav-close {").substringBefore("}")
        listOf(
            "display: inline-flex", "position: fixed", "top: 12px", "right: 12px",
            "z-index: 61", "width: 40px", "height: 40px", "border-radius: var(--r-md)",
        ).forEach {
            assertTrue(box.contains(it), "the closer is not the hamburger's box in the drawer's top-right: missing $it")
        }
        stepIs("--r-md", "8px")
        // 60 is the drawer, 55 the page under it: the closer has to be above the panel it belongs to, and the
        // panel is off-canvas by transform, which is what carries the fixed button out of sight with it.
        assertTrue(block.contains("html.js .sidebar"), "the drawer lost the rule the close button rides on")
    }

    /**
     * Which drawing the closer shows (Stage 129). The light theme gets the stroke, the dark theme the solid
     * block — and the choice is made by `data-theme`, never by the width, because a theme does not change when
     * the window does. The button's own box stays the same either way, so the swap must not be able to make the
     * control wider, taller, or visible on a page where it has nothing to close.
     */
    @Test
    fun `the closer's glyph follows the theme, not the width`() {
        val css = appCss()
        listOf(
            ".nav-close .glyph-outline { display: block; }",
            ".nav-close .glyph-filled { display: none; }",
            "html[data-theme=\"dark\"] .nav-close .glyph-outline { display: none; }",
            "html[data-theme=\"dark\"] .nav-close .glyph-filled { display: block; }",
        ).forEach { rule ->
            assertTrue(css.contains(rule), "the theme stopped choosing the closer's drawing: missing \"$rule\"")
        }
        // Outside the breakpoint, and nothing inside it picks a drawing: width decides whether the button exists,
        // the theme only which of its two faces is turned to the reader.
        val block = css.substringAfter("@media (max-width: 900px)").substringBefore("\n}")
        assertFalse(block.contains("glyph-outline") || block.contains("glyph-filled"),
            "the breakpoint decides the closer's drawing, so a theme change would need a resize")
        // The sizing rule must not show both at once, or the swap is only a second glyph behind the first.
        assertFalse(Regex("\\.nav-close svg \\{[^}]*display").containsMatchIn(css),
            "the sizing rule prints both drawings of the glyph")
    }

    /**
     * The reopen tab (Stage 130), as markup: a disclosure outside the panel it opens, because a hidden sidebar
     * cannot carry the only control that brings it back. It is a real `<button>` like the other two, it names
     * `#appSidebar`, and its `aria-expanded` starts **true** — unlike the hamburger's false, because a desktop page
     * ships with the sidebar in the layout until a reader asks for the width back.
     */
    @Test
    fun `the reopen tab is on the page, outside the panel it opens`() {
        val html = page()
        val tab = Regex("<button[^>]*id=\"sidebarTab\"[^>]*>.*?</button>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.value
            ?: error("a hidden sidebar would leave no way back")
        assertTrue(tab.contains("class=\"sidebar-tab\""), "the tab is not styled by the stylesheet: $tab")
        assertTrue(tab.contains("type=\"button\""), "the tab would submit a form it sits in")
        assertTrue(tab.contains("aria-controls=\"appSidebar\""), "the tab does not name the panel it opens: $tab")
        assertTrue(tab.contains("aria-expanded=\"true\""), "the shell ships with the sidebar already hidden: $tab")
        assertTrue(tab.contains("aria-label=\"Show sidebar\""), "the tab has no name: $tab")
        // One drawing, in strokes, like every other icon the shell draws — and pointing the way the panel comes from.
        val svg = Regex("<svg[^>]*>.*?</svg>", RegexOption.DOT_MATCHES_ALL).find(tab)?.value
            ?: error("the tab has no glyph")
        assertTrue(
            svg.contains("stroke=\"currentColor\"") && svg.contains("<path d=\"m13 9 3 3-3 3\"/>"),
            "the tab's glyph is not a panel opening to the right: $svg",
        )
        assertFalse(svg.contains("glyph-"), "the tab grew the closer's two-face treatment: $svg")
        // Its place in the document, not just its presence: a sibling of the sidebar, and the shell's only one.
        val aside = html.substringAfter("<aside").substringBefore("</aside>")
        assertFalse(aside.contains("id=\"sidebarTab\""), "the reopen tab is inside the panel that hides with it")
        assertEquals(1, Regex("id=\"sidebarTab\"").findAll(html).count(), "the shell printed the tab twice")
        // …and the closer it partners with is still the drawer's, printed once, inside the panel.
        assertEquals(1, Regex("id=\"navClose\"").findAll(html).count(), "the shell grew a second closer")
    }

    /**
     * The fold itself (Stage 130), read as the stylesheet's ordering: the desktop rules sit **above** the
     * breakpoint and the breakpoint answers each one, so a phone page gets the drawer it had before — the panel
     * back in the layout, the closer gated on an open drawer, and no edge tab on a screen where the hamburger is
     * the opener. Same selector, later source, is what makes that true, which is why the order is the assertion.
     */
    @Test
    fun `a wide page folds the sidebar away and the breakpoint answers it back`() {
        val css = appCss()
        // The whole at-rule, not `@media`: the stylesheet's own comments name the breakpoint in prose (line 77 is
        // one of them), and cutting at the word alone would stop the read inside a sentence.
        val mediaAt = css.indexOf("@media (max-width: 900px)")
        assertTrue(mediaAt > 0, "the stylesheet lost the breakpoint the drawer depends on")
        listOf(
            "html.js body.sidebar-hidden .sidebar { display: none; }",
            "html.js body.sidebar-hidden .sidebar-tab {",
            "html.js .nav-close {",
        ).forEach { rule ->
            assertTrue(css.indexOf(rule) in 0 until mediaAt, "\"$rule\" belongs above the breakpoint, not inside it")
        }
        // The panel leaves the flex row rather than animating out of it, so `main` (flex: 1) takes the whole width.
        val corner = css.substringBefore("@media (max-width: 900px)").substringAfter("html.js .nav-close {").substringBefore("}")
        listOf(
            "display: inline-flex", "position: absolute", "top: 10px", "right: 10px",
            "width: 34px", "height: 34px", "border-radius: var(--r-md)",
        ).forEach {
            assertTrue(corner.contains(it), "the desktop closer is not a chip in the sidebar's corner: missing $it")
        }
        val tab = css.substringAfter("html.js body.sidebar-hidden .sidebar-tab {").substringBefore("}")
        listOf(
            "display: inline-flex", "position: fixed", "top: 10px", "left: 0",
            "width: 22px", "height: 72px", "border-radius: 0 var(--r-md) var(--r-md) 0",
        ).forEach {
            assertTrue(tab.contains(it), "the reopen tab is not a slim strip on the page's left edge: missing $it")
        }
        // Both cut-outs are the app's ordinary corner radius, and that claim is a token plus a pixel: the rules ask
        // for `--r-md`, and `--r-md` is still the 8px they were drawn at.
        stepIs("--r-md", "8px")
        // Stage 131: the tab stays at the top of the edge, where the closer it answers to sits, rather than being
        // floated to the middle of the window. `translateY(-50%)` against a `top: 50%` is what centred it.
        listOf("top: 50%", "translateY", "transform").forEach {
            assertTrue(!tab.contains(it), "the reopen tab is being placed by the middle of the edge again: $it")
        }
        assertTrue(
            Regex("top: (\\d+)px").find(corner)?.groupValues?.get(1) ==
                Regex("top: (\\d+)px").find(tab)?.groupValues?.get(1),
            "the reopen tab no longer starts at the height the closer does",
        )
        // Stage 134 gave app.css a second breakpoint, so "everything after the 900px query" is no longer a block:
        // cut it at 520px or these three assertions would happily pass on a rule the fold had put in the phone's
        // own block, which is exactly the leak they exist to catch.
        val block = css.substringAfter("@media (max-width: 900px)").substringBefore("@media (max-width: 520px)")
        listOf(
            "html.js body.sidebar-hidden .sidebar { display: flex; }",
            "html.js .nav-close { display: none; }",
            "html.js body.sidebar-hidden .sidebar-tab { display: none; }",
        ).forEach { rule ->
            assertTrue(block.contains(rule), "the breakpoint no longer answers \"$rule\"")
        }
        // A hidden sidebar is a browser's memory, not anybody's data, so it must not reach the stylesheet as a
        // width of its own: the fold *reads* `sidebar-hidden` in the 900px block — three times above, each one
        // standing a desktop control down where the desktop has gone — and never *sets* it, and never gets a
        // third block. Two breakpoints are the drawer's and the detail strip's, and both are named so a new one
        // has to be added here deliberately rather than slipping in.
        assertEquals(
            listOf("900px", "520px"),
            Regex("@media\\s*\\(max-width:\\s*(\\d+px)\\)").findAll(css).map { it.groupValues[1] }.toList(),
            "app.css answers to a width this test does not know about",
        )
    }

    /**
     * The remembered fold (Stage 130), as script. Three things the markup and the stylesheet cannot say: the state
     * is read from one key before anything is painted — the same argument Stage 119 makes for the `js` class — the
     * two controls are one toggle, and the desktop half of that toggle stands down on a narrow page so the drawer's
     * own close path is left exactly as Stage 128 shipped it.
     */
    @Test
    fun `the fold is remembered in one key, read before the paint`() {
        val js = appJs()
        listOf(
            "localStorage.getItem('pts-sidebar')",
            "document.body.classList.add('sidebar-hidden')",
            "classList.toggle('sidebar-hidden'",
            "var SIDEBAR_KEY = 'pts-sidebar'",
            "localStorage.setItem(SIDEBAR_KEY, 'hidden')",
            "localStorage.removeItem(SIDEBAR_KEY)",
            "navClose.addEventListener('click', toggleSidebar)",
            "sidebarTab.addEventListener('click', toggleSidebar)",
            "if (!isWide()) return;",
            "aria-label', isWide() ? 'Hide sidebar' : 'Close navigation'",
            "aria-expanded', sidebarHidden() ? 'false' : 'true'",
        ).forEach { piece ->
            assertTrue(js.contains(piece), "app.js lost \"$piece\"")
        }
        // One name the file knows, in two places: the pre-paint read, which cannot reach a constant declared below
        // it, and the constant every write goes through.
        assertEquals(2, Regex("'pts-sidebar'").findAll(js).count(), "the sidebar state grew a second key")
        // Before the controls are found, and so before the reader could see the panel they chose to hide.
        val marked = js.indexOf("document.documentElement.classList.add('js')")
        val applied = js.indexOf("localStorage.getItem('pts-sidebar')")
        val wired = js.indexOf("getElementById('navClose')")
        assertTrue(marked in 0 until applied, "the fold is read before the shell is marked as scripted")
        assertTrue(applied < wired, "the remembered fold is applied only after the controls are wired, so the page jumps")
        // And the drawer's own close path is untouched by a second listener on the same button.
        assertTrue(js.contains("navClose.addEventListener('click', closeNav)"),
            "the closer stopped handing an open drawer to the shared close")
    }

    @Test
    fun `dense sheets keep a readable width instead of breaking every word to letters`() {
        val css = appCss()
        // The table still fills a wide panel through its percentages; the floor only stops it shrinking
        // past legibility, and the panel scrolls when the window is narrower than the floor.
        // Two selector lines because **Stage 134** added the calendar's month grid: `table.calendar-grid`
        // carries no `grid` class of its own, so `:has(table.grid)` never saw it and the one page holding a
        // seven-column sheet was the one page without a sideways scroll to reach its columns.
        listOf(
            ".card-panel:has(table.grid), .board-results:has(table.grid),",
            ".card-panel:has(table.calendar-grid) { overflow-x: auto; }",
        ).forEach { selector ->
            assertTrue(css.contains(selector), "a dense sheet has no sideways scroll to reach the columns that do not fit: $selector")
        }
        listOf(
            "table.projects-table { min-width: 1040px; }",
            "table.team-sheet { min-width: 1000px; }",
            "table.fc-table { min-width: 1280px; }",
            "table.fc-done-table { min-width: 1100px; }",
            "table.lessons-table { min-width: 1000px; }",
        ).forEach { rule ->
            assertTrue(css.contains(rule), "a dense sheet lost its readable floor: $rule")
        }
        // `associate` keeps the override's width, because the Stage 120 block comes after the base rules.
        val projects = Regex("table\\.projects-table col\\.(\\S+) \\{ width: (\\d+)%; \\}")
            .findAll(css).associate { it.groupValues[1] to it.groupValues[2].toInt() }
        assertEquals(10, projects.size, "the projects board lost a column")
        assertEquals(100, projects.values.sum(), "the projects board's columns no longer add up to the whole")
        assertEquals(6, projects["c-code"], "the code column cannot fit S-01 at the floor")
        assertEquals(11, projects["c-manager"], "a name breaks inside itself at the floor")
    }

    @Test
    fun `app js marks the document and owns the drawer's open state`() {
        val js = appJs()
        listOf(
            "document.documentElement.classList.add('js')",
            "getElementById('navToggle')",
            "getElementById('appSidebar')",
            "getElementById('navBackdrop')",
            "classList.toggle('nav-open'",
            "aria-expanded",
            "'Escape'",
            "(min-width: 901px)",
        ).forEach { piece ->
            assertTrue(js.contains(piece), "app.js lost \"$piece\"")
        }
        // Stage 128's second closer, found the same way and hung on the same close path.
        listOf("getElementById('navClose')", "navClose.addEventListener('click', closeNav)").forEach { piece ->
            assertTrue(js.contains(piece), "app.js lost \"$piece\"")
        }
    }

    /**
     * The drawer's closer runs the drawer's own close (Stage 128) rather than a second copy of it: one path keeps
     * the backdrop, Escape, the hamburger, the button and — since **Stage 134** — the bottom bar's *Menu* tab
     * agreeing about what closing means: the `nav-open` class off, the opener's label back to "Open navigation",
     * and focus returned to whichever control opened it, so a keyboard reader is not left on a button that just
     * vanished off-screen nor teleported to the top of the page from the bottom of it.
     */
    @Test
    fun `every closer hands the drawer to the one function that closes it`() {
        val js = appJs()
        assertFalse(js.contains("navClose.addEventListener('click', function"),
            "the close button grew its own copy of closing, which the other paths will not share")
        // The backdrop and the button are one-line closer handlers; the two controls that also open own a branch
        // each and call the same two functions from inside it.
        listOf("navBackdrop.addEventListener('click', closeNav)", "navClose.addEventListener('click', closeNav)")
            .forEach { handler -> assertTrue(js.contains(handler), "$handler is gone: the drawer grew a second close") }
        // Both openers pass themselves, which is what makes a close land focus back on the control that was
        // pressed rather than on a default: the hamburger is at the top of the page and the Menu tab at the
        // bottom, and only one of them is under the reader's thumb.
        listOf(
            "if (navOpen()) closeNav(); else openNav(navToggle);",
            "if (navOpen()) closeNav(); else openNav(mtabMenu);",
        ).forEach { branch ->
            assertTrue(js.contains(branch), "an opener no longer closes through the shared path: $branch")
        }
        assertTrue(js.contains("openedBy.focus()"), "a close no longer returns focus to the control that opened it")
        assertTrue(js.contains("getElementById('mtabMenu')"), "the bottom bar's Menu tab is not wired at all")
        // The tab's own state is kept in step by the same `setNav`, not by a second listener of its own.
        assertTrue(js.contains("mtabMenu.setAttribute('aria-expanded'"),
            "the Menu tab's aria-expanded is no longer set by the drawer's one state function")
        // Opening still hands focus to the drawer's first link, which the markup test proves is the logo.
        assertTrue(js.contains("navDrawer.querySelector('a, button')"),
            "the drawer's opening focus left the first link behind")
    }

    /**
     * **Stage 134** adds the checklist's bottom tab bar: five controls at the foot of a narrow page — *Home, My
     * tasks, To-do, Search, Menu* — four of them links and the fifth a `<button>` that opens the drawer Stage 119
     * built. Like the hamburger it is printed by the shell on every page and hidden above 900px by `app.css`,
     * because the shell is drawn by one function for every page and a bar that came and went with the window's
     * width would have to be redrawn to appear. The active tab is named by the page's own `section` — the same
     * word that lights the sidebar's `.nav-item active` — so the two cannot disagree about where the reader is.
     */
    @Test
    fun `every page prints the phone's row of five tabs, with the page's own tab lit`() {
        val html = page()
        val bar = Regex("<nav[^>]*id=\"mobileTabs\"[^>]*>").find(html)?.value
            ?: throw AssertionError("the shell no longer prints the bottom bar at all")
        assertTrue(bar.contains("class=\"mtabbar\""), "the bar is not the element the breakpoint shows: $bar")
        assertTrue(bar.contains("aria-label=\"Quick navigation\""),
            "a <nav> that does not say what it navigates is announced as \"navigation\" and nothing more: $bar")
        val tabs = html.substringAfter("id=\"mobileTabs\"").substringBefore("</nav>")
        // Four links, in the order the checklist names them, and the fifth control is the button below.
        val links = Regex("<a([^>]*)>").findAll(tabs).map { it.groupValues[1] }.toList()
        assertEquals(4, links.size, "the bar does not print the four destinations: $links")
        assertEquals(
            listOf("/app/projects", "/app/assigned-tasks", "/app/todo", "/app/search"),
            links.map { Regex("href=\"([^\"]+)\"").find(it)?.groupValues?.get(1) },
            "the bar's four tabs are not the checklist's Home, My tasks, To-do and Search, in that order",
        )
        assertEquals(
            listOf("Home", "My tasks", "To-do", "Search", "Menu"),
            Regex("<span class=\"mtab-label\">([^<]+)</span>").findAll(tabs).map { it.groupValues[1] }.toList(),
            "the bar's captions are not the words the checklist asked for, in the order it asked for them",
        )
        assertEquals(5, Regex("<svg").findAll(tabs).count(), "a tab is missing its glyph, or grew a second one")
        // This page's `section` is `projects`, so the first tab is the one wearing the active class — and only it.
        assertTrue(links[0].contains("class=\"mtab active\""), "the page's own tab is not lit up")
        assertTrue(links[0].contains("aria-current=\"page\""), "a lit tab that does not say so is lit for eyes only")
        assertEquals(1, Regex("mtab active").findAll(tabs).count(), "more than one tab claims to be the page")
        // The fifth is the drawer's disclosure from the bottom of the screen, so it is a button naming the drawer.
        val menu = Regex("<button[^>]*id=\"mtabMenu\"[^>]*>").find(tabs)?.value
            ?: throw AssertionError("the bar's Menu tab is not a button the script can find")
        listOf("class=\"mtab mtab-menu\"", "aria-controls=\"appSidebar\"", "aria-expanded=\"false\"",
            "aria-label=\"Open menu\"").forEach { piece ->
            assertTrue(menu.contains(piece), "the Menu tab lost $piece: $menu")
        }
        // Outside the drawer, like the reopen tab, because a bar that lived inside the panel it opens would be
        // hidden exactly when the reader needed it — the panel is off-canvas until something brings it back.
        assertTrue(html.indexOf("id=\"mobileTabs\"") > html.indexOf("id=\"appSidebar\""),
            "the bottom bar is printed inside the drawer it has a tab for")
    }

    /**
     * The bar's four destinations are everybody's pages, so every account gets all four: *Home* is `/app/projects`
     * where `/app` has always redirected, *My tasks* and *To-do* are the reader's own two sheets, and *Search*
     * answers only with what the reader may open ([searchGroups] is gated). The filter [mobileTabsFor] runs is
     * therefore a no-op today, and this case is what makes it stay an honest one rather than a gate that quietly
     * takes *Home* off a viewer's bar the day somebody adds `"projects"` to `NAV_PERMISSIONS`.
     */
    @Test
    fun `a viewer gets the same four tabs the manager gets`() {
        val manager = page(reader).substringAfter("id=\"mobileTabs\"").substringBefore("</nav>")
        val viewer = page(colleague).substringAfter("id=\"mobileTabs\"").substringBefore("</nav>")
        assertEquals(
            Regex("href=\"([^\"]+)\"").findAll(manager).map { it.groupValues[1] }.toList(),
            Regex("href=\"([^\"]+)\"").findAll(viewer).map { it.groupValues[1] }.toList(),
            "the bar offers a reader fewer doors than the sidebar does, and the sidebar offers all four to both",
        )
        assertEquals(mobileTabsFor(reader), MOBILE_TABS, "a manager's bar is not the four tabs")
        assertEquals(mobileTabsFor(colleague), MOBILE_TABS, "a viewer's bar is not the four tabs")
    }

    /**
     * The bar's stylesheet half: hidden above the breakpoint and printed inside it, `fixed` to the foot of the page
     * with the phone's own gesture strip padded out under it, and the page's content given that distance back so
     * the bar does not sit on the last row of a sheet.
     */
    @Test
    fun `the bar is hidden above 900px and fixed to the foot of the page below it`() {
        val css = appCss()
        val above = css.substringBefore("@media (max-width: 900px)")
        val block = css.substringAfter("@media (max-width: 900px)").substringBefore("@media (max-width: 520px)")
        assertTrue(".mtabbar { display: none; }" in above,
            "the bar is printed on a desktop page, where the sidebar is already the navigation")
        listOf(
            ".mtabbar {",
            "display: flex; position: fixed; left: 0; right: 0; bottom: 0; z-index: 54;",
            "gap: 2px; padding: 6px 6px calc(6px + env(safe-area-inset-bottom));",
            ".mtab.active { color: var(--accent-ink); background: var(--accent-soft); }",
            ".main { padding-bottom: calc(76px + env(safe-area-inset-bottom)); }",
        ).forEach { rule ->
            assertTrue(rule in block, "the breakpoint no longer answers \"$rule\"")
        }
        // Under the drawer's scrim (55) and not over it: when the menu is up the whole page is dimmed and the way
        // down is Stage 128's one close path, not a fifth control offering its own variant of it.
        assertTrue("z-index: 54;" in block, "the bar no longer sits under the drawer's backdrop")
        // The gesture strip is a phone's problem: nothing above the breakpoint pads for one, because nothing
        // above it is fixed to the foot of the page.
        assertFalse("safe-area-inset" in above, "a desktop rule is padding for a gesture strip it cannot have")
        assertEquals(2, Regex("calc\\([^)]*env\\(safe-area-inset-bottom\\)").findAll(block).count(),
            "the inset is not in exactly the two declarations that need it: the bar's own padding and the page's")
        // The active tab wears the accent word Stage 132 measured clear of AA, not the fill colour.
        assertFalse(block.contains(".mtab.active { color: var(--accent-strong)"),
            "the active tab is back to the accent that fails its own contrast on a pale surface")
    }

    /**
     * The checklist's "KPI row" is the project page's nine-cell detail strip, and **Stage 134** gives it two
     * columns at 900px and one at 520px. All nine weight selectors are answered in each block rather than
     * `.detail-strip .ef` alone: `.detail-strip .ef.cat-ef` is (0,3,0) and would outrank a (0,2,0) rule however
     * late in the file it sat, so a two-per-row strip written against `.ef` only would still be wearing the
     * desktop's 2.75 against 1.1 and come out ragged.
     */
    @Test
    fun `the detail strip takes two fields to a row at 900px and one at 520px`() {
        // Comments out first: the block's own prose names `.detail-strip .ef.cat-ef` to explain why all nine
        // weights are answered, and a selector count taken over the prose would count the explanation as rules.
        val css = appCss().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        val nine = Regex("\\.detail-strip \\.ef[^{]*\\{\\s*flex: 1 1 ([^;]+);\\s*\\}")
        val wide = css.substringAfter("@media (max-width: 900px)").substringBefore("@media (max-width: 520px)")
        val phone = css.substringAfter("@media (max-width: 520px)")
        val halves = nine.findAll(wide).map { it.groupValues[1] }.toList()
        val wholes = nine.findAll(phone).map { it.groupValues[1] }.toList()
        assertEquals(1, halves.size, "the 900px block does not answer the strip's weights in one rule: $halves")
        assertEquals(1, wholes.size, "the 520px block does not answer the strip's weights in one rule: $wholes")
        assertEquals("calc(50% - 7px)", halves[0], "two to a row has to give up half the strip's own 14px gap")
        assertEquals("100%", wholes[0], "below 520px two fields no longer fit side by side at a readable size")
        // Nine selectors in each of the two blocks, and the same nine: a tenth weight rule added to the desktop
        // strip is a rule the phone blocks have to answer too, or the strip goes ragged at exactly one width.
        listOf(wide to "900px", phone to "520px").forEach { (block, width) ->
            val selectors = Regex("\\.detail-strip \\.ef[^{}]*\\{").findAll(block).map { it.value }.toList()
            assertEquals(1, selectors.size, "the $width block grew a second rule against the strip")
            assertEquals(9, Regex("\\.detail-strip \\.ef").findAll(selectors[0]).count(),
                "the $width block does not answer all nine of the strip's weight selectors")
        }
        // The last block is the narrowest one, so it wins by source order on a 480px window that matches both.
        assertTrue(css.indexOf("@media (max-width: 520px)") > css.indexOf("@media (max-width: 900px)"),
            "the narrower breakpoint is above the wider one, so a phone keeps two fields to a row")
    }

    @Test
    fun `a pinned row leads with the project's ID, in the order it was pinned`() {
        val haul = project("S-01", "Haul road widening")
        val crusher = project("M-01", "Erdenet crusher liner")
        assertEquals(true, ProjectService.togglePin(reader, haul), "the first pin did not take")
        assertEquals(true, ProjectService.togglePin(reader, crusher), "the second pin did not take")

        val rows = pinnedRows(page())
        assertEquals(2, rows.size, "the sidebar's Pinned projects panel is not the reader's two pins")
        // **Stage 127:** the row opens with the project's ID — the same `projects.code` the Projects sheet prints
        // under its `ID` header — before the name that is the row's only link. Two rows, and the ID is what tells
        // them apart when the names are long enough to ellipsize.
        val first = rows[0]
        assertTrue(
            first.startsWith("<span class=\"code\">S-01</span>"),
            "the first pinned row does not lead with the project's ID: $first",
        )
        assertTrue(
            first.indexOf("<a href=\"/app/projects/$haul\">Haul road widening</a>") > first.indexOf("</span>"),
            "the name link does not follow the ID: $first",
        )
        // Pin order, not id order: the panel sorts by `position`, so the reader's list keeps the shape they made.
        assertTrue(rows[1].contains("class=\"code\">M-01<"), "the second pin is not listed second")
        // The row's own button still writes the same route, which is what turns it back off for this account.
        assertTrue(first.contains("<form action=\"/app/projects/$haul/pin\""), "the pinned row cannot unpin itself")
        assertTrue(Regex("×\\s*</button>").containsMatchIn(first), "the pinned row's button is not the × that removes it")
    }

    @Test
    fun `a pin is the reader's own list, and the × takes it off again`() {
        val haul = project("S-01", "Haul road widening")
        assertEquals(true, ProjectService.togglePin(colleague, haul), "the colleague's pin did not take")
        // Since Stage 127 every account may pin, so the panel is now read by more people than it used to be — and
        // what it shows each of them is still only their own. One table, keyed by employee number, and the route
        // writes the signed-in number rather than anything the request carries.
        assertTrue(
            page().contains("No pinned projects yet"),
            "a colleague's pin showed up on this reader's panel",
        )
        assertTrue(
            page(colleague).contains("<span class=\"code\">S-01</span>"),
            "the account that pinned the project does not see its ID",
        )
        assertEquals(false, ProjectService.togglePin(colleague, haul), "the second press did not unpin")
        assertTrue(page(colleague).contains("No pinned projects yet"), "the × left the row on the panel")
    }

    @Test
    fun `the pinned row gives the ID its own width and the name the rest`() {
        val css = appCss()
        // The row is flex; the ID takes the width its content needs and never shrinks, while the link absorbs the
        // leftover and ellipsises — which is why the link needs `min-width: 0` to be allowed to shrink at all.
        assertTrue(
            css.contains(".pinned-row .code { flex: 0 0 auto;"),
            "the pinned row's ID is not a fixed-content cell",
        )
        assertTrue(
            css.contains(".pinned-row a { flex: 1; min-width: 0;"),
            "a long project name pushes the ID or the × out of the sidebar",
        )
    }
}
