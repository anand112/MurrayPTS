package mn.murray.pts

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mn.murray.pts.db.Migrations
import mn.murray.pts.domain.BOARD_OTHER
import mn.murray.pts.domain.Member
import mn.murray.pts.repository.BucketRepository
import mn.murray.pts.repository.ChecklistRepository
import mn.murray.pts.repository.ProjectRepository
import mn.murray.pts.repository.TaskRepository
import mn.murray.pts.repository.TeamRepository
import mn.murray.pts.service.TaskDraft
import mn.murray.pts.service.TaskField
import org.jetbrains.exposed.sql.Database
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
 * The Team page's ordering, on the same real-schema harness as [TaskBoardRenderTest].
 *
 * The roster is stored in hire order, which on a page of fourteen departments reads as shuffled,
 * so the promise worth pinning is that people are grouped by department and the group labels come
 * out in the same order — in the table and in the assign form, which is the one place the same
 * list is shown a second way.
 *
 * The second half of what is pinned is that form (Stage 50): it is the one screen that writes a card without
 * anybody standing on a board, so which board it writes for has to be asked here, and a refusal has to come
 * back as this page with the words kept. The rules themselves are the service's — see
 * [mn.murray.pts.service.TaskServiceTest]'s `a no-project assign…` pair — and what is checked below is that
 * the panel asks the question in the same field and markers the card popup uses, and prints what it was told.
 */
class TeamPageRenderTest {

    private lateinit var ds: HikariDataSource

    /** Deliberately inserted out of alphabetical order: Engineering last, HSE first among repeats. */
    private val roster = listOf(
        Member("EMP0010", "Naran Purev", "HSE", "Safety Officer"),
        Member("EMP0011", "Bat-Erdene Tseren", "Operations", "Site Supervisor"),
        Member("EMP0012", "Munkhtsetseg Bold", "Engineering", "Mining Engineer"),
        Member("EMP0013", "Oyuna Damdin", "HSE", "Environmental Advisor"),
        Member("EMP0014", "Sodnom Gyamtso", "", "Field Assistant"),
    )

