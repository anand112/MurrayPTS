package mn.murray.pts

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mn.murray.pts.db.Migrations
import mn.murray.pts.domain.Member
import mn.murray.pts.domain.PROJECT_TYPES
import mn.murray.pts.domain.Project
import mn.murray.pts.domain.TASK_STATUSES
import mn.murray.pts.repository.ChecklistRepository
import mn.murray.pts.repository.ProjectBucketRepository
import mn.murray.pts.repository.ProjectRepository
import mn.murray.pts.repository.TaskRepository
import mn.murray.pts.repository.TeamRepository
import mn.murray.pts.service.ProjectDraft
import mn.murray.pts.service.ProjectField
import mn.murray.pts.service.ProjectService
import mn.murray.pts.service.TaskService
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.html.DIV
import kotlinx.html.div
import kotlinx.html.stream.createHTML

/**
 * Which parts of the Projects pages a Viewer is shown.
 *
 * `POST /app/projects` and `POST /app/projects/{id}/manager` check the role at the route, and a
 * route test would need a live server, so what this suite pins is the other half of the same
 * promise: the markup a non-manager is served simply cannot post to those endpoints. The create
 * popup and the set-manager form are the only writers of those two actions, so rendering them
 * away is what makes "management only" visible before it is enforced.
 */
class ProjectsPageRenderTest {

    private lateinit var ds: HikariDataSource
    private val manager = UserSession("EMP0001")
    private val viewer = UserSession("EMP0002")
    private lateinit var project: Project

