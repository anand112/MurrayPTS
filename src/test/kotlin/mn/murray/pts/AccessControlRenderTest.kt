package mn.murray.pts

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.Parameters
import kotlinx.html.DIV
import kotlinx.html.div
import kotlinx.html.stream.createHTML
import mn.murray.pts.db.Migrations
import mn.murray.pts.domain.AccessRoles
import mn.murray.pts.domain.Member
import mn.murray.pts.domain.Permission
import mn.murray.pts.repository.PositionRepository
import mn.murray.pts.repository.RoleRepository
import mn.murray.pts.repository.TeamRepository
import mn.murray.pts.repository.UserRepository
import mn.murray.pts.service.EmployeeService
import mn.murray.pts.service.RoleService
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The Roles & permissions table and its popup (Stage 110), read as markup.
 *
 * The screenshot is the specification: a **Roles & permissions** panel whose rows read ROLE / PERMISSIONS /
 * EMPLOYEES with one **Edit** each, **+ New role** at the panel's right edge, and a popup of thirteen named
 * tick boxes. So these tests measure the picture's own words and counts against a migrated database holding the
 * seeded catalogue — 13/13/9/1 chips, the catalogue in its order in every picker, and an EMPLOYEES column that
 * adds up to the accounts on the file.
 *
 * The other half is the update contract, which is what makes the page a board rather than a printout. Both
 * openers are GET forms onto the page itself (`?newrole=1`, `?roleedit=<id>`) and the popup posts to
 * `/app/roles/save` naming `#rolesPanel` as its block and `#roleModal` as its companion, so a save moves the
 * table and shuts the form off one response, while a refusal hands the page back with the popup still open and
 * its sentence under the name box.
 *
 * A reader without *Manage roles & permissions* is the case to watch: the table stays, because it is what tells
 * you which role a colleague is filed under, and every press that would change it goes — the pickers beside the
 * People rows included, which become tinted words.
 *
 * `createHTML()` breaks a line before a block element and leaves an inline run on one line, so the assertions are
 * made on one element or on one element's attribute run, never across two blocks.
 */
class AccessControlRenderTest {

    private lateinit var ds: HikariDataSource

    private var engineerPos = 0
    private var supervisorPos = 0

    /** The Owner: the reader every write-honouring page is drawn for, and the account nobody may delete. */
    private val owner = "EMP0000"

    /** A Project Manager — the reader the read-only table is drawn for. */
    private val manager = "EMP0001"

    /** The default role, one chip. */
    private val worker = "EMP0002"

    /** A Manager-level *position* under the default role: the row that proves a job title is not a right. */
    private val positioned = "EMP0003"

    /** On the roster with no login: the row that prints "No account". */
    private val noAccount = "EMP0004"

    /** The raw `Admin` word under the default role — the row that proves the word no longer decides. */
    private val adminWord = "EMP0005"

    /** A `Manager` word with its `role_id` cleared: the row that has to read the catalogue's default. */
    private val roleless = "EMP0006"

    /** The picture's four rows, in its order. */
    private val seededRoles = listOf(
        AccessRoles.OWNER, AccessRoles.ADMINISTRATOR, AccessRoles.PROJECT_MANAGER, AccessRoles.EMPLOYEE,
    )

