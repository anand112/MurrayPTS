package mn.murray.pts.service

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mn.murray.pts.db.Migrations
import mn.murray.pts.domain.DEFAULT_PROJECT_BUCKETS
import mn.murray.pts.domain.Member
import mn.murray.pts.domain.PROJECT_BUCKET_MAX
import mn.murray.pts.domain.PROJECT_TYPES
import mn.murray.pts.domain.Project
import mn.murray.pts.domain.migrateCode
import mn.murray.pts.domain.migrateType
import mn.murray.pts.domain.nextProjectCode
import mn.murray.pts.domain.codeMatchesType
import mn.murray.pts.domain.isValidProjectCode
import mn.murray.pts.repository.ProjectRepository
import mn.murray.pts.repository.TaskRepository
import mn.murray.pts.repository.TeamRepository
import mn.murray.pts.repository.TypeDefaultBucketRepository
import mn.murray.pts.seedOpeningThreeFor
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The create-project rules behind the modal, against a real SQLite file with the real Flyway
 * schema (V3 included) — the same way [DueDateAlertServiceTest] works.
 *
 * Each case asserts on the field the message is attached to, because that is the contract the
 * view depends on: a failure keeps the popup open and prints the sentence under its own input.
 */
class ProjectServiceTest {

    private lateinit var ds: HikariDataSource

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
        TeamRepository.upsert(Member("EMP0001", "Munkhtsetseg", "Operations", "Project Manager"))
        TeamRepository.upsert(Member("EMP0002", "Bat-Erdene", "Workshop", "Engineer"))
        // Stage 100: a create takes its board from its category's Settings list, and a category that lists
        // nothing cannot make a project at all. These cases were written against the board every project used to
        // open with, so each category is given those three first, the way Settings would name them — one test
        // below empties a category to prove the refusal.
        PROJECT_TYPES.forEach { seedOpeningThreeFor(it) }
    }

    @AfterEach
    fun tearDown() = ds.close()

    private fun draft(
        type: String = "Site projects",
        code: String = "S-01",
        name: String = "Haul road widening",
        client: String = "Oyu Tolgoi LLC",
        start: String = "2026-11-01",
        end: String = "2027-03-30",
        status: String = "",
        managerName: String = "Munkhtsetseg",
        revenue: String = "1,250,000",
        invoiced: String = "",
        newBuckets: List<String> = emptyList(),
    ) = ProjectDraft(
        type = type, code = code, name = name, client = client,
        start = start, end = end, status = status, managerName = managerName, revenue = revenue,
        invoiced = invoiced, newBuckets = newBuckets,
    )

    @Test
    fun `a complete draft is stored with its client, dates and revenue`() {
        val result = ProjectService.create(draft())
        assertNotNull(result.projectId, "expected success, got ${result.errors}")
        val saved = ProjectService.byId(result.projectId!!)!!
        assertEquals("S-01", saved.code)
        assertEquals("Haul road widening", saved.name)
        assertEquals("Oyu Tolgoi LLC", saved.client)
        assertEquals("2026-11-01", saved.start)
        // The form's "End date" is the stored `due`: the Calendar and the alert sweep read it.
        assertEquals("2027-03-30", saved.due)
        assertEquals(1_250_000.0, saved.revenue)
        assertEquals("Munkhtsetseg", saved.manager)
        assertEquals("EMP0001", TeamRepository.empNoByName()["Munkhtsetseg"])
    }

    @Test
    fun `status falls back to Not started when the form leaves it empty`() {
        val id = ProjectService.create(draft(status = "")).projectId
        assertNotNull(id)
        assertEquals("Not started", ProjectService.byId(id!!)!!.status)
    }

    @Test
    fun `a duplicate number is rejected under the code field and nothing is written`() {
        assertNotNull(ProjectService.create(draft()).projectId)
        val second = ProjectService.create(draft(name = "Second job", code = " s-01 "))
        assertNull(second.projectId)
        assertTrue(second.errors.containsKey(ProjectField.CODE), "expected a CODE error, got ${second.errors}")
        assertEquals(1, ProjectRepository.findAll().size, "a rejected create must not write a row")
    }

    @Test
    fun `a number that does not lead with the category letter is rejected`() {
        val result = ProjectService.create(draft(type = "Site projects", code = "M-07"))
        assertNull(result.projectId)
        assertTrue(result.errors.getValue(ProjectField.CODE).contains("S-"))
    }

    @Test
    fun `a malformed number is rejected`() {
        listOf("", "01", "S_01", "S-", "S-01/2026").forEach { bad ->
            val result = ProjectService.create(draft(code = bad))
            assertNull(result.projectId, "$bad should not be accepted")
            assertTrue(result.errors.containsKey(ProjectField.CODE), "$bad: ${result.errors}")
        }
    }

    @Test
    fun `an end date before the start date is rejected under the end field`() {
        val result = ProjectService.create(draft(start = "2027-03-01", end = "2026-12-01"))
        assertNull(result.projectId)
        assertTrue(result.errors.containsKey(ProjectField.END), "expected an END error, got ${result.errors}")
    }

    @Test
    fun `empty dates are fine and a malformed one is rejected under its own field`() {
        assertNotNull(ProjectService.create(draft(start = "", end = "", revenue = "")).projectId)
        assertTrue(ProjectService.create(draft(code = "S-05", start = "01/12/2026")).errors.containsKey(ProjectField.START))
        assertTrue(ProjectService.create(draft(code = "S-06", end = "13 October 2026")).errors.containsKey(ProjectField.END))
    }

    @Test
    fun `an unknown manager is rejected instead of silently dropped`() {
        val result = ProjectService.create(draft(managerName = "Someone Else"))
        assertNull(result.projectId)
        assertTrue(result.errors.containsKey(ProjectField.MANAGER))
    }

    @Test
    fun `revenue is optional, grouped input is accepted and nonsense is rejected`() {
        val spaced = ProjectService.create(draft(code = "S-02", revenue = "2 500 000"))
        assertNotNull(spaced.projectId)
        assertEquals(2_500_000.0, ProjectService.byId(spaced.projectId!!)!!.revenue)

        val text = ProjectService.create(draft(code = "S-03", revenue = "about a billion"))
        assertNull(text.projectId)
        assertTrue(text.errors.containsKey(ProjectField.REVENUE))

        val negative = ProjectService.create(draft(code = "S-04", revenue = "-5"))
        assertNull(negative.projectId, "negative revenue is not a typo we should store")
    }

    @Test
    fun `a blank description is rejected under the name field`() {
        val result = ProjectService.create(draft(name = "   "))
        assertNull(result.projectId)
        assertTrue(result.errors.containsKey(ProjectField.NAME))
    }

    @Test
    fun `an unknown category is rejected`() {
        val result = ProjectService.create(draft(type = "Everything else", code = "X-01"))
        assertNull(result.projectId)
        assertTrue(result.errors.containsKey(ProjectField.TYPE))
    }

    @Test
    fun `suggestions continue the highest number already used in that category`() {
        listOf("S-01", "S-02", "M-01").forEach { code ->
            ProjectRepository.insert(code, "$code job", "In progress", 10, null, "", categoryOf(code))
        }
        val suggested = ProjectService.suggestedCodes()
        assertEquals("S-03", suggested["Site projects"])
        assertEquals("M-02", suggested["Fabrication/Manufacturing"])
        assertEquals("O-01", suggested["Other"])
        // Every suggestion is itself a valid, non-clashing number for its category.
        suggested.forEach { (type, code) ->
            assertTrue(isValidProjectCode(code), "$code for $type")
            assertTrue(codeMatchesType(code, type), "$code for $type")
            assertFalse(code in ProjectRepository.existingCodes(), "$code already taken")
        }
    }

    // --- the lanes a new project starts with (V7, re-cut by V16) ---------------------------------------

    @Test
    fun `a new project is created with the three opening lanes, in the order the board draws them`() {
        val created = ProjectService.create(draft())
        assertNotNull(created.projectId, "expected success, got ${created.errors}")
        val id = created.projectId!!
        // The ask behind this: a new project is not an empty board somebody has to set up before they can
        // use it. Since Stage 100 the three arrive because this category's Settings list names them — not
        // because the code adds them — and they land in the order that list holds. Stage 75 calls them an opening
        // rather than a floor: every one of them is a lane this project's own manager can retitle or remove.
        assertEquals(DEFAULT_PROJECT_BUCKETS, TaskService.lanesOf(id).map { it.name })
        assertEquals((0..2).toList(), TaskService.lanesOf(id).map { it.position })
    }

    @Test
    fun `two projects are given two boards, not one shared list`() {
        val first = ProjectService.create(draft()).projectId!!
        val second = ProjectService.create(
            draft(code = "M-01", type = "Fabrication/Manufacturing", name = "Crusher liner supply")
        ).projectId!!
        assertTrue(TaskService.addLane(first, "Haul", "EMP0001", canManage = true).added)
        // The three it opened with first, then whatever this project's own manager added — a project's extra
        // lanes are appended, so a board is never re-cut underneath the cards that sit on it.
        assertEquals(DEFAULT_PROJECT_BUCKETS + "Haul", TaskService.lanesOf(first).map { it.name })
        assertEquals(DEFAULT_PROJECT_BUCKETS, TaskService.lanesOf(second).map { it.name })
        // Different rows, so a lane named on one board is not a column on the other — which is the whole
        // reason this is a per-project table rather than a project id on the Other tasks buckets.
        assertTrue(
            TaskService.lanesOf(first).map { it.id }
                .intersect(TaskService.lanesOf(second).map { it.id }.toSet())
                .isEmpty(),
            "two projects were handed the same lane rows",
        )
    }

    @Test
    fun `a project the popup refused leaves no lanes behind`() {
        val id = ProjectService.create(draft()).projectId!!
        val second = ProjectService.create(draft(code = "S-01", name = "Second job"))
        assertNull(second.projectId, "a duplicate number was written anyway")
        // One project on the board and exactly its three opening lanes in the table: a create the popup refused
        // must not leave an empty board for somebody else to trip over.
        assertEquals(1, ProjectRepository.findAll().size)
        assertEquals(DEFAULT_PROJECT_BUCKETS.size, TaskService.lanesOf(id).size)
    }

    // --- naming the board while the project is being created --------------------------------------------

    @Test
    fun `the create form's error keys are the fields the popup prints messages against`() {
        // Stage 32 put three lane inputs in this popup and the user asked for them back out on 2026-10-15. Stage 73
        // put the question back as a tick-list, Stage 92 took it out again, and Stage 98 took `ProjectField.BUCKETS`
        // with it. Stage 100 put the key back for the empty-category refusal alone, and **Stage 104 gave it an
        // input: the popup's Add bucket box.** Pinned here because `ProjectField` is the contract the modal prints
        // its messages against. INVOICED (Stage 60) joins the enum for the detail strip's save messages only; the
        // create popup builds its boxes field by field and deliberately offers no amount-invoiced box.
        assertEquals(
            listOf("TYPE", "CODE", "NAME", "CLIENT", "START", "END", "STATUS", "MANAGER", "REVENUE",
                "INVOICED", "BUCKETS"),
            ProjectField.entries.map { it.name },
        )
        val created = ProjectService.create(draft())
        assertNotNull(created.projectId, "expected success, got ${created.errors}")
        assertEquals(DEFAULT_PROJECT_BUCKETS, TaskService.lanesOf(created.projectId!!).map { it.name })
    }

    // --- the buckets a category gives a new project (catalogue V12, the Stage 98 rule) ------------------

    @Test
    fun `a new project opens with its category's whole Settings list, in that order`() {
        TypeDefaultBucketRepository.insert("Site projects", "Commissioning")
        TypeDefaultBucketRepository.insert("Site projects", "Snag list")
        // The create popup can add to the list since Stage 104, but a form that names nothing still gets the
        // category's rows whole: the list is not an offer to tick, it is the board. Every row lands, in list order,
        // at positions 0..4 — the same rows a board that already exists is given when Settings adds them.
        val id = ProjectService.create(draft(type = "Site projects", code = "S-10")).projectId!!
        assertEquals(
            DEFAULT_PROJECT_BUCKETS + listOf("Commissioning", "Snag list"),
            TaskService.lanesOf(id).map { it.name },
        )
        assertEquals((0..4).toList(), TaskService.lanesOf(id).map { it.position })
    }

    @Test
    fun `a create writes nothing to the catalogue`() {
        TypeDefaultBucketRepository.insert("Site projects", "Commissioning")
        ProjectService.create(draft(type = "Site projects", code = "S-10"))
        // A project inherits the category; it does not edit it — the list is still exactly what the setUp and this
        // test put there, with nothing added by the create.
        assertEquals(
            (DEFAULT_PROJECT_BUCKETS + "Commissioning"),
            TypeDefaultBucketRepository.of("Site projects").map { it.name },
        )
    }

    // --- Stage 104: what the create form itself names -----------------------------------------------------

    @Test
    fun `a bucket typed while creating lands after the category's list`() {
        TypeDefaultBucketRepository.insert("Site projects", "Commissioning", colourSlot = 2)
        val id = ProjectService.create(
            draft(type = "Site projects", code = "S-10", newBuckets = listOf("QAQC", "Dewatering"))
        ).projectId!!
        val board = TaskService.lanesOf(id)
        // Addition, never re-ordering: the Settings rows keep their places and the typed names follow them in the
        // order the form posted them — which is the order of the chips on the popup.
        assertEquals(DEFAULT_PROJECT_BUCKETS + listOf("Commissioning", "QAQC", "Dewatering"), board.map { it.name })
        assertEquals((0..5).toList(), board.map { it.position })
        // The typed column is in no catalogue to inherit a shade from, so it wears the hue of the position it took.
        assertEquals(2, board.first { it.name == "Commissioning" }.colourSlot)
        assertNull(board.first { it.name == "QAQC" }.colourSlot, "a typed bucket was given a colour it was never offered")
    }

    @Test
    fun `a bucket typed while creating is this board's and no other's`() {
        // The ask's own words: "this project only". So no `type_default_buckets` row is written and no sibling
        // project of the category grows a column — the reach Stage 77 gave a bucket typed on a live board with its
        // `also_type` tick left off, which is why this form prints no tick.
        val id = ProjectService.create(
            draft(type = "Site projects", code = "S-10", newBuckets = listOf("QAQC"))
        ).projectId!!
        assertEquals(DEFAULT_PROJECT_BUCKETS + "QAQC", TaskService.lanesOf(id).map { it.name })
        assertEquals(DEFAULT_PROJECT_BUCKETS, TypeDefaultBucketRepository.of("Site projects").map { it.name })
        // A second project of the same category, created after, does not see the first one's column.
        val sibling = ProjectService.create(
            draft(type = "Site projects", code = "S-11", name = "Second site")
        ).projectId!!
        assertEquals(DEFAULT_PROJECT_BUCKETS, TaskService.lanesOf(sibling).map { it.name })
    }

    @Test
    fun `a typed bucket the category already lists is refused rather than dropped`() {
        TypeDefaultBucketRepository.insert("Site projects", "Commissioning")
        val refused = ProjectService.create(
            draft(type = "Site projects", code = "S-10", newBuckets = listOf("commissioning"))
        )
        assertNull(refused.projectId, "a refused bucket write created a project")
        // The name is echoed as the reader typed it; the match that refused it ignores case.
        assertEquals(
            "commissioning is already one of site projects's buckets — this board gets it from Settings.",
            refused.errors[ProjectField.BUCKETS],
        )
        // seedBoard would have ignored the repeat in silence; here the reader watched a chip they added vanish.
        assertEquals(0, ProjectRepository.findAll().size, "a refused create wrote the project anyway")
    }

    @Test
    fun `a bucket typed twice on the same form is refused`() {
        val refused = ProjectService.create(
            draft(type = "Site projects", code = "S-10", newBuckets = listOf("QAQC", "qaqc"))
        )
        assertNull(refused.projectId)
        assertEquals("qaqc is on this board twice.", refused.errors[ProjectField.BUCKETS])
        val created = ProjectService.create(
            draft(type = "Site projects", code = "S-10", newBuckets = listOf("QAQC", "Dewatering"))
        )
        assertNotNull(created.projectId, "two different names were refused: ${created.errors}")
    }

    @Test
    fun `a bucket name too long for a column head is refused`() {
        // The same length rule the board's own add and Settings' add use, so one column head cannot be wider on
        // the create popup than it is anywhere else.
        val refused = ProjectService.create(
            draft(type = "Site projects", code = "S-10", newBuckets = listOf("a".repeat(PROJECT_BUCKET_MAX + 1)))
        )
        assertNull(refused.projectId)
        assertEquals("A bucket name is $PROJECT_BUCKET_MAX characters or fewer.", refused.errors[ProjectField.BUCKETS])
        // Blank names are what the box is when the reader never typed: no chip, nothing to refuse.
        val created = ProjectService.create(draft(type = "Site projects", code = "S-10", newBuckets = listOf("  ")))
        assertNotNull(created.projectId, "an empty box refused the create: ${created.errors}")
        assertEquals(DEFAULT_PROJECT_BUCKETS, TaskService.lanesOf(created.projectId!!).map { it.name })
    }

    @Test
    fun `a category with nothing in Settings is creatable once the form names a bucket`() {
        // Stage 100's flat refusal, narrowed: an empty category is still a board with nowhere to file work in, but
        // the popup's own box can fill it without a walk to Settings. (The setUp gave every category the three
        // phases; this one is emptied to prove both halves.)
        TypeDefaultBucketRepository.of("Fabrication/Manufacturing").forEach {
            TypeDefaultBucketRepository.delete(it.id)
        }
        val refused = ProjectService.create(
            draft(type = "Fabrication/Manufacturing", code = "M-01", name = "Crusher liner supply")
        )
        assertNull(refused.projectId)
        assertEquals(
            "This category has no buckets yet. Add one above, or list them in Settings under Project bucket settings.",
            refused.errors[ProjectField.BUCKETS],
        )
        // One name typed here is enough, and the board is exactly that name — not that name plus a trio, and not a
        // row added to the category on the way past.
        val id = ProjectService.create(
            draft(type = "Fabrication/Manufacturing", code = "M-01", name = "Crusher liner supply",
                newBuckets = listOf("Fabrication"))
        ).projectId!!
        assertEquals(listOf("Fabrication"), TaskService.lanesOf(id).map { it.name })
        assertEquals(listOf(0), TaskService.lanesOf(id).map { it.position })
        assertEquals(DEFAULT_PROJECT_BUCKETS, TypeDefaultBucketRepository.of("Site projects").map { it.name })
    }

    @Test
    fun `a bucket added to a category reaches the board of a project that already exists`() {
        val first = ProjectService.create(draft(type = "Site projects", code = "S-11")).projectId!!
        assertEquals(DEFAULT_PROJECT_BUCKETS, TaskService.lanesOf(first).map { it.name })
        // Stage 98: Settings' add is a column on every board of the category, not just an offer to the next one.
        val added = BucketDefaultsService.add("Site projects", "Commissioning", canManage = true)
        assertTrue(added.ok, added.message)
        assertEquals(DEFAULT_PROJECT_BUCKETS + "Commissioning", TaskService.lanesOf(first).map { it.name })
        val second = ProjectService.create(
            draft(type = "Site projects", code = "S-12", name = "Second site")
        ).projectId!!
        assertEquals(DEFAULT_PROJECT_BUCKETS + "Commissioning", TaskService.lanesOf(second).map { it.name })
    }

    private fun categoryOf(code: String): String = when (code.substringBefore('-')) {
        "S" -> "Site projects"
        "M" -> "Fabrication/Manufacturing"
        else -> "Other"
    }

    // --- the header strip's save (Stage 60) -----------------------------------------------------

    @Test
    fun `a good save rewrites the fields, keeps the progress and stores invoiced`() {
        val id = ProjectService.create(draft()).projectId!!
        ProjectRepository.setProgress(id, 62)   // progress is derived; an edit must never reset it
        val result = ProjectService.update(
            id,
            draft(name = "Haul road, phase two", client = "Turquoise Gold LLC",
                start = "2026-12-01", end = "2027-06-30", status = "Blocked",
                managerName = "Bat-Erdene", revenue = "3,000,000", invoiced = "750,000"),
            actorEmpNo = "EMP0001",
        )
        assertTrue(result.ok, "expected success, got ${result.errors}")
        val saved = ProjectService.byId(id)!!
        assertEquals("Haul road, phase two", saved.name)
        assertEquals("Turquoise Gold LLC", saved.client)
        assertEquals("2027-06-30", saved.due)
        assertEquals("Blocked", saved.status)
        assertEquals("Bat-Erdene", saved.manager)
        assertEquals(3_000_000.0, saved.revenue)
        assertEquals(750_000.0, saved.invoiced)
        assertEquals(62, saved.progress, "a save reset the derived progress")
    }

    @Test
    fun `an edit never touches the project number`() {
        val id = ProjectService.create(draft()).projectId!!
        assertTrue(ProjectService.update(id, draft(code = "S-99", name = "Renamed"), actorEmpNo = "EMP0001").ok)
        // The strip has no code field and the route never reads one; the stored number stands.
        assertEquals("S-01", ProjectService.byId(id)!!.code)
    }

    @Test
    fun `a refused save reports each field and writes nothing`() {
        val id = ProjectService.create(draft()).projectId!!
        val result = ProjectService.update(
            id,
            draft(type = "Everything else", name = "   ", start = "2027-03-01", end = "2026-12-01",
                managerName = "Nobody", revenue = "lots", invoiced = "-5"),
            actorEmpNo = "EMP0001",
        )
        assertFalse(result.ok)
        listOf(ProjectField.TYPE, ProjectField.NAME, ProjectField.END, ProjectField.MANAGER,
            ProjectField.REVENUE, ProjectField.INVOICED).forEach {
            assertTrue(result.errors.containsKey(it), "no message under $it: ${result.errors}")
        }
        assertEquals("Haul road widening", ProjectService.byId(id)!!.name, "a refused save wrote to the row")
    }

    // --- whole-project delete (Stage 60) --------------------------------------------------------

    @Test
    fun `a viewer cannot delete a project`() {
        val id = ProjectService.create(draft()).projectId!!
        assertFalse(ProjectService.delete(id, canManage = false), "a Viewer was allowed to delete")
        assertNotNull(ProjectService.byId(id), "the project went anyway")
    }

    @Test
    fun `management deleting a project takes its cards with it`() {
        val id = ProjectService.create(draft()).projectId!!
        val cardId = TaskRepository.insert(
            kind = "ASSIGNED", title = "Set out the haul road", projectId = id, due = "2027-01-20",
            priority = "Medium", assigneeEmpNo = "EMP0002", status = "Not started", bucketId = null, started = "",
        )
        assertTrue(ProjectService.delete(id, canManage = true))
        assertNull(ProjectService.byId(id), "the project survived its own delete")
        assertNull(TaskRepository.findById(cardId), "the card was left standing with no project")
    }

    @Test
    fun `deleting a project that is gone says so rather than pretending`() {
        assertFalse(ProjectService.delete(999_999, canManage = true))
    }
}