    @BeforeEach
    fun setUp(@TempDir dir: File) {
        ds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${File(dir, "projects.db").absolutePath}"
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            }
        )
        Migrations.migrate(ds)
        Database.connect(ds)
        TeamRepository.upsert(Member("EMP0001", "Munkhtselseg", "Operations", "Project Manager"))
        TeamRepository.upsert(Member("EMP0002", "Bat-Erdene", "Workshop", "Engineer"))
        TeamRepository.upsert(Member("EMP0003", "Naran", "Workshop", "Surveyor"))
        val id = ProjectRepository.insert(
            code = "S-01", name = "Haul road widening", status = "Ongoing", progress = 40,
            managerEmpNo = "EMP0001", due = "2027-03-30", type = "Site projects",
        )
        project = ProjectRepository.findById(id)!!
        // The board this page is cut by (V7). Every project the application makes has these six lanes —
        // `ProjectService.create` seeds them, and the V7 and V10 migrations did the same for the projects that
        // were already on the table — so a fixture that inserts a project by hand has to give it them too.
        ProjectBucketRepository.seedDefaults(id)
    }

    @AfterEach
    fun tearDown() = ds.close()

    private fun render(block: DIV.() -> Unit): String = createHTML().div(block = block)

    private fun occurrences(html: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(html).count()

    @Test
    fun `the create popup asks nothing about the project's lanes`() {
        val html = render { projectsContent(manager, null, canManage = true) }
        // Stage 32 put three lane inputs in this form; the user asked for them out again on 2026-10-15. What a
        // new project gets is its six standard sub-categories already there when its page opens (Stage 48), and
        // naming lanes belongs to the project page — so this popup must stay free of the subject, for a manager
        // too.
        listOf("lane1", "lane2", "lane3").forEach { name ->
            assertFalse(html.contains("name=\"$name\""), "the create form still asks for $name")
        }
        assertFalse(html.contains("Task lane"), "the create form labels a lane field")
        assertFalse(html.contains("Leave one blank"), "the create form still explains lane defaults")
        // A rejected create re-renders the same popup from what was typed, so the fields it re-prints are the
        // fields the form has. It must come back exactly as spare of lanes as it went in.
        val rejected = ProjectService.create(
            ProjectDraft(type = "Site projects", code = "S-01", name = "", managerName = "Naran"),
            actorEmpNo = "EMP0001",
        )
        val roundTrip = render {
            projectsContent(
                manager,
                null,
                ProjectForm(rejected.values, rejected.errors, ProjectService.suggestedCodes()),
                canManage = true,
            )
        }
        assertFalse(roundTrip.contains("name=\"lane"), "a rejected create re-asked about lanes")
        // The nine fields the form does have are all present and keep their values.
        listOf("type", "code", "name", "client", "start", "end", "status", "manager", "revenue").forEach { name ->
            assertTrue(roundTrip.contains("name=\"$name\""), "the popup lost its $name field")
        }
    }

    @Test
    fun `a viewer's board keeps the projects but loses the popup`() {
        val html = render { projectsContent(viewer, null, canManage = false) }
        // The board itself is shared: a Viewer still reads every project.
        assertTrue(html.contains("Haul road widening"), "the board lost its projects")
        assertTrue(html.contains("class=\"ftab"), "the filter tabs disappeared")
        // What is gone is the create form and the only button that can open it. The marker is the
        // popup's own hidden `board_q`, not the form's action: the search bar posts to the very same
        // URL as a GET form, so `action="/app/projects"` is on the page for every role now.
        assertFalse(html.contains("name=\"board_q\""), "a Viewer was served the create form")
        assertFalse(html.contains("projectModal"), "a Viewer was served the create popup")
        assertFalse(html.contains("data-project-modal-open"), "a Viewer was served the opener")
    }

    @Test
    fun `a manager's board carries the popup with the roster grouped by department`() {
        val html = render { projectsContent(manager, null, canManage = true) }
        assertTrue(html.contains("id=\"projectModal\""))
        assertTrue(html.contains("action=\"/app/projects\""))
        assertTrue(html.contains("Create project"))
        // Every category option brings its own next free number, so the suggestion cannot be lost.
        PROJECT_TYPES.forEach { type -> assertTrue(html.contains("value=\"$type\""), "no option for $type") }
        assertEquals(PROJECT_TYPES.size, occurrences(html, "data-suggest=\""))
        // The manager picker follows the Team page's shape rather than a flat list.
        assertTrue(html.contains("label=\"Workshop\""), "the roster is not grouped by department")
        assertEquals(2, occurrences(html, "<optgroup"), "expected Operations and Workshop groups")
    }

    @Test
    fun `a rejected create comes back with the popup open and its message in place`() {
        val rejected = ProjectService.create(
            ProjectDraft(type = "Site projects", code = "S-01", name = "", managerName = "Naran"),
            actorEmpNo = "EMP0001",
        )
        assertTrue(rejected.errors.isNotEmpty(), "duplicate number with a blank name should be refused")
        // This is the state the POST route re-renders under, and it re-renders it as a manager —
        // a Viewer never reaches it, because the route turns them away first.
        val html = render {
            projectsContent(
                manager,
                null,
                ProjectForm(rejected.values, rejected.errors, ProjectService.suggestedCodes()),
                canManage = true,
            )
        }
        assertTrue(html.contains("style=\"display:block\""), "the popup came back closed")
        assertTrue(html.contains("field-err"), "no field-level message was printed")
        // The number already taken comes back exactly as submitted, not replaced by a suggestion.
        assertTrue(html.contains("value=\"S-01\""), "the typed number was overwritten")
        // And so does the manager that was picked — createHTML indents between tags but keeps a
        // tag's attributes on one line, so "selected" is looked for inside the same <option>.
        assertTrue(
            Regex("value=\"Naran\"[^>]*selected").containsMatchIn(html),
            "the chosen manager came back unselected",
        )
        // The existing project is still listed behind the open popup.
        assertTrue(html.contains("Haul road widening"))
        assertEquals(1, occurrences(html, "projectModal"))
    }

    @Test
    fun `every category panel shares one ten-column grid`() {
        // Two categories, because the promise is about panels lining up with each other, not about
        // any one panel. Codes follow their category letter (R- for Repair/Maintenance). The second
        // project carries a start date and the first does not, so both states of the new column are
        // on the page at once.
        ProjectRepository.insert(
            code = "R-01", name = "Excavator overhaul", status = "Ongoing", progress = 10,
            managerEmpNo = "EMP0002", due = "2027-01-15", type = "Repair/Maintenance/other services",
            start = "2026-09-01",
        )
        val html = render { projectsContent(manager, null, canManage = true) }
        assertEquals(2, occurrences(html, "class=\"grid projects-table\""), "expected two tables on one grid")
        assertEquals(2, occurrences(html, "<colgroup>"))
        val grid = listOf(
            "c-code", "c-name", "c-client", "c-status", "c-progress", "c-manager",
            "c-start", "c-due", "c-rev", "c-pin",
        )
        val cols = Regex("<col class=\"(c-[a-z]+)\"").findAll(html).map { it.groupValues[1] }.toList()
        // table-layout: fixed takes its widths from these, so the same ten in the same order in every
        // table is what puts "Project" at one x on the whole board.
        assertEquals(grid + grid, cols, "the two panels do not share one column order")
        // Tag-specific needles, because `class="c-name"` alone also matches the <col> and the <th>, and
        // a count that mixes the three proves nothing about which header sits over which cell.
        grid.forEach { c ->
            assertEquals(2, occurrences(html, "<th class=\"$c\""), "$c: one header in each of the two panels")
            assertEquals(2, occurrences(html, "<td class=\"$c\""), "$c: one project cell in each of the two panels")
        }
        // Both dates say "date": a bare "End" over a column that is a project's finish, not a
        // deadline, is the ambiguity the create form and the project page already avoid.
        assertEquals(2, occurrences(html, "<th class=\"c-start\">Start date</th>"), "a panel lost its Start header")
        assertEquals(2, occurrences(html, "<th class=\"c-due\">End date</th>"), "a panel still says just End")
        // The recorded kick-off prints, and a project with none prints a dash rather than a blank cell
        // that reads like a value the board failed to show.
        assertEquals(1, occurrences(html, "<td class=\"c-start\">2026-09-01</td>"), "the start date was not printed")
        assertEquals(1, occurrences(html, "<td class=\"c-start\">—</td>"), "a blank start is not a dash")
        // Only the description column has no ceiling, so it is the one that may break a long word.
        assertTrue(html.contains("Haul road widening"))
        assertTrue(html.contains("Excavator overhaul"))
    }

    @Test
    fun `a viewer's project page cannot set the manager`() {
        val html = render { projectDetailContent(viewer, project, canManage = false) }
        assertFalse(html.contains("/manager\""), "a Viewer was served the set-manager form")
        assertFalse(html.contains("Set manager"), "a Viewer was served the set-manager button")
        // Two things a Viewer keeps, because they are theirs rather than management's:
        assertTrue(html.contains("/pin\""), "the personal pin form vanished")
        assertTrue(html.contains("Haul road widening"), "the project detail disappeared")
        // and the board below it is read-only for someone who neither manages nor has work here: no card
        // is a click target, and the page carries no editor opener at all.
        assertFalse(html.contains("taskEditOpener"), "a Viewer was given the editor opener")
        assertFalse(html.contains("data-task-card"), "a card was made clickable for a Viewer of nobody's work")
    }

    @Test
    fun `management edits the manager in the header strip, not a separate panel`() {
        val html = render { projectDetailContent(manager, project, canManage = true) }
        // Stage 60: the manager is now one field of the project's editable header strip, which posts to the
        // project's own update route. The *separate* set-manager panel that used to sit under the board stayed
        // gone — one control over the field, on the strip. `/app/projects/{id}/manager` still exists for the
        // route's own sake; nothing here posts to it.
        assertFalse(html.contains("Set manager"), "the set-manager button came back")
        assertFalse(html.contains("/manager\""), "the set-manager form came back")
        assertFalse(html.contains("manager-set"), "the panel's own markup came back")
        // The strip carries a manager picker and posts to the update route, with the named holder selected.
        assertTrue(html.contains("name=\"manager\""), "the header strip lost its manager picker")
        assertTrue(
            html.contains("action=\"/app/projects/${project.id}\""),
            "the header strip does not post to the project update route",
        )
        assertTrue(html.contains("Munkhtselseg"), "the manager the project names is not printed")
    }

    @Test
    fun `management's header is an editable strip and a whole-project delete`() {
        val html = render { projectDetailContent(manager, project, canManage = true) }
        // The fields the screenshot puts in reach of management, as inputs on one form.
        listOf("name", "client", "type", "start", "end", "status", "manager", "revenue", "invoiced").forEach { n ->
            assertTrue(html.contains("name=\"$n\""), "the editable strip is missing its $n field")
        }
        // Progress is NOT an input: it is derived from the board and prints as a read-only percentage cell.
        assertFalse(html.contains("name=\"progress\""), "progress became a box to type into")
        assertTrue(html.contains("id=\"project-progress\""), "the live-swap progress id went with the box")
        // The screenshot shows management's progress as a bare figure, not a bar: the read-only cell prints the
        // percentage and nothing draws a `.bar` under it. (The Viewer's card keeps its bar; this is the strip.)
        assertFalse(html.contains("class=\"bar\""), "management's progress cell grew a bar back")
        // The two whole-project actions in the head: a personal pin, the copy-link icon, and a delete behind a
        // confirm — in that left-to-right order, matching the screenshot.
        assertTrue(html.contains("action=\"/app/projects/${project.id}/pin\""), "the pin left the header")
        assertTrue(html.contains("Pin for me"), "the pin is not the personal control the screenshot shows")
        assertTrue(html.contains("data-copy-link"), "the copy-link icon button between the pin and the delete is gone")
        assertTrue(html.contains("action=\"/app/projects/${project.id}/delete\""), "there is no delete route")
        assertTrue(html.contains("Delete project"), "the delete button is missing")
        assertTrue(html.contains("data-confirm="), "the delete is not behind a confirmation")
        // The breadcrumb says which project this is.
        assertTrue(html.contains("class=\"crumb-code\"") && html.contains("S-01"), "no breadcrumb for the code")
    }

    @Test
    fun `management's money fields read as comma-grouped text, not bare numbers`() {
        // The screenshot prints REVENUE as "5,000,000" and INVOICED as "0". A numeric <input> cannot display a
        // grouped value, so the strip renders these as text fields carrying the grouped amount — and a save is
        // safe because the service's revenue parsing strips the commas back off. Zero shows as "0", not blank.
        val id = ProjectRepository.insert(
            code = "S-09", name = "Concentrator liner", status = "Ongoing", progress = 0,
            managerEmpNo = "EMP0001", due = "2027-03-30", type = "Site projects",
            revenue = 5_000_000.0, invoiced = 0.0,
        )
        val grouped = ProjectRepository.findById(id)!!
        val html = render { projectDetailContent(manager, grouped, canManage = true) }
        assertTrue(html.contains("value=\"5,000,000\""), "revenue is not the comma-grouped figure the picture shows")
        assertTrue(html.contains("name=\"revenue\"") && html.contains("name=\"invoiced\""), "a money field left the strip")
        // The zero amount prints as "0" (the field the picture shows), which the grouped form of 0.0 guarantees.
        assertTrue(html.contains("value=\"0\""), "an un-invoiced project shows a blank box, not the 0 the picture has")
    }

    @Test
    fun `a viewer's project page stays a read-only card with no strip or delete`() {
        val html = render { projectDetailContent(viewer, project, canManage = false) }
        // None of management's writes are offered to a Viewer: no update form, no delete, no editable inputs.
        assertFalse(html.contains("action=\"/app/projects/${project.id}\""), "a Viewer was served the edit form")
        assertFalse(html.contains("action=\"/app/projects/${project.id}/delete\""), "a Viewer was served the delete")
        assertFalse(html.contains("Save changes"), "a Viewer was served the save button")
        assertFalse(html.contains("class=\"edit-strip\""), "a Viewer was served the editable strip")
        // What they keep is the fact: the read-only nine-field card and their own pin.
        assertTrue(html.contains("<span class=\"detail-key\">Manager</span>"), "the read-only Manager field is gone")
        assertTrue(html.contains("action=\"/app/projects/${project.id}/pin\""), "the Viewer's own pin vanished")
    }


    /**
     * A second and third Site project for the search/sort tests. Deliberately inserted by each test
     * rather than in `setUp`, because the panel-grid test counts one row per panel and the popup test
     * counts optgroups — extra projects there would be testing the fixture, not the board.
     */
    private fun siteProject(code: String, name: String, status: String, managerEmpNo: String?, due: String) {
        ProjectRepository.insert(
            code = code, name = name, status = status, progress = 5,
            managerEmpNo = managerEmpNo, due = due, type = "Site projects", client = "Oyu Tolgoi LLC",
        )
    }

    @Test
    fun `the search bar narrows the panel it is on and keeps its own value`() {
        siteProject("S-02", "Weir repair", "Not started", "EMP0003", "2028-01-01")
        ProjectRepository.insert(
            code = "R-01", name = "Excavator overhaul", status = "Ongoing", progress = 10,
            managerEmpNo = "EMP0002", due = "2027-01-15", type = "Repair/Maintenance/other services",
        )
        val html = render {
            projectsContent(manager, null, canManage = true, filter = ProjectBoardFilter(text = "widening"))
        }
        assertTrue(html.contains("Haul road widening"), "the match was filtered away")
        assertFalse(html.contains("Weir repair"), "the search did not narrow the Site panel")
        // "haul" would be a poor word to search with here: it is inside "overhaul", and a substring
        // match is exactly what the bar promises. "widening" belongs to one project on this board.
        assertFalse(html.contains("Excavator overhaul"), "a non-matching panel was still printed")
        // The bar shows what was searched, so the page explains itself after a reload.
        assertTrue(html.contains("value=\"widening\""), "the search box came back empty")
        assertTrue(html.contains("data-livefilter"), "the box is not wired to the live filter")
        // And the tab links carry it, so moving to another category keeps the search instead of losing it.
        assertTrue(
            html.contains("href=\"/app/projects?type=Site+projects&amp;q=widening\""),
            "the Site tab drops the search",
        )
        // With one match in the whole board, the pills read one, not the catalogue's size.
        assertTrue(Regex("<span class=\"pill\">\\s*1<").containsMatchIn(html), "the pills still count everything")
        assertFalse(Regex("<span class=\"pill\">\\s*2<").containsMatchIn(html), "a pill counted a filtered-out project")
    }

    @Test
    fun `every word of a search has to match and a miss says so`() {
        siteProject("S-02", "Weir repair", "Not started", "EMP0003", "2028-01-01")
        val both = render {
            projectsContent(manager, null, canManage = true, filter = ProjectBoardFilter(text = "haul road"))
        }
        assertTrue(both.contains("Haul road widening"), "\"haul road\" should match one project")
        assertFalse(both.contains("Weir repair"))
        // Two words are ANDed, so a second word nobody has leaves the board empty — and the empty board
        // has to say "nothing matches", not "no projects of this type yet", which would be untrue.
        val none = render {
            projectsContent(manager, null, canManage = true, filter = ProjectBoardFilter(text = "haul missing"))
        }
        assertFalse(none.contains("Haul road widening"), "\"haul missing\" should match nothing")
        assertTrue(none.contains("Nothing here matches"), "the board blamed the category instead of the search")
        assertTrue(none.contains("Clear the search and the sort"), "no way back out of the filter")
        assertFalse(none.contains("class=\"grid projects-table\""), "an empty board printed a table")
    }

    @Test
    fun `sorting by status ignores the end date the board otherwise uses`() {
        // "Not started" is given the LATER end date, so the only reason it comes first is the status key.
        siteProject("S-02", "Weir repair", "Not started", "EMP0003", "2028-01-01")
        fun order(sort: String) = render {
            projectsContent(
                manager, "Site projects", canManage = true,
                filter = ProjectBoardFilter(statusSort = sort),
            )
        }
        val byDue = order(BoardSort.NONE)
        assertTrue(
            byDue.indexOf("Haul road widening") < byDue.indexOf("Weir repair"),
            "with no picker set the board is still end-date first",
        )
        val asc = order(BoardSort.ASC)
        assertTrue(
            asc.indexOf("Weir repair") < asc.indexOf("Haul road widening"),
            "Not started should sort ahead of Ongoing",
        )
        val desc = order(BoardSort.DESC)
        assertTrue(
            desc.indexOf("Haul road widening") < desc.indexOf("Weir repair"),
            "reversed should finish the Ongoing work first",
        )
        assertTrue(Regex("value=\"asc\"[^>]*selected").containsMatchIn(asc), "the status picker lost its value")
    }

    @Test
    fun `sorting by manager puts names in order and the unassigned project last either way`() {
        siteProject("S-02", "Weir repair", "Ongoing", "EMP0003", "2027-06-01")
        siteProject("S-03", "Bench reprofiling", "Ongoing", null, "2027-07-01")
        fun order(sort: String) = render {
            projectsContent(
                manager, "Site projects", canManage = true,
                filter = ProjectBoardFilter(managerSort = sort),
            )
        }
        val asc = order(BoardSort.ASC)
        // Munkhtselseg (S-01) then Naran (S-02); the unassigned one has nobody to compare with, so it
        // is filed after the names rather than before them as an empty string would sort.
        assertTrue(asc.indexOf("Haul road widening") < asc.indexOf("Weir repair"), "names are not A to Z")
        assertTrue(asc.indexOf("Bench reprofiling") > asc.indexOf("Weir repair"), "unassigned should sort last")
        val desc = order(BoardSort.DESC)
        assertTrue(desc.indexOf("Weir repair") < desc.indexOf("Haul road widening"), "names are not Z to A")
        assertTrue(desc.indexOf("Bench reprofiling") > desc.indexOf("Haul road widening"), "unassigned should still sort last")
    }

    @Test
    fun `the toolbar is a get form and a picker submits it by itself`() {
        val html = render {
            projectsContent(
                manager, "Site projects", canManage = true,
                filter = ProjectBoardFilter(text = "haul", managerSort = BoardSort.DESC),
            )
        }
        assertTrue(html.contains("class=\"board-toolbar\""), "the search row is missing")
        // One GET form for all three knobs, so the category tab rides along as a hidden field and a
        // sort cannot throw the reader back to the whole board.
        assertTrue(Regex("<form action=\"/app/projects\"[^>]*method=\"get\"").containsMatchIn(html) ||
            Regex("<form method=\"get\"[^>]*action=\"/app/projects\"").containsMatchIn(html),
            "the toolbar is not a GET form onto the board")
        assertTrue(html.contains("<input type=\"hidden\" name=\"type\" value=\"Site projects\">"), "the tab is not preserved")
        assertTrue(html.contains("name=\"q\""), "the search field has no name")
        assertTrue(html.contains("name=\"sort_status\""), "the status picker has no name")
        assertTrue(html.contains("name=\"sort_manager\""), "the manager picker has no name")
        // autosubmit is the app.js contract that makes a picker take effect without a button press;
        // the text box uses the debounced livefilter contract instead.
        assertEquals(2, occurrences(html, "data-autosubmit"), "both pickers should submit on change")
        assertEquals(1, occurrences(html, "data-livefilter"), "only the search box should live-filter")
        // The Search button belongs to the field it submits, so it has to sit between that field and the
        // first picker. Order rather than adjacency: createHTML() writes markup out indented, so an
        // "<input …><button" string test would fail on the newline between the two tags.
        val searchButton = Regex("<button[^>]*>Search</button>").find(html)?.range?.first ?: -1
        assertTrue(searchButton >= 0, "there is no Search button")
        assertTrue(html.indexOf("name=\"q\"") in 0 until searchButton, "the button is not after the search field")
        assertTrue(searchButton < html.indexOf("name=\"sort_status\""), "the button sits past the Status picker")
        assertTrue(searchButton < html.indexOf("name=\"sort_manager\""), "the button sits past the Manager picker")
        assertTrue(Regex("value=\"desc\"[^>]*selected").containsMatchIn(html), "the manager picker lost its value")
        assertTrue(html.contains("board-reset"), "an active filter offers no Clear link")
    }

    /**
     * The markup between the `<div … data-live-region>` that opens the block and the `</div>` that
     * closes it. A live refresh replaces exactly this, so what is inside it gets re-rendered and what
     * is outside it — the toolbar, the popup — survives the swap.
     */
    private fun liveRegion(html: String): String {
        val open = Regex("<div[^>]*data-live-region[^>]*>").find(html)?.range?.first ?: return ""
        var depth = 0
        var cursor = open
        while (true) {
            val nextOpen = html.indexOf("<div", cursor + 1)
            val nextClose = html.indexOf("</div>", cursor + 1)
            if (nextClose < 0) return ""
            if (nextOpen in 0 until nextClose) {
                depth++
                cursor = nextOpen
            } else {
                if (depth == 0) return html.substring(open, nextClose)
                depth--
                cursor = nextClose
            }
        }
    }

    @Test
    fun `the block a live refresh replaces leaves the toolbar and the popup alone`() {
        val html = render {
            projectsContent(
                manager, "Site projects", canManage = true,
                filter = ProjectBoardFilter(text = "widening"),
            )
        }
        val region = liveRegion(html)
        assertTrue(region.isNotEmpty(), "the board has no live region for app.js to replace")
        // Everything the filter changes is inside it, or a refresh would show a stale board.
        assertTrue(region.contains("class=\"filter-tabs\""), "the pills are outside the region")
        assertTrue(region.contains("Haul road widening"), "the rows are outside the region")
        assertTrue(region.contains("class=\"grid projects-table\""), "a panel table is outside the region")
        // The search box, its button and both pickers are NOT: replacing the box mid-typing is the one
        // thing that would put the caret back at the start, which is the whole complaint about reloading.
        assertFalse(region.contains("data-livefilter"), "the live box would be replaced while typing in it")
        assertFalse(region.contains("name=\"sort_status\""), "the Status picker is inside the region")
        assertFalse(region.contains("board-toolbar"), "the toolbar is inside the region")
        // And the create popup stays, so a refresh cannot close a form half filled in.
        assertFalse(region.contains("board_q"), "the create popup would be swapped away mid-edit")
    }

    // --- what the project page does not print -------------------------------------------------------

    /** A card on the project this suite's page is about. */
    private fun card(
        title: String,
        status: String = "Not started",
        assignee: String? = "EMP0002",
        due: String = "2027-01-20",
    ) = TaskRepository.insert(
        kind = "ASSIGNED", title = title, projectId = project.id, due = due,
        priority = "Medium", assigneeEmpNo = assignee, status = status, bucketId = null, started = "",
    )

    /** A due date the real clock has already run past, so a late card exists on the page. */
    private fun pastDue(days: Int) = LocalDate.now(ZoneOffset.UTC).minusDays(days.toLong()).toString()

    /** The whole vocabulary of the roll-up strip Stage 28 took off this page. */
    private val rollUpVocabulary = listOf(
        "project-summary", "sum-tiles", "sum-tile", "sum-key", "sum-val", "sum-cols", "sum-col",
        "sum-people", "sum-empty", "people hold open cards",
    )

    @Test
    fun `the project page prints no roll-up of the board under it`() {
        card("Set out the haul road", "In progress", "EMP0002")
        card("Survey the start line", "In progress", "EMP0003")
        // Overdue and finished cards, and a step part-ticked: the four things the tiles used to count.
        card("Standby the crew", "Blocked", assignee = null, due = pastDue(3))
        val completed = card("Mobilise the plant", "Completed")
        val steps = listOf(
            ChecklistRepository.insert(completed, "Order the aggregate"),
            ChecklistRepository.insert(completed, "Book the crew"),
        )
        ChecklistRepository.setDone(steps.first(), true)

        val html = render { projectDetailContent(viewer, project, canManage = false) }
        rollUpVocabulary.forEach {
            assertFalse(html.contains(it), "the strip came back, and printed $it")
        }
        // The numbers it used to repeat live in the board instead: one head per status column counting its own
        // cards (Stage 52 cut the project page to the five statuses), and every card on the page. The four cards
        // here are two In progress, one Blocked and one Completed, so they spread across four columns. Nothing
        // is lost: the same figures are one screen lower.
        assertEquals(
            TASK_STATUSES.size, occurrences(html, "class=\"kcol-count\""),
            "the board stopped counting its status columns",
        )
        listOf("Set out the haul road", "Survey the start line", "Standby the crew", "Mobilise the plant")
            .forEach { assertTrue(html.contains(it), "$it left the page") }
        // The project's own fields stay. An end date printed as the project's date is a fact about the
        // project; the countdown tile that repeated it was the thing that went.
        assertTrue(html.contains("<span class=\"detail-val\">2027-03-30</span>"), "the End date field went too")
        assertTrue(html.contains("id=\"project-progress\""), "the bar lost its id for the live swap")
        // A tick on a step still moves the bar it belongs to, and nothing else: #project-summary is out of
        // the companion list because there is no strip on this page to refresh. The step forms live inside
        // the popup now (Stage 41 took the plan off the card), so they are read from the editor its owner
        // opens rather than from the board a manager looks at.
        assertFalse(html.contains("task-checklist"), "steps showed on a manager's board of others' cards")
        assertFalse(html.contains("chk-row"), "a plan leaked onto the board that no longer prints one")
        val owned = card("Lay the culvert", "In progress", "EMP0002")
        ChecklistRepository.insert(owned, "Order the aggregate")
        val theirs = render {
            taskFormModal(
                editFormOf(
                    TaskService.editableBy(owned, "EMP0002", canManage = false)!!,
                    canEditAll = false,
                    viewerEmpNo = "EMP0002",
                ),
                BACK_PROJECT,
                project,
            )
        }
        assertTrue(
            theirs.contains("data-live-extra=\"#project-progress,#task-body-$owned\""),
            "the step forms no longer name the bar they move",
        )
        assertFalse(theirs.contains("#project-summary"), "a tick is still chasing a block that is not there")
        rollUpVocabulary.forEach { assertFalse(theirs.contains(it), "the owner's page still had $it") }
    }

    @Test
    fun `a viewer's project page shows every card of the project but edits only their own`() {
        // Stage 56: the project board is the *shared, whole-project* board for every reader, not the personal
        // slice the general Assigned tasks page prints. A Viewer standing on a project asks "what tasks does it
        // have and who is doing each" — so all three cards are here, each carrying its holder's avatar. What the
        // Viewer does NOT get is the right to touch a colleague's card: `data-task-card` / `draggable` stay on
        // `canManage || mine`, so their own card opens and walks while a stranger's is read-only.
        card("Set out the haul road", "In progress", "EMP0002")
        card("Survey the start line", "In progress", "EMP0003")
        card("Standby the crew", "Completed", "EMP0003")
        val html = render { projectDetailContent(viewer, project, canManage = false) }
        assertTrue(html.contains("Set out the haul road"), "this reader's own card left their page")
        assertTrue(html.contains("Survey the start line"), "a colleague's card was hidden — the board is not whole")
        assertTrue(html.contains("Standby the crew"), "a colleague's finished card was hidden")
        // "Who is doing" reads off the board: the colleague's holder (Naran) is on the page as an avatar.
        assertTrue(html.contains("Naran"), "a shown card did not say who is doing it")
        // The reader's own card is editable; a colleague's is not — no opener for cards that are not theirs.
        val ownOpenerCount = Regex("data-task-card=\"\\d+\"").findAll(html).count()
        assertEquals(1, ownOpenerCount, "a Viewer was offered an editor on a card that is not theirs")
        rollUpVocabulary.forEach { assertFalse(html.contains(it), "the strip is back as $it") }
    }

    @Test
    fun `an empty project says so in the board's own heading instead of a strip`() {
        val empty = render { projectDetailContent(manager, project, canManage = true) }
        rollUpVocabulary.forEach { assertFalse(empty.contains(it), "an empty page printed $it") }
        // The invitation to write the first card is the board's, and it is still there.
        assertTrue(empty.contains("nothing assigned yet, create the first one"), "the empty heading is wrong")
        assertTrue(empty.contains("board-empty"), "the empty board lost its own call to action")
        val theirs = render { projectDetailContent(viewer, project, canManage = false) }
        assertTrue(theirs.contains("nothing assigned to this project yet"), "the Viewer's line is wrong")
    }

    // --- the detail card's own layout ---------------------------------------------------------------

    @Test
    fun `revenue and progress are one-line fields inside the same run as the rest`() {
        // The nine-field read-only card is what a Viewer gets (Stage 60 gave management an editable strip
        // instead), so this layout test reads the page as a Viewer does.
        val html = render { projectDetailContent(viewer, project, canManage = false) }
        // Nine caption-and-value fields, all of them one line (`.detail-item` is a row and `.detail-grid` is a
        // wrapping run in app.css). The line itself is CSS and cannot be asserted from markup, so this pins
        // the shape the rules hang on, plus the one promise the layout carries: Revenue and Progress travel
        // together as a single unit *inside* that run — never a row of their own across the card.
        assertEquals(9, occurrences(html, "class=\"detail-item\""), "the card no longer prints nine equal fields")
        assertEquals(1, occurrences(html, "class=\"detail-pair\""), "the paired travel unit is printed twice")
        assertTrue(
            html.contains("<span class=\"detail-key\">Revenue</span>"),
            "Revenue is not caption-and-value like the others",
        )
        assertTrue(
            html.contains("<span class=\"detail-key\">Progress</span>"),
            "Progress is not caption-and-value like the others",
        )
        val pair = html.indexOf("class=\"detail-pair\"")
        assertTrue(pair > html.indexOf(">Status<"), "Revenue and Progress no longer follow Status")
        assertTrue(html.indexOf(">Revenue<") > pair, "Revenue is outside the travel unit")
        assertTrue(html.indexOf("id=\"project-progress\"") > pair, "the bar is outside the travel unit")
        assertTrue(html.contains("id=\"project-progress\""), "the bar lost its id for the live swap")
        // The unit must be a plain child of the run: a `grid-column: 1 / -1` row of its own is the shape that
        // was rejected, and it would have arrived as an inline style or a class on this very div.
        assertFalse(
            html.contains("detail-pair\" style"),
            "the pair is being given a width of its own again",
        )
        assertTrue(
            html.indexOf("<span class=\"detail-val\">2027-03-30</span>") < pair,
            "End date now prints after the pair, so the field order moved",
        )
    }

    // --- what the checklists measure, printed -------------------------------------------------------

    /** The text of the `<span class="pct">` the detail card prints, whatever else that span carries. */
    private fun pctSpan(html: String): String =
        Regex("<span class=\"pct\"[^>]*>([^<]*)</span>").find(html)?.groupValues?.get(1)
            ?: error("no percentage printed next to the bar")

    @Test
    fun `a project's bar reads its cards' checklists, not the figure filed on its row`() {
        val planned = card("Set out the haul road")
        val untouched = card("Compact the sub-base")
        ChecklistRepository.setDone(ChecklistRepository.insert(planned, "Order the aggregate"), true)
        ChecklistRepository.setDone(ChecklistRepository.insert(planned, "Book the crew"), true)
        ChecklistRepository.insert(untouched, "Lay the kerb")
        // Two cards, one finished at 100 and one not begun at 0, so the project is at 50. The steps alone
        // would answer 2 of 3 and the row's own column says 40 — the bar follows the cards, each counted
        // once, and neither of those.
        val html = render { projectDetailContent(manager, project, canManage = true) }
        // Management's progress cell prints the figure as plain text (no bar), so the percentage the checklists
        // add up to is what this test reads — the number, not a bar width.
        assertEquals("50%", pctSpan(html), "the figure is not the average of the cards on it")
        assertFalse(html.contains("40%"), "the figure stored on the row still won")
    }

    @Test
    fun `a project nobody has written steps for keeps the figure filed on its row`() {
        card("Set out the haul road")
        // Cards, but no plan on any of them: unmeasured is not 0%, and the figure falls back to the one this page
        // showed before checklists measured anything, rather than claim one nobody wrote.
        val html = render { projectDetailContent(manager, project, canManage = true) }
        assertEquals("40%", pctSpan(html), "an unmeasured project lost its own number")
    }

    @Test
    fun `the bar measures the whole project while the board shows every card read-only to a viewer`() {
        val mine = card("Set out the haul road", assignee = "EMP0002")
        val theirs = card("Compact the sub-base", assignee = "EMP0003")
        ChecklistRepository.insert(mine, "Order the aggregate")
        ChecklistRepository.setDone(ChecklistRepository.insert(theirs, "Book the crew"), true)
        // This reader's one card is at 0 and a colleague's at 100. The bar answers 50 — the project's own
        // figure, the same one the Projects board prints for this row. Since Stage 56 the board under it now
        // shows BOTH cards to this Viewer — the whole project, each with its holder — but only the reader's own
        // card opens an editor; the colleague's is read-only. The bar and the board agree on the whole project;
        // the difference between the two readers is only what they may edit.
        val html = render { projectDetailContent(viewer, project, canManage = false) }
        assertEquals("50%", pctSpan(html), "the bar was narrowed to this reader's slice")
        // The card states its own figure as the one percentage it prints; the count the boxes add up to is
        // in the editor its click opens, which is the only place the boxes are now.
        assertTrue(html.contains("class=\"kpct\">0%</span>"), "the card lost its own percentage")
        val popup = render {
            taskFormModal(
                editFormOf(
                    TaskService.editableBy(mine, "EMP0002", canManage = false)!!,
                    canEditAll = false,
                    viewerEmpNo = "EMP0002",
                ),
                BACK_PROJECT,
                project,
            )
        }
        // The plan belongs to the card's own reader: the box is here, the × and the add line are not.
        assertTrue(popup.contains("id=\"chk-"), "the owner's popup printed the plan with no box")
        assertFalse(popup.contains("checklist/add"), "the owner was offered to write a step")
        assertTrue(popup.contains("0 of 1 done"), "the popup lost the card's own step count")
        assertTrue(popup.contains("Order the aggregate"), "the popup printed no step of this reader's card")
        // Stage 56: the colleague's card is now ON the page — shown, but not openable by this Viewer.
        assertTrue(html.contains("Compact the sub-base"), "a colleague's card was hidden from the whole-project board")
        assertEquals(1, Regex("data-task-card=\"\\d+\"").findAll(html).count(),
            "a Viewer was offered an editor on the colleague's card")
        assertFalse(html.contains("data-task-card=\"$theirs\""), "the colleague's own card was made openable")
    }

    @Test
    fun `the projects board measures each project from its own cards`() {
        val bay = ProjectRepository.insert(
            code = "R-01", name = "Workshop bay rewiring", status = "Ongoing", progress = 40,
            managerEmpNo = "EMP0001", due = "", type = "Repair/Maintenance/other services",
        )
        val step = TaskRepository.insert(
            kind = "ASSIGNED", title = "Rewire the bay", projectId = bay, due = "",
            priority = "Low", assigneeEmpNo = "EMP0002", status = "In progress", bucketId = null, started = "",
        )
        ChecklistRepository.insert(step, "Isolate the panel")
        ChecklistRepository.setDone(ChecklistRepository.insert(step, "Tag the cable"), true)
        val html = render { projectsContent(manager, null, canManage = true) }
        // The bay's single card is half done, so the bay reads 50; the haul road has no cards at all and
        // keeps the 40 its row carries. One row per project, each measured by its own rule.
        assertEquals(
            // In the board's own order, which follows PROJECT_TYPES: the bay's category is printed
            // before the site project's, so the two bars come out 50 then 40.
            listOf("50%", "40%"),
            Regex("<span class=\"pct\">([^<]*)</span>").findAll(html).map { it.groupValues[1] }.toList(),
            "the board's bars are not what each project's cards add up to",
        )
    }
}