    @BeforeEach
    fun setUp(@TempDir dir: File) {
        ds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${File(dir, "access-control-page.db").absolutePath}"
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            },
        )
        Migrations.migrate(ds)
        Database.connect(ds)

        PositionRepository.insert("Project Manager", "Manager", "")
        engineerPos = PositionRepository.insert("Engineer", "Viewer", "")
        supervisorPos = PositionRepository.insert("Site Supervisor", "Manager", "")

        TeamRepository.upsert(Member(owner, "Super Admin", "IT / Systems", "Super Admin"))
        TeamRepository.upsert(Member(manager, "Munkhtsetseg Otchontoor", "Projects", "Project Manager"))
        TeamRepository.upsert(Member(worker, "Bat-Erdene Tudev", "Projects", "Engineer"))
        TeamRepository.upsert(Member(positioned, "Ganbold Erdene", "Operations", "Senior Surveyor"))
        TeamRepository.upsert(Member(noAccount, "Nyambaapurev", "Workshop", "Engineer"))
        TeamRepository.upsert(Member(adminWord, "Sundui Bayar", "Projects", "Project Manager"))
        TeamRepository.upsert(Member(roleless, "Nomin Erdene", "Workshop", "Fabricator"))

        // Every account is filed under a seeded role; `access` keeps its old word as a label only, and the two
        // deliberately mismatched rows — a Manager-level job and an Admin word, both under the default role —
        // are the ones the sheet has to answer for with the role.
        user(owner, "Super Admin", "Admin", null, superAdmin = true)
        user(manager, "Munkhtsetseg Otchontoor", "Manager", null)
        user(worker, "Bat-Erdene Tudev", "Viewer", engineerPos)
        user(positioned, "Ganbold Erdene", "Viewer", supervisorPos)
        user(adminWord, "Sundui Bayar", "Admin", supervisorPos, roleId = roleId(AccessRoles.EMPLOYEE))
        user(roleless, "Nomin Erdene", "Manager", null, roleId = roleId(AccessRoles.PROJECT_MANAGER))
        // `users.employee_number` is a foreign key onto the roster, so an account always has a row — but a
        // deleted role leaves its holders with a NULL column (V21's SET NULL), and the sheet has to read that as
        // the default role rather than as no rights or as everybody's.
        clearRole(roleless)
    }

    private fun clearRole(employeeNumber: String) {
        ds.connection.use { conn ->
            conn.prepareStatement("UPDATE users SET role_id = NULL WHERE employee_number = ?").use { st ->
                st.setString(1, employeeNumber)
                st.executeUpdate()
            }
        }
    }

    @AfterEach
    fun tearDown() = ds.close()

    private fun user(
        employeeNumber: String,
        name: String,
        access: String,
        positionId: Int?,
        roleId: Int? = null,
        superAdmin: Boolean = false,
    ) = UserRepository.insert(
        employeeNumber = employeeNumber, name = name, passwordHash = employeeNumber,
        access = access, email = "", positionId = positionId, isSuperAdmin = superAdmin,
        mustChangePassword = false, roleId = roleId,
    )

    private fun roleId(name: String): Int = RoleRepository.findByName(name)!!.id

    // --- the harness --------------------------------------------------------------------------------

    private fun render(block: DIV.() -> Unit): String = createHTML().div(block = block)

    private fun page(
        who: String = owner,
        canPeople: Boolean = true,
        canRoles: Boolean = true,
        canDepartments: Boolean = false,
        roleForm: RoleForm = RoleForm(),
        /** Stage 123: the Password column is `EMP0000`'s alone — and it only makes sense inside the people sheet. */
        canSetPassword: Boolean = who == owner && canPeople,
    ): String = render {
        accessContent(
            canPeople = canPeople,
            canRoles = canRoles,
            canDepartments = canDepartments,
            currentEmployeeNumber = who,
            roleForm = roleForm,
            canSetPassword = canSetPassword,
        )
    }

    /** *Manage employees* without *Manage roles & permissions*: the read-only reading of both tables. */
    private fun noRolesPage(): String = page(manager, canPeople = true, canRoles = false)

    private fun query(vararg pairs: Pair<String, String>): Parameters = Parameters.build {
        pairs.forEach { (key, value) -> append(key, value) }
    }

    private fun postForm(vararg pairs: Pair<String, List<String>>): Parameters = Parameters.build {
        pairs.forEach { (key, values) -> values.forEach { append(key, it) } }
    }

    private fun occurrences(html: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(html).count()

    /** The opening tag whose attributes carry [id] — the whole attribute run of one element. */
    private fun tag(html: String, id: String): String =
        Regex("<[^>]*id=\"$id\"[^>]*>").find(html)?.value ?: error("no element with id=$id")

    /** The roles panel: inside `#rolesPanel`, up to the People card that follows it. */
    private fun rolesPanel(html: String): String =
        html.substringAfter("id=\"rolesPanel\"").substringBefore("<div class=\"card-panel\">")

    /** The People sheet, between the first and second card panel below the roles table. */
    private fun peopleSheet(html: String): String =
        html.substringAfter("id=\"rolesPanel\"")
            .substringAfter("<div class=\"card-panel\">")
            .substringBefore("<div class=\"card-panel\">")

    /** The Add user card, from its heading to the next panel. */
    private fun addCard(html: String): String =
        html.substringAfter("<h3>Add user</h3>").substringBefore("<div class=\"card-panel\">")

    /** The popup, from its id to the page's own script tag; empty for a reader who may not write roles. */
    private fun modal(html: String): String =
        html.substringAfter("id=\"roleModal\"").substringBefore("<script src=\"/static/js/access-control.js\">")

    /** One role's row, from the `<tr>` that opens it to the `</tr>` that closes it. */
    private fun roleRow(section: String, roleName: String): String {
        val anchor = section.indexOf("<span class=\"role-name\">$roleName</span>")
            .takeIf { it >= 0 } ?: error("no roles row for $roleName on the page")
        return section.substring(section.lastIndexOf("<tr", anchor), section.indexOf("</tr>", anchor))
    }

    private fun roleNames(section: String): List<String> =
        Regex("<span class=\"role-name\">([^<]+)</span>").findAll(section).map { it.groupValues[1] }.toList()

    private fun roleCounts(section: String): List<Int> =
        Regex("<td class=\"role-count\">(\\d+)</td>").findAll(section).map { it.groupValues[1].toInt() }.toList()

    private fun chipCount(row: String): Int = occurrences(row, "<span class=\"chip perm\">")

    private fun chipLabels(row: String): List<String> =
        Regex("<span class=\"chip perm\">([^<]+)</span>").findAll(row).map { it.groupValues[1] }.toList()

    /** The option words of the select carrying [selectId], in the order they print. */
    private fun options(html: String, selectId: String): List<String> =
        optionWords(html.substringAfter("id=\"$selectId\"").substringBefore("</select>"))

    /** The option words of the first select in [section] that starts at [start]. */
    private fun optionsAt(section: String, start: String): List<String> =
        optionWords(section.substringAfter(start).substringBefore("</select>"))

    private fun optionWords(select: String): List<String> =
        Regex("<option[^>]*>([^<]*)</option>").findAll(select).map { it.groupValues[1] }.toList()

    /** The one option a select prints as already chosen. */
    private fun selectedOption(html: String, selectId: String): String =
        Regex("<option[^>]*selected[^>]*>([^<]*)</option>")
            .findAll(html.substringAfter("id=\"$selectId\"").substringBefore("</select>"))
            .map { it.groupValues[1] }.singleOrNull()
            ?: error("no single selected option in $selectId")

    /** The popup's tick boxes: each permission key, and whether it prints checked, in form order. */
    private fun picks(modalHtml: String): List<Pair<String, Boolean>> =
        Regex("<label class=\"perm-pick\">\\s*<input([^>]*)>").findAll(modalHtml).map { match ->
            val attrs = match.groupValues[1]
            val key = Regex("value=\"([^\"]+)\"").find(attrs)!!.groupValues[1]
            key to attrs.contains("checked=\"checked\"")
        }.toList()

    private fun ticked(modalHtml: String): List<String> = picks(modalHtml).filter { it.second }.map { it.first }

    // --- the roles table --------------------------------------------------------------------------

    @Test
    fun `the roles table is the picture's rows, with its chip counts and the accounts on file`() {
        // A role with nothing ticked is legitimate, so the empty branch is on the page too.
        assertTrue(RoleService.save(0, "Observer", emptySet()).saved)
        val table = rolesPanel(page())
        val rows = seededRoles + "Observer"

        assertEquals(rows, roleNames(table), "the table is not the catalogue in the catalogue's own order")
        assertTrue(
            table.contains("<h3 class=\"set-section\">Roles &amp; permissions</h3>"),
            "the panel does not carry the picture's heading",
        )
        assertTrue(table.contains("<th>Role</th>"), "the first column lost its heading")
        assertTrue(table.contains("<th>Permissions</th>"), "the chips column lost its heading")
        assertTrue(
            table.contains("<th class=\"role-count-head\">Employees</th>"),
            "the headcount column is not the one the stylesheet narrows",
        )
        assertTrue(table.contains("<th></th>"), "the Edit column has no heading slot")

        assertEquals(
            listOf(13, 13, 9, 1, 0),
            rows.map { chipCount(roleRow(table, it)) },
            "a row does not hold the chips the picture ticks",
        )
        // The chips print in the enum's order, so a row reads the way the popup's boxes do. (One label carries
        // an `&`, and the renderer writes it as an entity.)
        assertEquals(
            Permission.entries.map { it.label.replace("&", "&amp;") },
            chipLabels(roleRow(table, "Owner")),
            "Owner's chips are not the picture's words, in the picture's order",
        )
        assertEquals(
            listOf("View teammates' tasks"),
            chipLabels(roleRow(table, "Employee")),
            "the default role's one chip is not the picture's one chip",
        )
        assertTrue(
            roleRow(table, "Employee").contains("<span class=\"role-default\"> (default)</span>"),
            "the default role is not marked the way the picture marks it",
        )
        assertFalse(roleRow(table, "Owner").contains("role-default"), "a seeded role is marked as the default")
        assertTrue(
            roleRow(table, "Observer").contains("No permissions"),
            "an empty role prints nothing at all where its rights should be",
        )

        // Six accounts: Owner 1, Administrator 0, Project Manager 1, Employee 4 — the two mismatched rows and
        // the one whose role was deleted included, because an `access` word and a position level are no longer
        // counted anywhere.
        assertEquals(listOf(1, 0, 1, 4, 0), roleCounts(table), "the EMPLOYEES column is not the accounts on file")
        assertEquals(6, roleCounts(table).sum(), "the column does not add up to the accounts on the file")

        // Every row is one Edit, and every Edit is a live opener onto the popup — the added row included.
        assertEquals(5, occurrences(table, "name=\"roleedit\""), "a role row lost its opener")
        assertEquals(5, occurrences(table, "ghost-btn btn-sm"), "an opener is not the picture's button")
        assertEquals(1, occurrences(table, "+ New role"), "the panel lost its right-hand control")
        assertEquals(
            6,
            occurrences(table, "data-live-target=\"#roleModal\""),
            "an opener would navigate instead of opening — the panel's own button counted too",
        )
        assertTrue(
            table.contains("a position is only the job title beside its name"),
            "the caption still promises a rank behind the role",
        )
    }

    @Test
    fun `a reader without the roles chip reads the table and presses nothing`() {
        val html = noRolesPage()
        val table = rolesPanel(html)

        // The table is what tells this reader which role a colleague is filed under, so it stays in full.
        assertEquals(seededRoles, roleNames(table), "the read-only table lost a row")
        assertEquals(
            listOf(13, 13, 9, 1),
            seededRoles.map { chipCount(roleRow(table, it)) },
            "the read-only table lost a chip",
        )
        assertEquals(6, roleCounts(table).sum(), "the read-only headcount column is not the roster")
        // ... and every press that would change it is gone: the button, its form, and the column holding them.
        assertFalse(table.contains("+ New role"), "a reader without the chip was offered a new role")
        assertFalse(table.contains("roleedit"), "a reader without the chip was offered a row's popup")
        assertFalse(table.contains("ghost-btn btn-sm"), "an Edit button survived the flag")
        assertFalse(table.contains("<th></th>"), "the actions column kept its heading slot")
        assertFalse(html.contains("id=\"roleModal\""), "a popup nothing can open was put on the page")

        // The People sheet answers the same flag in its own column: a tinted word, not a picker.
        val sheet = peopleSheet(html)
        assertFalse(sheet.contains("role-pick"), "a reader without the chip was offered a role picker")
        assertFalse(sheet.contains("/app/access-control/role"), "a role write form printed for a reader who may not write")
        assertTrue(sheet.contains("<span class=\"chip atrisk\">Owner</span>"), "the Owner lost its tint")
        assertTrue(sheet.contains("<span class=\"chip planning\">Project Manager</span>"))
        assertTrue(sheet.contains("<span class=\"chip done\">Employee</span>"), "the default role lost its tint")
        assertTrue(
            sheet.contains("<span class=\"assignee none\">No account</span>"),
            "a roster row with no login vanished from the sheet",
        )
        assertTrue(
            html.contains("What an account may do comes from its role above"),
            "the positions card still calls a job title a rank",
        )
    }

    // --- the popup's two openers -------------------------------------------------------------------

    @Test
    fun `the address opens the popup, and only for the chip that can save what it opens`() {
        val pmId = roleId(AccessRoles.PROJECT_MANAGER)

        val closed = roleFormOf(query(), canRoles = true)
        assertFalse(closed.isOpen, "the ordinary page draws a popup")
        assertFalse(closed.isEdit)

        val creating = roleFormOf(query("newrole" to "1"), canRoles = true)
        assertTrue(creating.isOpen, "?newrole=1 drew a shut form")
        assertFalse(creating.isEdit, "+ New role was read as an edit of row 0")
        assertEquals("", creating.name)
        assertEquals(emptySet<Permission>(), creating.checked, "a new role opens already ticked")

        val editing = roleFormOf(query("roleedit" to pmId.toString()), canRoles = true)
        assertTrue(editing.isOpen, "?roleedit drew a shut form")
        assertTrue(editing.isEdit)
        assertEquals(AccessRoles.PROJECT_MANAGER, editing.name)
        assertEquals(
            RoleService.byId(pmId)!!.permissions.map { it.key }.sorted(),
            editing.checked.map { it.key }.sorted(),
            "the edit popup opens with the wrong boxes ticked",
        )

        // An id that is not a role, a word where a number belongs, and id 0 all answer the ordinary page — not a
        // create the reader did not ask for, and not a popup half-filled from a row that has gone.
        assertFalse(roleFormOf(query("roleedit" to "9999"), canRoles = true).isOpen, "a gone id opened a popup")
        assertFalse(roleFormOf(query("roleedit" to "abc"), canRoles = true).isOpen, "a word opened a popup")
        assertFalse(roleFormOf(query("roleedit" to "0"), canRoles = true).isOpen, "id 0 opened an edit of nothing")
        listOf(query("newrole" to "1"), query("roleedit" to pmId.toString())).forEach { asked ->
            assertFalse(
                roleFormOf(asked, canRoles = false).isOpen,
                "the address opened a popup for a reader without the chip",
            )
        }
    }

    @Test
    fun `the popup is the picture's thirteen boxes, in order, and posts to the pair the page swaps`() {
        val html = page()
        val shut = modal(html)
        assertTrue(tag(html, "roleModal").contains("class=\"modal\""), "the popup lost its backdrop class")
        assertFalse(tag(html, "roleModal").contains("display:block"), "a closed popup prints itself open")
        assertEquals(13, picks(shut).size, "the create form is not the picture's thirteen tick boxes")
        assertEquals(Permission.entries.map { it.key }, picks(shut).map { it.first }, "the boxes are not in the picture's order")
        assertTrue(ticked(shut).isEmpty(), "a create form opens with something already ticked")
        assertTrue(shut.contains("<h3>New role</h3>"), "the create popup is not titled as a create")
        assertTrue(shut.contains("Add role"), "the create popup's button is not the picture's")
        assertTrue(shut.contains("A new role starts with no permissions"), "the create popup hides what it opens with")
        assertFalse(shut.contains("name=\"id\""), "a create posts somebody else's row id")

        val pmId = roleId(AccessRoles.PROJECT_MANAGER)
        val editHtml = page(roleForm = roleFormOf(query("roleedit" to pmId.toString()), canRoles = true))
        val open = modal(editHtml)
        assertTrue(
            tag(editHtml, "roleModal").contains("style=\"display:block\""),
            "?roleedit drew the popup shut",
        )
        assertTrue(open.contains("<h3>Change role</h3>"), "the edit popup is not titled as an edit")
        assertTrue(open.contains("Save role"), "the edit popup's button is not the picture's")
        assertTrue(
            open.contains("Everybody holding this role picks up the new set"),
            "the edit popup hides what a save does to its holders",
        )
        assertTrue(open.contains("<input type=\"hidden\" name=\"id\" value=\"$pmId\">"), "the edit posts the wrong row")
        assertEquals(
            RoleService.byId(pmId)!!.permissions.map { it.key }.sorted(),
            ticked(open).sorted(),
            "the boxes a row's Edit prints ticked are not that row's chips",
        )
        assertEquals(13, picks(open).size, "the edit form lost a box nobody holds")

        // The three hooks the in-place swap needs: this popup's own close pair, its name box, its block pair.
        assertEquals(2, occurrences(open, "data-role-modal-close=\"true\""), "the popup lost its × or its Cancel")
        assertTrue(open.contains("id=\"roleName\""), "the popup has no name box for the caret to land in")
        assertTrue(open.contains("action=\"/app/roles/save\""), "the popup posts somewhere else")
        assertTrue(open.contains("data-live-target=\"#rolesPanel\""), "a save would navigate instead of moving the table")
        assertTrue(open.contains("data-live-extra=\"#roleModal\""), "a save leaves the form up over the new table")
    }

    @Test
    fun `a refused save comes back with the popup open, the word typed and every opener still there`() {
        val asked = roleFormOfForm(
            postForm(
                "name" to listOf(" Site Supervisor "),
                "perm" to listOf("create_projects", "manage_the_moon", "create_projects", "manage_buckets"),
            ),
            editing = null,
        )
        assertTrue(asked.isOpen, "a refused create is drawn with the form shut")
        assertEquals("Site Supervisor", asked.name, "the word the reader typed was thrown away")
        assertEquals(
            setOf(Permission.CREATE_PROJECTS, Permission.MANAGE_BUCKETS),
            asked.checked,
            "a chip the app cannot name, or a box posted twice, was kept",
        )
        assertEquals(0, asked.editId, "a create was read as an edit")

        val html = page(roleForm = asked.copy(nameError = "That role name is taken."))
        val sheet = modal(html)
        assertTrue(tag(html, "roleModal").contains("style=\"display:block\""), "the refusal closed the popup")
        assertTrue(
            sheet.contains("<p class=\"field-err\">That role name is taken.</p>"),
            "the refusal has no sentence under the box it refused",
        )
        assertTrue(sheet.contains("value=\"Site Supervisor\""), "the box is empty in the page the refusal answers with")
        assertEquals(
            listOf("create_projects", "manage_buckets"),
            ticked(sheet),
            "the refusal lost the boxes that were ticked",
        )
        assertFalse(sheet.contains("name=\"id\""), "a create's refusal answers with somebody else's row id")

        // The table comes back as it is drawn for an open form, so the reader can leave and re-enter a popup.
        val table = rolesPanel(html)
        assertEquals(1, occurrences(table, "+ New role"), "the refusal took the panel's own control away")
        assertEquals(4, occurrences(table, "name=\"roleedit\""), "the refusal took a row's Edit away")
        assertEquals(4, occurrences(table, "ghost-btn btn-sm"), "the refusal took a row's button away")

        // The same form opened on a real row keeps that row's id, so a refusal cannot turn an edit into a create.
        val pmId = roleId(AccessRoles.PROJECT_MANAGER)
        val again = roleFormOfForm(
            postForm("id" to listOf(pmId.toString()), "name" to listOf("Site Manager")),
            RoleService.byId(pmId),
        )
        assertEquals(pmId, again.editId, "a refused edit lost its row and would add a second one")
        assertEquals("Site Manager", again.name)
        assertTrue(again.checked.isEmpty(), "boxes nobody posted were invented")
    }

    // --- the People sheet's own role column --------------------------------------------------------

    @Test
    fun `the people sheet files every name under its role, and the roles chip alone may change it`() {
        val html = page()
        val sheet = peopleSheet(html)

        // The column's own heading, and no trace of the rank word the picture retired.
        assertTrue(sheet.contains("<th>Role</th>"), "the column is not the picture's Role")
        assertFalse(sheet.contains(">Access<"), "the sheet still has an Access column")
        assertFalse(html.contains("Access level"), "a panel still prints the retired ladder")

        listOf(owner, manager, worker, positioned, adminWord, roleless).forEach {
            assertEquals(seededRoles, options(html, "role-pick-$it"), "$it's picker is not the catalogue")
        }
        // Each picker already sits on the row's own role — including the two rows whose word or job title says
        // something else, which is the promise that a role decides and the rest of the row is a label.
        assertEquals(AccessRoles.OWNER, selectedOption(html, "role-pick-$owner"))
        assertEquals(AccessRoles.PROJECT_MANAGER, selectedOption(html, "role-pick-$manager"))
        assertEquals(AccessRoles.EMPLOYEE, selectedOption(html, "role-pick-$adminWord"), "the Admin word was read as a role")
        assertEquals(
            AccessRoles.EMPLOYEE,
            selectedOption(html, "role-pick-$positioned"),
            "the position's level was read as a role",
        )
        assertEquals(
            AccessRoles.EMPLOYEE,
            selectedOption(html, "role-pick-$roleless"),
            "an account whose role was deleted was not read back as the default",
        )
        assertTrue(sheet.contains("<span class=\"pname\">Ganbold Erdene</span>"))
        assertTrue(sheet.contains("Site Supervisor"), "the job title stopped printing beside the name")

        // The re-filed account moves a count in the table above, so its form names both blocks.
        val firstForm = sheet.substringAfter("action=\"/app/access-control/role\"").substringBefore("</form>")
        assertTrue(firstForm.contains("data-live-target=\"#role-cell-$owner\""), "the picker would navigate")
        assertTrue(firstForm.contains("data-live-extra=\"#rolesPanel\""), "a re-filed account leaves the EMPLOYEES column stale")
        assertTrue(firstForm.contains("name=\"employeeNumber\" value=\"$owner\""), "the picker does not say whose role it sets")
        assertTrue(firstForm.contains("data-autosubmit"), "the picker has to be submitted by hand")
        assertEquals(6, occurrences(sheet, "action=\"/app/access-control/role\""), "an account row lost its picker")
        assertFalse(sheet.contains("role-pick-$noAccount"), "the row with no account was offered a role")
    }

    @Test
    fun `the sheet's headings and the add-user card follow the two chips, not one rank`() {
        // Both chips: the People columns in full, a role picker in the card, and the sheet's own script.
        val full = peopleSheet(page())
        listOf("Employee", "Name", "Position", "Department", "Role", "Password", "Delete").forEach {
            assertTrue(full.contains("<th>$it</th>"), "the sheet lost its $it column")
        }
        val card = addCard(page())
        assertEquals(seededRoles, optionsAt(card, "<select name=\"role\""), "the card's picker is not the catalogue")
        assertTrue(
            card.contains("<option selected=\"selected\">Employee</option>"),
            "a new account does not open in the catalogue's default role",
        )
        assertTrue(
            card.contains("the role to the catalogue's default"),
            "the card no longer says where an account with no chosen role starts",
        )

        // *Manage employees* alone: the card is theirs, the picker is not.
        val peopleOnly = noRolesPage()
        assertTrue(peopleOnly.contains("<h3>Add user</h3>"), "the people chip lost its own card")
        assertFalse(addCard(peopleOnly).contains("name=\"role\""), "a reader without the roles chip picks a role")
        assertTrue(peopleOnly.contains("<h3>Manage positions</h3>"), "the people chip lost the positions card")

        // *Manage roles & permissions* alone: the tables print, and the four people controls do not.
        val rolesOnly = page(owner, canPeople = false, canRoles = true)
        val sheet = peopleSheet(rolesOnly)
        assertFalse(sheet.contains("<th>Password</th>"), "a reader without the people chip was offered a password box")
        assertFalse(sheet.contains("<th>Delete</th>"), "a reader without the people chip was offered a delete")
        assertFalse(sheet.contains("/app/access-control/update"), "an editable position cell printed")
        assertFalse(rolesOnly.contains("<h3>Add user</h3>"), "the add card printed for a reader who may not add")
        assertFalse(rolesOnly.contains("Manage positions"), "the positions card printed for a reader who may not manage them")
        assertTrue(sheet.contains("<th>Role</th>"), "the role column left with the picker it did not have")
        assertTrue(
            sheet.contains("whoever manages roles &amp; permissions"),
            "the sheet no longer says who sets the word beside a name",
        )
        assertTrue(
            sheet.contains("Manage employees is what adds and removes accounts"),
            "the sheet no longer says what the missing columns are for",
        )

        // Stage 123: an account that holds *Manage employees* but is not `EMP0000` still prints the Position
        // picker, the Delete column and the Add-user card — and no password control anywhere, because another
        // person's password is the super admin's alone.
        val administrator = page(adminWord, canPeople = true, canRoles = true)
        val adminSheet = peopleSheet(administrator)
        assertFalse(adminSheet.contains("<th>Password</th>"), "an Administrator was offered the password column")
        assertFalse(adminSheet.contains("/app/access-control/password"), "an Administrator was offered a password setter")
        assertFalse(addCard(administrator).contains("name=\"password\""), "an Administrator was offered the add-user password box")
        assertTrue(adminSheet.contains("<th>Delete</th>"), "an Administrator lost the delete column with the password one")
        assertTrue(
            adminSheet.contains("only the super admin can set another one"),
            "the sheet does not say who sets a password",
        )

        // The page's own script is the popup's way out, and it loads for both readings.
        assertEquals(1, occurrences(page(), "access-control.js"), "the page's script is not loaded once")
        assertEquals(1, occurrences(peopleOnly, "access-control.js"), "the read-only page lost its script")
    }

    // --- the People sheet's own department column (Stage 118) --------------------------------------

    @Test
    fun `the department column prints for everybody, and the pair holding every chip alone may change it`() {
        val html = page(canDepartments = true)
        val sheet = peopleSheet(html)

        // The column sits between Position and Role, and it follows the *roster*: the row with no login
        // has a department too, because a department is the person's, not their account's.
        assertTrue(sheet.contains("<th>Department</th>"), "the sheet lost the Department column")
        assertTrue(
            sheet.indexOf("<th>Position</th>") < sheet.indexOf("<th>Department</th>") &&
                sheet.indexOf("<th>Department</th>") < sheet.indexOf("<th>Role</th>"),
            "the department did not print between Position and Role",
        )

        // Each picker offers the nine in the request's order after the stored word. None of this fixture's
        // roster words is on the list, so every row proves the placeholder: selected, and disabled so the
        // old free-text word can never be posted back as a department.
        listOf(
            owner to "IT / Systems", manager to "Projects", worker to "Projects", positioned to "Operations",
            noAccount to "Workshop", adminWord to "Projects", roleless to "Workshop",
        ).forEach { (emp, stored) ->
            assertEquals(
                listOf("— $stored —") + EmployeeService.DEPARTMENTS,
                options(html, "department-pick-$emp"),
                "$emp's picker is not the stored word and the nine",
            )
            assertEquals("— $stored —", selectedOption(html, "department-pick-$emp"), "$emp did not open on its stored word")
            val chosen = Regex("<option[^>]*selected[^>]*>")
                .find(sheet.substringAfter("id=\"department-pick-$emp\"").substringBefore("</select>"))!!.value
            assertTrue(chosen.contains("disabled"), "$emp's stored word can be posted back")
        }

        // The form names the one cell it replaces, says whose department it sets, and submits itself.
        val form = sheet.substringAfter("action=\"/app/access-control/department\"").substringBefore("</form>")
        assertTrue(form.contains("data-live-target=\"#department-cell-$owner\""), "the picker would navigate")
        assertTrue(
            form.contains("name=\"employeeNumber\" value=\"$owner\""),
            "the picker does not say whose department it sets",
        )
        assertTrue(form.contains("data-autosubmit"), "the picker has to be submitted by hand")
        assertEquals(
            7, occurrences(sheet, "action=\"/app/access-control/department\""),
            "a roster row lost its department picker",
        )

        // The picker follows the all-chip pair rather than *Manage employees*: an account with every chip
        // but that one still files people, and one holding *Manage employees* alone reads words instead.
        assertEquals(
            7,
            occurrences(
                peopleSheet(page(owner, canPeople = false, canRoles = true, canDepartments = true)),
                "action=\"/app/access-control/department\"",
            ),
            "the department picker followed the people chip",
        )
        val readOnly = peopleSheet(page())
        assertFalse(readOnly.contains("/app/access-control/department"), "a reader without the pair was offered the picker")
        assertFalse(readOnly.contains("department-pick-"), "a reader without the pair was offered the picker")
        assertTrue(readOnly.contains("<th>Department</th>"), "the department column left with the picker it did not have")
        assertEquals(
            "IT / Systems",
            readOnly.substringAfter("id=\"department-cell-$owner\">").substringBefore("</td>"),
            "the department stopped printing as words",
        )

        // A person already filed under one of the nine opens on that word and needs no placeholder.
        TeamRepository.upsert(Member(worker, "Bat-Erdene Tudev", "Finance", "Engineer"))
        val moved = peopleSheet(page(canDepartments = true))
        assertEquals(EmployeeService.DEPARTMENTS, options(moved, "department-pick-$worker"), "the nine grew a placeholder")
        assertEquals("Finance", selectedOption(moved, "department-pick-$worker"))
    }
}
