package mn.murray.pts.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mn.murray.pts.domain.Permission
import mn.murray.pts.repository.RoleRepository
import mn.murray.pts.repository.UserRepository
import mn.murray.pts.security.Authorization
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * V21 moves an account's rights off the person and onto a role — and rewrites nothing else.
 *
 * Before Stage 110 the app resolved access from three ranks (`is_super_admin`, then the *permission level* of
 * the position the account was filed under, then its own `access` word). The user asked for the picture on
 * Access control instead: a named role holding any of thirteen chips, and **the role deciding everything** —
 * a position becoming a job title. So this migration has two jobs, and both are about rows a live database
 * already holds:
 *
 *  - seed the catalogue the picture shows (four roles, 13/13/9/1 chips, Employee marked default);
 *  - file every account already on file under the role that gives it *the rights it had yesterday*, so nobody
 *    gains or loses a right on the day this runs.
 *
 * Each case builds V1–V20, writes `team_members`, `positions` and `users` rows the way a live database holds
 * them, and then runs the real [Migrations.migrate] — a backfill's answer for a row cannot be checked against
 * the code that now reads it, only against the SQL that wrote it. The deletion case is the one that keeps
 * somebody's housekeeping from becoming a loss of people: `users.role_id` is `ON DELETE SET NULL`, so a role
 * taken off the catalogue hands its holders to the default instead of taking the holders with it.
 *
 * Nothing here touches `data/`: each test gets its own temporary SQLite file.
 */
class RoleMigrationTest {