    @BeforeEach
    fun setUp(@TempDir dir: File) {
        ds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${File(dir, "team.db").absolutePath}"
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            }
        )
        Migrations.migrate(ds)
        Database.connect(ds)
        roster.forEach { TeamRepository.upsert(it) }
    }

    @AfterEach
    fun tearDown() = ds.close()

    private fun render(block: DIV.() -> Unit): String = createHTML().div(block = block)

    private fun occurrences(html: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(html).count()

    /** The department labels the page prints, in the order they appear. */
    private fun departmentLabels(html: String): List<String> =
        Regex("<span class=\"dept-name\">([^<]*)</span>").findAll(html).map { it.groupValues[1] }.toList()

    /**
     * The assign panel, from its own div to the script that drives its board picker. The tests below are about
     * one half of this page, and the sheet above it prints a hundred nodes none of them care about — so each
     * assertion reads the slice, the same way the board tests read a popup.
     */
    private fun panel(html: String): String {
        val start = html.indexOf("id=\"assignPanel\"")
        if (start < 0) return ""
        val end = html.indexOf("task-modal.js", start)
        return html.substring(start, if (end < 0) html.length else end)
    }

    /** A picker's opening tag, which is where its `name` and its `required` are written. */
    private fun selectTag(html: String, name: String): String =
        Regex("<select[^>]*name=\"${Regex.escape(name)}\"[^>]*>").find(html)?.value.orEmpty()

    /** One half of the panel's own field tag, so `hidden` is read off the field rather than off its neighbours. */
    private fun halfTag(html: String, half: String): String =
        Regex("<div[^>]*data-board-half=\"${Regex.escape(half)}\"[^>]*>").find(html)?.value.orEmpty()

    /** A picker and its options, since which columns the board offers is the other half of the field. */
    private fun selectRegion(html: String, name: String): String {
        val start = Regex("<select[^>]*name=\"${Regex.escape(name)}\"").find(html)?.range?.first ?: return ""
        val end = html.indexOf("</select>", start)
        return html.substring(start, if (end < 0) html.length else end + "</select>".length)
    }

    /** Whether this block has [value] as the answer it opens on. */
    private fun selectedOption(html: String, value: String): Boolean =
        Regex("<option[^>]*value=\"${Regex.escape(value)}\"[^>]*selected").containsMatchIn(html)

    @Test
    fun `one heading row per department, in alphabetical order`() {
        val html = render { teamContent(canManage = false, viewerEmpNo = "EMP0011") }
        assertEquals(
            listOf("Engineering", "HSE", "Operations", UNASSIGNED_DEPARTMENT),
            departmentLabels(html),
        )
        // One row each, spanning the grid so the numeric columns stay in line under it. The
        // hidden member-task rows carry the same colspan, so this pins the heading row's own
        // shape rather than counting the attribute.
        assertEquals(4, occurrences(html, "class=\"dept-row\""))
        // createHTML indents, so adjacency has to be matched across the whitespace it inserts.
        assertTrue(
            Regex(
                "<tr class=\"dept-row\">\\s*<td colspan=\"12\">\\s*<div class=\"dept-cell\">"
            ).containsMatchIn(html),
            "the heading row is not a full-width row",
        )
    }

    @Test
    fun `people sit under their own department, named within it`() {
        val html = render { teamContent(canManage = false, viewerEmpNo = "EMP0011") }
        val engineering = html.indexOf(">Engineering<")
        val hse = html.indexOf(">HSE<")
        val operations = html.indexOf(">Operations<")
        assertTrue(engineering in 0..hse, "Engineering is not the first group")
        assertTrue(hse < operations, "HSE does not come before Operations")
        // Inside HSE the two officers are in name order, not roster order.
        assertTrue(
            html.indexOf("Naran Purev") < html.indexOf("Oyuna Damdin"),
            "HSE members are not sorted by name",
        )
        // Each person appears after their own heading and before the next one.
        assertTrue(html.indexOf("Munkhtsetseg Bold") > engineering)
        assertTrue(html.indexOf("Munkhtsetseg Bold") < hse)
    }

    @Test
    fun `a heading states its headcount and says people in the plural`() {
        val html = render { teamContent(canManage = false, viewerEmpNo = "EMP0011") }
        assertTrue(html.contains("2 people"), "the HSE group does not count its two members")
        assertEquals(3, occurrences(html, "1 person"), "singles are not worded as one person")
    }

    @Test
    fun `an empty department is labelled instead of dropped`() {
        val html = render { teamContent(canManage = false, viewerEmpNo = "EMP0011") }
        // Nobody disappears into a blank cell at the end of the sort.
        assertTrue(departmentLabels(html).contains(UNASSIGNED_DEPARTMENT))
        assertTrue(html.indexOf("Sodnom Gyamtso") > html.indexOf(">$UNASSIGNED_DEPARTMENT<"))
    }

    @Test
    fun `the assign form offers the same departments as option groups`() {
        val html = render { teamContent(canManage = true, viewerEmpNo = "EMP0011") }
        assertEquals(4, occurrences(html, "<optgroup"), "the assign form lost its groups")
        assertTrue(html.contains("label=\"Engineering\""), "no Engineering group in the dropdown")
        // The option text is what the page has always shown; only its grouping changed.
        assertTrue(html.contains("EMP0012 · Munkhtsetseg Bold (Mining Engineer)"))
        // Managers get the assign panel and the quick-add buttons; viewers get neither.
        assertTrue(html.contains("action=\"/app/team/assign\""))
        assertEquals(5, occurrences(html, "class=\"quick-add\""))
        assertFalse(render { teamContent(canManage = false, viewerEmpNo = "EMP0011") }.contains("action=\"/app/team/assign\""))
    }

    @Test
    fun `grouping the team does not change who is on the page`() {
        val html = render { teamContent(canManage = false, viewerEmpNo = "EMP0011") }
        assertEquals(roster.size, occurrences(html, "class=\"avatar sm\""), "a member was lost or doubled")
    }

    @Test
    fun `a viewer opens the task list on their own row only`() {
        val html = render { teamContent(canManage = false, viewerEmpNo = "EMP0011") }
        // The roster itself is the same page for everybody; only the drill-down is theirs.
        roster.forEach { assertTrue(html.contains(it.name), "${it.name} left the roster") }
        assertEquals(1, occurrences(html, "tasks-toggle"), "a task list opened on somebody else's row")
        assertEquals(roster.size - 1, occurrences(html, ">—<"), "the rows they may not open are not blanked")
        // Their own row still carries the hidden list the toggle shows.
        assertEquals(1, occurrences(html, "class=\"member-tasks\""))
        // …and none of the manager's shortcuts come with it.
        assertFalse(html.contains("quick-add"))
        assertFalse(html.contains("assignPanel"))
        assertFalse(html.contains("task-modal.js"), "the panel's board picker came with a panel nobody may see")
    }

    @Test
    fun `management still opens every row`() {
        val html = render { teamContent(canManage = true, viewerEmpNo = "EMP0011") }
        assertEquals(roster.size, occurrences(html, "tasks-toggle"))
        assertEquals(roster.size, occurrences(html, "class=\"member-tasks\""))
        assertEquals(0, occurrences(html, ">—<"))
    }

    /**
     * The fourth place a project's percentage prints is the Team popup, and it is the same measurement
     * the board, the project page and Settings show — the average of what that project's cards' checklists
     * have reached, with the figure filed on the row only where nothing can be measured. Three pages, one
     * number per project, is what this pins.
     */
    @Test
    fun `the popup's project figure comes from that project's checklists`() {
        val bay = ProjectRepository.insert(
            code = "S-09", name = "Haul road widening", status = "Ongoing", progress = 40,
            managerEmpNo = "EMP0011", due = "", type = "Site projects",
        )
        val card = TaskRepository.insert(
            kind = "ASSIGNED", title = "Set out the alignment", projectId = bay, due = "",
            priority = "Medium", assigneeEmpNo = "EMP0011", status = "In progress", bucketId = null, started = "",
        )
        val steps = listOf(
            ChecklistRepository.insert(card, "Walk the line"),
            ChecklistRepository.insert(card, "Mark the crossings"),
            ChecklistRepository.insert(card, "Photograph the start"),
            ChecklistRepository.insert(card, "File the notes"),
        )
        ChecklistRepository.setDone(steps.first(), true)
        val html = render { teamContent(canManage = true, viewerEmpNo = "EMP0011") }
        assertTrue(html.contains("\"progress\":25"), "the popup did not print what the four steps measure")
        assertFalse(html.contains("\"progress\":40"), "the popup still printed the figure filed on the row")
    }

    // --- Stage 50: the panel's two boards, and the column a card with no project is filed in ----

    /**
     * The question the panel now asks first, on the same field name and the same markers as the card popup's:
     * a task with no project is an Other task, so the panel has to be able to *say* one rather than leave a
     * Project box blank — and a blank is what Stage 48 took out of this page on purpose.
     */
    @Test
    fun `the assign panel asks which of the two boards the card belongs to`() {
        val html = render { teamContent(canManage = true, viewerEmpNo = "EMP0011") }
        val form = panel(html)
        assertEquals(1, occurrences(form, "data-board-toggle"), "the panel has two board pickers or none")
        assertEquals(2, occurrences(form, "data-board-half=\"project\""), "the project half lost a field")
        assertEquals(1, occurrences(form, "data-board-half=\"other\""), "the backlog half is not one column picker")
        assertTrue(form.contains(">On a project<") && form.contains(">Other tasks<"), "the picker lost an answer")
        // The panel opens as it always did — on a project — and puts the half nobody asked for away rather than
        // taking it out of the form: a field that is only hidden still posts, which is what lets the route read
        // the one answer instead of two.
        assertTrue(selectedOption(selectRegion(form, "board"), "project"), "the panel does not open on a project")
        assertFalse(halfTag(form, "project").contains("hidden"), "the half it opens on is put away")
        assertTrue(halfTag(form, "other").contains("hidden"), "the other half came up unasked")
        // Which is also why neither of the project half's pickers is `required` any more: a hidden box is still
        // validated by the browser, and this half is hidden whenever the reader is naming a column instead. The
        // rule is the service's — see TaskServiceTest's `a no-project assign…` pair.
        assertFalse(selectTag(form, "project").contains("required"), "the hidden half would block the post")
        assertFalse(selectTag(form, "lane").contains("required"), "the hidden half would block the post")
        assertTrue(selectTag(form, "assignee").contains("required"), "the panel stopped asking who")
        // The script is the popup's own, for this one field, and it travels with the panel that uses it.
        assertTrue(html.contains("src=\"/static/js/task-modal.js\""), "nothing drives the board picker")
    }

    @Test
    fun `the column picker lists the board's own columns and opens on no answer`() {
        val site = BucketRepository.insert("Site works")
        BucketRepository.insert("Idle plant")
        val form = panel(render { teamContent(canManage = true, viewerEmpNo = "EMP0011") })
        val column = selectRegion(form, "column")
        assertTrue(column.contains(">Site works<") && column.contains(">Idle plant<"), "the board lost a column")
        // Management is asked which column, and no column is the default: a card written without one is a card
        // that appears on no column of the only board that prints it.
        assertTrue(selectedOption(column, ""), "the picker opens on a column nobody chose")
        assertFalse(selectedOption(column, "$site"), "a card came up already filed")
        assertFalse(form.contains("no columns yet"), "an empty board was announced on a board that has two")
    }

    @Test
    fun `a board with no columns says so under the picker that has nothing to offer`() {
        // Nothing to choose is the one state this panel cannot write out of, and it has to be said rather than
        // left as an empty box — the service refuses the card, so the page says why before anybody is refused.
        val form = panel(render { teamContent(canManage = true, viewerEmpNo = "EMP0011") })
        assertTrue(form.contains("The Other tasks board has no columns yet"), "the empty picker is unexplained")
    }

    @Test
    fun `a refused assign comes back with the words typed and the reason under its field`() {
        BucketRepository.insert("Site works")
        val html = render {
            teamContent(
                canManage = true, viewerEmpNo = "EMP0011",
                assign = AssignForm(
                    values = TaskDraft(
                        title = "Rebuild the weighbridge", projectName = "", assigneeEmpNo = "EMP0012",
                        started = "", due = "2026-11-20", priority = "High", status = "Not started",
                        projectLane = null, columnId = null, board = BOARD_OTHER,
                    ),
                    errors = mapOf(TaskField.COLUMN to "Choose which column this card sits in."),
                ),
            )
        }
        val form = panel(html)
        assertTrue(form.contains("value=\"Rebuild the weighbridge\""), "the typed task was dropped")
        assertTrue(form.contains("value=\"2026-11-20\""), "the typed due date was dropped")
        assertTrue(selectedOption(selectRegion(form, "assignee"), "EMP0012"), "the person chosen was forgotten")
        assertTrue(selectedOption(selectRegion(form, "priority"), "High"), "the priority chosen was forgotten")
        assertTrue(selectedOption(selectRegion(form, "board"), "other"), "the panel came back on the wrong half")
        // The half the refusal belongs to is the one the reader is shown, and the other is put away: a reason
        // printed under a field that is hidden is a message nobody can read.
        assertFalse(halfTag(form, "other").contains("hidden"), "the refusal printed under a field put away")
        assertTrue(halfTag(form, "project").contains("hidden"), "the project half stayed up with its answer refused")
        assertTrue(
            form.contains("<p class=\"field-err\">Choose which column this card sits in.</p>"),
            "the refusal is nowhere on the page",
        )
    }
}
