package mn.murray.pts.service

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mn.murray.pts.db.Migrations
import mn.murray.pts.domain.Member
import mn.murray.pts.domain.autoEmail
import mn.murray.pts.repository.PositionRepository
import mn.murray.pts.repository.ProjectRepository
import mn.murray.pts.repository.TaskRepository
import mn.murray.pts.repository.TeamRepository
import mn.murray.pts.repository.UserRepository
import mn.murray.pts.security.PasswordHasher
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
 * The rules behind the Team sheet on Settings (Stage 46), against a real SQLite file carrying the real
 * Flyway schema — the same harness [LessonServiceTest] uses, because this sheet is a *join*: a roster
 * row, an account row, a catalog position and a card count, and the whole point of the page is that
 * those four agree about one person.
 *
 * Four rules are the ones the page cannot be trusted for, and each is tested from both sides here:
 *
 *  * **A row is a person, not an account.** The sheet is the roster *plus* any account that is not on
 *    it, so a colleague with no login is still a row — and the popup opened on that row creates the
 *    account rather than editing one that does not exist.
 *  * **The Role column answers "what may this account do"**, which is
 *    [mn.murray.pts.security.Authorization.effectiveRole] and not the `access` column. Two of the
 *    fixture's accounts are deliberately the two things at once: [positioned] is filed under a Manager
 *    position while its own column says Viewer, and [adminAccess] is the other way round — which is also
 *    the row the delete bar reads differently, because that bar compares the raw column.
 *  * **What one reader may do to a row is decided per row**, and the answer for the trash is the *word*
 *    the button prints, so the reason a greyed bin cannot press is measured here rather than trusted to
 *    the stylesheet.
 *  * **The popup writes through [UserService] and never around it**, so the legacy rules — a manager's
 *    new account is a Viewer whose password is its own number, only an administrator sets a position, a
 *    role or a password — stay in one place. The refusals below check that nothing was written at all,
 *    not just that the answer was "no".
 *
 * The rest is the sheet's own arithmetic: which rows the three controls leave, where a valueless row
 * sorts to, and what the two numbers on the count line count.
 */
class EmployeeServiceTest {

    private lateinit var ds: HikariDataSource

    private var crusher = 0
    private var managerPos = 0
    private var engineerPos = 0
    private var supervisorPos = 0

    /** The reader most of these sheets are drawn for: a manager, who may not set roles. */
    private val manager = "EMP0001"

    /** An ordinary account, with two open cards and one closed one. */
    private val worker = "EMP0002"

    /** Viewer in its own column, Manager through the position it is filed under. */
    private val positioned = "EMP0003"

    /** On the roster and nowhere else — the row that has no account to delete. */
    private val noAccount = "EMP0004"

    /** Raw `Admin` in its own column, Manager through its position: what the delete bar compares. */
    private val adminAccess = "EMP0005"

    /** No department, no position link, and ten open cards — the sheet's two fallbacks and its number order. */
    private val noDepartment = "EMP0006"