    private fun poolFor(dbFile: File) =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"
                // The live pool opens with this too, and the two cascades V21 leans on — a role's chips going
                // with it, a holder falling back to null — only fire when the connection asks for them.
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            },
        )

    /** V1–[version] only: the schema a database has before V21 runs. */
    private fun migrateTo(ds: HikariDataSource, version: String) {
        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .target(version)
            .baselineOnMigrate(true)
            .load()
            .migrate()
    }

    private fun update(ds: HikariDataSource, sql: String) {
        ds.connection.use { conn -> conn.createStatement().use { it.executeUpdate(sql) } }
    }

    private fun query(ds: HikariDataSource, sql: String): List<List<String?>> =
        ds.connection.use { conn ->
            conn.createStatement().use { st ->
                val rs = st.executeQuery(sql)
                val width = rs.metaData.columnCount
                val out = mutableListOf<List<String?>>()
                while (rs.next()) out += (1..width).map { rs.getString(it) }
                out
            }
        }

    private fun one(ds: HikariDataSource, sql: String): String? = query(ds, sql).singleOrNull()?.single()

    /** Every account on the file, as `employee_number|role name` — nulls read as `none`. */
    private fun roleOfEach(ds: HikariDataSource): List<String> =
        query(
            ds,
            "SELECT u.employee_number, COALESCE(r.name, 'none') FROM users u " +
                "LEFT JOIN roles r ON r.id = u.role_id ORDER BY u.employee_number",
        ).map { it.joinToString("|") }

    /** A roster row, because `users.employee_number` is a foreign key onto `team_members`. */
    private fun insertPerson(ds: HikariDataSource, emp: String, name: String) {
        update(
            ds,
            "INSERT INTO team_members (employee_number, name, department, position) " +
                "VALUES ('$emp', '$name', 'Projects', 'Engineer')",
        )
    }

    private fun insertPosition(ds: HikariDataSource, name: String, level: String): Int {
        update(ds, "INSERT INTO positions (name, permission_level, description) VALUES ('$name', '$level', '')")
        return one(ds, "SELECT id FROM positions WHERE name = '$name'")!!.toInt()
    }

    /** An account in the shape a V20 database holds one: the rank trio, and nothing about a role. */
    private fun insertUser(
        ds: HikariDataSource,
        emp: String,
        name: String,
        access: String,
        positionId: Int?,
        superAdmin: Boolean = false,
    ) {
        insertPerson(ds, emp, name)
        update(
            ds,
            "INSERT INTO users (employee_number, name, password_hash, access, email, position_id, " +
                "is_super_admin, must_change_password, created_at, updated_at) VALUES (" +
                "'$emp', '$name', 'x', '$access', '$emp@murray.mn', ${positionId ?: "NULL"}, " +
                "${if (superAdmin) 1 else 0}, 0, '2026-02-02T02:02:02Z', '2026-02-02T02:02:02Z')",
        )
    }

    // --- the catalogue the picture shows ---------------------------------------------------------------

    @Test
    fun `the seeded catalogue is the picture's four roles, in its order, with its chip counts`(@TempDir dir: File) {
        val ds = poolFor(File(dir, "v20-empty.db"))
        try {
            migrateTo(ds, "20")
            Migrations.migrate(ds)

            assertEquals(
                listOf("1|Owner|1|0", "2|Administrator|2|0", "3|Project Manager|3|0", "4|Employee|4|1"),
                query(ds, "SELECT id, name, sort_order, is_default FROM roles ORDER BY sort_order, id")
                    .map { it.joinToString("|") },
                "the catalogue V21 seeds is not the picture's",
            )
            // The chip counts are the picture's own: the two top roles all thirteen, the Project Manager the
            // nine about work, and Employee the one thing it may read of a colleague.
            assertEquals(
                listOf("1|13", "2|13", "3|9", "4|1"),
                query(ds, "SELECT role_id, COUNT(*) FROM role_permissions GROUP BY role_id ORDER BY role_id")
                    .map { it.joinToString("|") },
                "a seeded role does not hold the chips the picture counts",
            )
            assertEquals(
                "Employee", one(ds, "SELECT name FROM roles WHERE is_default = 1"),
                "the role a new account starts in is not marked default",
            )
            // The four the Project Manager loses are exactly the ones that decide the app's own settings,
            // which is why the panels under the Team sheet moved with this migration.
            val managerChips = query(ds, "SELECT permission FROM role_permissions WHERE role_id = 3")
                .map { it.single() }.toSet()
            listOf(
                Permission.MANAGE_EMPLOYEES, Permission.MANAGE_PROJECT_TYPES,
                Permission.MANAGE_EMAIL_SETTINGS, Permission.MANAGE_ROLES,
            ).forEach { assertFalse(it.key in managerChips, "the Project Manager was seeded with $it") }
            assertEquals(9, Permission.entries.count { it.key in managerChips }, "nine chips is not nine keys the app knows")
        } finally {
            ds.close()
        }
    }

    @Test
    fun `a chip key the app cannot name grants nothing and costs nothing`(@TempDir dir: File) {
        val ds = poolFor(File(dir, "v20-unknown-chip.db"))
        try {
            migrateTo(ds, "20")
            Migrations.migrate(ds)
            insertUser(ds, "EMP0007", "Nyamaa", access = "Viewer", positionId = null)
            // The tolerance V1 gave `projects.status`, again: nothing CHECKs the column, and the app names the
            // permissions it knows. A row nobody edited by hand cannot appear; one that did must not become a
            // right, and must not take the chips the role really holds away with it.
            update(ds, "INSERT INTO role_permissions (role_id, permission) VALUES (4, 'delete_the_moon')")
            Database.connect(ds)
            val employee = RoleRepository.findByName("Employee")!!
            assertEquals(setOf(Permission.VIEW_TEAMMATES_TASKS), employee.permissions, "an unknown key cost the role its real chip")
            assertTrue(Authorization.canDo("EMP0007", Permission.VIEW_TEAMMATES_TASKS), "the known chip stopped working")
            assertEquals(13, RoleRepository.findByName("Owner")!!.permissions.size, "an unknown key widened a seeded role")
        } finally {
            ds.close()
        }
    }

    // --- yesterday's resolution, written onto today's rows ---------------------------------------------

    @Test
    fun `every account already on file keeps the rights it had yesterday`(@TempDir dir: File) {
        val ds = poolFor(File(dir, "v20-live.db"))
        try {
            migrateTo(ds, "20")
            val adminPos = insertPosition(ds, "Director", "Admin")
            val managerPos = insertPosition(ds, "Project Lead", "Manager")
            val workerPos = insertPosition(ds, "Engineer", "Viewer")

            insertUser(ds, "EMP0000", "Super Admin", access = "Viewer", positionId = null, superAdmin = true)
            // The two rows that decide the order of the backfill's CASE, written against the *old* resolution on
            // purpose: a position's level beat the account's own word, even when the word was the higher one.
            // V21 has to copy that answer, not the friendlier one — this is the migration that must not quietly
            // hand somebody a right or take one away.
            insertUser(ds, "EMP0001", "Boss", access = "Viewer", positionId = adminPos)
            insertUser(ds, "EMP0002", "Lead", access = "Admin", positionId = managerPos)
            insertUser(ds, "EMP0003", "Quiet", access = "Viewer", positionId = workerPos)
            insertUser(ds, "EMP0004", "ByWord", access = "Admin", positionId = null)
            insertUser(ds, "EMP0005", "Plain", access = "Manager", positionId = null)
            insertUser(ds, "EMP0006", "Blank", access = "", positionId = null)

            Migrations.migrate(ds)

            assertEquals(
                listOf(
                    "EMP0000|Owner", "EMP0001|Administrator", "EMP0002|Project Manager", "EMP0003|Employee",
                    "EMP0004|Administrator", "EMP0005|Project Manager", "EMP0006|Employee",
                ),
                roleOfEach(ds),
                "the backfill did not reproduce yesterday's resolution",
            )
            Database.connect(ds)
            // The same answer through the code that will now use it, so a row cannot be filed under one role
            // and read as another.
            assertEquals("Owner", Authorization.roleOf("EMP0000").name)
            assertTrue(Authorization.canDo("EMP0001", Permission.MANAGE_ROLES), "an Admin-level position lost its people rights")
            assertFalse(Authorization.canDo("EMP0002", Permission.MANAGE_ROLES), "a Manager-level position gained the roles chip")
            assertFalse(Authorization.canDo("EMP0003", Permission.CREATE_PROJECTS), "a Viewer-level position gained the board")
            assertTrue(Authorization.canDo("EMP0004", Permission.MANAGE_EMPLOYEES))
            assertTrue(Authorization.canDo("EMP0005", Permission.CREATE_PROJECTS))
            assertEquals(setOf(Permission.VIEW_TEAMMATES_TASKS), Authorization.roleOf("EMP0006").permissions)

            // And the ranks the migration left alone: it added a column, it did not rewrite a row. `access`
            // keeps the word it had — Stage 110 stops reading it, and a row an operator inspects by hand still
            // says what it said before.
            assertEquals(
                listOf(
                    "EMP0000|Viewer|NULL|1", "EMP0001|Viewer|$adminPos|0", "EMP0002|Admin|$managerPos|0",
                    "EMP0003|Viewer|$workerPos|0", "EMP0004|Admin|NULL|0", "EMP0005|Manager|NULL|0",
                    "EMP0006||NULL|0",
                ),
                query(
                    ds,
                    "SELECT employee_number, access, COALESCE(position_id, 'NULL'), is_super_admin " +
                        "FROM users ORDER BY employee_number",
                ).map { it.joinToString("|") },
                "V21 rewrote a column that was already there",
            )
            // `permission_level` stays in the catalog — the column is no longer read for rights, but the
            // operator can still see and edit it, and dropping it would throw away a value they typed.
            assertEquals("Admin", one(ds, "SELECT permission_level FROM positions WHERE name = 'Director'"))
        } finally {
            ds.close()
        }
    }

    // --- the column that saves a deletion from becoming a loss -----------------------------------------

    @Test
    fun `a role taken off the catalogue hands its holders back to the default`(@TempDir dir: File) {
        val ds = poolFor(File(dir, "v20-roles-delete.db"))
        try {
            migrateTo(ds, "20")
            insertUser(ds, "EMP0001", "Lead", access = "Manager", positionId = null)
            insertUser(ds, "EMP0002", "Deputy", access = "Manager", positionId = null)
            Migrations.migrate(ds)
            Database.connect(ds)
            assertEquals("Project Manager", Authorization.roleOf("EMP0001").name)

            // Somebody decides the catalogue is wrong and deletes a row. The people must survive it and land on
            // the default role rather than on nothing: `ON DELETE SET NULL` plus [RoleRepository.roleFor]'s
            // fallback, which is the pair that keeps a housekeeping click from locking the office out.
            update(ds, "DELETE FROM roles WHERE name = 'Project Manager'")
            assertEquals(2, one(ds, "SELECT COUNT(*) FROM users")!!.toInt(), "a role delete took the accounts with it")
            assertEquals(
                listOf("EMP0001|none", "EMP0002|none"), roleOfEach(ds),
                "the holders kept pointing at a row that is gone",
            )
            assertEquals(
                "0", one(ds, "SELECT COUNT(*) FROM role_permissions WHERE role_id = 3"),
                "a deleted role left its chips behind it",
            )
            assertNull(UserRepository.findByEmpNo("EMP0001")!!.roleId, "the read the app uses returned a stale id")
            assertEquals("Employee", Authorization.roleOf("EMP0001").name, "the holder was not read as the default role")
            assertFalse(Authorization.canDo("EMP0001", Permission.CREATE_PROJECTS), "a re-filed account kept a right it lost")
            assertEquals("Employee", RoleRepository.defaultRole().name, "the catalogue lost the row a null role falls back to")
        } finally {
            ds.close()
        }
    }

    @Test
    fun `an account added after the migration is filed under a role the same way`(@TempDir dir: File) {
        val ds = poolFor(File(dir, "v21-new.db"))
        try {
            migrateTo(ds, "20")
            Migrations.migrate(ds)
            insertPerson(ds, "EMP0007", "Nyamaa")
            Database.connect(ds)
            // The insert path the routes use, naming no role: the legacy word decides the row exactly the way
            // V21's backfill decided the migrated ones, so a new account and an old one cannot diverge.
            UserRepository.insert(
                employeeNumber = "EMP0007", name = "Nyamaa", passwordHash = "x", access = "Manager",
                email = "", positionId = null, isSuperAdmin = false, mustChangePassword = false,
            )
            assertEquals("Project Manager", RoleRepository.roleOf("EMP0007").name, "a new account did not get a role")
            assertTrue(UserRepository.findByEmpNo("EMP0007")!!.roleId!! > 0)

            RoleRepository.setRole("EMP0007", RoleRepository.findByName("Owner")!!.id)
            assertEquals("Owner", Authorization.roleOf("EMP0007").name)
            // `access` follows as a label — nothing authorizes off it — because a row read by an operator's SQL
            // or the old importer still has to say something true about the person.
            assertEquals("Admin", UserRepository.findByEmpNo("EMP0007")!!.access)
            assertTrue(Authorization.canDo("EMP0007", Permission.MANAGE_ROLES))
        } finally {
            ds.close()
        }
    }
}