/**
 * Pure domain arithmetic around the V3 categories: old labels and old three-letter codes
 * translating forward, and the next-number rule that feeds the form's suggestion.
 */
class ProjectCategoryTest {

    private fun project(code: String, type: String) =
        Project(1, code, "x", "In progress", 0, "", "", type)

    @Test
    fun `pre-V3 labels map onto the nine categories`() {
        assertEquals("Fabrication/Manufacturing", migrateType("Manufacturing"))
        assertEquals("Repair/Maintenance/other services", migrateType("Repair/ Maintenance/ Other Services"))
        assertEquals("Labor supply", migrateType("Labour supply"))
        assertEquals("Site projects", migrateType("Site projects"))
        // A label we do not know is left alone rather than guessed into "Other".
        assertEquals("Warehouse overflow", migrateType("Warehouse overflow"))
    }

    @Test
    fun `pre-V3 code prefixes collapse to the category letter`() {
        assertEquals("S-01", migrateCode("SIT-01"))
        assertEquals("M-01", migrateCode("MFG-01"))
        assertEquals("R-02", migrateCode("rms-02"))
        assertEquals("L-02", migrateCode("LAB-02"))
        assertEquals("S-04", migrateCode("S-04"))
        assertEquals("CUSTOM-7", migrateCode("CUSTOM-7"))
    }

    @Test
    fun `the next code counts the highest number in the category across old and new shapes`() {
        val existing = listOf(project("SIT-04", "Site projects"), project("S-07", "Site projects"))
        assertEquals("S-08", nextProjectCode(existing, "Site projects"))
        assertEquals("E-01", nextProjectCode(existing, "Engineering"))
        // A code from another category never feeds this one's counter.
        assertEquals("M-01", nextProjectCode(listOf(project("S-01", "Site projects")), "Fabrication/Manufacturing"))
    }
}
