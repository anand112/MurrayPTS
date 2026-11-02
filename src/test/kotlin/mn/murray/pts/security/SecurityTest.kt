package mn.murray.pts.security

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mn.murray.pts.db.Migrations
import mn.murray.pts.domain.AccessRoles
import mn.murray.pts.domain.Member
import mn.murray.pts.domain.Permission
import mn.murray.pts.repository.RoleRepository
import mn.murray.pts.repository.TeamRepository
import mn.murray.pts.repository.UserRepository
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
 * The thirteen chips themselves (Stage 110) — the pure part, with no database under it.
 *
 * The picture the user attached to Access control is the specification: thirteen named permissions, in that
 * order, with those words. [Permission.key] is what a row, a form and a URL carry, so a rename of the printed
 * label is a one-line change; [Permission.ofAll] is what turns a POST's tick boxes into a set, and the two
 * things it must never do are invent a permission nobody ticked and keep one the app cannot name.
 */
class PermissionTest {

    @Test
    fun `there are thirteen permissions, with the picture's own words and keys`() {
        assertEquals(13, Permission.entries.size)
        assertEquals(
            listOf(
                "Create projects", "Edit project details", "Delete projects", "Manage buckets",
                "Edit any task", "Assign tasks to others", "View teammates' tasks", "View Forecasts tab",
                "Edit forecast amounts", "Manage employees", "Manage project types",
                "Manage email settings", "Manage roles & permissions",
            ),
            Permission.entries.map { it.label },
            "a permission's printed word drifted from the picture",
        )
        assertEquals(
            listOf(
                "create_projects", "edit_project_details", "delete_projects", "manage_buckets",
                "edit_any_task", "assign_tasks", "view_teammates_tasks", "view_forecasts",
                "edit_forecast_amounts", "manage_employees", "manage_project_types",
                "manage_email_settings", "manage_roles",
            ),
            Permission.entries.map { it.key },
            "a permission's key changed — that is the value a role row and a form field carry",
        )
        assertEquals(
            Permission.entries.toSet(),
            Permission.ofAll(Permission.entries.map { it.key }),
            "a key the enum printed was not one it could read back",
        )
    }

    @Test
    fun `a form's ticks parse case- and space-insensitively and the rest is dropped`() {
        assertEquals(Permission.MANAGE_ROLES, Permission.of("  manage_roles "))
        assertEquals(Permission.MANAGE_ROLES, Permission.of("MANAGE_ROLES"))
        assertNull(Permission.of("can_do_everything"))
        assertNull(Permission.of(null))
        assertNull(Permission.of(""))
        assertEquals(
            setOf(Permission.CREATE_PROJECTS),
            Permission.ofAll(listOf("create_projects", "nonsense", "", "create_projects")),
            "ofAll kept a word the app cannot name, or repeated one it did",
        )
        assertEquals(emptySet<Permission>(), Permission.ofAll(emptyList()))
    }
}

/**
 * Who may do what, now that a **role** answers the question (Stage 110).
 *
 * Three ranks — Viewer < Manager < Admin — used to be resolved per account from the super-admin flag, then the
 * linked position's permission level, then the `access` word. That ladder is gone: the database carries the
 * picture's catalogue and every account is filed under one role, so these tests run against a real migrated
 * file rather than against a hand-built [mn.murray.pts.domain.User]. Five rules are the ones the app now trusts:
 *
 *  * **The seeded four reproduce the ladder** — thirteen chips for Owner and Administrator, nine for Project
 *    Manager, one for Employee — which is what makes the day this ships a non-event for everybody on the roster.
 *  * **The role wins, and the position's level does not** — an account whose `access` word says Viewer and whose
 *    role is Owner holds *Manage roles & permissions*. A job title is a job title.
 *  * **A custom role's chips are exactly its power** — one tick means one route, which is the whole reason the
 *    routes ask for a chip rather than for the page-level convenience flags.
 *  * **A rename or a re-tick moves everybody at once**, because the rights are read from the role on every
 *    request and are not copied onto the accounts.
 *  * **"No role" is neither "no rights" nor "all rights"** — it is the default role, so a null column cannot
 *    lock the last account out of the app.
 */
class AuthorizationTest {

    private lateinit var ds: HikariDataSource

