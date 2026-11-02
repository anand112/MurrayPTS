package mn.murray.pts.service

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
 * The sentences behind the Roles & permissions popup (Stage 110).
 *
 * [RoleRepository] writes and refuses, [mn.murray.pts.security.Authorization] decides who is allowed to press
 * Save at all, and [mn.murray.pts.db.RoleMigrationTest] plus `SecurityTest` already pin the seeded catalogue,
 * the chip arithmetic and the name rules a row can earn. What is left — and what this class is about — is the
 * one object the route actually calls, because the popup prints *its* words under the name box. So the tests
 * here are about the shape of a [RoleSaveResult]: which sentence comes back for which press, that a refusal
 * carries no role with it, and that nothing was written when it does.
 *
 * Four promises are the ones a reader would notice if they broke:
 *
 *  * **Every refusal is a sentence, never an exception and never a silent null** — the popup has one place to
 *    print a reason, so a half-answer would leave a box that closed with no explanation.
 *  * **A row that is gone is refused, not created** — the popup was opened on a named role, and answering a
 *    delete-with-another-tab by adding a row would leave the catalogue with two.
 *  * **Saving a role under the name it already carries is not a clash** — the popup's Save button re-posts the
 *    whole form, chips included, so re-ticking one box cannot be refused for renaming the row onto itself.
 *  * **An empty set of chips is a role** — every subset is legitimate, so a save with nothing ticked lands with
 *    nothing ticked rather than being read as a blank form.
 */
class RoleServiceTest {

    private lateinit var ds: HikariDataSource

