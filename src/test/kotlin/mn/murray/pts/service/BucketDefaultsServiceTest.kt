package mn.murray.pts.service

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mn.murray.pts.db.Migrations
import mn.murray.pts.domain.DEFAULT_PROJECT_BUCKETS
import mn.murray.pts.domain.PROJECT_BUCKET_MAX
import mn.murray.pts.domain.PROJECT_TYPES
import mn.murray.pts.repository.TypeDefaultBucketRepository
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
 * The rules behind Settings' *Bucket defaults* panel (Stage 61, V12): what a management account may add to a
 * category's set of extra lanes, and what is refused.
 *
 * The one rule that matters most is the one that protects Stage 48: a card is filed in one of the six standard
 * lanes or it is not on a project, so a default that *is* one of the six would put the same column on a board
 * twice. The rest are the ordinary shape of a name — not blank, not past a column head's width, not already in
 * this category — compared the same trimmed, case-insensitive way a live board compares its own duplicate lane.
 *
 * Every case runs against a real SQLite file with the real Flyway schema, the way [ProjectServiceTest] does.
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

    @Test
    fun `management adds a lane to a category and it reads back in board order`() {
        assertTrue(add("Site projects", "Commissioning").ok)
        assertTrue(add("Site projects", "Snag list").ok)
        // Extras number from six, because the six standard lanes hold 0..5 on a live board.
        assertEquals(listOf("Commissioning", "Snag list"), BucketDefaultsService.extrasOf("Site projects").map { it.name })
        assertEquals(listOf(6, 7), BucketDefaultsService.extrasOf("Site projects").map { it.position })
    }

    @Test
    fun `a non-manager is refused and nothing is written`() {
        val result = add("Site projects", "Commissioning", canManage = false)
        assertFalse(result.ok)
        assertTrue(result.message.contains("management", ignoreCase = true), result.message)
        assertTrue(BucketDefaultsService.extrasOf("Site projects").isEmpty(), "a refused add wrote a row")
    }

    @Test
    fun `a name that is one of the six is refused, because the floor is not an extra`() {
        // This is Stage 48's rule kept from the settings side: a card is filed in a standard lane or it is not
        // on a project, so offering one of the six as an *addition* would put the column on the board twice.
        DEFAULT_PROJECT_BUCKETS.forEach { standard ->
            val result = add("Site projects", standard)
            assertFalse(result.ok, "$standard slipped through as an extra")
            assertTrue(result.message.contains("every project board already starts with"), result.message)
        }
        // Compared trimmed and ignoring case, the way a live board compares a duplicate — so " site projects " is
        // the same refusal, not a second column that differs from the standard one only in spacing or capitals.
        assertFalse(add("Fabrication/Manufacturing", "  fabrication/manufacturing ").ok)
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
    }

    @Test
    fun `a stale or hand-typed category falls back to the first one`() {
        assertEquals(PROJECT_TYPES.first(), BucketDefaultsService.typeFor("  " + PROJECT_TYPES.first() + " "))
        assertEquals(PROJECT_TYPES.first(), BucketDefaultsService.typeFor("Not a category"))
        assertEquals(PROJECT_TYPES.first(), BucketDefaultsService.typeFor(null))
        // A valid category is kept as typed, trimmed — only an unknown one falls back.
        assertEquals("Site projects", BucketDefaultsService.typeFor("Site projects"))
        // extrasOf reads through the same fallback, so an unknown ?lanes= shows the first category rather than
        // an empty panel or a made-up one.
        add(PROJECT_TYPES.first(), "Commissioning")
        assertEquals(listOf("Commissioning"), BucketDefaultsService.extrasOf("Not a category").map { it.name })
    }

    @Test
    fun `removing a default takes it off the catalogue`() {
        val result = add("Site projects", "Commissioning")
        assertTrue(result.ok)
        val id = TypeDefaultBucketRepository.of("Site projects").single().id
        assertFalse(BucketDefaultsService.remove(id, canManage = false), "a non-manager removed a default")
        assertTrue(BucketDefaultsService.extrasOf("Site projects").isNotEmpty(), "a refused remove took the row")
        assertTrue(BucketDefaultsService.remove(id, canManage = true))
        assertTrue(BucketDefaultsService.extrasOf("Site projects").isEmpty(), "the row survived its own remove")
        // Removing an id that is not there says so rather than pretending.
        assertFalse(BucketDefaultsService.remove(id, canManage = true))
    }

    @Test
    fun `the V12 table starts empty so today's projects are unchanged until a default is added`() {
        PROJECT_TYPES.forEach { assertTrue(BucketDefaultsService.extrasOf(it).isEmpty(), "$it was seeded by the migration") }
        assertNull(TypeDefaultBucketRepository.find(1), "V12 wrote a row it was never supposed to")
    }
}
