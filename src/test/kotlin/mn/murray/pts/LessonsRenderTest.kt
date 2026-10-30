package mn.murray.pts

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mn.murray.pts.db.Lessons
import mn.murray.pts.db.Migrations
import mn.murray.pts.domain.LESSON_TEXT_MAX
import mn.murray.pts.domain.Member
import mn.murray.pts.repository.LessonRepository
import mn.murray.pts.repository.ProjectRepository
import mn.murray.pts.repository.TaskRepository
import mn.murray.pts.repository.TeamRepository
import mn.murray.pts.service.LessonDraft
import mn.murray.pts.service.LessonField
import mn.murray.pts.service.LessonFilter
import mn.murray.pts.service.LessonService
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlinx.html.DIV
import kotlinx.html.div
import kotlinx.html.stream.createHTML

/**
 * What the Lesson learnt sheet (Stage 44) puts on the page, and what it keeps off it.
 *
 * The picture is the specification: seven columns in a stated order, the author's own initials in
 * their own colour, the project printed as its number and linked, one line of lesson with the rest
 * of it a click away, a dot that says whether the crew has talked about it yet, and three square
 * buttons that belong to the row's author and to management only.
 *
 * The other half is the update contract, which is what makes the page a sheet rather than a list of
 * links: the toolbar is outside the live region, every row owns its own forms, a state flip swaps
 * that one `<tr>`, a row that changes list swaps the whole sheet *and* the count beside the pickers,
 * and the popup is one node that both the add control and a click on a lesson write into. One
 * promise here is structural rather than visual — a form inside a form is dropped by the parser, and
 * the control that looked like a swap becomes a page load — so it is measured on the markup.
 *
 * `createHTML()` breaks a line for a block element and leaves an inline run on one line, so the
 * assertions here are made on a single element (`<span class="…">text</span>`, `<th …>…</th>`) or on
 * one element's attribute run, never across two blocks.
 */
class LessonsRenderTest {

    private lateinit var ds: HikariDataSource

    private var crusher = 0
    private var haulRoad = 0
    private var plateCard = 0
    private var backlogCard = 0

    /** The reader these pages are drawn for: an ordinary account, which wrote lesson [mine] and [away]. */
    private val me = "EMP0002"

    private var mine = 0
    private var theirs = 0
    private var away = 0

    /** A row V9 carried over: no author, no date, no project — the fourth shape the sheet must print. */
    private var carried = 0

