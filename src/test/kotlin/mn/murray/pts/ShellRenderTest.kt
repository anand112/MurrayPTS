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
 * **The desktop rail** supersedes Stage 130's complete hiding: the same corner button now switches between the full
 * sidebar and a 64px icon rail, so no second opener is printed. Every nav link keeps its destination and gains its
 * visible label as `title` / `aria-label`; the stylesheet animates the flex width and answers every rail rule inside
 * the existing 900px breakpoint; `app.js` reads `pts-sidebar` before paint, maps legacy `hidden` to the rail, and
 * keeps the one button's name and `aria-expanded` honest. The drawer itself remains Stage 128's.
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
    fun `a project detail head without page actions keeps only the notification dropdown`() {
        val html = createHTML().html {
            appPage(UserSession(reader), "projects", "Haul road widening") { }
        }
        val head = html.substringAfter("<div class=\"page-head\">").substringBefore("<div class=\"content\">")
        assertFalse(head.contains("class=\"page-actions\""), "the project head still carries a page action")
        assertFalse(head.contains("+ New task"), "the project head still offers New task")
        assertTrue(head.contains("id=\"notif\""), "the notification dropdown left the project head")
    }

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
        // This one button reports the desktop rail state as well as closing an open phone drawer.
        assertTrue(button.contains("aria-expanded=\"true\""), "the shell must start with the sidebar expanded: $button")
        // The two drawings (Stage 129): the panel with its divider and both state chevrons, once in strokes and
        // once solid. The regexes must reach the closing tag — an opening-tag-only match cannot see a glyph.
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
                outline.contains("class=\"fold-chevron\" d=\"m16 15-3-3 3-3\"") &&
                outline.contains("class=\"expand-chevron\" d=\"m10 9 3 3-3 3\""),
            "the outlined drawing is not the panel with both directional chevrons: $outline",
        )
        assertTrue(
            filled.contains("fill=\"currentColor\"") && filled.contains("fill-rule=\"evenodd\"") &&
                filled.contains("M9 3h1.6v18H9z") &&
                filled.contains("class=\"fold-chevron\"") && filled.contains("M15.5 7.5 11 12l4.5 4.5h2L13 12l4.5-4.5z") &&
                filled.contains("class=\"expand-chevron\"") && filled.contains("M10.5 7.5 15 12l-4.5 4.5h-2L13 12l-4.5-4.5z"),
            "the solid drawing does not cut its divider and both chevrons out of the block: $filled",
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
        assertTrue(css.contains(".nav-toggle, .nav-backdrop, .nav-close { display: none; }"),
            "the hamburger, backdrop or closer prints before a rule has switched it on")
        assertFalse(css.contains("sidebar-tab"), "the retired desktop edge tab still competes with the one toggle")
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
     * The rail keeps one real toggle and every destination. The old edge tab is gone because the panel itself is
     * never removed; each nav link is still an anchor with its existing href and active class, and its visible
     * label supplies both the hover title and the accessible name when the rail hides that label.
     */
    @Test
    fun `one corner button owns the rail and every navigation link keeps its name`() {
        val html = page()
        val aside = html.substringAfter("<aside").substringBefore("</aside>")
        assertEquals(1, Regex("id=\"navClose\"").findAll(html).count(), "the shell must have exactly one rail toggle")
        assertFalse(html.contains("sidebarTab") || html.contains("sidebar-tab"), "the retired edge tab still competes")
        val button = Regex("<button[^>]*id=\"navClose\"[^>]*>.*?</button>", RegexOption.DOT_MATCHES_ALL)
            .find(aside)?.value.orEmpty()
        assertTrue(button.contains("type=\"button\""), "the rail toggle is not keyboard-operable as a button")
        assertTrue(button.contains("aria-controls=\"appSidebar\""), "the toggle does not name its panel")
        assertTrue(button.contains("aria-expanded=\"true\""), "the default expanded state is not reported")

        val links = Regex("<a class=\"nav-item[^\"]*\"[^>]*>.*?</a>", RegexOption.DOT_MATCHES_ALL)
            .findAll(aside).map { it.value }.toList()
        assertTrue(links.size > 1, "the fixture rendered no navigation destinations")
        fun attribute(tag: String, name: String): String? = Regex("$name=\"([^\"]+)\"").find(tag)?.groupValues?.get(1)
        links.forEach { link ->
            val label = Regex("<span class=\"label\">([^<]+)</span>").find(link)?.groupValues?.get(1)
                ?: error("a navigation link lost its visible label: $link")
            assertTrue(attribute(link, "href")?.startsWith("/app") == true, "a nav item stopped being a link: $link")
            assertEquals(label, attribute(link, "title"), "the rail hover title is not the existing label: $link")
            assertEquals(label, attribute(link, "aria-label"), "the rail accessible name is not the existing label: $link")
            assertTrue(link.contains("<svg"), "a navigation destination lost its icon: $link")
            assertFalse(Regex("\\son[a-z]+=").containsMatchIn(link), "navigation grew an inline handler")
        }
        assertEquals(1, links.count { it.contains("class=\"nav-item active\"") }, "the current page lost its highlight")
    }

    /**
     * The desktop rail, read as geometry and cascade. The panel remains in the flex row at 64px, its icons stay
     * centred at a comfortable size, long content disappears, counts become dots, and the one corner button points
     * to the result of its next press. Width and flex basis animate with existing tokens; `main` needs no script or
     * fixed width to take the space. The old hide-the-panel and edge-tab model is absent, not shadowed.
     */
    @Test
    fun `a wide page folds the sidebar to a centred icon rail`() {
        val css = appCss()
        val mediaAt = css.indexOf("@media (max-width: 900px)")
        assertTrue(mediaAt > 0, "the stylesheet lost the breakpoint the drawer depends on")
        listOf("html.js .nav-close {", "html.js body.sidebar-rail .sidebar {").forEach { rule ->
            assertTrue(css.indexOf(rule) in 0 until mediaAt, "\"$rule\" belongs above the drawer breakpoint")
        }
        val corner = css.substringAfter("html.js .nav-close {").substringBefore("}")
        listOf(
            "display: inline-flex", "position: absolute", "top: 10px", "right: 10px",
            "width: 34px", "height: 34px", "border-radius: var(--r-md)",
        ).forEach {
            assertTrue(corner.contains(it), "the one toggle moved or lost its button box: missing $it")
        }
        val rail = css.substringAfter("html.js body.sidebar-rail .sidebar {").substringBefore("}")
        listOf("width: 64px", "flex: 0 0 64px", "padding-left: 10px", "padding-right: 10px", "overflow-x: hidden")
            .forEach { assertTrue(rail.contains(it), "the sidebar is not a narrow rail: missing $it") }
        assertFalse(rail.contains("display: none"), "the rail removes the panel and its navigation with it")

        val sidebar = css.substringAfter("html.js .sidebar {").substringBefore("}")
        listOf(
            "width var(--dur-2) var(--ease-std)", "flex-basis var(--dur-2) var(--ease-std)",
            "padding var(--dur-2) var(--ease-std)",
        ).forEach { assertTrue(sidebar.contains(it), "the rail width does not use the shared motion tokens: $it") }
        val reduced = baseCss().substringAfter("@media (prefers-reduced-motion: reduce)")
        assertTrue(reduced.contains("transition-duration: .01ms !important"), "reduced motion does not stand the rail width down")
        val main = css.substringAfter("\n.main {").substringBefore("}")
        assertTrue(main.contains("flex: 1") && main.contains("min-width: 0"), "main cannot naturally take the freed width")

        val hidden = css.substringAfter("html.js body.sidebar-rail .side-brand,").substringBefore("}")
        listOf(".side-caption", ".pinned", ".side-foot", "display: none").forEach {
            assertTrue(hidden.contains(it), "the rail still shows long sidebar content: missing $it")
        }
        val nav = css.substringAfter("html.js body.sidebar-rail .sidebar nav {").substringBefore("}")
        assertTrue(nav.contains("margin-top: var(--sp-7)"), "the first icon would sit under the corner toggle")
        val item = css.substringAfter("html.js body.sidebar-rail .nav-item {").substringBefore("}")
        listOf("position: relative", "justify-content: center", "padding: 10px 0").forEach {
            assertTrue(item.contains(it), "the rail links are not centred icon targets: missing $it")
        }
        val icon = css.substringAfter("html.js body.sidebar-rail .nav-item svg {").substringBefore("}")
        assertTrue(icon.contains("width: 20px") && icon.contains("height: 20px"), "the rail icons lost their comfortable size")
        assertTrue(css.contains("html.js body.sidebar-rail .nav-item .label { display: none; }"), "rail labels still take space")
        val pill = css.substringAfter("html.js body.sidebar-rail .nav-item .pill {").substringBefore("}")
        listOf("position: absolute", "width: 8px", "height: 8px", "padding: 0", "font-size: 0").forEach {
            assertTrue(pill.contains(it), "the count is not a compact rail badge: missing $it")
        }
        assertTrue(css.contains("html.js body.sidebar-rail .nav-close .fold-chevron { display: none; }"), "the rail still points inward")
        assertTrue(css.contains("html.js body.sidebar-rail .nav-close .expand-chevron { display: inline; }"), "the rail toggle does not point outward")
        assertFalse(css.contains("sidebar-hidden") || css.contains("sidebar-tab"), "the old hidden-sidebar model is still active")
        assertEquals(
            listOf("900px", "520px"),
            Regex("@media\\s*\\(max-width:\\s*(\\d+px)\\)").findAll(css).map { it.groupValues[1] }.toList(),
            "app.css answers to a width this test does not know about",
        )
    }

    /**
     * The remembered rail, as script. One per-browser key is read before the controls are wired; both the new
     * `rail` value and Stage 130's legacy `hidden` value apply the rail before paint. One real button owns the
     * desktop toggle and reports its state, while its narrow-screen half stands down so the shared drawer close
     * path remains exactly the one Stage 128 shipped.
     */
    @Test
    fun `the rail is remembered before paint and its one toggle keeps ARIA honest`() {
        val js = appJs()
        listOf(
            "localStorage.getItem('pts-sidebar')",
            "rememberedSidebar === 'rail' || rememberedSidebar === 'hidden'",
            "document.body.classList.add('sidebar-rail')",
            "var SIDEBAR_KEY = 'pts-sidebar'",
            "classList.toggle('sidebar-rail'",
            "localStorage.setItem(SIDEBAR_KEY, 'rail')",
            "localStorage.removeItem(SIDEBAR_KEY)",
            "navClose.addEventListener('click', toggleSidebar)",
            "if (!isWide()) return;",
            "navClose.setAttribute('aria-expanded', isWide() && sidebarRail() ? 'false' : 'true')",
            "sidebarRail() ? 'Expand sidebar' : 'Collapse sidebar'",
        ).forEach { piece ->
            assertTrue(js.contains(piece), "app.js lost \"$piece\"")
        }
        assertEquals(2, Regex("'pts-sidebar'").findAll(js).count(), "the sidebar state grew a second key")
        assertFalse(js.contains("sidebarTab") || js.contains("sidebar-hidden"), "the retired second toggle still has script")
        val marked = js.indexOf("document.documentElement.classList.add('js')")
        val applied = js.indexOf("localStorage.getItem('pts-sidebar')")
        val wired = js.indexOf("getElementById('navClose')")
        assertTrue(marked in 0 until applied, "the rail is read before the shell is marked as scripted")
        assertTrue(applied < wired, "the remembered rail is applied only after the controls are wired, so the page jumps")
        assertTrue(js.contains("navClose.addEventListener('click', closeNav)"),
            "the closer stopped handing an open drawer to the shared close")
    }

    /** A browser remembering the desktop rail must still get Stage 128's complete, labelled phone drawer. */
    @Test
    fun `a remembered rail leaves the phone drawer unchanged`() {
        val css = appCss()
        val block = css.substringAfter("@media (max-width: 900px)").substringBefore("@media (max-width: 520px)")
        listOf(
            "html.js .sidebar {",
            "width: 100%",
            "transform: translateX(-100%)",
            "html.js body.nav-open .sidebar { transform: none; }",
            "html.js body.sidebar-rail .sidebar {",
            "width: 100%; flex-basis: auto;",
            "padding: var(--sp-5) 14px var(--sp-4);",
            "html.js body.sidebar-rail .side-brand { display: inline; }",
            "html.js body.sidebar-rail .nav-item .label { display: block; }",
            "html.js body.sidebar-rail .nav-item .pill {",
            "position: static",
            "html.js body.sidebar-rail .nav-close .fold-chevron { display: inline; }",
            "html.js body.sidebar-rail .nav-close .expand-chevron { display: none; }",
            "html.js .nav-close { display: none; }",
            "html.js body.nav-open .nav-close {",
        ).forEach { rule ->
            assertTrue(block.contains(rule), "the drawer breakpoint no longer restores \"$rule\"")
        }
        val drawerAt = block.indexOf("html.js .sidebar {")
        val railResetAt = block.indexOf("html.js body.sidebar-rail .sidebar {")
        val labelResetAt = block.indexOf("html.js body.sidebar-rail .nav-item .label { display: block; }")
        assertTrue(drawerAt in 0 until railResetAt, "the rail reset must come after the drawer's own geometry")
        assertTrue(railResetAt < labelResetAt, "the drawer labels are not restored by the rail reset")
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
        val html = createHTML().html {
            appPage(UserSession(reader), "dashboard", "Dashboard") { }
        }
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
            listOf("/app", "/app/assigned-tasks", "/app/todo", "/app/search"),
            links.map { Regex("href=\"([^\"]+)\"").find(it)?.groupValues?.get(1) },
            "the bar's four tabs are not the checklist's Home, My tasks, To-do and Search, in that order",
        )
        assertEquals(
            listOf("Home", "My tasks", "To-do", "Search", "Menu"),
            Regex("<span class=\"mtab-label\">([^<]+)</span>").findAll(tabs).map { it.groupValues[1] }.toList(),
            "the bar's captions are not the words the checklist asked for, in the order it asked for them",
        )
        assertEquals(5, Regex("<svg").findAll(tabs).count(), "a tab is missing its glyph, or grew a second one")
        // This page's `section` is `dashboard`, so Home is the one tab wearing the active class — and only it.
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
     *
     * This is the read-only copy. Management's editable copy of the same row does not carry `detail-strip`,
     * and the test below pins its fold — a row that folds for one reader and not for the other is the defect
     * that pair exists to prevent.
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

    /**
     * The same row, management's copy of it. The project page draws its field row twice — editable for a reader
     * who may write the project, read-only for everybody else — and only the read-only copy carries
     * `detail-strip`. A fold written against that class alone therefore never reaches management, which is what
     * Stage 134's first pass did: measured at 375px, the ten editable cells came out 7–20px apiece, because
     * `flex: 1.3 1 0` is a *zero* basis and every cell fits one line at any width and simply divides it. The
     * controls inside them then pushed the document 19px past its own width. This pins the fold that answers
     * the copy with the controls in it, and the source order that lets the read-only copy keep its narrower gap.
     */
    @Test
    fun `management's editable field row folds at the same two widths`() {
        // Comments out first, for the reason the test above gives: the prose names these selectors to explain
        // them, and a count taken over the prose would count the explanation as rules.
        val css = appCss().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        val wide = css.substringAfter("@media (max-width: 900px)").substringBefore("@media (max-width: 520px)")
        val phone = css.substringAfter("@media (max-width: 520px)")
        val folded = Regex("\\.edit-strip \\.ef[^{]*\\{\\s*flex: 1 1 ([^;]+);\\s*\\}")
        listOf(wide to "900px", phone to "520px").forEach { (block, width) ->
            val bases = folded.findAll(block).map { it.groupValues[1] }.toList()
            assertEquals(1, bases.size, "the $width block does not answer the editable row in one rule: $bases")
            assertEquals(
                if (width == "900px") "calc(50% - 9px)" else "100%", bases[0],
                "the $width fold gives the editable row the wrong basis",
            )
            // One rule, five selectors — one per desktop weight rule, counted below rather than assumed here.
            val selectors = Regex("\\.edit-strip \\.ef[^{}]*\\{").findAll(block).map { it.value }.toList()
            assertEquals(1, selectors.size, "the $width block grew a second rule against the editable row")
            assertEquals(5, Regex("\\.edit-strip \\.ef").findAll(selectors[0]).count(),
                "the $width block does not answer all five of the editable row's weight selectors")
            // The read-only copy carries both classes and has the narrower gap, so it has to come second:
            // equal specificity, later source is the whole of what decides 7px against 9px on that element.
            assertTrue(block.indexOf(".edit-strip .ef,") < block.indexOf(".detail-strip .ef,"),
                "the $width block folds the read-only copy first, so its 14px gap is answered with 9px")
        }
        // The five desktop shares this answers, counted where they live: a sixth weight rule added up there is
        // a rule both phone blocks have to answer too, or the row goes ragged at exactly one width.
        val desktop = css.substringBefore("@media (max-width: 900px)")
        assertEquals(5, Regex("\\.edit-strip \\.ef[^{]*\\{[^}]*flex: [\\d.]+ 1 0;").findAll(desktop).count(),
            "the editable row's desktop weight rules moved, and the fold answers a count that is no longer true")
        // And the gap arithmetic the two bases are half of: 18px on the editable row, 14px on the read-only one.
        assertTrue(css.contains("gap: var(--sp-3) 18px;"),
            "the editable row's column gap is no longer 18px, so 9px is not half of it")
        assertTrue(css.contains(".detail-strip { margin-top: 2px; gap: var(--sp-3) 14px; }"),
            "the read-only row's column gap is no longer 14px, so 7px is not half of it")
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

    @Test
    fun `the to-do board settles on a list, and lets a drag scroll it first`() {
        // Comments out first, as everywhere in this file: the block's own prose names the selectors it is
        // explaining, and a count taken over the prose would count the explanation as rules.
        val css = appCss().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        val wide = css.substringAfter("@media (max-width: 900px)").substringBefore("@media (max-width: 520px)")
        val desktop = css.substringBefore("@media (max-width: 900px)")

        val board = wide.substringAfter(".todo-board {").substringBefore("}")
        assertTrue(board.contains("scroll-snap-type: x proximity"),
            "the to-do board scrolls sideways at this width, but a flick can stop between two lists: $board")
        val list = wide.substringAfter(".todo-board .todo-list {").substringBefore("}")
        assertTrue(list.contains("scroll-snap-align: start"),
            "a snap container with nothing aligned to it settles nowhere: $list")

        // Proximity and not the kanban row's mandatory: todo.js drags the lists along this same axis and
        // scrolls the board to reach the next one, which a mandatory container would snap straight back.
        assertFalse(board.contains("mandatory"),
            "a mandatory snap would fight the edge-scroll todo.js runs on the board it is snapping")
        assertTrue(wide.substringAfter(".kanban, .kanban-lanes {").substringBefore("}")
            .contains("scroll-snap-type: x mandatory"),
            "the kanban row lost the mandatory snap Stage 134 gave it")

        // The scroll the snap needs is the desktop rule's, and it is unchanged: snap with no overflow-x is a
        // declaration with nothing to act on, and a list that lost its basis is no longer a row to page.
        assertTrue(desktop.substringAfter(".todo-board {").substringBefore("}").contains("overflow-x: auto"),
            "the to-do board no longer scrolls sideways, so there is nothing for the snap to settle")
        assertTrue(desktop.substringAfter(".todo-board .todo-list {").substringBefore("}")
            .contains("flex: 0 0 330px"),
            "the lists lost the fixed basis that makes them a row to page through")

        // And still exactly two width breakpoints: this went into the shell's own block, not a new one.
        assertEquals(2, Regex("@media \\(max-width: \\d+px\\)").findAll(css).count(),
            "a third width breakpoint appeared")
    }

    @Test
    fun `the sidebar's counts have an address a write can land on`() {
        val html = page()

        // A pill is addressed by the section it belongs to so that a form can name the count its own write
        // moves and land it from the same response as the block the reader acted on. Which pill a form names
        // is decided per action, from what that action really moves: the To-do figure counts the reader's
        // to-dos, so the To-do page's writes carry it, while *Assigned tasks* counts every card finished ones
        // included (Stage 54) -- so a status change moves no pill at all and names none.
        listOf("todo", "assigned-tasks", "projects", "team", "forecasts", "lessons", "other-tasks").forEach {
            val addressed = Regex("id=\"nav-pill-" + Regex.escape(it) + "\">\\d+</span>")
            assertTrue(addressed.containsMatchIn(html), "the $it pill is not addressed, or prints no number: $html")
            // Once. `swapBlock` answers `getElementById`, so a second copy of the id is a companion that only
            // ever lands one of the two figures the reader can see.
            assertEquals(1, html.split("id=\"nav-pill-$it\"").size - 1, "$it has more than one pill")
        }
        // Calendar, Access control and Settings carry no count and so draw no pill. They must not print an
        // address for one either: an id nothing renders is an id a companion silently fails to find, and the
        // framework's answer to a missing companion is to skip it -- which is indistinguishable from working.
        listOf("calendar", "access-control", "settings").forEach {
            assertFalse("nav-pill-$it" in html, "a pill that is not drawn has an address")
        }
    }

    @Test
    fun `a to-do write in flight is visible, and the focus is handed where the reader is going`() {
        val css = appCss()
        // The dim, and the transition that keeps it from arriving between two frames: the same `.55` and the
        // same `--dur-1` the three blocks that already had one use, so the app has one idea of "busy" rather
        // than four. Without these `sendToBlock` adds `pts-busy` to a block nothing answers, and a POST that
        // has not come back yet looks exactly like one that has.
        assertTrue(
            ".todo-board, .todo-list, .todo-new-list { transition: opacity var(--dur-1) var(--ease-std); }" in css,
            css,
        )
        assertTrue(
            ".todo-board.pts-busy, .todo-list.pts-busy, .todo-new-list.pts-busy { opacity: .55; }" in css,
            css,
        )

        val js = appJs()
        // The focus hand-back, and the contract line that documents it. `swapBlock` gives the focus back to
        // whichever control had it, which is right for a tick and wrong for the two writes that remove or
        // empty the control the reader pressed, so a block form may name the destination instead.
        assertTrue("[data-live-focus]" in js, "the markup contract does not document the attribute it honours")
        assertTrue("getAttribute('data-live-focus')" in js, js)
        assertTrue(
            "focusNext.focus({ preventScroll: true })" in js,
            "the hand-back scrolls the page to the control, which is a reload by another name",
        )
        // It runs after the companions: a named destination has to win over the control `swapBlock` already
        // restored, or the two fight and which one lands depends on the order of a response's ids.
        val send = js.substringAfter("function sendToBlock")
        assertTrue(
            send.indexOf("extraIds(form).forEach") in 1..send.indexOf("data-live-focus"),
            "the focus is named before the blocks it lives in have been swapped",
        )
    }

    @Test
    fun `the pinned list is addressed, and each row hands the focus to a neighbour`() {
        val haul = project("S-02", "Site fencing")
        assertEquals(true, ProjectService.togglePin(colleague, haul), "the colleague's pin did not take")
        val one = page(colleague)

        // Addressed once, and on every page: `swapBlock` answers `getElementById`, and the block is in the
        // sidebar, so a pin form anywhere in the app can name it as the companion its own write moves.
        assertEquals(1, one.split("id=\"pinnedProjects\"").size - 1, "the pinned block is not addressed once")
        assertTrue("data-live-target=\"#pinnedProjects\"" in one, one)
        assertTrue("id=\"pinned-unpin-$haul\"" in one, one)
        // The row goes, so the *block* is the target: a narrower one would be a row that is not in the response
        // at all, and `sendToBlock` treats a missing target as a failed swap and navigates -- the reload this
        // stage exists to remove. The sheet's star rides along when the reader is on the Projects page and is
        // simply skipped elsewhere, which is what a companion is for.
        val block = one.substringAfter("id=\"pinnedProjects\"").substringBefore("side-foot")
        assertTrue("data-live-extra=\"#board-results\"" in block, "the sheet's star does not ride along: $block")
        assertEquals(0, block.split("data-live-focus=").size - 1, "a lone row names a destination it does not have")

        val crusher = project("S-03", "Crusher relocation")
        assertEquals(true, ProjectService.togglePin(colleague, crusher), "the second pin did not take")
        val two = page(colleague)
        // Focus cannot come back to an × that is no longer there, so each row names a neighbour's: the next
        // one, or the previous one when it was the last. Which of the two it is follows `pinnedIds`, so what is
        // pinned here is that every row has a destination and that the destination is a row this page draws.
        val destinations = Regex("data-live-focus=\"#pinned-unpin-(\\d+)\"")
            .findAll(two).map { it.groupValues[1] }.toList()
        assertEquals(2, destinations.size, "a row of two has no focus destination: $two")
        destinations.forEach { assertTrue("id=\"pinned-unpin-$it\"" in two, "row $it names a destination that is not drawn") }
        assertEquals(setOf("$haul", "$crusher"), destinations.toSet(), "the two rows do not point at each other")

        val css = appCss()
        // The dim, and the region that had a transition but no dim of its own: `sendToBlock` adds `pts-busy` to
        // whatever block a form names, so a target with no rule behind it is a POST in flight that looks
        // exactly like one that has already landed.
        assertTrue(
            ".pinned, .pin-spot, [data-live-region] { transition: opacity var(--dur-1) var(--ease-std); }" in css,
            css,
        )
        assertTrue(
            ".pinned.pts-busy, .pin-spot.pts-busy, [data-live-region].pts-busy { opacity: .55; }" in css,
            css,
        )
    }
}
