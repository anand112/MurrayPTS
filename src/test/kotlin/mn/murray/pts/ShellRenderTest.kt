package mn.murray.pts

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.html.html
import kotlinx.html.stream.createHTML
import mn.murray.pts.db.Migrations
import mn.murray.pts.domain.Member
import mn.murray.pts.repository.TeamRepository
import mn.murray.pts.repository.UserRepository
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
 * The stylesheet half is read because a breakpoint cannot be seen in markup: app.css has exactly one
 * `@media` rule (Stage 119 is the first), it asks for `max-width: 900px`, and inside it the drawer is
 * full width and off-canvas under `html.js`, the button is the only visible opener, the backdrop is
 * shown only while `body.nav-open`, and both card boards ask for two columns a row. The script half is
 * the mirror image: `app.js` puts the `js` class on the document (so a scripting-off reader gets the
 * stacked sidebar instead of an unopenable drawer), and it owns the toggle, Escape, the backdrop and
 * the resize that closes a drawer the desktop no longer shows.
 *
 * Stage 120's answer to the same shrink is measured here as well: the dense fixed-layout sheets
 * (projects, Team, the two forecast ledgers, Lesson learnt) keep a minimum width and their panels
 * scroll sideways, so a narrow window no longer prints "S 0 1" and "C O D E" one letter a line.
 */
class ShellRenderTest {

    private lateinit var ds: HikariDataSource

    private val reader = "EMP0001"

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
    }

    @AfterEach
    fun tearDown() = ds.close()

    /** The whole document for one page: `createHTML` needs an `HTML` receiver, and `appPage` draws the shell. */
    private fun page(): String = createHTML().html {
        appPage(UserSession(reader), "projects", "Projects") { }
    }

    private fun appCss(): String = File("src/main/resources/static/css/app.css").readText()
    private fun appJs(): String = File("src/main/resources/static/js/app.js").readText()

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

    @Test
    fun `one breakpoint at 900px takes the drawer off-canvas and folds the boards to two a row`() {
        val css = appCss()
        // At-rules, not the word: the comments above and inside this block name `@media` themselves.
        assertEquals(1, Regex("@media\\s*\\(").findAll(css).count(), "app.css grew a second breakpoint")
        val block = css.substringAfter("@media (max-width: 900px)")
        assertTrue(css.contains(".nav-toggle, .nav-backdrop { display: none; }"), "the hamburger is visible on a desktop")
        listOf(
            "html.js .sidebar",
            "transform: translateX(-100%)",
            "width: 100%",
            "html.js body.nav-open .sidebar { transform: none; }",
            "html.js .nav-toggle",
            "display: inline-flex",
            "html.js body.nav-open .nav-backdrop { display: block; }",
            ".kanban, .kanban-lanes { grid-template-columns: repeat(2, minmax(0, 1fr)); }",
        ).forEach { rule ->
            assertTrue(block.contains(rule), "the breakpoint lost \"$rule\"")
        }
        // Without scripting the drawer is never off-canvas, because the rule that takes it there is scoped to html.js.
        assertFalse(
            Regex("\\n    \\.sidebar \\{[^}]*translateX").containsMatchIn(block),
            "a scripting-off reader gets an unopenable drawer",
        )
        assertTrue(css.contains("translateX(-100%)"), "the drawer does not slide in from the left")
    }

    @Test
    fun `dense sheets keep a readable width instead of breaking every word to letters`() {
        val css = appCss()
        // The table still fills a wide panel through its percentages; the floor only stops it shrinking
        // past legibility, and the panel scrolls when the window is narrower than the floor.
        assertTrue(
            css.contains(".card-panel:has(table.grid), .board-results:has(table.grid) { overflow-x: auto; }"),
            "a dense sheet has no sideways scroll to reach the columns that do not fit",
        )
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
    }
}