    @BeforeEach
    fun setUp(@TempDir dir: File) {
        ds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${File(dir, "roles.db").absolutePath}"
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            },
        )
        Migrations.migrate(ds)
        Database.connect(ds)
    }

    @AfterEach
    fun tearDown() = ds.close()

    private fun role(name: String) = RoleRepository.findByName(name)!!

    /** A person first, then their account — `users.employee_number` is a foreign key onto the roster. */
    private fun account(employeeNumber: String, access: String, roleId: Int?, superAdmin: Boolean = false) {
        TeamRepository.upsert(Member(employeeNumber, "Holder $employeeNumber", "Projects", "Tester"))
        UserRepository.insert(
            employeeNumber = employeeNumber, name = "Holder $employeeNumber", passwordHash = "x",
            access = access, email = "holder@murray.mn", positionId = null, isSuperAdmin = superAdmin,
            mustChangePassword = false, roleId = roleId,
        )
    }

    private fun setRoleColumn(employeeNumber: String, roleId: Int?) {
        ds.connection.use { conn ->
            conn.prepareStatement("UPDATE users SET role_id = ? WHERE employee_number = ?").use { st ->
                st.setObject(1, roleId)
                st.setString(2, employeeNumber)
                st.executeUpdate()
            }
        }
    }

    @Test
    fun `the catalogue is the picture's four roles, in its order`() {
        assertEquals(
            listOf(AccessRoles.OWNER, AccessRoles.ADMINISTRATOR, AccessRoles.PROJECT_MANAGER, AccessRoles.EMPLOYEE),
            RoleRepository.findAll().map { it.name },
            "the roles table is not in the order the sheet prints it",
        )
        assertEquals(
            listOf(13, 13, 9, 1),
            RoleRepository.findAll().map { it.permissions.size },
            "a seeded role no longer holds the chips the picture shows",
        )
        assertEquals(
            Permission.entries.toSet(),
            role(AccessRoles.OWNER).permissions,
            "Owner does not hold every permission",
        )
        assertEquals(role(AccessRoles.OWNER).permissions, role(AccessRoles.ADMINISTRATOR).permissions)
        assertEquals(setOf(Permission.VIEW_TEAMMATES_TASKS), role(AccessRoles.EMPLOYEE).permissions)
        assertTrue(role(AccessRoles.EMPLOYEE).isDefault, "Employee is the role a new account starts in")
        assertEquals(role(AccessRoles.EMPLOYEE).id, RoleRepository.defaultRole().id)
    }

    @Test
    fun `the project manager keeps the work and loses the people`() {
        val manager = role(AccessRoles.PROJECT_MANAGER)
        listOf(
            Permission.CREATE_PROJECTS, Permission.EDIT_PROJECT_DETAILS, Permission.DELETE_PROJECTS,
            Permission.MANAGE_BUCKETS, Permission.EDIT_ANY_TASK, Permission.ASSIGN_TASKS,
            Permission.VIEW_TEAMMATES_TASKS, Permission.VIEW_FORECASTS, Permission.EDIT_FORECAST_AMOUNTS,
        ).forEach { assertTrue(manager.may(it), "Project Manager lost $it") }
        listOf(
            Permission.MANAGE_EMPLOYEES, Permission.MANAGE_PROJECT_TYPES,
            Permission.MANAGE_EMAIL_SETTINGS, Permission.MANAGE_ROLES,
        ).forEach { assertFalse(manager.may(it), "Project Manager gained $it") }
    }

    @Test
    fun `a role decides the rights, not the account's old access word or its position`() {
        // Viewer in the column that no longer decides anything, Owner in the role that does.
        account("EMP9001", "Viewer", role(AccessRoles.OWNER).id)
        assertTrue(Authorization.canDo("EMP9001", Permission.MANAGE_ROLES))
        assertTrue(Authorization.canManage("EMP9001"))
        assertTrue(Authorization.canAdmin("EMP9001"))

        // The other way round: an Admin word holding the default role gets the default role's one chip.
        account("EMP9002", "Admin", role(AccessRoles.EMPLOYEE).id)
        assertEquals(setOf(Permission.VIEW_TEAMMATES_TASKS), Authorization.permissionsOf("EMP9002"))
        assertFalse(Authorization.canAdmin("EMP9002"))
        assertFalse(Authorization.canManage("EMP9002"))
    }

    @Test
    fun `an account with no role id at all falls back to the default role`() {
        // Inserted with no role named, then the column cleared by hand — the shape V21's SET NULL leaves behind
        // when a role somebody deleted was holding them.
        account("EMP9003", "Manager", role(AccessRoles.PROJECT_MANAGER).id)
        setRoleColumn("EMP9003", null)
        assertNull(UserRepository.findByEmpNo("EMP9003")!!.roleId)
        assertEquals(AccessRoles.EMPLOYEE, Authorization.roleOf("EMP9003").name)
        assertTrue(Authorization.canDo("EMP9003", Permission.VIEW_TEAMMATES_TASKS))
        assertFalse(Authorization.canDo("EMP9003", Permission.EDIT_ANY_TASK))

        // An account that does not exist is the same answer: no rights beyond the default, and nothing thrown.
        assertEquals(AccessRoles.EMPLOYEE, Authorization.roleOf("EMP9999").name)
        assertFalse(Authorization.canAdmin("EMP9999"))
    }

    @Test
    fun `a custom role holds exactly the chips it was ticked with`() {
        val widened = setOf(Permission.ASSIGN_TASKS, Permission.VIEW_TEAMMATES_TASKS)
        val created = RoleRepository.create("Site Supervisor", widened)
        assertNotNull(created)
        assertEquals(widened, created!!.permissions)
        account("EMP9004", "Viewer", created.id)

        assertTrue(Authorization.canDo("EMP9004", Permission.ASSIGN_TASKS))
        assertTrue(Authorization.canDo("EMP9004", Permission.VIEW_TEAMMATES_TASKS))
        // One board right is enough for the page-level convenience flag, and no people right for the other.
        assertTrue(Authorization.canManage("EMP9004"))
        assertFalse(Authorization.canAdmin("EMP9004"))
        listOf(
            Permission.CREATE_PROJECTS, Permission.EDIT_ANY_TASK, Permission.MANAGE_ROLES,
            Permission.MANAGE_EMPLOYEES, Permission.EDIT_FORECAST_AMOUNTS,
        ).forEach { assertFalse(Authorization.canDo("EMP9004", it), "a one-chip role also holds $it") }
    }

    @Test
    fun `widening or renaming a role moves everybody holding it, on the next request`() {
        val supervisor = RoleRepository.create("Yard Foreman", setOf(Permission.VIEW_TEAMMATES_TASKS))!!
        account("EMP9005", "Employee", supervisor.id)
        account("EMP9006", "Employee", supervisor.id)
        assertFalse(Authorization.canDo("EMP9005", Permission.EDIT_ANY_TASK))

        RoleRepository.update(
            supervisor.id, "Depot Foreman",
            setOf(Permission.VIEW_TEAMMATES_TASKS, Permission.EDIT_ANY_TASK),
        )
        // Both holders, without either account being touched: the rights are read from the role every time.
        listOf("EMP9005", "EMP9006").forEach { emp ->
            assertEquals("Depot Foreman", Authorization.roleOf(emp).name, "$emp kept the old name")
            assertTrue(Authorization.canDo(emp, Permission.EDIT_ANY_TASK), "$emp did not pick up the new chip")
        }
    }

    @Test
    fun `a role name cannot be invented twice or left blank`() {
        assertNotNull(RoleRepository.create("Planner", emptySet()))
        assertNull(RoleRepository.create("  ", setOf(Permission.MANAGE_ROLES)), "a blank name was written")
        assertNull(RoleRepository.create(AccessRoles.EMPLOYEE, emptySet()), "a second Employee was written")
        assertNull(RoleRepository.create(" employee ", emptySet()), "the check is case-sensitive")

        val added = RoleRepository.create("Site Supervisor", emptySet())
        assertNotNull(added)
        assertNull(
            RoleRepository.update(added!!.id, "Planner", emptySet()),
            "a rename onto a word another role already carries was written",
        )
        assertNull(RoleRepository.update(added.id, "", emptySet()), "a blank rename was written")
        assertNull(RoleRepository.update(9999, "Nobody", emptySet()), "an id that is not a role was written")
        assertEquals("Site Supervisor", RoleRepository.findById(added.id)?.name, "a refusal still retitled the row")
    }

    @Test
    fun `an account's role is filed by number and the legacy word is kept in step`() {
        val supervisor = RoleRepository.create("Planner", setOf(Permission.VIEW_TEAMMATES_TASKS))!!
        account("EMP9007", "Viewer", role(AccessRoles.EMPLOYEE).id)
        assertTrue(RoleRepository.setRole("emp9007", supervisor.id), "a catalogue id was refused")
        val stored = UserRepository.findByEmpNo("EMP9007")!!
        assertEquals(supervisor.id, stored.roleId)
        // The `access` column is only a label now, and the app never authorizes off it — but a row read by an
        // operator's SQL or the old importer still has to say something true about the person.
        assertEquals("Viewer", stored.access)
        assertFalse(RoleRepository.setRole("EMP9007", 4242), "a role id that is not on the catalogue was written")
        assertEquals(supervisor.id, UserRepository.findByEmpNo("EMP9007")!!.roleId, "the refusal moved the account")

        assertEquals("Admin", RoleRepository.legacyAccessWord(AccessRoles.OWNER))
        assertEquals("Admin", RoleRepository.legacyAccessWord(AccessRoles.ADMINISTRATOR))
        assertEquals("Manager", RoleRepository.legacyAccessWord(AccessRoles.PROJECT_MANAGER))
        assertEquals("Viewer", RoleRepository.legacyAccessWord("Planner"))
        assertEquals(AccessRoles.OWNER, RoleRepository.legacyRoleName("viewer", isSuperAdmin = true))
        assertEquals(AccessRoles.ADMINISTRATOR, RoleRepository.legacyRoleName("Admin", false))
        assertEquals(AccessRoles.PROJECT_MANAGER, RoleRepository.legacyRoleName(" manager ", false))
        assertEquals(AccessRoles.EMPLOYEE, RoleRepository.legacyRoleName(null, false))
    }

    @Test
    fun `the employees column counts every account, including the ones with no role`() {
        account("EMP9008", "Viewer", role(AccessRoles.PROJECT_MANAGER).id)
        account("EMP9009", "Viewer", null)          // filed under the default through the legacy mapping
        val counts = RoleRepository.counts()
        assertEquals(1, counts[role(AccessRoles.PROJECT_MANAGER).id])
        assertEquals(1, counts[role(AccessRoles.EMPLOYEE).id], "an account with no role was counted nowhere")
        assertEquals(
            2, counts.values.sum(),
            "the EMPLOYEES column adds up to something other than the accounts on the file",
        )
    }
}