    @BeforeEach
    fun setUp(@TempDir dir: File) {
        ds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${File(dir, "employees.db").absolutePath}"
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            }
        )
        Migrations.migrate(ds)
        Database.connect(ds)

        managerPos = PositionRepository.insert("Project Manager", "Manager", "")
        engineerPos = PositionRepository.insert("Engineer", "Viewer", "")
        supervisorPos = PositionRepository.insert("Site Supervisor", "Manager", "")

        // The roster's free-text job word and the catalog entry an account is filed under live in two
        // different tables, so EMP0003 is given two different words on purpose.
        TeamRepository.upsert(Member("EMP0000", "Super Admin", "IT / Systems", "Super Admin"))
        TeamRepository.upsert(Member(manager, "Munkhtsetseg Otchontoor", "Projects", "Project Manager"))
        TeamRepository.upsert(Member(worker, "Bat-Erdene Tudev", "Projects", "Engineer"))
        TeamRepository.upsert(Member(positioned, "Ganbold Erdene", "Operations", "Senior Surveyor"))
        TeamRepository.upsert(Member(noAccount, "Nyambaapurev", "Workshop", "Engineer"))
        TeamRepository.upsert(Member(adminAccess, "Sundui Bayar", "Projects", "Project Manager"))
        // `users.employee_number` is a foreign key onto `team_members`, so every account is a person
        // first: this row is on the roster with neither a department nor a position link filled in,
        // which is the shape the sheet's two fallbacks are written for.
        TeamRepository.upsert(Member(noDepartment, "Nomin Erdene", "", ""))

        user("EMP0000", "Super Admin", "Admin", null, superAdmin = true)
        user(manager, "Munkhtsetseg Otchontoor", "Manager", null)
        user(worker, "Bat-Erdene Tudev", "Viewer", engineerPos, email = "bat@murray.mn")
        user(positioned, "Ganbold Erdene", "Viewer", supervisorPos)
        user(adminAccess, "Sundui Bayar", "Admin", supervisorPos)
        user(noDepartment, "Nomin Erdene", "Viewer", null)

        crusher = project("M-01250", "Erdenet Crusher Modernisation")
        // Two open and one closed for the worker, so the count column is measured against a card set
        // rather than a fixture that happens to make every number right.
        card(worker, "Detail plate drawings - Rev A", "In progress")
        card(worker, "RFQ - 20mm steel plate", "Not started")
        card(worker, "Site visit report", "Completed")
        // Ten, so the count column has to order 2 before 10 rather than reading a "1" ahead of a "2".
        for (n in 1..10) card(noDepartment, "Open card $n", "In progress")
    }

    @AfterEach
    fun tearDown() = ds.close()

    private fun project(code: String, name: String): Int = ProjectRepository.insert(
        code = code, name = name, status = "Ongoing", progress = 40, managerEmpNo = manager,
        due = "2026-12-01", type = "Fabrication/Manufacturing", client = "Erdenet Mining Corporation",
        revenue = 2_340_000_000.0,
    )

    private fun card(assignee: String, title: String, status: String): Int = TaskRepository.insert(
        kind = "ASSIGNED", title = title, projectId = crusher, due = "", priority = "Medium",
        assigneeEmpNo = assignee, status = status, bucketId = null, started = "",
    )

    private fun user(
        employeeNumber: String,
        name: String,
        access: String,
        positionId: Int?,
        email: String = "",
        superAdmin: Boolean = false,
    ) = UserRepository.insert(
        employeeNumber = employeeNumber, name = name, passwordHash = PasswordHasher.hash(employeeNumber),
        access = access, email = email, positionId = positionId, isSuperAdmin = superAdmin,
        mustChangePassword = false,
    )

    /** The sheet as one of the app's three readers. */
    private fun asManager(filter: EmployeeFilter = EmployeeFilter()) =
        EmployeeService.sheet(filter, manager, canManage = true, canAdmin = false)

    private fun asAdmin(filter: EmployeeFilter = EmployeeFilter()) =
        EmployeeService.sheet(filter, "EMP0000", canManage = true, canAdmin = true)

    private fun asViewer(filter: EmployeeFilter = EmployeeFilter()) =
        EmployeeService.sheet(filter, worker, canManage = false, canAdmin = false)

    private fun rows(sheet: EmployeeSheet): List<String> = sheet.rows.map { it.employeeNumber }

    private fun row(sheet: EmployeeSheet, employeeNumber: String): EmployeeRow =
        sheet.rows.first { it.employeeNumber == employeeNumber }

    // --- who is on the sheet ------------------------------------------------------------------------

    @Test
    fun `the sheet is every person on the roster, account or none`() {
        val sheet = asManager()
        assertEquals(
            setOf("EMP0000", manager, worker, positioned, noAccount, adminAccess, noDepartment),
            rows(sheet).toSet(), "a person was dropped or invented by the union",
        )
        // [noAccount] is on the sheet with no login and [adminAccess] with two words for one job: the
        // sheet lists people, and an account is a thing a person may or may not have yet. (`users` is a
        // foreign key onto `team_members`, so the second half of that union — an account nobody is filed
        // under — cannot be built here, and the row-building code keeps its branch for it anyway.)
        assertEquals(7, sheet.total, "the count line counts something other than the rows")
        assertEquals(7, sheet.shown, "an uncut sheet counted itself short")
        // The position list is gathered from every row rather than the filtered ones, so a search that
        // leaves two people cannot make the picker lose a job that still exists on the roster.
        assertEquals(
            listOf("Engineer", "Project Manager", "Site Supervisor", "Super Admin", "Team Member"),
            sheet.positions, "the position list is not the distinct positions, in case-insensitive order",
        )
    }

    @Test
    fun `a colleague with no account is a row with no role and no login to delete`() {
        val r = row(asAdmin(), noAccount)
        assertFalse(r.hasAccount, "a roster-only row claimed an account")
        assertEquals("", r.role, "a person with no login was given a level they do not have")
        assertEquals(autoEmail("Nyambaapurev"), r.email, "the sheet invented an address their account would not get")
        assertEquals("Workshop", r.department, "the roster's own department was not printed")
        assertEquals(0, r.openTasks, "a person with no cards read as a blank rather than a zero")
        assertTrue(r.mayEdit, "an administrator was refused the pencil that creates this person's account")
        assertEquals(
            "This person has no account to delete yet — the pencil creates one.",
            r.deleteBlocked, "the bin on a row with no account can only fail, so it says so instead",
        )
    }

    @Test
    fun `the position column is the catalog entry the account is filed under`() {
        val sheet = asManager()
        // Two words, two tables: the catalog link is what the app enforces, so it wins over the roster's
        // free-text job title, and the sheet and the Access control page cannot name a different job.
        assertEquals("Site Supervisor", row(sheet, positioned).position, "the roster's free-text word beat the catalog link")
        assertEquals("Project Manager", row(sheet, manager).position)
        // An account with no link falls back to the roster's word, and one with neither falls back to the
        // same words the Access control page prints.
        UserRepository.updatePosition(worker, null)
        assertEquals(
            "Engineer",
            row(EmployeeService.sheet(EmployeeFilter(), worker, true, true), worker).position,
            "an unlinked account lost the roster's word",
        )
        assertEquals("Team Member", row(sheet, noDepartment).position, "nobody's fallback word is not the app's")
    }

    @Test
    fun `the role column prints what the account can actually do`() {
        val sheet = asManager()
        assertEquals("Manager", row(sheet, positioned).role, "the sheet printed the access column over the position's level")
        assertEquals("Viewer", row(sheet, worker).role)
        assertEquals("Manager", row(sheet, manager).role)
        assertEquals("Admin", row(sheet, "EMP0000").role, "the super admin was not read through the same rule")
        // The other direction: a raw `Admin` filed under a Manager position reads as a Manager here —
        // and that is the row the delete bar answers for differently, on purpose.
        assertEquals("Manager", row(sheet, adminAccess).role, "the column printed the override rather than the position")

        // The popup asks the other question — which field to write — so it reads the account itself.
        val account = EmployeeService.account(positioned)!!
        assertEquals("Viewer", account.access, "the account's own column changed under the sheet")
        assertEquals("Site Supervisor", EmployeeService.positionOf(account), "the picker lost the catalog entry")
        assertEquals("", EmployeeService.positionOf(EmployeeService.account(manager)!!), "an unlinked account was given a catalog position")
    }

    // --- the count column ---------------------------------------------------------------------------

    @Test
    fun `management counts every row and anybody else counts their own`() {
        assertEquals(2, row(asManager(), worker).openTasks, "a closed card was counted as open")
        assertEquals(10, row(asManager(), noDepartment).openTasks)
        assertEquals(0, row(asManager(), manager).openTasks, "a person with no cards is a zero, not a blank")

        assertEquals(2, row(asViewer(), worker).openTasks, "an ordinary account cannot see its own workload")
        assertNull(row(asViewer(), positioned).openTasks, "a Viewer was shown a colleague's count")
        assertNull(row(asViewer(), manager).openTasks, "a Viewer was shown management's count")
        assertTrue(row(asViewer(), positioned).hollow(EmployeeColumn.TASKS), "a count nobody may see is not the column's blank")
    }

    @Test
    fun `the count line counts the rows the reader is shown`() {
        val cut = asManager(EmployeeFilter(position = "Engineer"))
        assertEquals(2, cut.shown, "the count is the whole roster rather than what the picker left")
        assertEquals(7, cut.total, "the total counted the filtered sheet instead of the roster")

        val noneLeft = asManager(EmployeeFilter(text = "zzz"))
        assertTrue(noneLeft.isEmpty, "a filter that leaves nothing still printed rows")
        assertEquals(0, noneLeft.shown)
        assertEquals(7, noneLeft.total, "the empty sheet does not say none of seven were left")
    }

    // --- the three controls ------------------------------------------------------------------------

    @Test
    fun `the search box reads the name, the number and the address`() {
        // The address the column prints rather than the one the row holds: a person with no email of
        // their own is found by the address their account would get, because that is what the sheet shows.
        assertEquals(listOf(worker), rows(asManager(EmployeeFilter(text = "bat@murray"))), "a stored address was not searchable")
        assertEquals(listOf(adminAccess), rows(asManager(EmployeeFilter(text = "sundui.bayar"))), "the generated address was not searchable")
        assertEquals(listOf(worker), rows(asManager(EmployeeFilter(text = "p0002"))), "a match on the number was missed")
        assertEquals(listOf(worker), rows(asManager(EmployeeFilter(text = "TUDEV"))), "the search is case-sensitive")
        assertEquals(listOf(positioned), rows(asManager(EmployeeFilter(text = "  ganbold  "))), "the term was not trimmed before matching")
        // A department is a picker's question and not the box's: the picture labels the box by name,
        // number and address, so a search that also read jobs answers for rows nobody typed.
        assertEquals(emptyList<String>(), rows(asManager(EmployeeFilter(text = "Operations"))), "the box searched a column it does not name")
    }

    @Test
    fun `the two pickers are exact and answer together`() {
        assertEquals(listOf(worker, noAccount), rows(asManager(EmployeeFilter(position = "Engineer"))).sorted())
        assertEquals(
            listOf(manager, positioned, adminAccess),
            rows(asManager(EmployeeFilter(role = "Manager"))).sorted(),
            "the role picker is not the three levels the sheet prints",
        )
        assertEquals(listOf("EMP0000"), rows(asManager(EmployeeFilter(role = "Admin"))),
            "the picker answered for the access column rather than the level the sheet prints")
        // Both at once is the row they agree about, and neither is a row that only one of them leaves.
        assertEquals(listOf(worker), rows(asManager(EmployeeFilter(position = "Engineer", role = "Viewer"))))
        assertEquals(emptyList<String>(), rows(asManager(EmployeeFilter(position = "Engineer", role = "Admin"))))
        assertFalse(EmployeeFilter().isActive, "an uncut sheet claims a filter it has not been given")
        assertTrue(EmployeeFilter(role = "Viewer").isActive, "a picker nobody moved is not the view on screen")
    }

    @Test
    fun `a view round-trips through the address it writes`() {
        val view = EmployeeFilter(text = "bat", position = "Engineer", role = "Viewer",
            column = EmployeeColumn.TASKS, descending = true)
        val query = view.queryFor()
        assertTrue(query.startsWith("/app/settings?"), "the sheet's address is not this page: $query")
        listOf("q=bat", "position=Engineer", "role=Viewer", "sort=tasks%3Adesc").forEach {
            assertTrue(query.contains(it), "the address lost $it: $query")
        }
        assertEquals(
            view,
            EmployeeFilter.of(mapOf("q" to "bat", "position" to "Engineer", "role" to "Viewer", "sort" to "tasks:desc")),
            "the keys the sheet writes are not the keys it reads",
        )
        // A route's own key rides on the end, after the view, so a flash note is not lost by a picker.
        assertTrue(view.queryFor(extra = listOf("note=USER_ADDED")).endsWith("&note=USER_ADDED"), "the note was dropped: $query")
        // Clear answers the three controls and nothing else: a reader who searched their way to five rows
        // still wants to read them in the order they chose.
        val cleared = view.cleared()
        assertFalse(cleared.isActive, "the cleared view still claims a filter")
        assertEquals(EmployeeColumn.TASKS, cleared.column)
        assertTrue(cleared.descending, "Clear reset the order the reader set")
        assertEquals("/app/settings?sort=tasks%3Adesc", cleared.queryFor())
        // And the default order writes no key at all, so the address bar reads as a page nobody has cut.
        assertEquals("/app/settings", EmployeeFilter().queryFor())
        // An unknown column, or an unparsable direction, is the sheet's own default rather than a crash.
        assertEquals(EmployeeFilter(), EmployeeFilter.of(mapOf("sort" to "wibble")))
        assertEquals(EmployeeColumn.ROLE, EmployeeFilter.of(mapOf("sort" to "role:up")).column)
        assertFalse(EmployeeFilter.of(mapOf("sort" to "role:up")).descending)
    }

    // --- the seven headers --------------------------------------------------------------------------

    @Test
    fun `the default order is the name column, head first`() {
        assertEquals(
            listOf(worker, positioned, manager, noDepartment, noAccount, adminAccess, "EMP0000"),
            rows(asManager()), "the sheet is not read down the name the picture shows",
        )
        assertEquals("", EmployeeFilter().sortValue, "the sheet's own default put a key in the address")
    }

    @Test
    fun `a header names the order it asks for, never the one on screen`() {
        val head = EmployeeFilter()
        // The current column is turned over; every other column is asked for head-first, so one press
        // does one thing rather than changing the column and silently resetting the direction.
        assertEquals("name:desc", head.sortValueFor(EmployeeColumn.NAME))
        assertEquals("tasks", head.sortValueFor(EmployeeColumn.TASKS))
        assertEquals("▲", head.glyphFor(EmployeeColumn.NAME), "the column being read down does not say which way")
        assertEquals("", head.glyphFor(EmployeeColumn.TASKS), "a column that is not the order wore the sheet's arrow")

        val reversed = EmployeeFilter(column = EmployeeColumn.NAME, descending = true)
        assertEquals("name", reversed.sortValueFor(EmployeeColumn.NAME), "a Z-A column offered to stay Z-A")
        assertEquals("▼", reversed.glyphFor(EmployeeColumn.NAME))
        assertEquals("name:desc", reversed.sortValue, "a reversed name column left its direction out of the address")

        val byTasks = EmployeeFilter(column = EmployeeColumn.TASKS)
        assertEquals("tasks:desc", byTasks.sortValueFor(EmployeeColumn.TASKS), "an ascending count offered to stay ascending")
        assertEquals("name", byTasks.sortValueFor(EmployeeColumn.NAME), "a foreign column asked for the reversed order")
        assertEquals("", byTasks.glyphFor(EmployeeColumn.NAME), "the arrow followed the reader off the column")

        val countDown = EmployeeFilter(column = EmployeeColumn.TASKS, descending = true)
        assertEquals("tasks", countDown.sortValueFor(EmployeeColumn.TASKS), "a reversed count offered to reverse again")
    }

    @Test
    fun `one head reverses its own column and leaves the rest alone`() {
        assertEquals(
            listOf("EMP0000", adminAccess, noAccount, noDepartment, manager, positioned, worker),
            rows(asManager(EmployeeFilter(column = EmployeeColumn.NAME, descending = true))),
            "the name column read the wrong way",
        )
        assertEquals(
            listOf("EMP0000", manager, worker, positioned, noAccount, adminAccess, noDepartment),
            rows(asManager(EmployeeFilter(column = EmployeeColumn.EMPLOYEE))), "the number column is not read as a number",
        )
        // Ranked Viewer, Manager, Admin — and a person with no login has no rank, so it goes last.
        assertEquals(
            listOf(worker, noDepartment, positioned, manager, adminAccess, "EMP0000", noAccount),
            rows(asManager(EmployeeFilter(column = EmployeeColumn.ROLE))), "the role column is not ranked",
        )
        // Ties inside one rank are broken by the name, so a head click cannot shuffle the sheet.
        val managers = asManager(EmployeeFilter(column = EmployeeColumn.ROLE)).rows.filter { it.role == "Manager" }
        assertEquals(
            listOf(positioned, manager, adminAccess),
            managers.map { it.employeeNumber },
            "three Managers printed in an order the sheet does not choose",
        )
    }

    @Test
    fun `the count column orders as a number and not as text`() {
        val up = rows(asManager(EmployeeFilter(column = EmployeeColumn.TASKS)))
        assertTrue(up.indexOf(worker) < up.indexOf(noDepartment), "2 sorted after 10: $up")
        assertEquals(noDepartment, up.last(), "the biggest workload is not the foot of an ascending count")
        val down = rows(asManager(EmployeeFilter(column = EmployeeColumn.TASKS, descending = true)))
        assertEquals(noDepartment, down.first(), "the reversed column reversed the digits too")
        assertTrue(down.indexOf(noDepartment) < down.indexOf(worker), "$down")
    }

    @Test
    fun `a row with nothing in the column lands last in both orders`() {
        listOf(false, true).forEach { descending ->
            val way = if (descending) "Z-A" else "A-Z"
            val byDepartment = EmployeeFilter(column = EmployeeColumn.DEPARTMENT, descending = descending)
            // The account with no roster row has no department, which is not the same as a blank one.
            assertEquals(noDepartment, rows(asManager(byDepartment)).last(), "a row with no department sorted into the cut ($way)")
            val byRole = EmployeeFilter(column = EmployeeColumn.ROLE, descending = descending)
            assertEquals(noAccount, rows(asAdmin(byRole)).last(), "a person with no login took the rank of nothing ($way)")
        }
        // For a Viewer the dash is somebody else's count, and all six of them stay below the one row the
        // reader is allowed to count — the blanks are a block at the end, not a value inside the order.
        listOf(false, true).forEach { descending ->
            val forViewer = rows(asViewer(EmployeeFilter(column = EmployeeColumn.TASKS, descending = descending)))
            assertEquals(worker, forViewer.first(), "a Viewer's own count left the head of the column")
            assertEquals(
                setOf("EMP0000", manager, positioned, noAccount, adminAccess, noDepartment),
                forViewer.takeLast(6).toSet(), "the rows a Viewer may not count were not kept at the end",
            )
        }
    }

    // --- what this reader may do --------------------------------------------------------------------

    @Test
    fun `the pencil is an administrator's and nobody else's`() {
        asManager().rows.forEach { assertFalse(it.mayEdit, "a manager was offered the account editor") }
        asViewer().rows.forEach { assertFalse(it.mayEdit, "a Viewer was offered the account editor") }
        asAdmin().rows.forEach { assertTrue(it.mayEdit, "an administrator lost a row's pencil") }
        assertEquals(1, asManager().rows.count { it.isSelf }, "the sheet marked two rows as the reader")
        assertTrue(row(asManager(), manager).isSelf, "the sheet lost the reader's own row")
        assertFalse(row(asViewer(), manager).isSelf, "a row is only the reader's for the reader it is drawn for")
    }

    @Test
    fun `the bin prints the rule that refuses it`() {
        val viewer = asViewer()
        listOf(positioned, manager, "EMP0000", noAccount, worker).forEach {
            assertEquals("Only management can delete an account.", row(viewer, it).deleteBlocked, "a Viewer was offered a live bin")
        }
        // Greyed rather than absent, so the column stays lined up and the reason is on the button that is
        // already asking the question.
        assertEquals(7, viewer.rows.count { it.deleteBlocked != null }, "a greyed bin was taken off the sheet")

        val management = asManager()
        assertNull(row(management, worker).deleteBlocked, "management was barred from an ordinary account")
        assertEquals("You cannot delete your own account.", row(management, manager).deleteBlocked)
        assertEquals(
            "Managers cannot delete Admin accounts.", row(management, adminAccess).deleteBlocked,
            "the bar reads the account's own level, as the delete route does — not the role the column prints",
        )
        assertEquals("The Super Admin account cannot be deleted.", row(management, "EMP0000").deleteBlocked)
        assertEquals("This person has no account to delete yet — the pencil creates one.", row(management, noAccount).deleteBlocked)

        val admin = asAdmin()
        assertNull(row(admin, worker).deleteBlocked, "an administrator was barred from an ordinary account")
        assertNull(row(admin, adminAccess).deleteBlocked, "an administrator was barred from an Admin account")
        // The reader's own row is the super admin's row here, and the super rule is checked first — the
        // same order `UserService.deleteUser` answers in.
        assertEquals("The Super Admin account cannot be deleted.", row(admin, "EMP0000").deleteBlocked)
    }

    @Test
    fun `one row, for the address that asks for it`() {
        assertEquals(worker, EmployeeService.row(" emp0002 ", manager, true, false)?.employeeNumber, "a hand-typed number was not matched")
        assertNull(EmployeeService.row("EMP9999", manager, true, false), "an unknown number answered with a row")
        assertNull(EmployeeService.row("   ", manager, true, false), "an empty number answered with a row")
        // Read through the same sheet the page prints, so a popup cannot be filled with a name or a role
        // the sheet is not willing to show — including for a reader who may not edit anybody.
        val asPeer = EmployeeService.row(positioned, worker, false, false)
        assertNotNull(asPeer, "a Viewer cannot even read a row the sheet prints for them")
        assertEquals("Manager", asPeer!!.role, "the popup's row was built on different rules than the sheet's")
        assertFalse(asPeer.mayEdit, "the row answered with an editor its reader may not use")
        assertNotNull(EmployeeService.row(noAccount, manager, true, true), "a roster-only row was invisible to the route")
        assertNull(EmployeeService.account("EMP9999"), "an unknown number answered with an account")
    }

    // --- the popup's writes --------------------------------------------------------------------------

    private fun create(
        who: String,
        canManage: Boolean,
        canAdmin: Boolean,
        employeeNumber: String = "EMP0023",
        name: String = "Tuyaa Jamsran",
        email: String = "",
        position: String = "",
        role: String = "",
        password: String = "",
        edit: String = "",
    ) = EmployeeService.save(
        EmployeeDraft(edit = edit, employeeNumber = employeeNumber, name = name, email = email,
            position = position, role = role, password = password),
        who, canManage, canAdmin,
    )

    @Test
    fun `an administrator's new account is the one the popup described`() {
        val result = create("EMP0000", canManage = true, canAdmin = true, email = "tuyaa@murray.mn",
            position = "Project Manager", role = "Manager", password = "opd2026")
        assertTrue(result.saved, "the create was refused: ${result.errors}")
        val account = UserRepository.findByEmpNo("EMP0023")!!
        assertEquals("Manager", account.access)
        assertEquals("tuyaa@murray.mn", account.email)
        assertEquals(managerPos, account.positionId, "the catalog link was not written")
        assertFalse(account.mustChangePassword, "a password somebody typed is not a default to change")
        assertTrue(PasswordHasher.verify("opd2026", account.passwordHash), "the password written is not the one typed")
        assertFalse(result.isEdit, "a create came back as an edit")
        // The roster gets its row from the same call, so the new account is a person on the sheet the
        // moment it exists — and the sheet reads it through the same four joins as every other row.
        val onSheet = row(EmployeeService.sheet(EmployeeFilter(), "EMP0000", true, true), "EMP0023")
        assertEquals("Tuyaa Jamsran", onSheet.name)
        assertEquals("Project Manager", onSheet.position)
        assertEquals("Manager", onSheet.role)
        assertEquals(0, onSheet.openTasks, "a brand new account was not counted")
    }

    @Test
    fun `the popup writes the account's own level and the sheet prints what that adds up to`() {
        // The two pickers write two columns, and the app's locked rule reads the position first: an
        // account typed as a Manager but filed under a Viewer job *is* a Viewer, and the Role column
        // says so rather than repeating the word the picker was left on. This is the pair [adminAccess]
        // is on the sheet for, reached the other way round — through the write.
        val result = create("EMP0000", canManage = true, canAdmin = true, position = "Engineer", role = "Manager")
        assertTrue(result.saved, "the create was refused: ${result.errors}")
        assertEquals("Manager", UserRepository.findByEmpNo("EMP0023")!!.access, "the picker's word was not stored")
        assertEquals("Viewer", row(EmployeeService.sheet(EmployeeFilter(), "EMP0000", true, true), "EMP0023").role,
            "the sheet printed the override rather than the level the app enforces")
    }

    @Test
    fun `a manager's new account starts as a Viewer whose password is its own number`() {
        val result = create(manager, canManage = true, canAdmin = false)
        assertTrue(result.saved, "a manager's create was refused: ${result.errors}")
        val account = UserRepository.findByEmpNo("EMP0023")!!
        assertEquals("Viewer", account.access, "a manager gave an account a level it may not give")
        assertTrue(account.mustChangePassword, "the default password was not flagged for change")
        assertTrue(PasswordHasher.verify("EMP0023", account.passwordHash), "the default password is not the number")
        assertEquals(autoEmail("Tuyaa Jamsran"), account.email, "a blank address left the field blank")
        assertNull(account.positionId, "a manager's create picked a catalog position")
    }

    @Test
    fun `a manager's popup asks for nothing about roles, and a hand-built post is refused`() {
        // The create form a manager is served has three fields, so the three it does not print are the
        // three this check is about: a POST built by hand cannot bring itself a role.
        listOf(
            create(manager, true, false, role = "Admin") to "a role",
            create(manager, true, false, position = "Project Manager") to "a position",
            create(manager, true, false, password = "opd2026") to "a password",
        ).forEach { (result, what) ->
            assertFalse(result.saved, "a manager set $what")
            assertEquals("Only an administrator can set a position, a role or a password.", result.error(EmployeeField.PERMISSION))
            assertNull(UserRepository.findByEmpNo("EMP0023"), "the account was written before the refusal")
        }
    }

    @Test
    fun `an edit changes the account and keeps the name`() {
        val result = create(worker, canManage = true, canAdmin = true, edit = positioned,
            name = "Somebody Else Entirely", email = "ganbold@murray.mn", position = "Engineer", role = "Viewer")
        assertTrue(result.saved, "the edit was refused: ${result.errors}")
        val account = UserRepository.findByEmpNo(positioned)!!
        assertEquals("ganbold@murray.mn", account.email)
        assertEquals(engineerPos, account.positionId, "the edit left the catalog link where it was")
        assertEquals("Viewer", account.access)
        // The name is not among the fields an edit writes: projects and cards name their people in those
        // words, so a rename here would leave them pointing at a name that no longer exists.
        assertEquals("Ganbold Erdene", account.name, "an edit renamed the account")
        assertEquals("Ganbold Erdene", TeamRepository.findByEmpNo(positioned)?.name, "an edit renamed the roster too")
        // What an edit does move is the roster's free-text job, so the two words cannot drift apart.
        assertEquals("Engineer", TeamRepository.findByEmpNo(positioned)?.position, "the roster kept a different job for them")
        // A blank password field means keep it, so the number still opens the account afterwards.
        assertTrue(PasswordHasher.verify(positioned, account.passwordHash), "an edit replaced a password nobody typed")
    }

    @Test
    fun `an edit that sets a password replaces it, and a short one changes nothing`() {
        val set = create(worker, true, true, edit = positioned, name = "Ganbold Erdene", email = "g@g.mn", password = "newpass1")
        assertTrue(set.saved, "the password write was refused: ${set.errors}")
        val account = UserRepository.findByEmpNo(positioned)!!
        assertTrue(PasswordHasher.verify("newpass1", account.passwordHash), "the new password is not the one typed")
        assertFalse(PasswordHasher.verify(positioned, account.passwordHash), "the old password still opens the account")
        assertFalse(account.mustChangePassword, "a password an administrator chose is not a default to change")
        assertTrue(set.isEdit, "an edit came back as a create")

        // The email in the same POST was valid and none of it landed: a form that says it failed has to
        // mean all of its fields, not only the ones it liked.
        val refused = create(worker, true, true, edit = positioned, name = "Ganbold Erdene", email = "moved@murray.mn", password = "abc")
        assertFalse(refused.saved)
        assertEquals("A password is 4 characters or more.", refused.error(EmployeeField.PASSWORD))
        assertEquals("g@g.mn", UserRepository.findByEmpNo(positioned)!!.email, "a refused edit wrote the field it liked")
    }

    @Test
    fun `every refusal the popup can bring back belongs under its own field`() {
        val cases = listOf(
            create(worker, false, false) to ("PERMISSION" to "Only management can add an account."),
            create("EMP0000", true, false, employeeNumber = "", name = "Tuyaa") to
                ("EMP_NO" to "Every account is signed in by its employee number."),
            create("EMP0000", true, true, employeeNumber = worker) to
                ("EMP_NO" to "There is already an account with that number."),
            create("EMP0000", true, true, employeeNumber = "EMP 0023") to
                ("EMP_NO" to "An employee number is letters and digits, like EMP0023."),
            create("EMP0000", true, true, name = "   ") to
                ("NAME" to "Write the name the sheet should print."),
            create("EMP0000", true, true, role = "Owner") to
                ("ROLE" to "Choose one of the three roles on the list."),
            create("EMP0000", true, true, position = "Foreman") to
                ("POSITION" to "That position is no longer in the catalog."),
            create(worker, true, true, edit = "EMP0099", name = "Nobody") to
                ("ROW" to "That account is no longer on the sheet."),
            create(worker, true, false, edit = positioned, name = "Ganbold Erdene") to
                ("PERMISSION" to "Only an administrator can change an account."),
        )
        cases.forEach { (result, expected) ->
            val (fieldName, message) = expected
            val field = EmployeeField.valueOf(fieldName)
            assertFalse(result.saved, "a write was accepted that should answer \"$message\"")
            assertEquals(field, result.errors.keys.singleOrNull(), "$message did not land under exactly one field: ${result.errors}")
            assertEquals(message, result.error(field))
        }
        // None of the nine writes above landed, in any order.
        assertEquals(6, UserRepository.count(), "a refusal put an account on the sheet")
    }

    @Test
    fun `a refusal keeps the words that were typed`() {
        val result = create("EMP0000", true, true, employeeNumber = "EMP0023", name = "Tuyaa Jamsran",
            email = "tuyaa@murray.mn", role = "Owner")
        assertFalse(result.saved)
        assertEquals("tuyaa@murray.mn", result.values.email, "the popup threw the address away")
        assertEquals("EMP0023", result.values.employeeNumber, "the popup threw the number away")
        assertEquals("Tuyaa Jamsran", result.values.name, "the popup threw the name away")
        assertEquals("Owner", result.values.role, "the popup reset the picker the reader had just used")
        assertFalse(result.isEdit, "a create came back stating it was an edit")
    }

    @Test
    fun `a number is matched the way the sheet writes it`() {
        // The popup prints capitals and the route reads what was typed, so a lower-case entry is the same
        // account rather than a second one spelled differently.
        val result = create("EMP0000", true, true, employeeNumber = "emp0023")
        assertTrue(result.saved, "the create was refused: ${result.errors}")
        assertNotNull(UserRepository.findByEmpNo("EMP0023"), "the account landed under the typed spelling")
        assertNull(UserRepository.findByEmpNo("emp0023"))
        // And asking to edit a number that exists in another case edits that account, rather than
        // answering EMP_TAKEN and then creating a second person.
        val again = create("EMP0000", true, true, edit = "emp0023", name = "Tuyaa Jamsran", email = "t@t.t")
        assertTrue(again.saved, "the edit was refused: ${again.errors}")
        assertEquals("t@t.t", UserRepository.findByEmpNo("EMP0023")?.email, "a lower-case edit wrote elsewhere")
        assertEquals(7, UserRepository.count(), "a case difference created a second account")
    }

    @Test
    fun `the two pickers offer the catalog and the three roles`() {
        assertEquals(listOf("Engineer", "Project Manager", "Site Supervisor"), EmployeeService.positionNames().take(3),
            "the position picker is not the catalog in a stable order")
        assertEquals(listOf("Viewer", "Manager", "Admin"), EmployeeService.roles(), "the role picker is not the app's three levels")
    }
}