    @BeforeEach
    fun setUp(@TempDir dir: File) {
        ds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${File(dir, "lessons-page.db").absolutePath}"
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            }
        )
        Migrations.migrate(ds)
        Database.connect(ds)
        TeamRepository.upsert(Member("EMP0001", "Baatar Uuganbayar", "Executive", "Project Manager"))
        TeamRepository.upsert(Member("EMP0002", "Sarangerel Bold", "Projects", "Project Manager"))
        TeamRepository.upsert(Member("EMP0003", "Ganbold Erdene", "Operations", "Site Supervisor"))

        crusher = project("M-01250", "Erdenet Crusher Modernisation")
        haulRoad = project("S-00005", "Oyu Tolgoi haul road widening")
        plateCard = card(crusher, "Detail plate drawings - Rev A")
        backlogCard = card(null, "Update HSE induction deck")

        mine = log(me, "Order the liner six months early.", crusher, plateCard, "2026-07-30")
        theirs = log("EMP0003", "Quote in the client's currency.", haulRoad, 0, "2026-07-26")
        away = log(me, "Camp beds: keep a ten percent buffer.", 0, 0, "2026-07-01")
        carried = transaction {
            Lessons.insert {
                it[title] = "Monsoon drainage planning"
                it[summary] = ""
                it[lesson] = "Monsoon drainage planning"
                it[loggedOn] = ""
                it[discussed] = false
                it[archived] = false
            } get Lessons.id
        }
        LessonService.setDiscussed(theirs, true, "EMP0003", false)
        LessonService.setArchived(away, true, me, false)
    }

    @AfterEach
    fun tearDown() = ds.close()

    private fun project(code: String, name: String): Int = ProjectRepository.insert(
        code = code, name = name, status = "In progress", progress = 40, managerEmpNo = "EMP0001",
        due = "2026-12-01", type = "Fabrication/Manufacturing", client = "Erdenet Mining Corporation",
        revenue = 2_340_000_000.0,
    )

    private fun card(projectId: Int?, title: String): Int = TaskRepository.insert(
        kind = if (projectId == null) "OTHER" else "ASSIGNED", title = title, projectId = projectId,
        due = "", priority = "Medium", assigneeEmpNo = me, status = "In progress",
        bucketId = null, started = "",
    )

    /** Write a lesson through the service, as the account that would have typed it into the popup. */
    private fun log(who: String, text: String, projectId: Int, taskId: Int, on: String): Int {
        val result = LessonService.save(
            LessonDraft(text = text, projectId = projectId, taskId = taskId, loggedOn = on), who, false,
        )
        assertTrue(result.saved, "the setup refused: $text — ${result.errors}")
        return LessonRepository.all().first { it.text == text }.id
    }

    private fun render(block: DIV.() -> Unit): String = createHTML().div(block = block)

    private fun occurrences(html: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(html).count()

    /** The opening tag whose attributes carry [id] — the whole attribute run of one element. */
    private fun tag(html: String, id: String): String =
        Regex("<[^>]*id=\"$id\"[^>]*>").find(html)?.value ?: error("no element with id=$id")

    /** The opening tag of the first element whose class attribute is exactly [klass]. */
    private fun tagOf(html: String, klass: String): String =
        Regex("<[^>]*class=\"$klass\"[^>]*>").find(html)?.value ?: error("no element with class=$klass")

    /** One row's markup, from its own id attribute to the `</tr>` that closes it. */
    private fun row(html: String, id: Int): String {
        val start = html.indexOf("id=\"ll-row-$id\"").takeIf { it >= 0 } ?: error("no row $id on the page")
        return html.substring(start, html.indexOf("</tr>", start))
    }

    /** The live region — everything a picker or a row write replaces. */
    private fun region(html: String): String =
        html.substringAfter("id=\"ll-board\"").substringBefore("id=\"lessonModal\"")

    /** The popup's own markup, from its id to the script that drives it. */
    private fun modal(html: String): String =
        html.substringAfter("id=\"lessonModal\"").substringBefore("/static/js/lesson-modal.js")

    /** The toolbar's picker form, whose fields answer for the sheet. */
    private fun pickers(html: String): String =
        html.substringAfter("id=\"llFilter\"").substringBefore("</form>")

    /** One column's header cell, from its `<th` to the `</th>` that closes it. */
    private fun header(html: String, klass: String): String {
        val start = html.indexOf("<th class=\"$klass\"").takeIf { it >= 0 } ?: error("no $klass header")
        return html.substring(start, html.indexOf("</th>", start) + "</th>".length)
    }

    /** The popup's lesson field as a whole: its attributes and the words standing inside it. */
    private fun textArea(html: String): String =
        Regex("<textarea[^>]*id=\"lessonText\"[^>]*>(.*?)</textarea>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.value ?: error("no lesson field on the page")

    /** The same field's content, edges off — what a reader who opens the popup finds typed. */
    private fun textAreaValue(html: String): String =
        textArea(html).substringAfter(">").substringBeforeLast("</textarea>").trim()

    /** The deepest form nesting on the page — 2 would be a form the browser silently drops. */
    private fun maxFormDepth(html: String): Int {
        var depth = 0
        var max = 0
        Regex("</?form\\b").findAll(html).forEach {
            if (it.value.startsWith("</")) depth-- else { depth++; max = maxOf(max, depth) }
        }
        assertEquals(0, depth, "the page's forms are not balanced")
        return max
    }

    private fun sheet(
        filter: LessonFilter = LessonFilter(),
        who: String = me,
        canManage: Boolean = false,
        form: LessonForm = LessonForm(),
    ): String = render {
        lessonsContent(
            board = LessonService.board(filter, who, canManage),
            filter = filter,
            form = form,
            projects = ProjectRepository.findAll(),
            tasks = LessonService.taskChoices(),
            employees = TeamRepository.findAll(),
        )
    }

    /** The popup as `?edit=<id>` draws it for [who] — read-only half when that reader may not write it. */
    private fun popup(entryId: Int, who: String = me, canManage: Boolean = false): LessonForm {
        val entry = LessonService.find(entryId)!!
        return LessonForm(
            id = entry.id, typed = entry.text, projectId = entry.projectId, projectCode = entry.projectCode,
            projectName = entry.projectName, taskId = entry.taskId, taskLabel = entry.taskTitle,
            loggedOn = entry.loggedOn, employeeName = entry.employeeName, open = true,
            readOnly = !LessonService.mayWrite(entry, who, canManage),
        )
    }

    // --- the columns --------------------------------------------------------------------------------

    @Test
    fun `the sheet prints the seven columns in the order the picture reads them`() {
        val html = sheet()
        assertEquals(1, occurrences(html, "<table class=\"grid lessons-table\">"), "the active list printed twice")
        val columns = listOf(
            "l-date" to "Date", "l-employee" to "Employee", "l-project" to "Project #", "l-task" to "Task",
            "l-text" to "Lesson learnt", "l-status" to "Status", "l-actions" to "",
        )
        columns.forEach { (klass, text) ->
            assertEquals(1, occurrences(html, "<th class=\"$klass\""), "$klass: the sheet printed that column twice")
            // Checked as a cell rather than a whole tag because the date column's heading is also the
            // control that reverses it: `<th class="l-date"><button …>Date<span …>▼</span></button></th>`.
            val cell = header(html, klass)
            if (text.isEmpty()) {
                assertEquals("<th class=\"$klass\"></th>", cell, "the sheet's last column printed a heading")
            } else {
                assertTrue(cell.contains(">$text<"), "$klass: the header does not say \"$text\"")
            }
        }
        // `table-layout: fixed` takes its widths from the colgroup, so the columns that are sized are the
        // columns that are headed — same names, same order.
        assertEquals(
            columns.map { it.first },
            Regex("<col class=\"(l-[a-z]+)\"").findAll(html).map { it.groupValues[1] }.toList(),
            "the sheet's widths are not over its headers",
        )
        // Three rows above the Archived heading, newest first, and the undated carry-over last of them.
        assertEquals(
            listOf(mine, theirs, carried),
            Regex("id=\"ll-row-(\\d+)\"").findAll(html).map { it.groupValues[1].toInt() }.toList(),
            "the active list is not the three rows in the order the date column says",
        )
    }

    @Test
    fun `the employee column is the person who logged it, in their own colour`() {
        val html = sheet(LessonFilter(showArchived = true))
        assertTrue(row(html, mine).contains("<span class=\"avatar sm ll-a1\">SB</span>"), "no avatar carrying the author's initials")
        assertTrue(row(html, mine).contains("<span class=\"ll-person-name\">Sarangerel Bold</span>"), "the column prints something other than the roster's name")
        assertTrue(row(html, theirs).contains(">GE</span>"), "a colleague's initials are missing")

        // The tint is the account's, not the row's: the same person is the same colour on every lesson,
        // which is what makes the column readable at a glance.
        val tint = Regex("avatar sm (ll-a\\d)").find(row(html, mine))!!.groupValues[1]
        assertEquals(tint, Regex("avatar sm (ll-a\\d)").find(row(html, away))!!.groupValues[1], "one account, two colours")

        // A row nobody owns prints no person, because there is nobody to print.
        assertTrue(row(html, carried).contains("<td class=\"l-employee\"><span class=\"ll-none\">—</span></td>"), "an unowned lesson invented an author")
        assertFalse(row(html, carried).contains("avatar"), "an unowned lesson drew an avatar")
    }

    @Test
    fun `the project column is the job's number as a link and the task column its card`() {
        val html = sheet()
        // The number, not the name: the sheet is read down a narrow column and cross-checked against the
        // project pages, which are numbered the same way.
        assertTrue(row(html, mine).contains("<a href=\"/app/projects/$crusher\" class=\"row-link\"><span class=\"code\">M-01250</span></a>"), "the project is not its linked number")
        assertTrue(row(html, mine).contains("<td class=\"l-task\"><span class=\"ll-task\">Detail plate drawings - Rev A</span></td>"), "the card title is missing")
        assertTrue(row(html, theirs).contains("<span class=\"code\">S-00005</span>"))

        // A lesson about no project and no card says so with the sheet's own dash rather than a blank cell
        // that could read as a row that has not loaded yet.
        listOf("l-project", "l-task").forEach {
            assertTrue(
                row(html, carried).contains("<td class=\"$it\"><span class=\"ll-none\">—</span></td>"),
                "the carry-over's $it cell is not a dash",
            )
        }
    }

    @Test
    fun `the status column is the lesson's own pill and the date column the day it was logged`() {
        val html = sheet()
        assertEquals(1, occurrences(html, "<td class=\"l-status\"><span class=\"chip ll-discussed\">Discussed</span></td>"), "the discussed pill changed shape or count")
        assertEquals(2, occurrences(html, "<td class=\"l-status\"><span class=\"chip ll-undiscussed\">Not discussed</span></td>"), "the active rows lost their state")
        // Not the card's chip: `.chip.done` is a blue card state on the boards, and "discussed" is a fact
        // about a lesson, so the two are named apart and drawn apart.
        assertFalse(html.contains("chip done"), "a lesson's state was drawn as a card's")
        assertTrue(row(html, mine).contains("<td class=\"l-date\">30 Jul 2026</td>"), "the date is not the day the sheet reads")
        assertTrue(row(html, carried).contains("<td class=\"l-date\">—</td>"), "an undated carry-over was given a date")
    }

    @Test
    fun `the lesson column holds the whole text and lets the stylesheet cut it`() {
        val html = sheet()
        val cell = row(html, mine)
        // Truncating in the markup would hide the rest from a reader who widens the window, and from the
        // popup the click opens — so the cell carries all of it and the button's title names it too.
        val button = Regex("<button[^>]*class=\"ll-open\"[^>]*>").find(cell)!!.value
        assertTrue(button.contains("title=\"Order the liner six months early.\""), "the click does not say what it opens")
        assertTrue(cell.contains("Order the liner six months early.</button>"), "the cell cut the text out of the markup")
        assertEquals(3, occurrences(html, "class=\"opener-form ll-text-form\""), "every row should open the popup")
        assertFalse(html.contains("class=\"lesson-text\""), "the retired one-cell class is back")
    }

    // --- the update contract ------------------------------------------------------------------------

    @Test
    fun `the two pickers are one live GET form and the count is their companion`() {
        val html = sheet()
        val form = tag(html, "llFilter")
        assertTrue(form.startsWith("<form action=\"/app/lessons\" method=\"get\""), "the pickers post instead of answering on their own address")
        assertTrue(form.contains("data-live-extra=\"#ll-counts\""), "choosing a project leaves the count at the old number")
        assertEquals(1, occurrences(html, "<select name=\"project\" data-autosubmit"), "the project picker changed shape")
        assertTrue(pickers(html).contains("<select name=\"employee\" data-autosubmit"))
        assertTrue(html.contains("<option value=\"0\" selected=\"selected\">All projects</option>"), "the picker has no way back to every project")
        assertTrue(html.contains("<option value=\"\" selected=\"selected\">All employees</option>"))
        // The two pickers are the page's only auto-submitting fields: a date or a card that posted on
        // change would be a lesson refused on the field its writer has not reached yet.
        assertEquals(2, occurrences(html, "data-autosubmit"), "the sheet grew a third control that submits itself")

        // The count is the toolbar's, not the sheet's, so a refresh of the rows cannot take it out of the
        // line it sits in — and a picker change cannot leave it counting the unfiltered sheet. The sheet
        // does name `#ll-counts` as a swap target, because a row leaving it changes the number too: what
        // must not be in there is the element itself.
        assertTrue(html.indexOf("id=\"ll-counts\"") < html.indexOf("id=\"ll-board\""), "the count moved under the sheet")
        assertFalse(region(html).contains("<span class=\"ll-counts\""), "the count is inside the live region")
        assertTrue(html.contains("<span class=\"ll-counts\" id=\"ll-counts\">3 active, 1 archived</span>"))
    }

    @Test
    fun `the date header and the archived heading live in the region and belong to the toolbar`() {
        val html = sheet()
        val sort = tagOf(html, "ll-sort")
        assertTrue(sort.contains("form=\"llFilter\""), "the header button is a form of its own")
        assertTrue(sort.contains("name=\"sort\""))
        // The order it *asks* for, never the one on screen, so a reload cannot flip the sheet back again.
        assertTrue(sort.contains("value=\"old\""), "newest first offered to stay newest first")
        assertTrue(sort.contains("title=\"Show the oldest lesson first\""))
        assertTrue(html.contains("<span class=\"ll-sort-glyph\">▼</span>"), "the column does not say which way it runs")
        assertTrue(region(html).contains("class=\"ll-sort\""), "the header control is not part of the sheet it reorders")

        val toggle = tagOf(html, "ll-arch-toggle")
        assertTrue(toggle.contains("form=\"llFilter\""), "the heading is a form of its own")
        assertTrue(toggle.contains("name=\"archived\""))
        assertTrue(toggle.contains("value=\"1\""), "a closed archive offered to close again")
        assertTrue(toggle.contains("aria-expanded=\"false\""))
        assertTrue(html.contains("<span class=\"ll-arch-caret\">▸</span>"))
        assertTrue(html.contains("<span class=\"pill\">1</span>"), "the heading does not count what is under it")
    }

    @Test
    fun `the order and the open archive ride with every control on the sheet`() {
        val view = LessonFilter(project = crusher, employee = me, oldestFirst = true, showArchived = true)
        val html = sheet(view)
        val form = pickers(html)
        // The pickers answer for `project` and `employee` themselves — duplicating those names in hidden
        // fields would state the same choice twice in one address. The other two keys have no field of
        // their own in the form, so they arrive as hidden ones or the first picker change loses them.
        assertFalse(form.contains("<input type=\"hidden\" name=\"project\""), "the toolbar duplicates its own project picker")
        assertTrue(form.contains("<input type=\"hidden\" name=\"sort\" value=\"old\">"), "choosing a project jumps the sheet back to newest-first")
        assertTrue(form.contains("<input type=\"hidden\" name=\"archived\" value=\"1\">"), "choosing a project folds the archive the reader is standing in")

        // A row write cannot use those plain names: its own `archived` is the state the row is being
        // moved to and its own `project` the job the lesson is about, so the view travels prefixed.
        val actions = row(html, mine)
        listOf("board_project" to "$crusher", "board_employee" to me, "board_sort" to "old", "board_archived" to "1").forEach { (name, value) ->
            assertTrue(actions.contains("<input type=\"hidden\" name=\"$name\" value=\"$value\">"), "a row posted without $name")
        }
        // The opener forms are GETs onto this page, so theirs are the plain keys.
        assertTrue(row(html, mine).contains("<input type=\"hidden\" name=\"project\" value=\"$crusher\">"), "the click on a lesson lost the picker")

        // And the two flip controls ask for the other half, from the toolbar they belong to.
        assertTrue(tagOf(html, "ll-sort").contains("value=\"new\""), "oldest-first offered to go oldest-first again")
        assertTrue(html.contains("<span class=\"ll-sort-glyph\">▲</span>"))
        assertTrue(tagOf(html, "ll-arch-toggle").contains("value=\"0\""), "an open archive offered to open")
        assertTrue(tagOf(html, "ll-arch-toggle").contains("aria-expanded=\"true\""))
        assertTrue(html.contains("<span class=\"ll-arch-caret\">▾</span>"))

        // Clearing the pickers is the reader's whole question, so the address it loads keeps the order
        // they set and the half they had open.
        val clear = Regex("<a href=\"[^\"]*\" class=\"board-reset\">").find(html)!!.value
        assertTrue(clear.contains("sort=old") && clear.contains("archived=1"), "Clear reset the sheet's view: $clear")
        assertFalse(clear.contains("project"), "Clear left one picker still set")
    }

    @Test
    fun `nothing on this page nests a form inside a form`() {
        // The parser drops the inner `<form>` rather than nesting it, which turns a control meant to
        // swap a block into a page load — so this is measured, not assumed, and measured with the popup
        // open too, because that is the state with the most forms on the page at once.
        listOf(
            sheet(),
            sheet(LessonFilter(showArchived = true)),
            sheet(form = LessonForm(open = true, loggedOn = "2026-10-16")),
            sheet(form = popup(mine)),
            sheet(form = popup(theirs)),
        ).forEach { assertEquals(1, maxFormDepth(it), "a form on this page sits inside another form") }
    }

    @Test
    fun `a row that only changes state stays exactly where it is`() {
        // Drawn for management with the archive open, so every one of the four shapes — undiscussed,
        // discussed, put away, carried over — is on the page and offered its buttons.
        val html = sheet(LessonFilter(showArchived = true), who = "EMP0001", canManage = true)
        val actions = row(html, mine)
        val status = Regex("<form action=\"/app/lessons/status\"[^>]*>").find(actions)!!.value
        assertTrue(status.contains("method=\"post\""))
        assertTrue(status.contains("data-live-target=\"#ll-row-$mine\""), "ticking a lesson would redraw the whole sheet")
        assertFalse(status.contains("data-live-extra"), "a state flip replaces a block the row does not change")
        // The value is the state being asked for, so the button is absolute, and the glyph beside it is
        // the one that suits the row's own state.
        assertTrue(actions.contains("<input type=\"hidden\" name=\"id\" value=\"$mine\">"))
        assertTrue(actions.contains("<input type=\"hidden\" name=\"discussed\" value=\"1\">"))
        assertTrue(actions.contains("<button type=\"submit\" class=\"icon-btn\" title=\"Mark it discussed\" aria-label=\"Mark it discussed\">✓</button>"))
        // The discussed row has already been ticked, so it offers the way back instead.
        assertTrue(row(html, theirs).contains("<input type=\"hidden\" name=\"discussed\" value=\"0\">"))
        assertTrue(row(html, theirs).contains("aria-label=\"Mark it not discussed\""))
        // A lesson that has been put away is not also being discussed.
        assertFalse(row(html, away).contains("/app/lessons/status"), "an archived row offers a discussion state")
    }

    @Test
    fun `a row that leaves the sheet takes the sheet and the count with it`() {
        val html = sheet(LessonFilter(showArchived = true))
        val deleted = Regex("<form action=\"/app/lessons/delete\"[^>]*>").find(row(html, mine))!!.value
        assertTrue(deleted.contains("data-live-target=\"#ll-board\""), "deleting a row left its `<tr>` on the page")
        assertTrue(deleted.contains("data-live-extra=\"#ll-counts\""), "the count kept the deleted row in it")
        val archived = Regex("<form action=\"/app/lessons/archive\"[^>]*>").find(row(html, mine))!!.value
        assertTrue(archived.contains("data-live-target=\"#ll-board\""), "archiving a row left it on the active list")
        assertTrue(archived.contains("data-live-extra=\"#ll-counts\""))
        assertTrue(html.contains("data-confirm=\"Delete this lesson? It cannot be brought back.\""), "the bin asks for nothing")
        assertTrue(html.contains("aria-label=\"Delete this lesson\""))

        // An archived row comes back through the same door, and that is the only change it offers.
        val awayRow = row(html, away)
        assertTrue(awayRow.contains("<input type=\"hidden\" name=\"archived\" value=\"0\">"), "the archive list cannot bring a lesson back")
        assertTrue(awayRow.contains("aria-label=\"Restore this lesson\""))
        assertTrue(awayRow.contains(">↩</button>"), "an archived row does not offer the restore")
        assertEquals(2, occurrences(awayRow, "class=\"ll-action\""), "an archived row changed its button count")
    }

    @Test
    fun `only the author and management are offered a row's buttons`() {
        val asStranger = sheet()
        // Three buttons on the reader's own row, and nothing on the other two: an ordinary account writes
        // its own lessons and reads everybody else's.
        assertEquals(3, occurrences(asStranger, "class=\"ll-action\""), "the row buttons changed count for an ordinary account")
        assertEquals(2, occurrences(asStranger, "<td class=\"l-actions\"></td>"), "a row somebody else owns is offering its buttons")
        assertFalse(row(asStranger, theirs).contains("/app/lessons/status"), "a colleague's row can be ticked from here")
        assertFalse(row(asStranger, carried).contains("ll-action"), "an unowned carry-over was open to any account")
        // Reading the lesson is not writing it: the click on the text is every reader's, whoever wrote it.
        assertEquals(3, occurrences(asStranger, "class=\"opener-form ll-text-form\""), "a peer lost the read of a lesson")

        // Management is offered every row — including the carry-over nobody owns — and nothing else moves.
        val asManagement = sheet(who = "EMP0001", canManage = true)
        assertEquals(9, occurrences(asManagement, "class=\"ll-action\""), "management was not offered every active row")
        assertEquals(3, occurrences(asManagement, "class=\"opener-form ll-text-form\""), "management's sheet grew a control of its own")
    }

    @Test
    fun `the counter counts the sheet the reader is looking at`() {
        assertTrue(sheet(LessonFilter(project = crusher)).contains("1 active, 0 archived"), "the count is the whole list rather than the filtered one")
        assertTrue(sheet(LessonFilter(employee = me)).contains("1 active, 1 archived"), "the reader's own sheet miscounts its halves")
        assertTrue(sheet(LessonFilter(project = haulRoad, employee = me)).contains("0 active, 0 archived"), "an empty cut still counts the unfiltered sheet")
    }

    // --- the popup ----------------------------------------------------------------------------------

    @Test
    fun `the page arrives with the popup drawn but closed`() {
        val html = sheet()
        assertFalse(tag(html, "lessonModal").contains("style"), "a popup nobody asked for came up open")
        // The form is on the page rather than fetched by the script, so a reader with no scripts gets the
        // same fields from the address that asks for them — and the swap has one node to write into.
        assertTrue(html.contains("id=\"lessonText\""))
        assertTrue(html.contains("/static/js/lesson-modal.js"), "the popup lost the script that closes it")
    }

    @Test
    fun `the add control opens the popup without moving the sheet`() {
        val html = sheet(form = LessonForm(open = true, loggedOn = "2026-10-16"))
        val opener = Regex("<form action=\"/app/lessons\" method=\"get\" class=\"opener-form\"[^>]*>").find(html)!!.value
        assertTrue(opener.contains("data-live-target=\"#lessonModal\""), "the add button would navigate instead of opening")
        assertTrue(html.contains("<input type=\"hidden\" name=\"new\" value=\"1\">"))
        assertTrue(html.contains("<button type=\"submit\" class=\"ghost-btn btn-sm\">+ Add lesson</button>"))

        assertTrue(tag(html, "lessonModal").contains("style=\"display:block\""), "the popup did not come up")
        val form = Regex("<form action=\"/app/lessons/save\"[^>]*>").find(modal(html))!!.value
        assertTrue(form.contains("data-live-target=\"#ll-board\""), "a saved lesson would not appear on the sheet")
        assertTrue(form.contains("data-live-extra=\"#ll-counts,#lessonModal\""), "the count or the popup itself is left stale")
        assertFalse(modal(html).contains("<input type=\"hidden\" name=\"id\""), "a new lesson arrived as an edit")

        val text = tag(html, "lessonText")
        assertTrue(text.startsWith("<textarea"), "the lesson is a one-line field")
        assertTrue(text.contains("name=\"text\""))
        assertTrue(text.contains("maxlength=\"$LESSON_TEXT_MAX\""), "the field can type past what the service refuses")
        assertTrue(tag(html, "lessonDate").contains("type=\"date\""), "the date is a free-text box")
        assertTrue(tag(html, "lessonDate").contains("value=\"2026-10-16\""), "a new lesson is not dated today by default")
        assertTrue(html.contains("<option value=\"\" selected=\"selected\">— No project —</option>"), "the popup forces a project")
        assertTrue(html.contains("<option value=\"\" selected=\"selected\">— No card —</option>"), "the popup forces a card")
        // The cards are grouped by their project rather than filtered by the picker above: a popup that
        // had to be re-rendered to fill its own second field would be a page load wearing a mask.
        assertTrue(html.contains("<optgroup label=\"M-01250\">"), "the cards are not grouped under their project's number")
        assertTrue(html.contains("<optgroup label=\"No project\">"), "the backlog's cards have no group")
        assertTrue(html.contains("<button type=\"submit\" class=\"submit\">Add lesson</button>"))
        // The author is the session, so the popup that writes a lesson asks for nothing about it.
        assertFalse(modal(html).contains("name=\"employee\""), "the popup let a lesson be attributed to somebody else")
        assertTrue(html.contains("It goes on the sheet under your name"))
    }

    @Test
    fun `a click on a lesson opens that lesson, filled in`() {
        val html = sheet(form = popup(mine))
        assertTrue(modal(html).contains("<input type=\"hidden\" name=\"id\" value=\"$mine\">"), "the edit popup is writing a new row")
        assertTrue(html.contains("<button type=\"submit\" class=\"submit\">Save lesson</button>"))
        assertTrue(html.contains("<h3>Lesson learnt</h3>"), "an edit is titled as an add")
        assertTrue(modal(html).contains("<option value=\"$crusher\" selected=\"selected\">M-01250 · Erdenet Crusher Modernisation</option>"), "the stored project is not the one on screen")
        assertTrue(modal(html).contains("<option value=\"$plateCard\" selected=\"selected\">Detail plate drawings - Rev A</option>"), "the stored card is not the one on screen")
        assertEquals("Order the liner six months early.", textAreaValue(html), "the edit lost the words")
        assertTrue(tag(html, "lessonDate").contains("value=\"2026-07-30\""), "the edit lost the date")
        // The promise the popup makes about the author, to the reader about to change the words.
        assertTrue(html.contains("The lesson stays under the name it was logged with"), "the edit popup does not say who owns the row")
        // The click itself is a GET onto this page carrying the row, and the sheet it came from.
        assertTrue(row(html, mine).contains("<input type=\"hidden\" name=\"edit\" value=\"$mine\">"), "the text is not a click onto this row")
    }

    @Test
    fun `a colleague's lesson opens to be read, not written`() {
        val html = sheet(form = popup(theirs))
        assertEquals(0, occurrences(modal(html), "<form"), "a reader was served a form over somebody else's row")
        assertFalse(html.contains("/app/lessons/save"), "the read-only popup can post")
        assertFalse(html.contains("id=\"lessonText\""), "the read-only popup drew an editor")
        assertTrue(html.contains("<button type=\"button\" class=\"ghost-btn\" data-lesson-modal-close=\"true\">Close</button>"))
        assertTrue(html.contains("Logged by Ganbold Erdene. Only its author and management can change what a lesson says."), "the popup does not say whose lesson this is")
        assertTrue(html.contains("<p class=\"ll-plain\">Quote in the client's currency.</p>"), "the full text is not on the page")
        // The read-only half names everything the writable half offers as a picker — the job in full, and
        // the sheet's own dash where there is no card.
        assertTrue(html.contains("<p class=\"ll-plain\">S-00005 · Oyu Tolgoi haul road widening</p>"), "the read-only half names only the code")
        assertTrue(html.contains("<p class=\"ll-plain\">26 Jul 2026</p>"), "the read-only half lost the date")
        assertTrue(html.contains("<p class=\"ll-plain\">—</p>"), "the read-only half printed a blank where a card should be")
    }

    @Test
    fun `a refused save comes back with the words and the reason under the field that caused it`() {
        val refused = LessonService.save(
            LessonDraft(text = "x".repeat(LESSON_TEXT_MAX + 1), projectId = 999_999, taskId = plateCard, loggedOn = "30/07/2026"),
            me, false,
        )
        assertFalse(refused.saved)
        val html = sheet(
            form = LessonForm(
                typed = refused.values.text, projectId = refused.values.projectId,
                loggedOn = refused.values.loggedOn, errors = refused.errors,
            ),
        )
        assertTrue(tag(html, "lessonModal").contains("style=\"display:block\""), "the popup closed on a refusal")
        assertEquals(
            LESSON_TEXT_MAX + 1,
            textAreaValue(html).length,
            "the typed words were thrown away",
        )
        // Three reasons, one under each field that caused it — nothing at the top of the page, and no
        // field the reader has to guess at.
        assertEquals(
            listOf("That project is no longer on the list.", "Choose a date the sheet can sort by.", "A lesson is 500 characters or fewer."),
            Regex("<p class=\"field-err\">([^<]*)</p>").findAll(modal(html)).map { it.groupValues[1] }.toList(),
            "the reasons are not the fields' own, in the order the fields print",
        )
        // The view the reader was on is the view the popup comes back on.
        val filtered = sheet(
            LessonFilter(project = crusher, oldestFirst = true),
            form = LessonForm(typed = "words", errors = mapOf(LessonField.TEXT to "Write what was learned — the sheet has nothing to show without it.")),
        )
        listOf("board_project" to "$crusher", "board_sort" to "old").forEach { (name, value) ->
            assertTrue(modal(filtered).contains("<input type=\"hidden\" name=\"$name\" value=\"$value\">"), "the popup posted without $name")
        }
    }

    // --- the archive and the two empties -------------------------------------------------------------

    @Test
    fun `the archived heading is printed whether or not there is anything under it`() {
        val closed = sheet()
        assertEquals(1, occurrences(closed, "class=\"ll-archived\""), "the page hides the place lessons go")
        assertEquals(1, occurrences(closed, "<table class=\"grid lessons-table\">"), "a closed archive drew a second table")
        assertFalse(closed.contains("Camp beds"), "an archived lesson is printed on the active sheet")

        val open = sheet(LessonFilter(showArchived = true))
        assertEquals(2, occurrences(open, "<table class=\"grid lessons-table\">"), "the open archive did not print its own list")
        // The same seven columns, so a row that was put away reads exactly as it did before.
        listOf("l-date", "l-employee", "l-project", "l-task", "l-text", "l-status", "l-actions").forEach {
            assertEquals(2, occurrences(open, "<th class=\"$it\">"), "the archive list is missing the $it column")
        }
        assertTrue(open.contains("Camp beds: keep a ten percent buffer."))

        // With the heading open over nothing the page says so in words instead of a table of blanks.
        val noneArchived = sheet(LessonFilter(project = crusher, showArchived = true))
        assertTrue(noneArchived.contains("Nothing has been archived."), "an open empty archive printed a table of nothing")
        assertEquals(1, occurrences(noneArchived, "<table class=\"grid lessons-table\">"), "the empty archive drew its own table anyway")
    }

    @Test
    fun `an empty sheet says which of the two empties it is`() {
        LessonRepository.all().forEach { LessonService.remove(it.id, "EMP0001", canManage = true) }
        val fresh = sheet()
        assertTrue(fresh.contains("Nothing has been logged yet."), "a page with no lessons at all is not told so")
        assertTrue(fresh.contains("it goes on this sheet under their own name"), "the empty page does not say what a lesson is")
        assertEquals(0, occurrences(fresh, "<table class=\"grid lessons-table\">"), "an empty sheet drew a header over nothing")
        // The add control is offered twice: once in the toolbar where it always lives, once on the empty
        // page itself, because that is where the reader is looking.
        assertEquals(2, occurrences(fresh, "+ Add lesson</button>"), "the empty sheet does not offer the way in")
        assertFalse(fresh.contains("No lesson matches"), "a fresh page claims a filter emptied it")

        val nothingMatches = sheet(LessonFilter(project = crusher, employee = "EMP0003"))
        assertTrue(nothingMatches.contains("No lesson matches this project and employee."), "a filtered empty is not told apart from a fresh page")
        assertTrue(nothingMatches.contains("Show every lesson"), "the reader is left on the empty view")
        assertFalse(nothingMatches.contains("Nothing has been logged yet."), "a filtered-empty page claims the whole sheet is empty")
    }

    @Test
    fun `the pickers offer the projects and the roster the page was drawn with`() {
        val html = sheet(LessonFilter(project = crusher, employee = "EMP0003"))
        assertTrue(html.contains("<option value=\"$crusher\" selected=\"selected\">M-01250 · Erdenet Crusher Modernisation</option>"), "the chosen project is not the one on screen")
        assertTrue(html.contains("<option value=\"EMP0003\" selected=\"selected\">Ganbold Erdene</option>"), "the chosen employee is not the one on screen")
        // Both lists are the page's own, so a project with no lessons is still one a reader can ask for,
        // and every account on the roster is offered whether it has logged anything or not.
        assertEquals(3, occurrences(selectNamed(html, "project"), "<option value="), "the project picker is not the project list plus All")
        assertEquals(4, occurrences(selectNamed(html, "employee"), "<option value="), "the employee picker is not the roster plus All")
        // The cards the popup offers come from both boards, under their project's number or under none.
        assertTrue(html.contains("<option value=\"$plateCard\">Detail plate drawings - Rev A</option>"))
        assertTrue(html.contains("<option value=\"$backlogCard\">Update HSE induction deck</option>"), "the backlog's cards are not offered")
        assertFalse(html.contains("name=\"q\""), "the sheet grew a search box the picture does not have")
    }

    /**
     * The stylesheet rules the sheet's own markup depends on.
     *
     * Two of them were measured off the rendered page after the sheet printed its lesson text across
     * the Status and Actions columns, and both are worth pinning because the markup gives no sign of
     * either: the cell's form carries the shared `.opener-form` class, and the column shares are seven
     * percentages that have to add up. Reading `app.css` is the only way to hold either to account.
     */
    private fun appCss(): String = File("src/main/resources/static/css/app.css").readText()

    @Test
    fun `the lesson cell's form is a block even against the shared opener rule`() {
        val css = appCss()
        val opener = Regex("""\.opener-form\s*\{[^}]*display:\s*inline-flex""").find(css)
            ?: error("the shared opener rule no longer sets inline-flex — re-check what the lesson cell inherits")
        val cell = Regex("""form\.ll-text-form\s*\{[^}]*display:\s*block""").find(css)
            ?: error("the lesson cell's form is not stated as a block, so the whole sentence lays out side by side")
        // A bare `.ll-text-form` and a bare `.opener-form` are one class deep each, and CSS hands a tie to
        // whichever rule comes last in the file — which is what made the cell an inline-flex box sized to
        // the button's full sentence, printing the lesson over the two columns to its right. Naming the
        // element makes this (0,1,1) against that (0,1,0), so it wins on specificity and the order stops
        // mattering: the cell is as wide as its column, and `.ll-open` truncates inside it.
        assertTrue(cell.value.startsWith("form."), "the cell's rule is a bare class and can lose on order again")
        assertFalse(
            Regex("""(^|[^.\w])\.ll-text-form\s*\{""").containsMatchIn(css),
            "a second, unqualified rule for the lesson cell is back to lose the tie",
        )
        assertTrue(opener.value.contains("inline-flex"), "the opener rule the cell must outspecify has changed")
    }

    @Test
    fun `the seven columns share the sheet's width exactly`() {
        val shares = Regex("""table\.lessons-table col\.(l-[a-z]+)\s*\{\s*width:\s*(\d+)%""")
            .findAll(appCss())
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        assertEquals(
            setOf("l-date", "l-employee", "l-project", "l-task", "l-text", "l-status", "l-actions"),
            shares.keys, "the grid no longer has the seven columns the page prints",
        )
        assertEquals(100, shares.values.sum(), "the shares must add to 100 on a table-layout: fixed grid")
        // A percentage is only enough for what the column has to spell, and these four are fixed shapes:
        // a date, a project code, the dot-and-words pill, and three 30px squares with 6px between them.
        // Each was measured on the rendered sheet with nowrap; the shares below are that width plus the
        // cell's 20px of padding. Short by even a few pixels and the label splits into two lines, every
        // row grows, and the last action button paints over the column's own edge.
        assertTrue(shares.getValue("l-date") >= 9, "a date needs 84px, so 9% is the least it can have")
        assertTrue(shares.getValue("l-status") >= 13, "the pill needs 124px and splits into two lines below that")
        assertTrue(shares.getValue("l-actions") >= 12, "three buttons need 122px or the archive spills past the edge")
    }

    /** One of the page's selects, by the key it posts — the toolbar's comes first, so a name used twice
     * reads as the sheet's control rather than the popup's. */
    private fun selectNamed(html: String, name: String): String =
        Regex("<select name=\"$name\"[^>]*>.*?</select>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.value ?: error("no select named $name")
}
