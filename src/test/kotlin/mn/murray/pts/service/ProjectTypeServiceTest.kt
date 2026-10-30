package mn.murray.pts.service

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mn.murray.pts.db.Migrations
import mn.murray.pts.domain.DEFAULT_PROJECT_BUCKETS
import mn.murray.pts.domain.PROJECT_TYPES
import mn.murray.pts.domain.SHIPPED_PROJECT_TYPES
import mn.murray.pts.domain.TYPE_PREFIX
import mn.murray.pts.domain.Member
import mn.murray.pts.domain.codeMatchesType
import mn.murray.pts.domain.installProjectTypes
import mn.murray.pts.domain.resetProjectTypes
import mn.murray.pts.repository.ProjectRepository
import mn.murray.pts.repository.TeamRepository
import mn.murray.pts.repository.TypeDefaultBucketRepository
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

/**
 * The rules behind Settings' *Project types* panel (Stage 63, V14), against a real SQLite file with the real
 * Flyway schema — the same way [ProjectServiceTest] and [BucketDefaultsServiceTest] work.
 *
 * Two guarantees carry the whole design and every case below aims at one of them:
 *
 *  * **A letter is issued once and never reused.** It is the first token of every project number filed under
 *    the category, so a second category led by `S` would hand out `S-04` twice. That is why `add` refuses a
 *    letter already held and `rename` cannot change one.
 *  * **A label is what the rest of the database stores.** `projects.type` and `type_default_buckets.type`
 *    hold the words, not the letter, so a rename has to carry them across in the same write or the projects
 *    filed under the old words fall out of every panel that groups by category. A rename that lands therefore
 *    leaves the numbers exactly as they were and changes what they read as.
 *
 * The catalogue is published into the domain as a process-wide view (`PROJECT_TYPES`), so [tearDown] resets
 * it: a test that added a category must not leave the next class looking at it.
 */
class ProjectTypeServiceTest {

    private lateinit var ds: HikariDataSource