class LoginRateLimiterTest {

    @Test
    fun `blocks after max attempts within the window and recovers after it passes`() {
        var now = java.time.Instant.parse("2026-01-01T00:00:00Z")
        val limiter = LoginRateLimiter(maxAttempts = 3, windowSeconds = 60, now = { now })

        assertFalse(limiter.isBlocked("ip1"))
        limiter.recordFailure("ip1")
        limiter.recordFailure("ip1")
        assertFalse(limiter.isBlocked("ip1"))
        limiter.recordFailure("ip1")
        assertTrue(limiter.isBlocked("ip1"))
        assertTrue(limiter.retryAfterSeconds("ip1") in 1..60)

        // Other keys are independent.
        assertFalse(limiter.isBlocked("ip2"))

        // Advance beyond the window -> attempts age out.
        now = now.plusSeconds(61)
        assertFalse(limiter.isBlocked("ip1"))
    }

    @Test
    fun `reset clears history`() {
        val limiter = LoginRateLimiter(maxAttempts = 1, windowSeconds = 60)
        limiter.recordFailure("ip")
        assertTrue(limiter.isBlocked("ip"))
        limiter.reset("ip")
        assertFalse(limiter.isBlocked("ip"))
    }
}

class CsrfTest {

    private val secret = "test-secret-value"

    @Test
    fun `token is stable for a session and validates`() {
        val t1 = Csrf.tokenFor("session-abc", secret)
        val t2 = Csrf.tokenFor("session-abc", secret)
        assertEquals(t1, t2)
        assertTrue(Csrf.isValid("session-abc", secret, t1))
    }

    @Test
    fun `token from another session or secret is rejected`() {
        val t = Csrf.tokenFor("session-abc", secret)
        assertFalse(Csrf.isValid("session-xyz", secret, t))
        assertFalse(Csrf.isValid("session-abc", "other-secret", t))
    }

    @Test
    fun `blank inputs are rejected`() {
        assertFalse(Csrf.isValid(null, secret, "x"))
        assertFalse(Csrf.isValid("", secret, "x"))
        assertFalse(Csrf.isValid("session-abc", secret, null))
        assertFalse(Csrf.isValid("session-abc", secret, ""))
    }
}
