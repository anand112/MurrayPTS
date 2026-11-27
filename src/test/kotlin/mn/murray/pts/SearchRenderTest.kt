package mn.murray.pts

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.html.html
import kotlinx.html.stream.createHTML
import mn.murray.pts.db.Migrations
import mn.murray.pts.domain.Member
import mn.murray.pts.repository.BucketRepository
import mn.murray.pts.repository.ProjectRepository
import mn.murray.pts.repository.RoleRepository
import mn.murray.pts.repository.TaskRepository
import mn.murray.pts.repository.TeamRepository
import mn.murray.pts.repository.TodoRepository
import mn.murray.pts.repository.UserRepository
import mn.murray.pts.service.LessonDraft
import mn.murray.pts.service.LessonService
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
 * The search page (**Stage 134**), read as the six groups it looks across, as the markup that prints them, and as
 * the stylesheet that keeps the result list readable at a phone's width.
 *
 * The page is the bottom bar's *Search* tab, so it is a destination rather than a box in the page head: a tab has
 * to land somewhere, and a popup opened from a tab has nothing to be the back button's answer to. The form is a
 * plain GET onto `/app/search`, so the words a reader typed are in the address, a shared link opens the same
 * results, and a browser with no script gets the same page — the one difference being that nothing focuses the box.
 *
 * Three promises are measured here, and they are the three a route test cannot see:
 *
 *  * **Nothing is a new way into a row.** Every result is a link to the page that row already lives on —
 *    `/app/projects/{id}` for a project, `?edit={id}` on that page for one of its cards, `/app/other-tasks`,
 *    `/app/lessons`, `/app/todo` and `/app/team` — and a group whose page the reader's role cannot open is not
 *    searched at all, so the page never advertises a door that would refuse on the other side.
 *  * **The reader's own rights are the only filter.** To-do items belong to one account and only that account's
 *    are searched; archived cards are not results, because the boards they were archived off do not print them
 *    either; and the Other-tasks group is gated by the same chips the sidebar link is ([NAV_PERMISSIONS]) rather
 *    than by a second opinion of who may see the backlog.
 *  * **The page says what it did.** One sentence, printed in all three of its states — nothing typed, a word with
 *    no answer, a word with one — because a search page that stays silent on an empty result is
 *    indistinguishable from a search page that broke.
 */
class SearchRenderTest {

    private lateinit var ds: HikariDataSource

    /** The account these tests search as: a Project Manager on the roster, an Administrator by role where stated. */
    private val reader = "EMP0001"

    /** A second account, on the roster with the default (Employee) role, for the promises that are per-reader. */
    private val colleague = "EMP0002"

    /** Somebody with no department in the roster, so the People group has a blank to file under *Unassigned*. */
    private val driver = "EMP0003"