    @BeforeEach
    fun setUp(@TempDir dir: File) {
        ds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${File(dir, "project-types.db").absolutePath}"
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            },
        )
        Migrations.migrate(ds)
        Database.connect(ds)
        TeamRepository.upsert(Member("EMP0001", "Munkhtsetseg", "Operations", "Project Manager"))
    }

    @AfterEach
    fun tearDown() {
        resetProjectTypes()
        ds.close()
    }

    /** One project, filed under a category and coded the way the create form would code it. */
    private fun project(type: String, code: String, name: String) {
        // Stage 100: a category with no buckets cannot make a project, so the category is given the legacy trio
        // before the create below.
        if (mn.murray.pts.repository.TypeDefaultBucketRepository.of(type).isEmpty()) {
            mn.murray.pts.seedOpeningThreeFor(type)
        }
        val result = ProjectService.create(
            ProjectDraft(
                type = type, code = code, name = name, client = "Oyu Tolgoi LLC",
                start = "2026-11-01", end = "2027-03-30", status = "", managerName = "Munkhtsetseg",
                revenue = "1,000,000", invoiced = "",
            ),
        )
        assertNotNull(result.projectId, "the project did not save: ${result.errors}")
    }

    // --- reading ------------------------------------------------------------------------------------------

    @Test
    fun `the nine categories come off the table, and the domain's views read the same list`() {
        val rows = ProjectTypeService.list()
        assertEquals(SHIPPED_PROJECT_TYPES.map { it.code }, rows.map { it.code }, "V14 did not seed the shipped nine in order")
        assertEquals(SHIPPED_PROJECT_TYPES.map { it.name }, rows.map { it.name }, "a seeded category is not the one the app shipped")
        // Reading the table is what republishes it, so the picker and the panel cannot be seen to disagree.
        assertEquals(rows.map { it.name }, PROJECT_TYPES)
        assertEquals("S", TYPE_PREFIX["Site projects"])
    }

    // --- adding -------------------------------------------------------------------------------------------

    @Test
    fun `management adds a category and it joins every list the app builds from the catalogue`() {
        val result = ProjectTypeService.add("p", "  Parts supply  ", canManage = true)
        assertTrue(result.ok, result.message)
        // The letter is upper-cased and the name trimmed on the way in, because both are read back out of a
        // hand-typed form and a `p` would never match a project number's `[A-Z]` head.
        val added = ProjectTypeService.list().last()
        assertEquals("P", added.code)
        assertEquals("Parts supply", added.name)
        assertTrue(PROJECT_TYPES.contains("Parts supply"), "the create picker did not gain the category")
        assertEquals("P", TYPE_PREFIX["Parts supply"], "the letter map did not gain the category")
        // A category is only real once a project can be coded under it.
        assertTrue(codeMatchesType("P-01", "Parts supply"))
        assertFalse(codeMatchesType("P-01", "Site projects"))
    }

    @Test
    fun `a refused category writes nothing and says why`() {
        val cases = listOf(
            Triple("S", "Shutdown works", "S already leads"),
            Triple("", "No letter", "one character"),
            Triple("PP", "Two letters", "one character"),
            Triple("1", "A digit", "one character"),
            Triple("P", "", "Enter a category name."),
            Triple("P", "   ", "Enter a category name."),
            Triple("P", "  site PROJECTS ", "already a category called"),
            Triple("P", "A".repeat(61), "60 characters or fewer"),
        )
        cases.forEach { (code, name, why) ->
            val result = ProjectTypeService.add(code, name, canManage = true)
            assertFalse(result.ok, "$code/$name should have been refused")
            assertTrue(result.message.contains(why), "$code/$name said \"${result.message}\", not \"$why\"")
        }
        assertEquals(9, ProjectTypeService.list().size, "a refused category still wrote a row")
        // The right is checked again in the service, so a caller that skipped the route cannot write either.
        val blocked = ProjectTypeService.add("P", "Parts supply", canManage = false)
        assertFalse(blocked.ok)
        assertTrue(blocked.message.contains("Only management"))
        assertEquals(9, ProjectTypeService.list().size, "a non-manager wrote a category")
    }

    // --- renaming -----------------------------------------------------------------------------------------

    @Test
    fun `a rename carries the label across the projects and the lane defaults, and leaves the letter alone`() {
        project("Site projects", "S-01", "Haul road widening")
        TypeDefaultBucketRepository.insert("Site projects", "Commissioning")
        val result = ProjectTypeService.rename("Site projects", "Site works", canManage = true)
        assertTrue(result.ok, result.message)
        // The answer counts what moved, because that is what a rename actually costs.
        assertTrue(result.message.contains("1 project now reads as Site works."), result.message)
        assertTrue(result.message.contains("S still leads"), result.message)
        assertEquals(listOf("S-01"), ProjectRepository.findByType("Site works").map { it.code }, "the number changed")
        assertTrue(ProjectRepository.findByType("Site projects").isEmpty(), "a project kept the old label")
        assertEquals(
            DEFAULT_PROJECT_BUCKETS + "Commissioning",
            TypeDefaultBucketRepository.of("Site works").map { it.name },
            "the category's default lanes were left behind",
        )
        // And the catalogue is the new words under the same letter, which is what every picker now prints.
        assertFalse(PROJECT_TYPES.contains("Site projects"))
        assertTrue(PROJECT_TYPES.contains("Site works"))
        assertEquals("S", TYPE_PREFIX["Site works"])
    }

    @Test
    fun `a rename is refused when the name is taken, and the same name back is not a write`() {
        val taken = ProjectTypeService.rename("Site projects", "Other", canManage = true)
        assertFalse(taken.ok)
        assertTrue(taken.message.contains("already a category called"), taken.message)
        assertEquals("S", TYPE_PREFIX["Site projects"], "a refused rename moved the letter")
        val blank = ProjectTypeService.rename("Site projects", "   ", canManage = true)
        assertFalse(blank.ok)
        assertTrue(blank.message.contains("Enter a category name."), blank.message)
        val missing = ProjectTypeService.rename("No such category", "Anything", canManage = true)
        assertFalse(missing.ok)
        assertTrue(missing.message.contains("There is no category called"), missing.message)
        assertFalse(ProjectTypeService.rename("Site projects", "Site projects", canManage = false).ok, "a non-manager renamed a category")
        // Naming a category what it is already called answers as a success without a write: the panel says so
        // rather than reporting a rename that changed nothing.
        val same = ProjectTypeService.rename("Site projects", " Site projects ", canManage = true)
        assertTrue(same.ok, same.message)
        assertTrue(same.message.contains("is unchanged."), same.message)
        assertEquals(9, ProjectTypeService.list().size)
    }

    // --- retiring -----------------------------------------------------------------------------------------

    @Test
    fun `a category with projects under it cannot be retired, and an empty one can`() {
        project("Other", "O-01", "Workshop refurbishment")
        val held = ProjectTypeService.remove("O", canManage = true)
        assertFalse(held.ok)
        assertTrue(held.message.contains("1 project is filed under Other."), held.message)
        assertTrue(held.message.contains("Move them to another category first"), held.message)
        assertTrue(PROJECT_TYPES.contains("Other"), "a refused retirement took the category off the list")
        ProjectService.delete(ProjectRepository.findByType("Other").single().id, canManage = true)
        val gone = ProjectTypeService.remove("O", canManage = true)
        assertTrue(gone.ok, gone.message)
        assertFalse(PROJECT_TYPES.contains("Other"), "the retired category is still a picker option")
        assertEquals(8, ProjectTypeService.list().size)
    }

    @Test
    fun `retiring a category takes its default lanes with it, and an unknown letter is simply not there`() {
        assertTrue(ProjectTypeService.add("P", "Parts supply", canManage = true).ok)
        TypeDefaultBucketRepository.insert("Parts supply", "QAQC")
        assertTrue(ProjectTypeService.remove("P", canManage = true).ok)
        assertTrue(TypeDefaultBucketRepository.of("Parts supply").isEmpty(), "a default outlived the category holding it")
        val missing = ProjectTypeService.remove("Z", canManage = true)
        assertFalse(missing.ok)
        assertTrue(missing.message.contains("no longer on the list"), missing.message)
        assertFalse(ProjectTypeService.remove("L", canManage = false).ok, "a non-manager retired a category")
        assertTrue(PROJECT_TYPES.contains("Labor supply"), "a refused retirement moved a category")
    }

    // --- the published view -------------------------------------------------------------------------------

    @Test
    fun `an empty catalogue falls back to the shipped nine rather than leaving the app with no category`() {
        // `installProjectTypes` is what a read of the table does; a table that cannot answer — no row yet, or
        // a database that is not up — must not leave the create form with an empty picker.
        installProjectTypes(emptyList())
        assertEquals(SHIPPED_PROJECT_TYPES.map { it.name }, PROJECT_TYPES)
        resetProjectTypes()
        assertEquals(SHIPPED_PROJECT_TYPES.map { it.name }, PROJECT_TYPES)
    }
}
