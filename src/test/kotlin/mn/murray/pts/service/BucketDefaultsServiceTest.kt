package mn.murray.pts.service

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mn.murray.pts.db.Migrations
import mn.murray.pts.domain.DEFAULT_PROJECT_BUCKETS
import mn.murray.pts.domain.PROJECT_BUCKET_MAX
import mn.murray.pts.domain.PROJECT_TYPES
import mn.murray.pts.repository.ProjectBucketRepository
import mn.murray.pts.repository.TypeDefaultBucketRepository
import mn.murray.pts.seedOpeningThreeFor
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The rules behind Settings' *Project bucket settings* panel (Stage 61, V12): what a management account may list
 * for a category, and what is refused.
 *
 * **Since Stage 100 the list is the whole board.** A project opens with exactly these rows, in this order, and a
 * category that lists none cannot be created from — so the three phases are ordinary names here now: a category
 * that wants Engineering, Procurement and Production lists them, and [BucketDefaultsService.add] accepts one of
 * those words like any other. The rules left are the ordinary shape of a name — not blank, not past a column
 * head's width, not already in this category — compared the same trimmed, case-insensitive way a live board
 * compares its own duplicate lane. [BucketDefaultsService.nameProblem] is the one copy of those rules, shared with
 * a live board's own picker (see [mn.murray.pts.service.TaskServiceTest]).
 *
 * Every case runs against a real SQLite file with the real Flyway schema, the way [ProjectServiceTest] does.
 *
 * **Stage 98** added the second half of the panel's promise: a row is not only what the next project opens with,
 * it is the column itself. [BucketDefaultsService.add] walks the category's existing boards, so most of the cases
 * at the bottom of this class build real projects first — the assertion the user made is *"when add a new bucket
 * on project bucket settings its not adding on project"*, and a board is the only place that sentence can be
 * answered.
 */
class BucketDefaultsServiceTest {

    private lateinit var ds: HikariDataSource