    @BeforeEach
    fun setUp(@TempDir dir: File) {
        ds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${File(dir, "role-service.db").absolutePath}"
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

    /** A person first, then an account under a role — `users.employee_number` is a foreign key onto the roster. */
    private fun account(employeeNumber: String, roleName: String) {
        TeamRepository.upsert(Member(employeeNumber, "Holder $employeeNumber", "Projects", "Tester"))
        UserRepository.insert(
            employeeNumber = employeeNumber, name = "Holder $employeeNumber", passwordHash = "x",
            access = RoleRepository.legacyAccessWord(roleName), email = "holder@murray.mn",
            positionId = null, isSuperAdmin = false, mustChangePassword = false,
            roleId = role(roleName).id,
        )
    }

    private fun catalogueNames() = RoleService.catalogue().map { it.name }

    @Test
    fun `the reads the table is drawn from are the catalogue in its own order`() {
        assertEquals(
            listOf(AccessRoles.OWNER, AccessRoles.ADMINISTRATOR, AccessRoles.PROJECT_MANAGER, AccessRoles.EMPLOYEE),
            catalogueNames(),
            "the popup's rows are not the picture's rows, in the picture's order",
        )
        // The opener names its role in the address bar, so 0 means "the create form" and an id that is not a
        // role means the row went away — both are the same answer to the renderer: nothing to pre-fill.
        assertEquals(AccessRoles.EMPLOYEE, RoleService.byId(role(AccessRoles.EMPLOYEE).id)?.name)
        assertNull(RoleService.byId(0), "the create opener was handed somebody else's row")
        assertNull(RoleService.byId(-3), "a negative id reached the table")
        assertNull(RoleService.byId(9999), "an id that is not on the catalogue was answered with a role")
        assertEquals(
            RoleRepository.counts(),
            RoleService.counts(),
            "the employees column is read through a second, different rule",
        )
    }

    @Test
    fun `every refusal is a sentence about the box, and none of them writes a row`() {
        val before = catalogueNames()

        val blank = RoleService.save(0, "   ", setOf(Permission.MANAGE_ROLES))
        assertFalse(blank.saved)
        assertEquals("A role needs a name.", blank.error)

        val clashing = RoleService.save(0, "  Employee  ", emptySet())
        assertFalse(clashing.saved)
        assertEquals("That role name is taken.", clashing.error, "a name with different padding slipped past")

        // A row deleted underneath an open popup: the reader named a role, so answering with a create would
        // leave the catalogue holding two of them. A made-up id is refused for the same reason.
        val temporary = RoleService.save(0, "Temporary", emptySet()).role!!
        ds.connection.use { it.createStatement().execute("DELETE FROM roles WHERE id = ${temporary.id}") }
        val reopened = RoleService.save(temporary.id, "Site Manager", setOf(Permission.CREATE_PROJECTS))
        assertFalse(reopened.saved)
        assertEquals("That role is no longer on the catalogue.", reopened.error)
        assertFalse(RoleService.save(9999, "Nobody", emptySet()).saved)

        // Renaming an added role onto a seeded one is the same clash seen from the other side.
        val added = RoleService.save(0, "Planner", emptySet()).role
        assertNotNull(added)
        val renamed = RoleService.save(added!!.id, "Owner", emptySet())
        assertFalse(renamed.saved)
        assertEquals("That role name is taken.", renamed.error)
        assertEquals("Planner", RoleService.byId(added.id)?.name, "a refusal still retitled the row")

        assertEquals(before + "Planner", catalogueNames(), "a refused save added, removed or renamed a row")
        assertEquals(
            13,
            RoleService.byId(role(AccessRoles.OWNER).id)?.permissions?.size,
            "the seeded rows were rewritten while their own popup was refused",
        )
    }

    @Test
    fun `a new role lands at the end of the catalogue, is not the default, and holds exactly its ticks`() {
        val ticks = setOf(Permission.VIEW_TEAMMATES_TASKS, Permission.ASSIGN_TASKS)
        val saved = RoleService.save(0, "Site Supervisor", ticks)
        assertTrue(saved.saved)
        assertEquals(ticks, saved.role!!.permissions, "the popup's ticks are not what the row holds")
        assertEquals(catalogueNames().last(), "Site Supervisor", "a new role cut in front of the seeded ones")
        assertFalse(saved.role!!.isDefault, "an account-made role became the default")
        assertEquals(5, saved.role!!.sortOrder, "the new row is not one past the last in the catalogue")
        assertNotNull(RoleService.byId(saved.role!!.id))

        // Any subset is a legitimate role, including none at all: nothing ticked is a read-only role, not a
        // blank form, and the popup is allowed to save it.
        val silent = RoleService.save(0, "Observer", emptySet())
        assertTrue(silent.saved, "a role with no chips was refused")
        assertEquals(emptySet<Permission>(), silent.role!!.permissions)
    }

    @Test
    fun `a save rewrites the row it opened, keeps its place, and moves everybody holding it`() {
        val supervisor = RoleService.save(0, "Yard Foreman", setOf(Permission.VIEW_TEAMMATES_TASKS)).role!!
        account("EMP9101", "Yard Foreman")
        account("EMP9102", "Yard Foreman")
        val position = catalogueNames().indexOf("Yard Foreman")
        val employeesCount = RoleService.counts()[supervisor.id]

        // Re-ticking one box re-posts the whole form — including the name it already carries. That is not a
        // clash with another row, and a refusal here would make the popup impossible to use.
        val sameName = RoleService.save(supervisor.id, "  Yard Foreman ", setOf(Permission.VIEW_TEAMMATES_TASKS))
        assertTrue(sameName.saved, "saving a role under its own name was read as a duplicate")

        val widened = RoleService.save(
            supervisor.id, "Depot Foreman",
            setOf(Permission.VIEW_TEAMMATES_TASKS, Permission.EDIT_ANY_TASK),
        )
        assertTrue(widened.saved)
        assertEquals(supervisor.id, widened.role!!.id, "the rename wrote a second row")
        assertEquals(position, catalogueNames().indexOf("Depot Foreman"), "a rename moved the row in the table")
        assertEquals(widened.role!!.permissions, RoleService.byId(supervisor.id)?.permissions)
        assertEquals(2, widened.role!!.permissions.size, "the old single chip survived the re-tick")
        assertEquals(employeesCount, RoleService.counts()[supervisor.id], "the rename changed who holds the role")

        // Widening here is the whole point of the picture: the rights live on the role, so both holders pick
        // the new chip up on their next request without their own rows being touched.
        listOf("EMP9101", "EMP9102").forEach { emp ->
            assertEquals("Depot Foreman", RoleRepository.roleOf(emp).name, "$emp kept the old name")
            assertTrue(
                RoleRepository.permissionsOf(RoleRepository.roleOf(emp).id).contains(Permission.EDIT_ANY_TASK),
                "$emp did not pick up the new chip",
            )
        }
    }
}