    @BeforeEach
    fun setUp(@TempDir dir: File) {
        ds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${File(dir, "search.db").absolutePath}"
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            }
        )
        Migrations.migrate(ds)
        Database.connect(ds)
        TeamRepository.upsert(Member(reader, "Munkhtsetseg", "Operations", "Project Manager"))
        TeamRepository.upsert(Member(colleague, "Bat-Erdene", "Workshop", "Engineer"))
        TeamRepository.upsert(Member(driver, "Ganzorig", "", "Driver"))
        UserRepository.insert(reader, "Munkhtsetseg", "x", "Manager", "munkh@murrays.mn", null, false, false)
        UserRepository.insert(colleague, "Bat-Erdene", "x", "Viewer", "baterdene@murrays.mn", null, false, false)
        UserRepository.insert(driver, "Ganzorig", "x", "Viewer", "ganzorig@murrays.mn", null, false, false)
    }

    @AfterEach
    fun tearDown() = ds.close()

    /** The page as the route serves it: the shell with `section = "search"`, so the bar's own tab is lit. */
    private fun page(query: String, empNo: String = reader): String = createHTML().html {
        appPage(UserSession(empNo), "search", "Search") { searchContent(it, query) }
    }

    /**
     * Just the page's own content, from the box down. The shell prints its backdrop, its reopen tab and the bottom
     * bar *after* the content, and the bar carries four `href`s of its own — so a slice that ran to the end of the
     * document would count the tabs as results.
     */
    private fun results(html: String): String =
        html.substringAfter("id=\"appSearch\"").substringBefore("id=\"navBackdrop\"")

    /** One project row, written straight to the table the way [ShellRenderTest] writes one. */
    private fun project(
        code: String,
        name: String,
        client: String = "",
        status: String = "In progress",
        manager: String = reader,
    ): Int = ProjectRepository.insert(
        code = code, name = name, status = status, progress = 0,
        managerEmpNo = manager, due = "2027-03-30", type = "Site projects", client = client,
    )

    /**
     * A card on [projectId]'s board. `ASSIGNED` is the kind [TaskService] reads for the project boards — the
     * backlog's cards are `OTHER`, and a card of any other kind is on neither, which is what makes the two groups
     * below two different sheets rather than one sheet read twice.
     */
    private fun card(title: String, projectId: Int, assignee: String? = reader): Int = TaskRepository.insert(
        kind = "ASSIGNED", title = title, projectId = projectId, due = "2026-12-15", priority = "Medium",
        assigneeEmpNo = assignee, status = "In progress", bucketId = null, started = "2026-11-01",
    )

    /** A card on the shared backlog, standing in a column named [bucket]. */
    private fun backlogCard(title: String, bucket: String, assignee: String? = null): Int = TaskRepository.insert(
        kind = "OTHER", title = title, projectId = null, due = "2026-12-15", priority = "Medium",
        assigneeEmpNo = assignee, status = "Not started", bucketId = BucketRepository.insert(bucket),
        started = "2026-11-01",
    )

    /**
     * The one group with this id, as markup: its own opening `<div>` through the next group's. The id is an
     * attribute *inside* that opening tag, so the slice has to start at the `<div` before it — starting at the
     * attribute itself would cut the panel's own class off, which is the very thing the markup cases read.
     */
    private fun group(html: String, id: String): String {
        val body = results(html)
        val marker = "data-search-group=\"$id\""
        val at = body.indexOf(marker)
        if (at < 0) return ""
        val next = body.indexOf("data-search-group=", at + marker.length)
        val start = body.lastIndexOf("<div", at)
        val end = if (next < 0) body.length else body.lastIndexOf("<div", next)
        return body.substring(start, end)
    }

    /** The hrefs one group prints, in the order it prints them. */
    private fun hrefs(html: String, id: String): List<String> =
        Regex("href=\"([^\"]+)\"").findAll(group(html, id)).map { it.groupValues[1] }.toList()

    /** The rows [searchGroups] found in one group, by id, for the cases that read the data rather than the markup. */
    private fun rows(query: String, id: String, empNo: String = reader): List<SearchRow> =
        searchGroups(query, empNo).firstOrNull { it.id == id }?.rows.orEmpty()

    /** Give [empNo] the seeded role that holds every chip, so a gated group has something to be gated against. */
    private fun makeAdministrator(empNo: String) {
        val role = RoleRepository.findByName("Administrator")
            ?: throw AssertionError("the catalogue this database was migrated with has no Administrator role")
        assertEquals(true, RoleRepository.setRole(empNo, role.id), "the role did not take on $empNo")
    }

    @Test
    fun `the page is one box over a list of links, and every link is a page the row already lives on`() {
        val haul = project("S-01", "Haul road widening", client = "Oyu Tolgoi LLC")
        val html = page("haul")

        val form = Regex("<form[^>]*action=\"/app/search\"[^>]*>").find(html)?.value
            ?: throw AssertionError("the search page has no form, so a reader with no script cannot search at all")
        assertTrue(form.contains("method=\"get\""), "the words a reader typed would not be in the address: $form")
        val box = Regex("<input[^>]*id=\"appSearch\"[^>]*>").find(html)?.value
            ?: throw AssertionError("the box is not there to be found")
        listOf("name=\"q\"", "value=\"haul\"", "type=\"text\"", "autofocus").forEach { piece ->
            assertTrue(box.contains(piece), "the box lost $piece, so a submit would not carry the word: $box")
        }
        // An empty box on arrival is a box nobody has typed in yet, and taking focus for that is a phone's
        // keyboard in the reader's face before they asked for one.
        assertFalse(Regex("<input[^>]*id=\"appSearch\"[^>]*>").find(page(""))!!.value.contains("autofocus"),
            "the page takes focus on arrival, so a tab press opens a keyboard instead of a page")

        val projects = group(html, "projects")
        assertTrue(projects.contains("<p class=\"panel-caption\">Projects · 1</p>"),
            "the group's caption does not say which sheet it is and how many rows it found")
        assertEquals(listOf("/app/projects/$haul"), hrefs(html, "projects"),
            "a project result is not a link to the project's own page")
        assertTrue(projects.contains("<span class=\"search-title\">Haul road widening</span>"),
            "the row does not lead with the name the reader searched for")
        val detail = Regex("<span class=\"search-detail\">([^<]+)</span>").find(projects)?.groupValues?.get(1).orEmpty()
        listOf("S-01", "In progress", "Oyu Tolgoi LLC", "Manager: Munkhtsetseg").forEach { piece ->
            assertTrue(detail.contains(piece), "the row's one line of context lost $piece: $detail")
        }
        // A list, not a sheet: the group reuses the house panel and row link, and the one page whose whole job is
        // being readable on a phone must not need Stage 120's sideways scroll to be read.
        assertFalse(projects.contains("<table"), "the search results are a table, which a phone cannot read")
        assertTrue(projects.contains("class=\"card-panel search-group\""), "the group is not the house panel")
        assertTrue(projects.contains("class=\"row-link search-row\""), "a result is not the house row link")
        // The bar's own tab is lit by the page's `section`, the same word the sidebar's row is chosen by.
        val bar = html.substringAfter("id=\"mobileTabs\"").substringBefore("</nav>")
        assertTrue(Regex("<a[^>]*href=\"/app/search\"[^>]*>").find(bar)!!.value.contains("class=\"mtab active\""),
            "the Search tab is not lit on the search page")
        assertTrue(bar.contains("aria-current=\"page\""), "the lit tab does not say so to a screen reader")
    }

    @Test
    fun `the page says what it looked for and what came of it, in all three states`() {
        project("S-01", "Haul road widening")
        assertEquals(2, SEARCH_MIN, "the shortest word the page will look for has moved")
        // The sentence as a reader sees it: kotlinx.html escapes the quotes around the word it searched for, so
        // the markup carries `&quot;` where the page carries a quote. Comparing the escaped bytes would pin the
        // renderer's escaping rather than the words the page says.
        val said = { html: String ->
            Regex("<p class=\"panel-caption search-said\">([^<]+)</p>").find(html)?.groupValues?.get(1)
                ?.replace("&quot;", "\"")?.replace("&amp;", "&").orEmpty()
        }
        // Nothing typed: an invitation, and no groups at all, because a page of everything is an answer to nothing.
        val blank = page("")
        assertTrue(said(blank).startsWith("Type two letters or more."),
            "an empty search page does not say what it is for: ${said(blank)}")
        assertFalse(blank.contains("data-search-group"), "an empty page printed a group of results")
        // One letter is under the floor, so the page has not looked: it repeats the invitation rather than claiming
        // the word was searched for and found wanting, and it prints no row carrying an "h".
        assertEquals(emptyList<SearchGroup>(), searchGroups("h", reader), "one letter was searched for")
        assertTrue(said(page("h")).startsWith("Type two letters or more."),
            "a one-letter word was told it had no matches, which is not what happened: ${said(page("h"))}")
        assertFalse(page("h").contains("data-search-group"), "a one-letter word printed a group of results")
        // A word with no answer says so, which is the state a broken page and a working one share.
        assertEquals("Nothing matches \"zzzz\".", said(page("zzzz")), "a word with no answer is not reported")
        // And a word with one counts both halves of the answer: how many rows, in how many sheets.
        assertEquals("\"haul\" — 1 result in 1 place.", said(page("haul")),
            "one row in one sheet is not counted in the singular")
        // A second sheet with one row on it: the card's own project is named for something else, so the count moves
        // in both of its halves rather than in one.
        val conveyor = project("S-02", "Conveyor replacement")
        card("Widen the haul road", conveyor)
        assertEquals("\"haul\" — 2 results in 2 places.", said(page("haul")),
            "two rows in two sheets are not counted in the plural")
    }

    @Test
    fun `a project is found by any word on its row, in any case`() {
        project("S-01", "Haul road widening", client = "Oyu Tolgoi LLC", status = "In progress")
        project("M-01", "Crusher liner replacement", client = "Oyu Tolgoi South", status = "Blocked", manager = colleague)
        // Code, name, client, status and the manager's name are all words a reader might remember instead of the one
        // the project is filed under, and the needle is lowercased once by the caller rather than per field.
        listOf(
            "s-01" to "Haul road widening",
            "crusher" to "Crusher liner replacement",
            "blocked" to "Crusher liner replacement",
            "munkhtsetseg" to "Haul road widening",
            "bat-erdene" to "Crusher liner replacement",
        ).forEach { (needle, expected) ->
            assertEquals(listOf(expected), rows(needle, "projects").map { it.title },
                "\"$needle\" did not find the one project row it is a word on")
        }
        // Two rows carry this client, so the group reports both rather than the first.
        assertEquals(listOf("Haul road widening", "Crusher liner replacement"), rows("tolgoi", "projects").map { it.title },
            "a needle both rows carry did not find both")
    }

    @Test
    fun `a card is a result, an archived card is not, and a backlog card needs the board's own chips`() {
        val haul = project("S-01", "Haul road widening")
        val belt = card("Replace conveyor belt", haul)
        assertEquals(listOf("/app/projects/$haul?edit=$belt"), rows("conveyor", "tasks").map { it.href },
            "a project card does not open its own popup on the page it belongs to")

        // Archived cards are off every board, so they are off this page too: a result that opens nothing is worse
        // than no result, because the reader has to open it to find that out.
        assertEquals(true, TaskRepository.archive(belt, null, "Engineering", "2026-11-12"), "the archive did not take")
        assertEquals(emptyList<SearchRow>(), rows("conveyor", "tasks"), "an archived card is still a search result")

        // The backlog's cards are a gated group, and the gate is the sidebar's own: *Edit any task* or *Manage
        // buckets*. A reader with neither is not shown a group whose page would send them back to `/app`.
        val spare = backlogCard("Conveyor spare delivery", "Delivery")
        makeAdministrator(reader)
        assertEquals(listOf("/app/other-tasks?edit=$spare"), rows("conveyor", "other-tasks").map { it.href },
            "a role holding the chips does not get the backlog's cards")
        assertEquals(emptyList<SearchRow>(), rows("conveyor", "other-tasks", colleague),
            "the backlog was searched for an account whose role cannot open its page")
        assertFalse(page("conveyor", colleague).contains("data-search-group=\"other-tasks\""),
            "the page printed a backlog group for an account that may not open the backlog")
    }

    @Test
    fun `a to-do item is found only by the account that owns it`() {
        val mine = TodoRepository.insertList("Site visits", reader)
        TodoRepository.insertItem("Inspect the haul road", mine, reader, due = "2026-11-20")
        val theirs = TodoRepository.insertList("Site visits", colleague)
        TodoRepository.insertItem("Inspect the haul road", theirs, colleague, due = "2026-11-20")
        // Both items are on the database, so "one row each" below is the ownership rule answering and not an
        // empty fixture passing by accident.
        assertEquals(2, DemoData.todos.size, "the fixture is not two items on two accounts' lists")

        val mineRows = rows("haul", "todo")
        assertEquals(1, mineRows.size, "the reader's own to-do list is not one item")
        assertEquals("/app/todo", mineRows[0].href, "a to-do result does not open the To-do page")
        assertTrue(mineRows[0].detail.contains("Site visits"), "the row does not say which list the item is on")
        assertTrue(mineRows[0].detail.contains("Open"), "the row does not say whether the item is done")
        val theirsRows = rows("haul", "todo", colleague)
        assertEquals(1, theirsRows.size, "a second account's item was searched, or their own was not")
        assertEquals(mineRows[0].title, theirsRows[0].title, "the two lists are not holding the same fixture")
        // A list's own name is a word the reader may remember instead of the item's.
        assertEquals(1, rows("visits", "todo").size, "an item is not found by the name of the list it is on")
    }

    @Test
    fun `a lesson is found by its own words and opens the sheet's popup`() {
        val haul = project("S-01", "Haul road widening")
        val belt = card("Replace conveyor belt", haul)
        val saved = LessonService.save(
            LessonDraft(text = "Belt tension was checked too late", projectId = haul, taskId = belt, loggedOn = "2026-11-12"),
            reader, false,
        )
        assertEquals(true, saved.saved, "the lesson fixture was refused: ${saved.errors}")
        val found = rows("tension", "lessons")
        assertEquals(1, found.size, "a lesson is not found by a word in its own text")
        assertTrue(found[0].href.startsWith("/app/lessons?edit="), "a lesson result does not open its own popup")
        assertTrue(found[0].detail.contains("S-01"), "the lesson row does not say which project it was logged against")
        // The sheet's own reading, archive left out: a lesson somebody removed is not a result.
        LessonService.remove(found[0].href.substringAfter("edit=").toInt(), reader, true)
        assertEquals(emptyList<SearchRow>(), rows("tension", "lessons"), "a removed lesson is still a search result")
    }

    @Test
    fun `people are found by name, position, department or number, under the department the roster files them in`() {
        val people = rows("munkht", "people")
        assertEquals(1, people.size, "a name is not found by the word the reader typed")
        assertEquals("/app/team", people[0].href, "a person is not a link to the Team sheet")
        assertEquals("Munkhtsetseg", people[0].title, "the row does not lead with the person's name")
        assertEquals("Project Manager · Operations · EMP0001", people[0].detail,
            "the row does not carry the position, the department and the number a reader narrows by")
        // A roster blank is filed under the word the Team sheet prints, not printed as a gap between two dots.
        assertEquals(listOf("Driver · Unassigned · EMP0003"), rows("ganzorig", "people").map { it.detail },
            "a person with no department is not filed under the label the Team sheet gives them")
        assertEquals(1, rows("workshop", "people").size, "a department is not a word a reader can search by")
        assertEquals(1, rows("emp0002", "people").size, "an employee number is not a word a reader can search by")
    }

    @Test
    fun `a long title is cut on a word boundary and never in the middle of a word`() {
        project("S-01", List(30) { "word" }.joinToString(" "))
        val title = rows("word", "projects").single().title
        assertTrue(title.endsWith("…"), "a title too long for one row was printed whole")
        assertEquals(90, title.length, "the cut is not the length the row was measured for")
        assertEquals(List(18) { "word" }.joinToString(" "), title.removeSuffix("…"),
            "the cut landed inside a word rather than on the space before it")
        // And a title that fits is left alone: an ellipsis on a short name is a mark that something is missing.
        project("M-01", "Crusher liner")
        assertEquals("Crusher liner", rows("crusher", "projects").single().title, "a title that fits was cut anyway")
    }

    @Test
    fun `a result list is a column of two-line rows, and the stylesheet says so`() {
        val css = File("src/main/resources/static/css/app.css").readText()
        listOf(
            ".search-said { margin-top: 0; }",
            ".search-list { display: flex; flex-direction: column; }",
            ".search-title { font-size: var(--fs-11); font-weight: 600; line-height: 1.4; }",
            ".search-detail { font-size: var(--fs-8); line-height: 1.45; color: var(--muted); }",
            ".search-list .search-row:last-child { border-bottom: none; }",
        ).forEach { rule ->
            assertTrue(css.contains(rule), "app.css no longer answers \"$rule\"")
        }
        val row = css.substringAfter(".search-row {").substringBefore("}")
        listOf("display: flex", "flex-direction: column", "border-bottom: 1px solid var(--input-border)").forEach {
            assertTrue(row.contains(it), "a result row lost $it, so the list has no lines between its rows")
        }
        // Both halves of the row are on the scale Stage 132 put in base.css rather than typed here, which is what
        // lets a reader's own font size move the whole list at once.
        assertTrue(row.contains("padding: var(--sp-3) 0"), "a result row is not padded from the scale")
    }
}