    @BeforeEach
    fun setUp(@TempDir dir: File) {
        ds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${File(dir, "bucket-defaults.db").absolutePath}"
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            },
        )
        Migrations.migrate(ds)
        Database.connect(ds)
    }

    @AfterEach
    fun tearDown() = ds.close()

    private fun add(type: String, name: String, canManage: Boolean = true) =
        BucketDefaultsService.add(type, name, canManage)

    /**
     * A real board, cut the way the create route cuts one, so the fan-out has something to reach.
     *
     * Since Stage 100 a category with an empty list cannot make a project, so a test that wants a board gives its
     * category the three phases first — the board shape every project had before this stage, which keeps the
     * "three plus what was added" assertions below meaningful. A category that already has rows is left alone.
     */
    private fun project(type: String, code: String, name: String = "Haul road widening"): Int {
        if (TypeDefaultBucketRepository.of(type).isEmpty()) seedOpeningThreeFor(type)
        val result = ProjectService.create(ProjectDraft(type = type, code = code, name = name))
        return result.projectId ?: error("could not create $code: ${result.errors}")
    }

    @Test
    fun `management adds a lane to a category and it reads back in board order`() {
        assertTrue(add("Site projects", "Commissioning").ok)
        assertTrue(add("Site projects", "Snag list").ok)
        // A list numbers from 0 since Stage 100: the first row a category is given is its board's first column.
        assertEquals(listOf("Commissioning", "Snag list"), BucketDefaultsService.bucketsOf("Site projects").map { it.name })
        assertEquals(listOf(0, 1), BucketDefaultsService.bucketsOf("Site projects").map { it.position })
    }

    @Test
    fun `a non-manager is refused and nothing is written`() {
        val result = add("Site projects", "Commissioning", canManage = false)
        assertFalse(result.ok)
        assertTrue(result.message.contains("management", ignoreCase = true), result.message)
        assertTrue(BucketDefaultsService.bucketsOf("Site projects").isEmpty(), "a refused add wrote a row")
    }

    @Test
    fun `the three phases are ordinary bucket names since Stage 100`() {
        // The pedestal is gone. Before Stage 100 a category carried only the *extras* on top of a trio the code
        // wrote, so one of these names would have handed a board the same column twice and was refused here. Now
        // the list is the whole board, so a category that wants the three phases names them — and the refusal has
        // moved to the duplicate check, which is about the word, not its history.
        DEFAULT_PROJECT_BUCKETS.forEach { standard ->
            val result = add("Site projects", standard)
            assertTrue(result.ok, "$standard was refused: ${result.message}")
        }
        assertEquals(DEFAULT_PROJECT_BUCKETS, BucketDefaultsService.bucketsOf("Site projects").map { it.name })
        // Compared trimmed and ignoring case, the way a live board compares a duplicate.
        assertFalse(add("Site projects", "  produCTION ").ok)
        // A project of the category opens with exactly those three rows, at 0..2.
        val id = project("Site projects", "S-01")
        assertEquals(DEFAULT_PROJECT_BUCKETS, TaskService.lanesOf(id).map { it.name })
        assertEquals((0..2).toList(), TaskService.lanesOf(id).map { it.position })
    }

    @Test
    fun `a blank name, an over-long name and a case-insensitive repeat are each refused`() {
        assertFalse(add("Site projects", "   ").ok, "a blank lane name was accepted")
        assertFalse(add("Site projects", "x".repeat(PROJECT_BUCKET_MAX + 1)).ok, "a name past the column head's width was accepted")
        assertTrue(add("Site projects", "Commissioning").ok)
        val repeat = add("Site projects", "commissioning")
        assertFalse(repeat.ok, "a case-insensitive duplicate of an existing extra was accepted")
        assertTrue(repeat.message.contains("already adds"), repeat.message)
        // The repeat lands on *this* category only: another category is free to add the same word.
        assertTrue(add("Labor supply", "Commissioning").ok)
        // …and a bucket belongs to exactly the category it was chosen under, which is what the panel's add line
        // now says out loud. Both lists hold their own row, no category holds the other's, and a category nobody
        // named still lists nothing at all.
        assertEquals(listOf("Commissioning"), BucketDefaultsService.bucketsOf("Site projects").map { it.name })
        assertEquals(listOf("Commissioning"), BucketDefaultsService.bucketsOf("Labor supply").map { it.name })
        assertTrue(BucketDefaultsService.bucketsOf("Goods sales").isEmpty(), "a bucket leaked to an untouched category")
    }

    @Test
    fun `a stale or hand-typed category falls back to the first one`() {
        assertEquals(PROJECT_TYPES.first(), BucketDefaultsService.typeFor("  " + PROJECT_TYPES.first() + " "))
        assertEquals(PROJECT_TYPES.first(), BucketDefaultsService.typeFor("Not a category"))
        assertEquals(PROJECT_TYPES.first(), BucketDefaultsService.typeFor(null))
        // A valid category is kept as typed, trimmed — only an unknown one falls back.
        assertEquals("Site projects", BucketDefaultsService.typeFor("Site projects"))
        // bucketsOf reads through the same fallback, so an unknown ?lanes= shows the first category rather than
        // an empty panel or a made-up one.
        add(PROJECT_TYPES.first(), "Commissioning")
        assertEquals(listOf("Commissioning"), BucketDefaultsService.bucketsOf("Not a category").map { it.name })
    }

    @Test
    fun `removing a default takes it off the catalogue`() {
        val result = add("Site projects", "Commissioning")
        assertTrue(result.ok)
        val id = TypeDefaultBucketRepository.of("Site projects").single().id
        assertFalse(BucketDefaultsService.remove(id, canManage = false), "a non-manager removed a default")
        assertTrue(BucketDefaultsService.bucketsOf("Site projects").isNotEmpty(), "a refused remove took the row")
        assertTrue(BucketDefaultsService.remove(id, canManage = true))
        assertTrue(BucketDefaultsService.bucketsOf("Site projects").isEmpty(), "the row survived its own remove")
        // Removing an id that is not there says so rather than pretending.
        assertFalse(BucketDefaultsService.remove(id, canManage = true))
    }

    @Test
    fun `the V12 table starts empty so today's projects are unchanged until a bucket is added`() {
        PROJECT_TYPES.forEach { assertTrue(BucketDefaultsService.bucketsOf(it).isEmpty(), "$it was seeded by the migration") }
        assertNull(TypeDefaultBucketRepository.find(1), "V12 wrote a row it was never supposed to")
    }

    // --- Stage 98: a catalogue row is a column on every board of the category ----------------------

    @Test
    fun `adding a bucket puts the column on every existing board of that category and no other`() {
        val siteOne = project("Site projects", "S-01")
        val siteTwo = project("Site projects", "S-02", name = "Second site")
        val labor = project("Labor supply", "L-01", name = "Mine gate crew")
        assertEquals(DEFAULT_PROJECT_BUCKETS, TaskService.lanesOf(siteOne).map { it.name })

        val added = add("Site projects", "Commissioning")
        assertTrue(added.ok, added.message)

        // The board that already existed is the one the user was looking at when they said the add "not adding on
        // project", so it is the assertion this stage exists for.
        assertEquals(DEFAULT_PROJECT_BUCKETS + "Commissioning", TaskService.lanesOf(siteOne).map { it.name })
        assertEquals(DEFAULT_PROJECT_BUCKETS + "Commissioning", TaskService.lanesOf(siteTwo).map { it.name })
        // The fan-out follows the category, not the catalogue: another category's board is untouched.
        assertEquals(DEFAULT_PROJECT_BUCKETS, TaskService.lanesOf(labor).map { it.name }, "the add reached another category's board")
        // Appended last, the way every lane this app writes goes on a board: the three keep 0..2 and the new
        // column takes 3 with no gap.
        assertEquals((0..3).toList(), TaskService.lanesOf(siteOne).map { it.position })
        // A project created after the add opens with the same board, so the two orders cannot disagree.
        val siteThree = project("Site projects", "S-03", name = "Third site")
        assertEquals(DEFAULT_PROJECT_BUCKETS + "Commissioning", TaskService.lanesOf(siteThree).map { it.name })
    }

    @Test
    fun `a board that already holds the name in another case gets no second column`() {
        val id = project("Site projects", "S-01")
        // A manager made their own column by that word, in their own capitalisation.
        ProjectBucketRepository.insert(id, "commissioning")

        val added = add("Site projects", "Commissioning")
        assertTrue(added.ok, added.message)
        // No fourth-and-fifth column: the board keeps the lane it had, spelled the way its owner typed it.
        assertEquals(DEFAULT_PROJECT_BUCKETS + "commissioning", TaskService.lanesOf(id).map { it.name },
            "the board was handed a second column for a name it already carried")
        // The catalogue keeps its own spelling, so a board cut afterwards is given that one — the duplicate rule
        // is about the column, not about the word.
        val next = project("Site projects", "S-02", name = "Second site")
        assertEquals(DEFAULT_PROJECT_BUCKETS + "Commissioning", TaskService.lanesOf(next).map { it.name })
    }

    @Test
    fun `the receipt says how many boards took the column`() {
        // No board yet: the add promises the next project and claims nothing about a screen.
        val none = add("Site projects", "Commissioning")
        assertTrue(none.message.contains("every new Site projects project will open with it"), none.message)
        assertFalse(none.message.contains("gained the column"), none.message)

        project("Site projects", "S-01")
        val one = add("Site projects", "Snag list")
        assertTrue(one.message.contains("the one Site projects board without it has the column"), one.message)

        project("Site projects", "S-02", name = "Second site")
        val two = add("Site projects", "Handover pack")
        assertTrue(two.message.contains("2 Site projects boards gained the column"), two.message)
    }

    @Test
    fun `removing a catalogue row stops future boards opening with it and leaves every live column`() {
        val live = project("Site projects", "S-01")
        assertTrue(add("Site projects", "Commissioning").ok)
        assertEquals(DEFAULT_PROJECT_BUCKETS + "Commissioning", TaskService.lanesOf(live).map { it.name })

        val id = TypeDefaultBucketRepository.of("Site projects").first { it.name == "Commissioning" }.id
        assertTrue(BucketDefaultsService.remove(id, canManage = true))
        // The column the add put there stays: a removed default is a decision about the *next* project, exactly
        // as it was before this stage. (Deleting a lane from one project's board is that board's own popup.)
        assertEquals(DEFAULT_PROJECT_BUCKETS + "Commissioning", TaskService.lanesOf(live).map { it.name })
        val next = project("Site projects", "S-02", name = "Second site")
        assertEquals(DEFAULT_PROJECT_BUCKETS, TaskService.lanesOf(next).map { it.name }, "a removed row still seeded a new board")
    }
}
