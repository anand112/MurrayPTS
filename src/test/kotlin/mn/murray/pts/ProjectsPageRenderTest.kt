package mn.murray.pts

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mn.murray.pts.db.Migrations
import mn.murray.pts.domain.BUCKET_DOT_COLOURS
import mn.murray.pts.domain.DEFAULT_PROJECT_BUCKETS
import mn.murray.pts.domain.Member
import mn.murray.pts.domain.PROJECT_TYPES
import mn.murray.pts.domain.Project
import mn.murray.pts.domain.TASK_STATUSES
import mn.murray.pts.domain.TYPE_PREFIX
import mn.murray.pts.repository.ChecklistRepository
import mn.murray.pts.repository.ProjectBucketRepository
import mn.murray.pts.repository.ProjectRepository
import mn.murray.pts.repository.TaskRepository
import mn.murray.pts.repository.TeamRepository
import mn.murray.pts.repository.TypeDefaultBucketRepository
import mn.murray.pts.service.ProjectDraft
import mn.murray.pts.service.ProjectField
import mn.murray.pts.service.ProjectService
import mn.murray.pts.service.TaskService
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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
            code = "S-01", name = "Haul road widening", status = "In progress", progress = 40,
            managerEmpNo = "EMP0001", due = "2027-03-30", type = "Site projects",
        )
        project = ProjectRepository.findById(id)!!
        // The board this page is cut by (Stage 75): the three phases. Since Stage 100 a create takes the board
        // from its category's Settings list, so a fixture that inserts the row by hand gives it the three lanes
        // the page's copy was written against.
        seedOpeningThree(id)
    }

    @AfterEach
    fun tearDown() = ds.close()

    private fun render(block: DIV.() -> Unit): String = createHTML().div(block = block)

    private fun occurrences(html: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(html).count()

    /** The Archive panel's own block, from its id to the script the page loads for it. */
    private fun archivePanel(html: String): String =
        html.substringAfter("id=\"projectArchive\"")
            .substringBefore("<script src=\"/static/js/archive-modal.js\"")

    @Test
    fun `the create popup asks nothing about the project's lanes`() {
        val html = render { projectsContent(manager, null, canManage = true) }
        // Stage 32 put three lane inputs in this form; the user asked for them out again on 2026-10-15, and since
        // Stage 92 the create form asks nothing about the board's columns at all — no lane text fields, none of
        // Stage 32's explanatory copy, and no bucket ticks either (pinned by the test below).
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
    fun `the create popup shows the category's buckets and adds to them, never to Settings`() {
        // Stage 73's ask: management chooses, while creating the project, what kinds of work it has. **Stage 92**
        // took that back, **Stage 100** made the category's Settings list the whole board, and **Stage 104** put an
        // Add bucket line back — as an addition, never as a choice. So the list printed here is read-only (no tick,
        // no way to take one away) and the box appends after it, this board only.
        TypeDefaultBucketRepository.insert("Site projects", "Commissioning")
        val html = render { projectsContent(manager, null, canManage = true) }
        val field = divElement(html, "bucket-pick")
        assertTrue(field.contains(">Buckets<"), "the popup does not label its bucket line")
        // The category's own rows, one per category so the script can switch them with the picker, and exactly one
        // of them shown: the popup's opening category.
        val presets = divElement(html, "id=\"projectBucketPresets\"")
        assertEquals(PROJECT_TYPES.size, occurrences(presets, "data-preset-for="), "a category has no bucket row")
        assertEquals(1, occurrences(presets, "class=\"bucket-preset-row on\""), "the popup shows two categories' rows")
        assertEquals(PROJECT_TYPES.first(), shownPresetRow(html), "the shown row is not the opening category's")
        assertTrue(
            presets.contains("<span class=\"bucket-preset\">Commissioning</span>"),
            "the category's own bucket is not listed on the popup",
        )
        // A category that lists nothing says so on its own row rather than printing an empty run of nothing.
        assertEquals(
            PROJECT_TYPES.size - 1, occurrences(presets, "class=\"bucket-preset none\""),
            "an empty category does not say it is empty",
        )
        assertTrue(presets.contains("— none listed in Settings yet —"), "the empty category line is not readable")
        // The addition: one box, one button, and the words that say what it does. No `also_type`, because a box
        // that quietly adds a column to every other board of the category is not what the box would say.
        assertTrue(field.contains("name=\"new_bucket\""), "the popup cannot name a further bucket")
        assertTrue(field.contains("id=\"newProjectBucketName\""), "the box the script clears is not there")
        assertTrue(field.contains("data-bucket-add"), "the + Add bucket button is not there")
        assertTrue(field.contains("+ Add bucket"), "the button uses other words than the board's box does")
        assertTrue(field.contains("id=\"newProjectBucketList\""), "the popup has nowhere to put a chip")
        assertTrue(
            field.contains("The board opens with the buckets above, then any you add here"),
            "the popup does not say the typed bucket is an addition",
        )
        assertTrue(
            field.contains("changes nothing in Settings"),
            "the popup does not say a typed bucket stays this project's own",
        )
        assertFalse(field.contains("name=\"also_type\""), "the create popup offers a category-wide bucket")
        assertFalse(field.contains("value=\"Commissioning\""), "the category's bucket is offered as something to tick")
        DEFAULT_PROJECT_BUCKETS.forEach {
            assertFalse(field.contains("value=\"$it\""), "an opening lane is offered as a bucket to tick")
        }
        // The list is not a control: every `value=` on this line is a chip the reader added, and on a fresh popup
        // there are none — only the still-empty box, which is the line's one input.
        assertEquals(1, occurrences(field, "<input"), "the bucket line posts more than the box")
        // The refusal an empty category earns prints under this line, only when the create comes back refused.
        assertFalse(field.contains("no buckets yet"), "a successful page printed a refusal")
    }

    @Test
    fun `a refused create brings the added buckets back as chips that post themselves`() {
        // Stage 73's lesson, applied to a chip: a name that exists only as ink on the page posts nothing, and a
        // box disabled to stop it being typed twice posts nothing either. So every chip carries its own hidden
        // `new_bucket`, and the box stays open beside them — which is how a reader without scripts still gets the
        // name they typed into a refused create.
        val rejected = ProjectService.create(
            ProjectDraft(
                type = "Site projects", code = "S-02", name = "", managerName = "Naran",
                newBuckets = listOf("QAQC", "Dewatering"),
            ),
            actorEmpNo = "EMP0001",
        )
        assertEquals(setOf(ProjectField.NAME), rejected.errors.keys, "the added buckets were refused: ${rejected.errors}")
        val html = render {
            projectsContent(
                manager,
                null,
                ProjectForm(rejected.values, rejected.errors, ProjectService.suggestedCodes()),
                canManage = true,
            )
        }
        val chips = divElement(html, "id=\"newProjectBucketList\"")
        listOf("QAQC", "Dewatering").forEach { name ->
            assertTrue(chips.contains("name=\"new_bucket\" value=\"$name\""), "$name did not come back")
            assertTrue(chips.contains("data-bucket-pending=\"$name\""), "$name is not a chip the script can drop")
            assertTrue(chips.contains("aria-label=\"Remove $name from this board\""), "$name has no named removal")
        }
        assertEquals(2, occurrences(chips, "data-bucket-drop"), "a chip's × is not a button the script answers")
        // Two chips and the open box: three fields under one name, which is what the route reads whole.
        assertEquals(3, occurrences(divElement(html, "bucket-pick"), "name=\"new_bucket\""), "the posted set is wrong")
        // The chip's own refusals print in the line's red note, not as a `.field-err` — an always-printed error
        // node would be the first thing a refused create tries to focus, empty or not. One error line for one
        // refused field is the proof it is not one.
        assertTrue(html.contains("id=\"newProjectBucketSaid\""), "the popup has no line for a refused name")
        assertEquals(1, occurrences(html, "class=\"field-err\""), "the popup printed the empty chip note as an error")
    }

    @Test
    fun `the popup prints one field error per refusal, in the bucket line included`() {
        // A category with nothing in Settings used to stop the create outright. Since Stage 104 the popup's own box
        // is the way out of it, so the message says both: add one above, or list them in Settings.
        val type = PROJECT_TYPES[1]
        val rejected = ProjectService.create(
            ProjectDraft(type = type, code = "${TYPE_PREFIX[type]}-01", name = "Tailings lift", managerName = "Naran"),
            actorEmpNo = "EMP0001",
        )
        assertEquals(setOf(ProjectField.BUCKETS), rejected.errors.keys, "refused for the wrong reason: ${rejected.errors}")
        val html = render {
            projectsContent(
                manager,
                null,
                ProjectForm(rejected.values, rejected.errors, ProjectService.suggestedCodes()),
                canManage = true,
            )
        }
        val field = divElement(html, "bucket-pick")
        assertTrue(field.contains("<p class=\"field-err\">${rejected.errors.getValue(ProjectField.BUCKETS)}</p>"),
            "the bucket refusal does not print under the bucket line")
        assertTrue(field.contains("Add one above"), "the refusal does not offer the popup's own box")
        // One node per refused field and no more: the always-printed chip note is not an error line.
        assertEquals(rejected.errors.size, occurrences(html, "class=\"field-err\""), "a field error is printed twice")
    }

    /** The category whose bucket row is the shown one on the create popup (Stage 104). */
    private fun shownPresetRow(html: String): String {
        val presets = divElement(html, "id=\"projectBucketPresets\"")
        val at = presets.indexOf("class=\"bucket-preset-row on\"")
        assertTrue(at >= 0, "no bucket row is shown on the popup")
        val tag = presets.substring(presets.lastIndexOf("<div", at), presets.indexOf('>', at) + 1)
        return Regex("data-preset-for=\"([^\"]*)\"").find(tag)?.groupValues?.get(1).orEmpty()
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
    fun `the create popup leads the number with the chosen category's letter`() {
        val html = render { projectsContent(manager, null, canManage = true) }
        val box = Regex("<input[^>]*id=\"projectCode\"[^>]*>").find(html)?.value.orEmpty()
        assertTrue(box.isNotEmpty(), "the popup has no project-number box")
        // Stage 92: the box opens with the category's letter and dash already in it, so the reader types only the
        // number. The next free number stays the grey placeholder — the lead says what the code starts with, not
        // what it will be — and `data-prefix` says which lead the box is showing, so the script can tell it apart
        // from a code the reader wrote.
        val first = PROJECT_TYPES.first()
        val letter = TYPE_PREFIX[first].orEmpty()
        assertTrue(letter.isNotEmpty(), "the first category has no letter")
        assertTrue(box.contains("value=\"$letter-\""), "the number did not open with $letter-: $box")
        assertTrue(box.contains("data-prefix=\"$letter-\""), "the box does not say which lead it shows: $box")
        val next = ProjectService.suggestedCodes()[first].orEmpty()
        assertTrue(box.contains("placeholder=\"$next\""), "the next free number is not offered as a hint: $box")
        assertTrue(html.contains("type the number after the dash"), "the hint does not say what is left to type")
        // The lead is drawn from the draft's category, not from the catalogue's first: a re-render after a refusal
        // asks the same question the reader answered.
        val site = render {
            projectsContent(
                manager, null,
                ProjectForm(
                    ProjectDraft(type = "Site projects", name = "Haul road widening"),
                    emptyMap(),
                    ProjectService.suggestedCodes(),
                ),
                canManage = true,
            )
        }
        val siteBox = Regex("<input[^>]*id=\"projectCode\"[^>]*>").find(site)?.value.orEmpty()
        assertTrue(siteBox.contains("value=\"S-\""), "the lead does not follow the draft's category: $siteBox")
        assertTrue(siteBox.contains("data-prefix=\"S-\""), "the lead and its marker disagree: $siteBox")
        assertTrue(siteBox.contains("placeholder=\"S-02\""), "the site's next free number is not the hint: $siteBox")
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
            code = "R-01", name = "Excavator overhaul", status = "In progress", progress = 10,
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
            code = "S-09", name = "Concentrator liner", status = "In progress", progress = 0,
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
    fun `the project page prints no bucket panel and its board holds the only bucket forms`() {
        // **Stage 79** took Stage 73's tick-list panel off this page. A project's buckets are written on the board
        // and nowhere else: the dashed `+ Add bucket` box adds, a column's `⋯` retitles and removes, and the one
        // rule that panel existed to enforce — a bucket with work filed in it does not go — is stated by that
        // popup's own count line and its unpressable `Remove bucket`, which the tests below pin.
        val html = render { projectDetailContent(manager, project, canManage = true) }
        assertFalse(html.contains("id=\"projectBuckets\""), "the bucket panel came back")
        assertFalse(html.contains("Save buckets"), "the panel's save button came back")
        assertFalse(html.contains("Project buckets"), "the panel's caption came back")
        assertFalse(html.contains("class=\"bucket-opt\""), "the panel's tick boxes came back")
        // The bulk tick-list was the page's only other writer of the save route, so exactly one form of that
        // action is printed here — and it lives inside the column popup.
        assertEquals(1, occurrences(html, "/buckets/save"), "a second bucket form is on this page")
        assertTrue(
            html.substringAfter("id=\"projectLaneModal\"").contains("/buckets/save"),
            "the page's only bucket save is not the popup's",
        )
    }

    @Test
    fun `the bucket controls are the project manager's too, and nobody else's`() {
        // Same right that lets a project's manager add a card (Stage 49): management or the account this project
        // names as its manager — not every management account, and not the readers of this project's work. Stage 79
        // moved where that right is drawn, from the panel under the header to the board's box and menus, so this
        // pin moves with it.
        val theirs = render { projectDetailContent(manager, project, canManage = false) }
        assertTrue(theirs.contains("name=\"laneadd\" value=\"1\""), "the project's own manager was refused the add box")
        assertEquals(
            DEFAULT_PROJECT_BUCKETS.size, occurrences(theirs, "class=\"kcol-menu\""),
            "the project's own manager was refused a column menu",
        )
        assertTrue(
            theirs.contains("<form action=\"/app/projects/${project.id}/buckets/save\""),
            "the project's own manager was refused the bucket form",
        )
        // A Viewer holds neither right, so they are served neither the openers nor a form whose every button the
        // route would refuse.
        val reader = render { projectDetailContent(viewer, project, canManage = false) }
        assertFalse(reader.contains("/buckets/save"), "a Viewer was served a writer of this project's buckets")
        assertFalse(reader.contains("projectLaneModal"), "a Viewer was handed the bucket popup")
    }

    @Test
    fun `the column popup's add asks management, and asks nobody else, about the category`() {
        // The dashed box and a column's `⋯` post to the create popup's one save route, so they inherit its one new
        // question too — and a manager who is not management sees the field without the box, rather than a box that
        // would be refused. Since **Stage 79** this is the page's only bucket-add form at all.
        val mine = render {
            projectDetailContent(manager, project, canManage = true, lane = LaneForm(open = true))
        }
        // Read the popup block rather than the whole form or the page: the scope sentence sits *above* the form, so
        // it belongs to the popup rather than to the POST.
        val modal = mine.substringAfter("id=\"projectLaneModal\"")
            .substringBefore("<script src=\"/static/js/lane-modal.js\"")
        val form = modal.substringAfter("<form action=\"/app/projects/${project.id}/buckets/save\"")
            .substringBefore("</form>")
        assertTrue(form.contains("name=\"also_type\""), "the board's add box asks nothing about the category")
        assertTrue(form.contains("Also make it a default bucket for Site projects"), "the box does not say what it ticks")
        assertFalse(inputTag(form, "name=\"also_type\"").contains("checked"), "the box came up on by default")
        assertTrue(
            form.contains("Off, this column is the project's own."),
            "the box does not say what leaving it alone means",
        )
        assertTrue(
            modal.contains("It belongs to this project"),
            "the add box still claims a typed name reaches the category on its own",
        )
        // A refusal from inside the popup carries the tick back (`?widen=1`), because a reader who asked for the
        // wider write and was stopped by the name rules should not have to ask again.
        val refused = render {
            projectDetailContent(
                manager, project, canManage = true,
                lane = LaneForm(typed = "Dewatering", open = true, nameError = "A bucket name is too long.", forType = true),
            )
        }
        val keptBox = refused.substringAfter("id=\"projectLaneModal\"")
            .substringAfter("<form action=\"/app/projects/${project.id}/buckets/save\"")
            .substringBefore("</form>")
        assertTrue(inputTag(keptBox, "name=\"also_type\"").contains("checked"), "the refusal threw the tick away")
        // The project's own manager, who is not management: the same field, and no box beside it.
        val theirs = render {
            projectDetailContent(manager, project, canManage = false, lane = LaneForm(open = true))
        }
        val theirModal = theirs.substringAfter("id=\"projectLaneModal\"")
            .substringBefore("<script src=\"/static/js/lane-modal.js\"")
        val theirForm = theirModal.substringAfter("<form action=\"/app/projects/${project.id}/buckets/save\"")
            .substringBefore("</form>")
        assertFalse(theirForm.contains("name=\"also_type\""), "the board's add box offered a manager the catalogue write")
        assertTrue(theirForm.contains("name=\"new_bucket\""), "the manager's add box lost its field")
        assertTrue(
            theirModal.contains("It belongs to this project"),
            "the manager's add box says nothing about where the name goes",
        )
        assertTrue(theirModal.contains("is only told about it if management tick the box"), "the box is management's, and the popup never said so")
    }

    @Test
    fun `a viewer's project page stays a read-only card with no strip or delete`() {
        val html = render { projectDetailContent(viewer, project, canManage = false) }
        // None of management's writes are offered to a Viewer: no update form, no delete, no save button, and no
        // control of any kind inside the field row. Since Stage 71 the row itself is SHARED with management —
        // the same `.edit-strip` run, so the two headers cannot drift apart — which is exactly why this asserts
        // the absence of inputs rather than of the class: the strip is the layout, not the permission.
        assertFalse(html.contains("action=\"/app/projects/${project.id}\""), "a Viewer was served the edit form")
        assertFalse(html.contains("action=\"/app/projects/${project.id}/delete\""), "a Viewer was served the delete")
        assertFalse(html.contains("Save changes"), "a Viewer was served the save button")
        val strip = detailStrip(html)
        assertTrue(strip.contains("class=\"edit-strip detail-strip\""), "the Viewer's fields left the shared row")
        assertFalse(strip.contains("<input"), "the read-only strip grew a control")
        assertFalse(strip.contains("<select"), "the read-only strip grew a picker")
        assertFalse(strip.contains("field-err"), "the read-only strip carries a save-refusal slot")
        // What they keep is the fact: the nine fields, in the same row, and their own pin.
        assertTrue(
            strip.contains("<span class=\"ef-label\">Manager</span>"),
            "the read-only Manager field is gone",
        )
        assertTrue(html.contains("action=\"/app/projects/${project.id}/pin\""), "the Viewer's own pin vanished")
    }

    /**
     * The Viewer's field row and nothing else — from the `div` that opens it to the `div` that closes it, found
     * by balancing the tags between them. Slicing rather than searching the whole page is what lets a test say
     * "no inputs here" when the page below the header legitimately has forms of its own.
     */
    private fun detailStrip(html: String): String {
        val open = html.indexOf("<div class=\"edit-strip detail-strip\">")
        assertTrue(open >= 0, "no read-only detail strip on the page")
        var depth = 1
        var at = open
        while (true) {
            val next = Regex("<div|</div>").find(html, at + 1) ?: break
            depth += if (next.value == "<div") 1 else -1
            if (depth == 0) return html.substring(open, next.range.last + 1)
            at = next.range.last
        }
        error("the detail strip was never closed")
    }


    /**
     * One `<div>` from its opening tag through its own `</div>`, found by anything unique inside its opening tag
     * — a class such as the create popup's `bucket-pick`. A block is a div holding divs, so a slice to the next
     * `</div>` would stop in the middle of its first child; this walks the nesting the way [detailStrip] does,
     * which is what lets an assertion say "this line holds no control" instead of "the page contains the word
     * input" somewhere.
     */
    private fun divElement(html: String, inOpeningTag: String): String {
        val found = html.indexOf(inOpeningTag)
        assertTrue(found >= 0, "$inOpeningTag is not on the page")
        val start = html.lastIndexOf("<div", found)
        assertTrue(start >= 0, "$inOpeningTag is not inside a div")
        var depth = 1
        var at = html.indexOf('>', start) + 1
        while (true) {
            val next = Regex("<div|</div>").find(html, at) ?: break
            depth += if (next.value == "</div>") -1 else 1
            if (depth == 0) return html.substring(start, next.range.last + 1)
            at = next.range.last + 1
        }
        error("$inOpeningTag was never closed")
    }

    /**
     * The `<input>` tag carrying [needle], so one assertion can name several attributes of one control without
     * pinning the order kotlinx.html happens to print them in.
     */
    private fun inputTag(html: String, needle: String): String {
        val at = html.indexOf(needle)
        assertTrue(at >= 0, "$needle is not on the page")
        val open = html.lastIndexOf("<input", at)
        assertTrue(open >= 0, "$needle is not inside an input")
        return html.substring(open, html.indexOf('>', at) + 1)
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
            code = "R-01", name = "Excavator overhaul", status = "In progress", progress = 10,
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
            "Not started should sort ahead of In progress",
        )
        val desc = order(BoardSort.DESC)
        assertTrue(
            desc.indexOf("Haul road widening") < desc.indexOf("Weir repair"),
            "reversed should finish the In progress work first",
        )
        assertTrue(Regex("value=\"asc\"[^>]*selected").containsMatchIn(asc), "the status picker lost its value")
    }

    @Test
    fun `the status picker on a new project reads as the progression, not as the most-used choice`() {
        // Stage 96's ask. This picker used to open with the status managers reached for most often, on the theory
        // that the common choice should be the nearest one; the reader asked instead for the order a project
        // actually moves through — and for the word *In progress*, which the app had been giving only to cards
        // while a project running at 40% called itself `Ongoing`. The list below is deliberately spelled out
        // rather than read from `PROJECT_STATUSES`: a test that asks the constant whether it matches itself
        // passes whatever the constant says, and the ask was about what the reader sees.
        val html = render { projectsContent(manager, null, canManage = true) }
        val picker = Regex("<select name=\"status\"[^>]*>.*?</select>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.value ?: error("the create popup has no status picker")
        assertEquals(
            listOf("Not started", "In progress", "On hold", "Blocked", "Completed"),
            Regex("<option value=\"([^\"]*)\"").findAll(picker).map { it.groupValues[1] }.toList(),
            "the status picker is not the progression",
        )
        // The form opens on the stage a project starts in, so a manager who touches nothing is not silently
        // creating a job that is already under way: `ProjectService.DEFAULT_STATUS` is the picker's first choice.
        assertTrue(
            picker.contains("<option value=\"Not started\" selected=\"selected\">"),
            "the picker does not open on the stage a new project starts in",
        )
        // And the same five words, in the same order, come back on a rejected create — the popup is re-printed
        // from what was typed, so a form that lost the order on its second pass would be a form with two orders.
        val rejected = ProjectService.create(
            ProjectDraft(type = "Site projects", code = "S-01", name = "", managerName = "Naran"),
            actorEmpNo = "EMP0001",
        )
        val roundTrip = render {
            projectsContent(
                manager, null, ProjectForm(rejected.values, rejected.errors, ProjectService.suggestedCodes()),
                canManage = true,
            )
        }
        assertEquals(
            picker,
            Regex("<select name=\"status\"[^>]*>.*?</select>", RegexOption.DOT_MATCHES_ALL).find(roundTrip)?.value,
            "a rejected create re-printed the picker in a different order",
        )
    }

    @Test
    fun `the board's status sort follows the picker`() {
        // Stage 96's second answer: *follow the picker*. `PROJECT_STATUS_ORDER` was its own list, which put
        // Blocked ahead of On hold because a stalled job wants looking at before a parked one — sound advice,
        // but it meant the order a manager read down the Status column was not the order the form offered them,
        // and the two lists had to be kept straight in prose that nobody read twice. They are one list now.
        val stages = listOf(
            "S-11" to "Completed", "S-12" to "Blocked", "S-13" to "In progress",
            "S-14" to "On hold", "S-15" to "Not started",
        )
        // Every row carries the same end date, so the only thing that can move a name is the status key.
        stages.forEach { (code, status) -> siteProject(code, "Row $status", status, "EMP0001", "2027-05-05") }
        fun order(sort: String) = render {
            projectsContent(
                manager, "Site projects", canManage = true,
                filter = ProjectBoardFilter(statusSort = sort),
            )
        }
        val progression = listOf("Not started", "In progress", "On hold", "Blocked", "Completed")
        val asc = order(BoardSort.ASC)
        val up = progression.map { asc.indexOf("Row $it") }
        assertTrue(up.all { it >= 0 }, "a stage's row is missing from the board: $up")
        assertEquals(up.sorted(), up, "the status sort is not the picker's order")
        val desc = order(BoardSort.DESC)
        val down = progression.map { desc.indexOf("Row $it") }
        assertEquals(down.sorted().reversed(), down, "reversed should run the progression backwards")
    }

    @Test
    fun `sorting by manager puts names in order and the unassigned project last either way`() {
        siteProject("S-02", "Weir repair", "In progress", "EMP0003", "2027-06-01")
        siteProject("S-03", "Bench reprofiling", "In progress", null, "2027-07-01")
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
        inBucket: Int? = null,
    ) = TaskRepository.insert(
        kind = "ASSIGNED", title = title, projectId = project.id, due = due,
        priority = "Medium", assigneeEmpNo = assignee, status = status, bucketId = null, started = "",
        projectBucketId = inBucket,
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
        // The numbers it used to repeat live in the board instead: one head per column counting its own cards, and
        // every card on the page. Stage 75 made the project page its buckets whatever the board holds, so what is
        // counted here is the three this project opened with plus the Unsorted column these four hand-inserted,
        // unfiled cards wait in. A card's own status prints on the card and changes from its popup, not from a
        // column. Nothing is lost: the same figures are one screen lower.
        val columns = TaskService.lanesOf(project.id).size + 1
        assertEquals(columns, occurrences(html, "class=\"kcol-head\""), "the board is not one column per bucket")
        assertEquals(columns, occurrences(html, "class=\"kcol-count\""), "the board stopped counting its columns")
        listOf("Set out the haul road", "Survey the start line", "Standby the crew", "Mobilise the plant")
            .forEach { assertTrue(html.contains(it), "$it left the page") }
        // The project's own fields stay. An end date printed as the project's date is a fact about the
        // project; the countdown tile that repeated it was the thing that went. (Since Stage 71 every value
        // carries its own text as a `title`, so a long one can be shortened on the page and still be complete
        // on hover — hence the attribute-tolerant match rather than the bare tag.)
        assertTrue(
            html.contains(Regex("<span class=\"detail-val\"[^>]*>2027-03-30</span>")),
            "the End date field went too",
        )
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
                    TaskService.openableBy(owned, "EMP0002", canManage = false)!!,
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
        // have and who is doing each" — so all three cards are here, each carrying its holder's avatar. **Stage
        // 86** opened every card on that board to this reader, which is the point of a shared board: all three
        // are click targets, and what a colleague's click opens is the read-only popup. What stays the reader's
        // alone is *moving* — `draggable` and the ✓ Done button — so their own card walks and the others do not.
        card("Set out the haul road", "In progress", "EMP0002")
        card("Survey the start line", "In progress", "EMP0003")
        card("Standby the crew", "Completed", "EMP0003")
        val html = render { projectDetailContent(viewer, project, canManage = false) }
        assertTrue(html.contains("Set out the haul road"), "this reader's own card left their page")
        assertTrue(html.contains("Survey the start line"), "a colleague's card was hidden — the board is not whole")
        assertTrue(html.contains("Standby the crew"), "a colleague's finished card was hidden")
        // "Who is doing" reads off the board: the colleague's holder (Naran) is on the page as an avatar.
        assertTrue(html.contains("Naran"), "a shown card did not say who is doing it")
        // Every card opens; the page's one opener is there for them (Stage 86).
        val openers = Regex("data-task-card=\"\\d+\"").findAll(html).count()
        assertEquals(3, openers, "a card on the shared board was not made clickable")
        assertTrue(html.contains("id=\"taskEditOpener\""), "the page lost the form a card's click fills")
        // The reader's own card is the only one offered movement: one move button, on their card, and nobody's
        // card is `draggable` here because this project's lanes are not this viewer's to file in (Stage 49).
        assertEquals(1, Regex("kmove-btn").findAll(html).count(), "a colleague's card was given a move button")
        assertFalse(html.contains("draggable=\"true\""), "a viewer of no lanes was offered a drag")
        rollUpVocabulary.forEach { assertFalse(html.contains(it), "the strip is back as $it") }
    }

    @Test
    fun `an empty project says so in the board's own heading instead of a strip`() {
        val empty = render { projectDetailContent(manager, project, canManage = true) }
        rollUpVocabulary.forEach { assertFalse(empty.contains(it), "an empty page printed $it") }
        // The invitation to write the first card is the board's, and it is still there.
        assertTrue(empty.contains("nothing assigned yet, create the first one"), "the empty heading is wrong")
        // Since **Stage 74** the columns are the invitation, so the `board-empty` box is not printed: one line per
        // bucket, each with its own `+ Add task` at the foot, says the same thing in the shape the reader works in.
        assertFalse(empty.contains("board-empty"), "an empty project with buckets still got the placeholder box")
        assertEquals(
            DEFAULT_PROJECT_BUCKETS.size, occurrences(empty, "class=\"kcol-add\""),
            "the empty board's columns did not each offer the first card",
        )
        val theirs = render { projectDetailContent(viewer, project, canManage = false) }
        assertTrue(theirs.contains("nothing assigned to this project yet"), "the Viewer's line is wrong")
    }

    // --- Stage 75: the project page is its bucket columns, cards on it or not ---------------------------

    /** Put one card on this project's first bucket, filed the way the popup files it. */
    private fun filedCard(title: String) {
        val lane = TaskService.lanesOf(project.id).first()
        card(title, "Not started", "EMP0002", inBucket = lane.id)
    }

    @Test
    fun `the project page is its buckets before its first card and after it`() {
        // Stage 75's one rule, tested from both sides of the line the user drew: the *same* project, before and
        // after its first card. Nothing in the data moves between the two — `status` and `project_bucket_id` are
        // separate columns of one row — and what changes on the page is nothing. The columns answer "which phase of
        // this project", and a card's state prints on the card and moves through its Done button or popup, never
        // through a column of its own.
        val before = render { projectDetailContent(manager, project, canManage = true) }
        DEFAULT_PROJECT_BUCKETS.forEach {
            assertTrue(before.contains("<span class=\"kcol-title\">$it</span>"), "$it is not a column yet")
        }
        filedCard("Standby the crew")
        val after = render { projectDetailContent(manager, project, canManage = true) }
        DEFAULT_PROJECT_BUCKETS.forEach {
            assertTrue(
                after.contains("<span class=\"kcol-title\">$it</span>"),
                "$it stopped being a column once the project had work",
            )
        }
        // One column per bucket, and no card has gone into an Unsorted column: `filedCard` put the only card on it
        // into the board's first bucket, which is what the columns are for.
        assertEquals(
            DEFAULT_PROJECT_BUCKETS.size, occurrences(after, "class=\"kcol\" data-lane"),
            "a board that has cards is not its buckets",
        )
        assertFalse(after.contains("class=\"kcol\" data-status"), "the board fell back to its five statuses")
        TASK_STATUSES.forEach {
            assertFalse(after.contains("<span class=\"kcol-title\">$it</span>"), "a status column is on the board")
        }
        // …and the board-side bucket controls come with the columns rather than with the emptiness Stage 74 had
        // to gate them on: this is the one place a card's column can be retitled or taken off.
        assertTrue(after.contains("lane-add-box"), "a full board stopped offering a bucket column")
        assertTrue(after.contains("projectLaneModal"), "the board popup went away with the emptiness")
        assertFalse(
            after.contains("id=\"projectLaneModal\" style=\"display:block\""),
            "a board with cards came back with its popup raised",
        )
    }

    @Test
    fun `an empty board colours each bucket the way Settings colours it`() {
        // The picture's dots: a column head carries the same dot the Settings catalogue gives that bucket, which is
        // the cycle of the stylesheet's hues in `bucketDotClass`. Position, not identity — and the first three
        // positions are the lanes a board opens with, so the two pages cannot disagree about one. (Stage 73's panel
        // printed the same dots beside its tick boxes until Stage 79 took it off this page; the rule it pinned here
        // was the correspondence between the two pages, and that correspondence stands on the board alone.)
        //
        // Since **Stage 80** a bucket may be *given* a colour instead, so the position is what a column with no
        // chosen swatch falls back to — which is every column on this board, because nothing here picked one.
        // Since **Stage 83** the popup's closed add half draws no swatch row, so the only dots on the page are
        // the heads themselves.
        val html = render { projectDetailContent(manager, project, canManage = true) }
        assertEquals(
            DEFAULT_PROJECT_BUCKETS.size, occurrences(html, "</span><span class=\"kcol-title\">"),
            "a lane head lost its dot",
        )
        assertEquals(
            DEFAULT_PROJECT_BUCKETS.size, occurrences(html, "class=\"bucket-dot dot-"),
            "the board grew dots that are not column heads",
        )
        DEFAULT_PROJECT_BUCKETS.forEachIndexed { index, name ->
            assertTrue(
                html.contains("class=\"bucket-dot dot-${index % BUCKET_DOT_COLOURS}\"></span>" +
                    "<span class=\"kcol-title\">$name</span>"),
                "$name is not on the dot its position gives it",
            )
        }
    }

    @Test
    fun `a column that was given a colour wears it and one that was not wears its position`() {
        // Stage 80's rule in one board: the hue is stored as a slot into the stylesheet's six, and a NULL slot
        // means "wear the colour of my place in the run" — so a project that never chose anything looks exactly as
        // it did before the choice existed, and a column that chose does not care where it sits.
        val lane = TaskService.lanesOf(project.id).first()
        val third = TaskService.lanesOf(project.id)[2]
        TaskService.renameLane(project.id, lane.id, lane.name, "EMP0001", canManage = true, colourSlot = 4)
        val html = render { projectDetailContent(manager, project, canManage = true) }
        assertTrue(
            html.contains("class=\"bucket-dot dot-4\"></span><span class=\"kcol-title\">${lane.name}</span>"),
            "${lane.name} was picked a colour and still wears its position",
        )
        assertTrue(
            html.contains("class=\"bucket-dot dot-2\"></span><span class=\"kcol-title\">${third.name}</span>"),
            "${third.name} lost the colour its position gives it",
        )
    }

    @Test
    fun `the colour a reader chose on a column is the swatch checked when it comes back`() {
        // A refusal is the form returning the reader's own answer, and since Stage 80 that includes the swatch:
        // `?lanecolour=` rides the redirect and `openingLaneForm` puts it back on the form. Since Stage 83 the
        // swatch row lives on the manage half only — creation asks for a name — so this opens a column's `⋯`,
        // which is also the only place a colour is chosen or changed now.
        val lane = TaskService.lanesOf(project.id).first()
        val html = render {
            projectDetailContent(
                manager, project, canManage = true,
                lane = LaneForm(laneId = lane.id, nameError = "That name is already taken", colourSlot = 3),
            )
        }
        assertEquals(6, occurrences(html, "type=\"radio\" name=\"colour\""), "the swatch row is not six choices")
        assertEquals(1, occurrences(html, "value=\"3\" checked=\"checked\""), "the chosen swatch did not come back")
    }

    @Test
    fun `the board's bucket controls are for the reader who may write them`() {
        // `mayEditLanes` is one right and three controls: a column's `⋯`, the dashed box, and the popup they fill.
        // A Viewer's page has none of them, and a hand-built POST is refused again by every route.
        val theirs = render { projectDetailContent(viewer, project, canManage = false) }
        assertFalse(theirs.contains("lane-add-box"), "a Viewer was offered a bucket to add")
        assertFalse(theirs.contains("kcol-menu"), "a Viewer was offered a bucket to manage")
        assertFalse(theirs.contains("projectLaneModal"), "a Viewer was handed the bucket popup")
        // The columns themselves stay — a project's board is the whole project for every reader since Stage 56.
        DEFAULT_PROJECT_BUCKETS.forEach {
            assertTrue(theirs.contains("<span class=\"kcol-title\">$it</span>"), "a Viewer lost a board column")
        }
        val ours = render { projectDetailContent(manager, project, canManage = true) }
        assertEquals(1, occurrences(ours, "class=\"lane-add-box\""), "the run of columns has no add box")
        assertTrue(ours.contains("name=\"laneadd\" value=\"1\""), "the add box opens nothing")
        assertTrue(ours.contains("data-live-target=\"#projectLaneModal\""), "an opener swaps a block that is not there")
    }

    @Test
    fun `every column of a board its reader may re-shape carries a menu`() {
        // Stage 75 took the standard-lane exception out of [mn.murray.pts.service.TaskService.renameLane] and
        // [removeLane], so a `⋯` over **Engineering** answers yes to both asks — and Stage 51's rule that a control
        // whose every button says no is a dead one is therefore what makes it *appear* over the opening lanes now.
        val plain = render { projectDetailContent(manager, project, canManage = true) }
        assertEquals(
            DEFAULT_PROJECT_BUCKETS.size, occurrences(plain, "class=\"kcol-menu\""),
            "an opening bucket was left without a menu",
        )
        val openedIds = Regex("name=\"laneedit\" value=\"(\\d+)\"").findAll(plain).map { it.groupValues[1] }.toList()
        assertEquals(
            TaskService.lanesOf(project.id).map { "${it.id}" }, openedIds,
            "a column's menu names the wrong bucket",
        )
        // Adding a bucket gives that column its own menu as well: the count follows the board, not the number of
        // lanes somebody typed a name for.
        TaskService.addLane(project.id, "QAQC", "EMP0001", canManage = true)
        val html = render { projectDetailContent(manager, project, canManage = true) }
        assertEquals(
            DEFAULT_PROJECT_BUCKETS.size + 1, occurrences(html, "class=\"kcol-menu\""),
            "the extra bucket has no menu",
        )
        assertEquals(
            DEFAULT_PROJECT_BUCKETS.size + 1, occurrences(html, "name=\"laneedit\""),
            "one menu names more than one bucket",
        )
    }

    @Test
    fun `the column popup asks for one name and carries every lane the board holds`() {
        // The dashed box inherits `saveLanes`, whose contract is *this is the whole set*. Stage 75 made that carry
        // matter far more than it did: with the opening lanes no longer protected by the service, an add that
        // posted only its own name would read as a request to untick every lane it left out — Engineering first.
        TaskService.addLane(project.id, "QAQC", "EMP0001", canManage = true)
        val html = render { projectDetailContent(manager, project, canManage = true) }
        // Since Stage 79 this popup is the page's one `/buckets/save` form, but read from the popup still costs
        // nothing and keeps the assertion about *this* form rather than about whatever else the page grows.
        val form = html.substringAfter("id=\"projectLaneModal\"")
            .substringAfter("<form action=\"/app/projects/${project.id}/buckets/save\"")
            .substringBefore("</form>")
        assertTrue(form.contains("name=\"bucket\" value=\"QAQC\""), "the add did not carry the board's other bucket")
        DEFAULT_PROJECT_BUCKETS.forEach {
            assertTrue(form.contains("name=\"bucket\" value=\"$it\""), "the add let $it be unticked by accident")
        }
        assertEquals(
            TaskService.lanesOf(project.id).size, occurrences(form, "name=\"bucket\""),
            "the add carried a set that is not the board's",
        )
        assertTrue(form.contains("name=\"new_bucket\""), "the box takes no name")
        assertTrue(form.contains("name=\"src\" value=\"board\""), "the board's save does not say it came from the board")
        assertTrue(form.contains("data-live-target=\"#projectBoard\""), "the save redraws the wrong block")
        assertTrue(
            form.contains("data-live-extra=\"#taskLane,#projectLaneModal\""),
            "a save left the card popup or the form itself stale",
        )
    }

    @Test
    fun `the column popup renames a bucket and takes it off the board with its work`() {
        val lane = TaskService.addLane(project.id, "QAQC", "EMP0001", canManage = true).bucket!!
        val opened = render {
            projectDetailContent(
                manager, project, canManage = true,
                lane = LaneForm(laneId = lane.id),
            )
        }
        // Up, because the page the server rendered says it is — the same bargain the backlog's popup keeps.
        assertTrue(
            opened.contains("id=\"projectLaneModal\" style=\"display:block\""),
            "the ?laneedit page kept the popup shut",
        )
        assertTrue(opened.contains("value=\"QAQC\""), "the rename field did not start from the column's name")
        assertTrue(
            opened.contains("<form action=\"/app/projects/${project.id}/buckets/rename\""),
            "the popup posts no rename",
        )
        assertTrue(opened.contains("name=\"lane\" value=\"${lane.id}\""), "the rename names the wrong bucket")
        assertTrue(
            opened.contains("<form action=\"/app/projects/${project.id}/buckets/delete\""),
            "an empty bucket offered no removal",
        )
        assertTrue(opened.contains("name=\"bucket\" value=\"${lane.id}\""), "the removal names the wrong bucket")
        assertTrue(opened.contains("name=\"src\" value=\"board\""), "a board write did not say it came from the board")
        // The button is pressable because the column is empty, and the popup says so — the count is read off this
        // column, not off the board, because since Stage 75 these columns exist whether the project has cards or
        // not, so a column here can very much be holding one.
        val modal = opened.substringAfter("id=\"projectLaneModal\"")
            .substringBefore("<script src=\"/static/js/lane-modal.js\"")
        assertFalse(modal.contains("card filed in it"), "an empty column was described as holding work")
        assertTrue(modal.contains("Nothing is filed in it"), "the popup did not say why removal is safe here")
        // File a card in the column and the popup, the board and the rename all stay exactly where they were —
        // and since **Stage 81** so does `Remove bucket`: an occupied column comes off with its cards, and the
        // popup says where they go instead of greying the button. That is the prototype's third answer, ported:
        // the reader is told the consequence before the press, not stopped by it.
        card("Hold the QAQC gate", inBucket = lane.id)
        val held = render {
            projectDetailContent(manager, project, canManage = true, lane = LaneForm(laneId = lane.id))
        }
        assertTrue(
            held.contains("its 1 card goes to Archive"),
            "an occupied column did not say where its work goes",
        )
        assertTrue(
            held.contains("restore it from the 🗄 panel"),
            "the popup did not say where the card can be got back",
        )
        val del = held.substringAfter("<form action=\"/app/projects/${project.id}/buckets/delete\"")
            .substringBefore("</form>")
        assertFalse(del.contains("disabled=\"disabled\""), "a column with work in it was refused its removal")
        assertTrue(del.contains("data-confirm="), "the press does not ask first")
        assertTrue(del.contains("Its 1 card will be moved to Archive"), "the confirm hides what happens to the card")
        // The stylesheet has to agree with the attribute. The last lane is the one column whose button is shut,
        // and a shut button that paints like an open one would be the wrong picture on the screen. The red is
        // pinned in the same breath because base.css writes `button.submit`, which a lone `.danger-btn` loses to.
        val css = File("src/main/resources/static/css/app.css").readText()
        assertTrue(
            css.contains(".submit.danger-btn { border-color: #d93025; color: #fff; background: #d93025; }"),
            "the destructive button is not coloured, so it reads as a save",
        )
        assertTrue(
            css.contains(".submit.danger-btn:disabled"),
            "a disabled removal is drawn the way a pressable one is",
        )
        assertTrue(
            held.contains("<form action=\"/app/projects/${project.id}/buckets/rename\""),
            "an occupied column lost its rename with its removal",
        )
        // Down to one column, the same popup is the one place removal is still refused — and it says why.
        TaskService.lanesOf(project.id).filter { it.id != lane.id }.forEach {
            assertTrue(TaskService.removeLane(project.id, it.id, "EMP0001", canManage = true).ok)
        }
        val last = render {
            projectDetailContent(manager, project, canManage = true, lane = LaneForm(laneId = lane.id))
        }
        val lastDel = last.substringAfter("<form action=\"/app/projects/${project.id}/buckets/delete\"")
            .substringBefore("</form>")
        assertTrue(lastDel.contains("disabled=\"disabled\""), "the last column offered its own removal")
        assertTrue(last.contains("A board keeps at least one bucket"), "the last column did not say why")
        // …and a page for a reader who may not write lanes gets no popup at all, however the URL is built.
        val denied = render { projectDetailContent(viewer, project, canManage = false, lane = LaneForm(laneId = lane.id)) }
        assertFalse(denied.contains("projectLaneModal"), "a Viewer was handed the bucket popup open")
    }

    @Test
    fun `a refused name is printed at the field and a landed write is said over the board`() {
        // The user's standing rule: an error belongs under the box it came from. The board path is what makes that
        // need saying, because the one sentence in the URL could otherwise be both the board's headline and the
        // field's. The route's answer decides which, and [mn.murray.pts.Application.openingLaneForm] is where that
        // is read — so what these two pages pin is that the renderer honours the choice: field or board, never both.
        val refused = render {
            projectDetailContent(
                manager, project, canManage = true,
                bucketNote = "This project already has a bucket called QAQC.", bucketNoteIsError = true,
                lane = LaneForm(
                    typed = "QAQC", open = true,
                    nameError = "This project already has a bucket called QAQC.",
                ),
            )
        }
        assertTrue(
            refused.contains("class=\"field-err\">This project already has a bucket called QAQC.<"),
            "the refusal is not at the field",
        )
        assertFalse(refused.contains("bucket-said"), "the same refusal printed again over the board")
        assertTrue(refused.contains("value=\"QAQC\""), "the field lost what was typed")
        assertTrue(
            refused.contains("id=\"projectLaneModal\" style=\"display:block\""),
            "a refusal came back with the form shut",
        )
        // A write that landed has no field to answer, so its sentence is the board's.
        val landed = render {
            projectDetailContent(
                manager, project, canManage = true,
                bucketNote = "QAQC is on the board.", noteOnBoard = true,
            )
        }
        assertTrue(landed.contains("bucket-said ok"), "a landed write said nothing over the columns")
        assertFalse(landed.contains("field-err"), "the board's plain sentence was printed as a refusal")
        // A sentence that arrives while nothing is open. This is the page a bookmarked `?buckets=…&bucketnote=…`
        // comes back as, and since **Stage 79** the board is the sentence's only home left on this page: the popup
        // stays shut — a reader who has closed it is not dragged back into it — and no field error appears, because
        // there is no field up to put it under.
        val closed = render {
            projectDetailContent(
                manager, project, canManage = true,
                bucketNote = "This project already has a bucket called QAQC.", bucketNoteIsError = true,
                noteOnBoard = true,
            )
        }
        assertTrue(closed.contains("bucket-said err"), "a bucket sentence with no form up went unsaid")
        assertFalse(
            closed.contains("id=\"projectLaneModal\" style=\"display:block\""),
            "a bucket sentence raised the board popup",
        )
        assertFalse(closed.contains("field-err"), "a board sentence was printed as a field's refusal")
    }

    // --- the Archive (Stage 81, V18) ----------------------------------------------------------------

    @Test
    fun `the header offers the Archive to the board's writers and draws it shut`() {
        val html = render { projectDetailContent(manager, project, canManage = true) }
        assertTrue(html.contains("class=\"icon-btn archive-btn\""), "the header lost its Archive button")
        assertTrue(html.contains("aria-label=\"Show the archived tasks\""), "the button does not name its action")
        assertTrue(html.contains("name=\"archive\" value=\"1\""), "the button says nothing to the URL")
        val panel = archivePanel(html)
        assertFalse(panel.contains("display:block"), "a page with no ?archive=1 came back with the panel up")
        assertTrue(panel.contains("Archived tasks (0)"), "the panel does not count what is in it")
        assertTrue(panel.contains("No archived tasks."), "an empty archive left the reader guessing")
        // The panel's state is the URL's, like every popup here: a plain GET form with no key shuts it, so the
        // same click works with scripts off and is one more block swap with them on.
        assertTrue(
            panel.contains("<form action=\"/app/projects/${project.id}\" method=\"get\""),
            "the close is not a plain GET back to this page",
        )
        // A reader with no right to reshape the board is handed neither the button nor the panel.
        listOf(
            render { projectDetailContent(viewer, project, canManage = false) },
            render { projectDetailContent(viewer, project, canManage = false, archiveOpen = true) },
        ).forEach { denied ->
            assertFalse(denied.contains("archive-btn"), "a Viewer was offered the Archive")
            assertFalse(denied.contains("projectArchive"), "a Viewer was handed the panel")
            assertFalse(denied.contains("archive-modal.js"), "a Viewer was sent a script for a panel they lack")
        }
    }

    @Test
    fun `the archive panel prints a put-away card with where it came from and the two things it can do`() {
        val lane = TaskService.addLane(project.id, "QAQC", "EMP0001", canManage = true).bucket!!
        val cardId = card("Hold the QAQC gate", inBucket = lane.id)
        assertTrue(TaskService.archiveCard(cardId, "EMP0001", canManage = true))
        val html = render { projectDetailContent(manager, project, canManage = true, archiveOpen = true) }
        assertTrue(html.contains("id=\"projectArchive\" style=\"display:block\""), "?archive=1 did not open the panel")
        val panel = archivePanel(html)
        assertTrue(panel.contains("Archived tasks (1)"), "the title does not count the card")
        assertTrue(panel.contains("class=\"at-title\">Hold the QAQC gate<"), "the row does not name the card")
        assertTrue(
            panel.contains("S-01 · was in &quot;QAQC&quot;"),
            "the row does not say where the card came from",
        )
        // The prototype's two buttons, each its own POST — a form cannot live inside a form — and both swap the
        // panel and the columns behind it, because restoring puts a card back on the board.
        assertTrue(panel.contains("<form action=\"/app/projects/${project.id}/archive/restore\""), "no Restore")
        assertTrue(panel.contains("<form action=\"/app/projects/${project.id}/archive/delete\""), "no Delete")
        assertEquals(2, occurrences(panel, "name=\"taskId\" value=\"$cardId\""), "a row button names the wrong card")
        assertEquals(2, occurrences(panel, "data-live-extra=\"#projectBoard\""), "a row button leaves the board stale")
        assertTrue(panel.contains(">Restore<"))
        assertTrue(panel.contains(">Delete permanently<"))
        assertTrue(panel.contains("Permanently delete Hold the QAQC gate?"), "the permanent delete asks nothing")
        assertTrue(panel.contains("This cannot be undone."))
        // A column's removal is the other way in, and its row keeps the column's name even though the column is
        // gone — that is what the snapshot is for.
        assertTrue(TaskService.removeLane(project.id, lane.id, "EMP0001", canManage = true).ok)
        val after = render { projectDetailContent(manager, project, canManage = true, archiveOpen = true) }
        assertTrue(after.contains("was in &quot;QAQC&quot;"), "the archived card lost the column it came from")
        assertTrue(archivePanel(after).contains("Archived tasks (1)"), "the panel counted a card twice or not at all")
    }

    @Test
    fun `an archived card is off the board, its column's count and the page's own list`() {
        val lane = TaskService.addLane(project.id, "QAQC", "EMP0001", canManage = true).bucket!!
        val held = card("Hold the QAQC gate", inBucket = lane.id)
        val stayed = card("Sign the gate sheet", inBucket = lane.id)
        assertTrue(TaskService.archiveCard(held, "EMP0001", canManage = true))
        val html = render { projectDetailContent(manager, project, canManage = true) }
        // The board is the page's own list of cards, and an archived one is not on it; its column's count drops
        // with it, which is the figure the lane head prints. The panel is drawn closed further down the page, so
        // the board is everything before it.
        val board = html.substringBefore("id=\"projectArchive\"")
        assertFalse(board.contains(">Hold the QAQC gate<"), "an archived card stayed on the board")
        assertTrue(board.contains(">Sign the gate sheet<"), "archiving one card took its neighbour too")
        // The lane head's count reads the same filtered rows: QAQC holds one card now, not two.
        assertEquals(1, occurrences(board, "class=\"kcol-count\">1<"), "the column still counts the archived card")
        assertTrue(archivePanel(html).contains("Archived tasks (1)"))
        assertNotNull(stayed)
    }

    // --- the detail card's own layout ---------------------------------------------------------------

    @Test
    fun `the nine read-only fields are one row of caption-over-value cells, with no pair and no row of their own`() {
        // The read-only card is what a Viewer gets (Stage 60 gave management an editable strip instead), so this
        // layout test reads the page as a Viewer does.
        val html = render { projectDetailContent(viewer, project, canManage = false) }
        val strip = detailStrip(html)
        // Nine cells, all of them the same object management's header uses: an `.ef` div holding an `.ef-label`
        // caption and one value. Stage 71's promise is that there is no tenth shape on the row — no wrapper that
        // lets two fields travel together, because that wrapper is what made the second line look designed.
        assertEquals(9, occurrences(strip, "class=\"ef "), "the row no longer prints nine cells")
        assertEquals(9, occurrences(strip, "<span class=\"ef-label\">"), "a field lost its caption")
        // Eight plain values: the ninth cell is Progress, which carries a bar and a percentage rather than a
        // piece of text, and that is the one field the row cannot shorten.
        assertEquals(8, occurrences(strip, "class=\"detail-val\""), "a caption has no value beside it")
        assertFalse(strip.contains("detail-pair"), "the paired travel unit came back")
        assertFalse(
            File("src/main/resources/static/css/app.css").readText().contains("detail-pair"),
            "app.css still styles a pair that nothing prints",
        )
        // Same nine fields, same order, same figures: nothing was dropped or reordered to fit the row.
        val captions = Regex("<span class=\"ef-label\">([^<]*)</span>").findAll(strip).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf(
                "Project number", "Category", "Client", "Manager", "Start date", "End date", "Status",
                "Revenue", "Progress",
            ),
            captions,
            "the fields are not the nine the card has always printed, in order",
        )
        // Revenue and Progress are ordinary cells now — the two that used to fall onto the line below.
        assertTrue(
            strip.indexOf("<span class=\"ef-label\">Revenue</span>") > strip.indexOf(">Status<"),
            "Revenue no longer follows Status in the run",
        )
        assertTrue(
            strip.contains("id=\"project-progress\""),
            "the progress cell lost the id the live swap repaints",
        )
        // The bar is still there (Stage 26's measurement, and the Viewer's only one), filled from the same
        // figure and followed by the percentage the tests read.
        assertTrue(strip.contains("class=\"progress\""), "the read-only card lost its progress bar")
        assertTrue(strip.contains("class=\"detail-line\""), "bar and percentage are no longer one line")
        // Every value carries its own text as a `title`, so shortening one on the page never hides it.
        Regex("<span class=\"detail-val\"([^>]*)>").findAll(strip).toList().let { vals ->
            assertEquals(8, vals.count(), "a value stopped being a title-carrying span")
            vals.forEach { assertTrue(it.groupValues[1].contains("title="), "a value has no full-text tooltip") }
        }
    }

    @Test
    fun `the read-only row is weighted to hold nine cells on one line`() {
        // The line itself is CSS, and this is the whole point of the change, so the rules are pinned where they
        // live rather than assumed from the markup: Stage 60's lesson is that a row of fields wraps the moment
        // its cells carry a content-based flex-basis, so every cell here must be zero-basis and weighted.
        val css = File("src/main/resources/static/css/app.css").readText()
        val block = css.substringAfter("/* --- Stage 71:")
        assertTrue(
            Regex("""\.detail-strip \.ef \{ flex: [\d.]+ 1 0;""").containsMatchIn(block),
            "the read-only cells are not zero-basis, so a ninth one can be pushed onto a second line",
        )
        // Every cell in the block is zero-basis: a `W 1 0` share, never a `1 1 120px` fallback that gives a
        // ninth cell an intrinsic width to be pushed on.
        Regex("""\.detail-strip \.ef[^{]*\{ flex: ([\d.]+) 1 0;""").findAll(block).toList().let { weighted ->
            assertTrue(weighted.size >= 8, "only ${weighted.size} of the read-only cells carry a zero-basis share")
            weighted.forEach {
                assertTrue(
                    it.groupValues[1].toDoubleOrNull()?.let { w -> w > 0 && w <= 3 } ?: false,
                    "a read-only cell's share of `${it.groupValues[1]}` is not a weight",
                )
            }
        }
        // The widest shares go to the two long-text fields — the category label and a person's full name — and
        // the first cell answers management's `:first-child` rule with its own weight instead of inheriting the
        // description share, because a project number is the row's shortest text but its caption is the longest.
        assertTrue(block.contains(".detail-strip .ef.wide-ef { flex: 2 1 0; }"), "the Manager field lost its share")
        assertTrue(block.contains(".detail-strip .ef.cat-ef { flex: 2.75 1 0; }"), "the Category field lost its share")
        assertTrue(
            block.contains(".detail-strip .ef:first-child { flex: 1.45 1 0; }"),
            "a project number is being given management's widest share again",
        )
        // A value that outgrows its share is shortened, never wrapped: a wrapped field makes the row two lines
        // tall, which is the bug. The full text rides on the cell's `title` (asserted above).
        val valRule = block.substringAfter(".detail-strip .detail-val {").substringBefore("}")
        listOf("white-space: nowrap", "overflow: hidden", "text-overflow: ellipsis").forEach {
            assertTrue(valRule.contains(it), "the read-only value lost `$it`, so it can wrap the row again")
        }
        // The captions clip the same way, or a window that is merely narrow lets "PROJECT NUMBER" paint across
        // the cell next to it — nine short labels are the price of one line.
        assertTrue(
            block.contains(".detail-strip .ef-label { overflow: hidden; text-overflow: ellipsis; }"),
            "a caption can spill into its neighbour",
        )
        // The row is the SAME object management's header uses, and the strip still wraps only as a
        // narrow-window fallback inherited from it.
        assertTrue(css.contains(".edit-strip { display: flex;"), "the shared row is gone")
        assertTrue(
            block.contains(".detail-strip .progress { flex: 0 1 72px;"),
            "the bar is back at its full width, which is what overflowed the progress cell",
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
                    TaskService.openableBy(mine, "EMP0002", canManage = false)!!,
                    canEditAll = false,
                    viewerEmpNo = "EMP0002",
                ),
                BACK_PROJECT,
                project,
            )
        }
        // The plan belongs to the card's own reader: box and add line are here, the × is not (Stage 89 made
        // the owner a writer of their own plan; striking a line off it stayed management's).
        assertTrue(popup.contains("id=\"chk-"), "the owner's popup printed the plan with no box")
        assertTrue(popup.contains("checklist/add"), "the owner was refused a step on their own card")
        assertFalse(popup.contains("checklist/remove"), "the owner was offered to delete a step")
        assertTrue(popup.contains("0 of 1 done"), "the popup lost the card's own step count")
        assertTrue(popup.contains("Order the aggregate"), "the popup printed no step of this reader's card")
        // Stage 56: the colleague's card is now ON the page. **Stage 86** made it openable too — every card on a
        // shared project board is a click target — but what it opens for this Viewer is the read-only popup, not
        // the editor: the reader's own card carries the box, and the colleague's is drawn stopped.
        assertTrue(html.contains("Compact the sub-base"), "a colleague's card was hidden from the whole-project board")
        assertEquals(2, Regex("data-task-card=\"\\d+\"").findAll(html).count(),
            "a card on the shared board was not made clickable")
        assertTrue(html.contains("data-task-card=\"$theirs\""), "the colleague's card was not openable")
        // Only the reader's own card walks: the ✓ Done button hangs on the holder, not on the click target.
        assertEquals(1, Regex("kmove-btn").findAll(html).count(), "a colleague's card was given a move button")
        assertTrue(
            Regex("data-id=\"$mine\" data-status=").containsMatchIn(html),
            "the reader's own card lost its Done button",
        )
        // Reading a colleague's card — the popup every reader now gets. It is the same card, with nothing in it
        // to write: no save, no tick box, no *Add note*, and every field greyed.
        val viewing = render {
            taskFormModal(
                editFormOf(
                    TaskService.openableBy(theirs, "EMP0002", canManage = false)!!,
                    canEditAll = false,
                    viewerEmpNo = "EMP0002",
                ),
                BACK_PROJECT,
                project,
            )
        }
        assertFalse(viewing.contains("Save task"), "a stranger was offered a save")
        assertTrue(viewing.contains(">Close<"), "the read-only popup has no way out but the corner")
        assertFalse(viewing.contains("class=\"chk-toggle\""), "a stranger was offered a tick box")
        assertTrue(viewing.contains("Book the crew"), "a stranger could not read the plan")
        assertTrue(viewing.contains("chk-box locked"), "the stranger's plan is not drawn stopped")
        assertFalse(viewing.contains("name=\"body\""), "a stranger was offered the note box")
    }

    @Test
    fun `the projects board measures each project from its own cards`() {
        val bay = ProjectRepository.insert(
            code = "R-01", name = "Workshop bay rewiring", status = "In progress", progress = 40,
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
