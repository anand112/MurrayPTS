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
                "<tr class=\"dept-row\">\\s*<td colspan=\"13\">\\s*<div class=\"dept-cell\">"
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
    fun `roster departments use the shared badge and a blank stays empty`() {
        val html = render { teamContent(canManage = false, viewerEmpNo = "EMP0011") }
        roster.filter { it.department.isNotBlank() }.forEach { member ->
            assertTrue(
                figuresRow(html, member.employeeNumber)
                    .contains("<span class=\"chip dept-chip\">${member.department}</span>"),
                "${member.name}'s department lost the shared badge",
            )
        }
        val unassigned = figuresRow(html, "EMP0014")
        assertTrue(unassigned.contains("<td></td>"), "a blank department stopped reading as an empty cell")
        assertFalse(unassigned.contains("chip dept-chip"), "a blank department was given an empty badge")
        assertEquals(4, occurrences(html, "class=\"chip dept-chip\""), "the roster gained or lost a department badge")
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
        // The roster and its figures are the same page for everybody (Stage 68); only the card-by-card list
        // underneath a figure is theirs, so exactly one row carries a control that opens one.
        roster.forEach { assertTrue(html.contains(it.name), "${it.name} left the roster") }
        assertEquals(1, occurrences(html, "tasks-toggle"), "a task list opened on somebody else's row")
        assertEquals(1, occurrences(html, "class=\"member-tasks\""))
        // Every other row still prints its own open-card figure — as a plain number, because there is nothing
        // under it for this reader to open, and a button that opens nothing reads as a broken page.
        assertEquals(roster.size - 1, occurrences(html, "tasks-static"), "a shared figure lost its number")
        assertEquals(0, occurrences(html, ">—<"), "a workload figure is still being withheld")
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
        // Management has a control on every row, so this page prints no plain figure at all.
        assertEquals(0, occurrences(html, "tasks-static"))
    }

    // --- Stage 67: the row's figures count that person's cards, from both boards -----------------

    /**
     * The six card figures a member's row prints, in the order the columns are headed: **In progress**,
     * **On hold**, **Not started**, **Blocked**, **Completed**, **Total**. They are read off the row rather than
     * off the page because the claim worth pinning is about one person's workload — and a number counted into the
     * wrong row is exactly the mistake this page has to keep making impossible.
     *
     * The **Projects** column is deliberately not in this list: it is a set of projects rather than a count of
     * cards, so it does not sum into the total next to it and it has its own reader, [projectCount].
     */
    private fun figures(html: String, emp: String): List<Int> {
        val start = html.indexOf("<tr data-emp=\"$emp\">")
        assertTrue(start >= 0, "the sheet printed no row for $emp")
        val end = html.indexOf("</tr>", start)
        val row = html.substring(start, if (end < 0) html.length else end)
        return Regex("<span class=\"count[^\"]*\">(\\d+)</span>").findAll(row).map { it.groupValues[1].toInt() }.toList()
    }

    /** A card on a project, filed with that project as its own. */
    private fun projectCard(emp: String, title: String, status: String, projectId: Int) =
        TaskRepository.insert(
            kind = "ASSIGNED", title = title, projectId = projectId, due = "",
            priority = "Medium", assigneeEmpNo = emp, status = status, bucketId = null, started = "",
        )

    /** A card on *Other tasks*, which belongs to no project and used to reach nobody's figures. */
    private fun backlogCard(emp: String, title: String, status: String, bucketId: Int) =
        TaskRepository.insert(
            kind = "OTHER", title = title, projectId = null, due = "",
            priority = "Medium", assigneeEmpNo = emp, status = status, bucketId = bucketId, started = "",
        )

    @Test
    fun `a member's figures come from their cards on both boards`() {
        val bay = ProjectRepository.insert(
            code = "S-11", name = "Stockpile drainage", status = "In progress", progress = 0,
            managerEmpNo = "EMP0010", due = "", type = "Site projects",
        )
        // Two cards filed on that project and three that sit outside every project on Other tasks. The row has
        // to add up all five: a card with no project is still work that is on the person.
        projectCard("EMP0011", "Set the outfall levels", "In progress", bay)
        projectCard("EMP0011", "Lay the culvert", "Completed", bay)
        val column = BucketRepository.insert("Idle plant")
        backlogCard("EMP0011", "Service the loader", "In progress", column)
        backlogCard("EMP0011", "Quote the conveyor belt", "Not started", column)
        backlogCard("EMP0011", "Chase the hydraulic hoses", "Blocked", column)
        val html = render { teamContent(canManage = true, viewerEmpNo = "EMP0010") }
        assertEquals(
            listOf(2, 0, 1, 1, 1, 5), figures(html, "EMP0011"),
            "the row counted one board and not the other",
        )
        // The colleague who manages the project but holds none of its cards has nothing to show — the figure is
        // the person's own work, not the projects their name is on.
        assertEquals(listOf(0, 0, 0, 0, 0, 0), figures(html, "EMP0010"), "a managed project was counted as work")
    }

    @Test
    fun `the five status figures add up to the row's total`() {
        val bay = ProjectRepository.insert(
            code = "S-12", name = "Weighbridge repair", status = "In progress", progress = 0,
            managerEmpNo = "EMP0010", due = "", type = "Site projects",
        )
        val column = BucketRepository.insert("Yard work")
        projectCard("EMP0013", "Strip the platform", "On hold", bay)
        backlogCard("EMP0013", "Re-pad the load cells", "In progress", column)
        backlogCard("EMP0013", "Test the readout", "Completed", column)
        val html = render { teamContent(canManage = true, viewerEmpNo = "EMP0010") }
        val numbers = figures(html, "EMP0013")
        assertEquals(6, numbers.size, "the row lost or gained a counted column")
        assertEquals(numbers.last(), numbers.take(5).sum(), "the columns do not account for every card in the total")
    }

    @Test
    fun `the columns are headed by the five states a card can actually hold`() {
        // The figures changed source in Stage 67, so the headings had to change with them: a card is `In progress`,
        // never the project word `Ongoing` that **Stage 96** retired, and a column headed by a word no card can
        // carry would silently count nothing forever.
        // Stage 68 put **Projects** in front of them as the one column that is not a card count.
        val html = render { teamContent(canManage = true, viewerEmpNo = "EMP0010") }
        val headings = Regex("<th class=\"num\">([^<]*)</th>").findAll(html).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf("Projects", "Tasks", "In progress", "On hold", "Not started", "Blocked", "Completed", "Total"),
            headings, "a status column names a state no task has",
        )
    }

    @Test
    fun `a viewer is given a colleague's figures and not their cards`() {
        val column = BucketRepository.insert("Idle plant")
        // One card each: the reader's own, and a colleague's. Stage 68 lets any reader read both rows' figures,
        // while the card behind either of them stays its holder's.
        backlogCard("EMP0010", "Chase the hydraulic hoses", "Blocked", column)
        backlogCard("EMP0011", "Service the loader", "In progress", column)
        val html = render { teamContent(canManage = false, viewerEmpNo = "EMP0010") }
        // The colleague's row carries its five status figures and its total, and no dash anywhere: how much is on
        // a person is the thing a colleague needs to know before they hand something over.
        assertEquals(listOf(1, 0, 0, 0, 0, 1), figures(html, "EMP0011"), "a colleague's workload is still hidden")
        assertEquals(0, occurrences(figuresRow(html, "EMP0011"), ">—<"), "a shared figure is still printed as a dash")
        // Their own row keeps its figures, and its backlog card is among them.
        assertEquals(listOf(0, 0, 0, 1, 0, 1), figures(html, "EMP0010"))
        // What stayed gated is the *list*: the colleague's Tasks figure is a plain number with nothing under it,
        // and a dead button would be read as the page being broken rather than the cards being somebody else's.
        assertFalse(figuresRow(html, "EMP0011").contains("tasks-toggle"), "a colleague's cards became openable")
        assertTrue(figuresRow(html, "EMP0011").contains("tasks-static"), "the shared figure kept a button to nowhere")
        assertEquals(1, occurrences(html, "class=\"member-tasks\""))
        // The popup follows the same split: the colleague's projects are named, their task titles are not.
        assertEquals(0, occurrences(popupSlice(html, "EMP0011"), "\"title\":\""), "a colleague's cards leaked into the popup")
        assertEquals(1, occurrences(popupSlice(html, "EMP0010"), "\"title\":\""), "the reader lost their own task list")
    }

    // --- Stage 68: a Projects figure on every row, for every reader -------------------------------

    /**
     * The row's **Projects** figure, read apart from [figures] on purpose: it is a count of *projects*, not one
     * of the six card figures, and the two are meant to be able to disagree — a member with three cards in no
     * project at all is a normal row, not a fault in either column.
     */
    private fun projectCount(html: String, emp: String): Int {
        val n = Regex("<span class=\"proj-count\">(\\d+)</span>").find(figuresRow(html, emp))?.groupValues?.get(1)
        return n?.toInt() ?: error("the row for $emp printed no Projects figure")
    }

    /** One member's entry in the popup's JSON island, from their own key to the next member's. */
    private fun popupSlice(html: String, emp: String): String {
        val start = html.indexOf("\"$emp\":{")
        assertTrue(start >= 0, "the popup carries no entry for $emp")
        val next = html.indexOf(",\"EMP", start + 1)
        val end = if (next < 0) html.indexOf("}</script>", start) else next
        return html.substring(start, if (end < 0) html.length else end)
    }

    /** The project codes the popup lists for [emp] — the set the row's figure above it counts. */
    private fun popupProjectCodes(html: String, emp: String): List<String> =
        Regex("\"code\":\"([^\"]+)\"").findAll(popupSlice(html, emp)).map { it.groupValues[1] }.toList()

    @Test
    fun `the Projects figure counts the projects a member is on, whichever way they are on them`() {
        val drainage = ProjectRepository.insert(
            code = "S-21", name = "Stockpile drainage", status = "In progress", progress = 0,
            managerEmpNo = "EMP0010", due = "", type = "Site projects",
        )
        val weigh = ProjectRepository.insert(
            code = "S-22", name = "Weighbridge repair", status = "In progress", progress = 0,
            managerEmpNo = "EMP0013", due = "", type = "Site projects",
        )
        val column = BucketRepository.insert("Idle plant")
        // Two cards, on two projects this member does not manage: the engineer is on both of them.
        projectCard("EMP0011", "Set the outfall levels", "In progress", drainage)
        projectCard("EMP0011", "Strip the platform", "Not started", weigh)
        // The manager of a project nobody has written a card for yet is carrying it all the same.
        // A member whose work sits only outside every project is on none.
        backlogCard("EMP0012", "Service the loader", "In progress", column)
        backlogCard("EMP0012", "Quote the conveyor belt", "Blocked", column)
        // And a project the member both manages and holds a card on is one project, not two.
        projectCard("EMP0010", "Survey the toe drain", "In progress", drainage)
        val html = render { teamContent(canManage = true, viewerEmpNo = "EMP0010") }
        assertEquals(2, projectCount(html, "EMP0011"), "the figure counted cards instead of projects")
        assertEquals(1, projectCount(html, "EMP0013"), "a managed project with no cards on it went uncounted")
        assertEquals(0, projectCount(html, "EMP0012"), "a backlog card was counted as a project")
        assertEquals(1, projectCount(html, "EMP0010"), "a project managed and worked was counted twice")
        // The two columns are read together and still differ, which is the reason for having both: two cards,
        // and no project anywhere near them.
        assertEquals(listOf(1, 0, 0, 1, 0, 2), figures(html, "EMP0012"))
    }

    @Test
    fun `the Projects figure is the popup's project list, even for a reader who may not see the cards`() {
        val widening = ProjectRepository.insert(
            code = "S-23", name = "Haul road widening", status = "In progress", progress = 0,
            managerEmpNo = "EMP0013", due = "", type = "Site projects",
        )
        val skid = ProjectRepository.insert(
            code = "S-24", name = "Water treatment skid", status = "In progress", progress = 0,
            managerEmpNo = "EMP0010", due = "", type = "Site projects",
        )
        projectCard("EMP0013", "Set out the alignment", "In progress", widening)
        projectCard("EMP0013", "Pipe the skid", "Not started", skid)
        // Read by somebody who is neither of them and manages nothing: the figures are shared, the cards are not.
        val html = render { teamContent(canManage = false, viewerEmpNo = "EMP0011") }
        assertEquals(2, projectCount(html, "EMP0013"), "the row's figure moved with the reader's rights")
        // The popup names the same set, manager first and then the project reached through a card — which is the
        // agreement Stage 68 had to reach: a number on the page that its own popup contradicts reads as a bug.
        assertEquals(listOf("S-23", "S-24"), popupProjectCodes(html, "EMP0013"), "row and popup counted different sets")
        // Management, looking at the same person, is given the same two — the list never changed with the reader,
        // so the figure that matches it cannot have been counting a third thing.
        assertEquals(
            listOf("S-23", "S-24"),
            popupProjectCodes(render { teamContent(canManage = true, viewerEmpNo = "EMP0013") }, "EMP0013"),
            "the popup's project list moved with the reader",
        )
    }

    /** The same row [figures] reads, as raw markup, so a test can count what one cell of one row prints. */
    private fun figuresRow(html: String, emp: String): String {
        val start = html.indexOf("<tr data-emp=\"$emp\">")
        val end = html.indexOf("</tr>", start)
        return html.substring(start, if (end < 0) html.length else end)
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
            code = "S-09", name = "Haul road widening", status = "In progress", progress = 40,
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

    /**
     * Stage 69's half of the page: the hook the re-count swaps, and what it must leave alone.
     *
     * A card dragged on a board writes its status and reloads *that* board; the sheet the reader came from keeps
     * printing the counts from before the move until something asks again. That something is `team.js`, and it
     * replaces one panel — so the two things worth pinning are that every figure on the page stands inside that
     * panel, and that the assign form does not, because a reader mid-sentence in a task title must not have the
     * words lifted out from under them by a refresh they did not ask for.
     */
    @Test
    fun `the sheet's figures stand in the one panel the script re-counts, and the assign form does not`() {
        val html = render { teamContent(canManage = true, viewerEmpNo = "EMP0011") }
        assertEquals(1, occurrences(html, "id=\"teamSheet\""), "the re-count has two panels to choose from")
        // Every figure is inside it: the table comes after the panel opens, and the rows' numbers with it.
        assertTrue(
            html.indexOf("<table") > html.indexOf("id=\"teamSheet\""),
            "the sheet is outside the panel the script swaps",
        )
        assertTrue(html.contains("count-total"), "the test page lost the figures it is here to place")
        // The panel closes before the assign form opens, and this reads it off the markup rather than off an
        // index: the slice starts inside the panel's own opening tag and stops at the form's, so every `<div` in
        // it is matched by a `</div>` and exactly one is left over — the panel's own. Any nested block left
        // unclosed, or the form still standing inside the panel, and the arithmetic is off by one.
        val start = html.indexOf("id=\"teamSheet\"")
        val formTag = html.lastIndexOf("<div", html.indexOf("id=\"assignPanel\"", start))
        assertTrue(formTag > start, "the page has no assign form to keep out of the swap")
        val slice = html.substring(start, formTag)
        assertEquals(
            1,
            occurrences(slice, "</div>") - occurrences(slice, "<div"),
            "the panel does not close before the form, so a re-count would clear what the reader is typing",
        )
        // And the script that does it is on the page.
        assertTrue(html.contains("src=\"/static/js/team.js\""), "nothing re-counts the sheet")
    }

    /**
     * The behaviour the hook is for, read off the file the browser runs — the same way the board's stylesheet is
     * pinned. What matters here is not that a request is made, but that the page never *restarts* to make it.
     */
    @Test
    fun `the sheet asks the server again when it comes back on screen, and never reloads itself`() {
        val js = File("src/main/resources/static/js/team.js").readText()
        assertTrue(js.contains("teamSheet"), "the script does not know which panel to replace")
        assertTrue(js.contains("getElementById(SHEET)"), "the script repaints by name rather than by node")
        // The two ways a stale sheet reaches the reader: the copy the Back button keeps, and the tab they return
        // to. Both are listened for, and the quiet period is what stops a flicked-past tab spending a request.
        assertTrue(js.contains("pageshow") && js.contains("persisted"), "a page from the back cache is never re-counted")
        assertTrue(js.contains("visibilitychange"), "a tab come back to is never re-counted")
        assertTrue(js.contains("cache: 'no-store'"), "a cached copy would re-paint the very numbers being replaced")
        // The reader's own state rides through the swap: which rows they had opened, and the popup they were in.
        assertTrue(js.contains("openDrills") && js.contains("reopen("), "the swap closes what the reader opened")
        assertTrue(js.contains("bind();"), "the swapped rows arrive with no listeners on them")
        // What this stage is *not* allowed to do: the answer to a stale number must not be a page that restarts.
        assertFalse(js.contains("location.reload"), "the sheet reloads instead of updating in place")
    }

    /** Dismissal is delegated and registered once, so replacing the popup's HTML cannot orphan its closer. */
    @Test
    fun `the member popup closes from its button outside click and Escape without stacking listeners`() {
        val js = File("src/main/resources/static/js/team.js").readText().replace("\r\n", "\n")
        val finalBind = js.lastIndexOf("bind();")
        assertTrue(finalBind >= 0, "the Team rows are never bound")
        val loadTime = js.substring(finalBind + "bind();".length)
        assertEquals(1, occurrences(loadTime, "pop.addEventListener('click'"), "the delegated closer is not registered once")
        assertEquals(1, occurrences(loadTime, "document.addEventListener('click'"), "outside click is not registered once")
        assertEquals(1, occurrences(loadTime, "document.addEventListener('keydown'"), "Escape is not registered once")
        assertTrue(loadTime.contains("e.target.closest('.mpop-x')"), "the closer is bound to a node a re-render replaces")
        assertTrue(loadTime.contains("e.stopPropagation();\n    close();"), "the closer also reads as an outside click")
        assertTrue(loadTime.contains("if (pop.hidden || pop.contains(e.target)) return;"), "inside clicks do not leave the popup alone")
        assertTrue(
            loadTime.contains(".team-table tbody tr[data-emp], .tasks-toggle, .quick-add"),
            "outside click would fight the row switch, task toggle or quick-add shortcut",
        )
        assertTrue(loadTime.contains("if (e.key === 'Escape' && !pop.hidden) close();"), "Escape does not close an open popup")
        assertTrue(js.contains("function close() { pop.hidden = true; current = null; }"), "a closed popup can reopen on re-count")
    }
}
