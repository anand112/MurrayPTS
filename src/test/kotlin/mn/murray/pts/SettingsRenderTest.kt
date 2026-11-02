package mn.murray.pts

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.Parameters
import kotlinx.html.DIV
import kotlinx.html.div
import kotlinx.html.stream.createHTML
import mn.murray.pts.db.Migrations
import mn.murray.pts.domain.DEFAULT_PROJECT_BUCKETS
import mn.murray.pts.domain.Member
import mn.murray.pts.domain.PROJECT_TYPES
import mn.murray.pts.repository.PositionRepository
import mn.murray.pts.repository.ProjectRepository
import mn.murray.pts.repository.TaskRepository
import mn.murray.pts.repository.TeamRepository
import mn.murray.pts.repository.TypeDefaultBucketRepository
import mn.murray.pts.repository.UserRepository
import mn.murray.pts.security.PasswordHasher
import mn.murray.pts.service.BucketDefaultsService
import mn.murray.pts.service.EmployeeColumn
import mn.murray.pts.service.EmployeeDraft
import mn.murray.pts.service.EmployeeField
import mn.murray.pts.service.EmployeeFilter
import mn.murray.pts.service.EmployeeService
import mn.murray.pts.service.MailSwitch
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
 * What the Settings page (Stage 46) puts on the screen, and what it keeps off it.
 *
 * The picture is the specification: a "Team" label, a card that opens a new account, a search box and
 * two pickers over a flat roster of seven columns plus the row's two buttons, and only then the reader's
 * own account and the password form that were already on this page. So the first tests here read the
 * sheet as markup — column by column, cell by cell — and check the two rights the sheet prints for:
 * a pencil that is an administrator's, and a bin that says in words why it cannot press.
 *
 * The other half is the update contract, which is what makes the sheet a page rather than a list of
 * links. The toolbar and the count line live outside the swapped region, so a search repaints rows and
 * nothing else; the seven headers are members of one carriage form of their own rather than buttons of
 * the toolbar's, because the toolbar already carries a hidden `sort` and a second field of that name
 * would state the order twice; every row's own form carries the whole view, prefixed where the row's
 * fields would collide with it. A form inside a form is dropped by the parser, so that promise is
 * measured rather than assumed — as are the column shares and the classes, which the stylesheet has to
 * agree with and which the markup gives no sign of.
 *
 * `createHTML()` breaks a line for a block element and leaves an inline run on one line, so the
 * assertions are made on one element (`<td class="…">…</td>`, `<span …>…</span>`) or on one element's
 * attribute run, never across two blocks.
 */
class SettingsRenderTest {

    private lateinit var ds: HikariDataSource

    private var engineerPos = 0
    private var supervisorPos = 0

    /** The super admin: everybody's pencil, and the row the app will not let go. */
    private val superAdmin = "EMP0000"

    /** The reader most of these pages are drawn for: a manager, who may not set roles. */
    private val manager = "EMP0001"

    /** An ordinary account with two open cards and one closed. */
    private val worker = "EMP0002"

    /** `Viewer` in its own column, `Manager` through the position it is filed under. */
    private val positioned = "EMP0003"

    /** On the roster with no login: the row whose pencil creates rather than changes. */
    private val noAccount = "EMP0004"

    /** Raw `Admin` in its own column, `Manager` through its position — what the delete bar compares. */
    private val adminAccess = "EMP0005"

    /** No department, no position link, ten open cards: the sheet's two fallbacks and its number order. */
    private val noDepartment = "EMP0006"

    /** The eight columns, in the order the picture reads them. */
    private val columns = listOf(
        "t-name" to "NAME", "t-emp" to "EMPLOYEE #", "t-position" to "POSITION",
        "t-department" to "DEPARTMENT", "t-role" to "ROLE", "t-email" to "EMAIL",
        "t-tasks" to "OPEN TASKS", "t-actions" to "",
    )

    @BeforeEach
    fun setUp(@TempDir dir: File) {
        ds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${File(dir, "settings-page.db").absolutePath}"
                connectionInitSql = "PRAGMA foreign_keys = ON"
                maximumPoolSize = 1
            },
        )
        Migrations.migrate(ds)
        Database.connect(ds)

        PositionRepository.insert("Project Manager", "Manager", "")
        engineerPos = PositionRepository.insert("Engineer", "Viewer", "")
        supervisorPos = PositionRepository.insert("Site Supervisor", "Manager", "")

        // The roster's free-text job word and the catalog entry an account is filed under live in two
        // different tables, so EMP0003 is given two different words on purpose.
        TeamRepository.upsert(Member(superAdmin, "Super Admin", "IT / Systems", "Super Admin"))
        TeamRepository.upsert(Member(manager, "Munkhtsetseg Otchontoor", "Projects", "Project Manager"))
        TeamRepository.upsert(Member(worker, "Bat-Erdene Tudev", "Projects", "Engineer"))
        TeamRepository.upsert(Member(positioned, "Ganbold Erdene", "Operations", "Senior Surveyor"))
        TeamRepository.upsert(Member(noAccount, "Nyambaapurev", "Workshop", "Engineer"))
        TeamRepository.upsert(Member(adminAccess, "Sundui Bayar", "Projects", "Project Manager"))
        // `users.employee_number` is a foreign key onto `team_members`, so every account is a person
        // first; this row has neither a department nor a position filled in.
        TeamRepository.upsert(Member(noDepartment, "Nomin Erdene", "", ""))

        user(superAdmin, "Super Admin", "Admin", null, superAdmin = true)
        user(manager, "Munkhtsetseg Otchontoor", "Manager", null)
        user(worker, "Bat-Erdene Tudev", "Viewer", engineerPos, email = "bat@murray.mn")
        user(positioned, "Ganbold Erdene", "Viewer", supervisorPos)
        user(adminAccess, "Sundui Bayar", "Admin", supervisorPos)
        user(noDepartment, "Nomin Erdene", "Viewer", null)

        val crusher = ProjectRepository.insert(
            code = "M-01250", name = "Erdenet Crusher Modernisation", status = "In progress", progress = 40,
            managerEmpNo = manager, due = "2026-12-01", type = "Fabrication/Manufacturing",
            client = "Erdenet Mining Corporation", revenue = 2_340_000_000.0,
        )
        card(crusher, worker, "Detail plate drawings - Rev A", "In progress")
        card(crusher, worker, "RFQ - 20mm steel plate", "Not started")
        card(crusher, worker, "Site visit report", "Completed")
        for (n in 1..10) card(crusher, noDepartment, "Open card $n", "In progress")
    }

    @AfterEach
    fun tearDown() = ds.close()

    private fun card(projectId: Int, assignee: String, title: String, status: String): Int =
        TaskRepository.insert(
            kind = "ASSIGNED", title = title, projectId = projectId, due = "", priority = "Medium",
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

    // --- the harness --------------------------------------------------------------------------------

    private fun render(block: DIV.() -> Unit): String = createHTML().div(block = block)

    /**
     * The page as one of the app's three readers gets it, with the sheet read for the same account.
     *
     * [form] defaults to the route's ordinary answer — no `?edit=` and no `?new=1`, but still carrying
     * this reader's rights, because `/app/settings` always passes `canManage` and `canAdmin` into
     * [employeeFormOf]. A test that wants the popup open says so with [createForm] or [editForm].
     */
    private fun page(
        who: String = manager,
        canManage: Boolean = true,
        canAdmin: Boolean = false,
        filter: EmployeeFilter = EmployeeFilter(),
        form: EmployeeForm? = null,
        note: PasswordStatus? = null,
        mailStatus: PasswordStatus? = null,
        mailDetail: String? = null,
        bucketNote: PasswordStatus? = null,
        bucketDetail: String? = null,
        bucketDefaultsType: String = PROJECT_TYPES.first(),
        typeNote: PasswordStatus? = null,
        typeDetail: String? = null,
        typeBeingEdited: String = "",
    ): String = render {
        settingsContent(
            session = UserSession(who),
            sheet = EmployeeService.sheet(filter, who, canManage, canAdmin),
            filter = filter,
            form = form ?: employeeFormOf(query(), null, null, "", canManage, canAdmin),
            canManage = canManage,
            canAdmin = canAdmin,
            note = note,
            mailStatus = mailStatus,
            mailDetail = mailDetail,
            bucketNote = bucketNote,
            bucketDetail = bucketDetail,
            bucketDefaultsType = bucketDefaultsType,
            typeNote = typeNote,
            typeDetail = typeDetail,
            typeBeingEdited = typeBeingEdited,
        )
    }

    private fun adminPage(
        filter: EmployeeFilter = EmployeeFilter(),
        form: EmployeeForm? = null,
    ): String = page(superAdmin, canManage = true, canAdmin = true, filter = filter, form = form)

    private fun viewerPage(filter: EmployeeFilter = EmployeeFilter()): String =
        page(worker, canManage = false, canAdmin = false, filter = filter)

    private fun query(vararg pairs: Pair<String, String>): Parameters = Parameters.build {
        pairs.forEach { (key, value) -> append(key, value) }
    }

    /** The popup as `?new=1` draws it for this reader — the route's own call, with the route's own rights. */
    private fun createForm(who: String = manager, canManage: Boolean = true, canAdmin: Boolean = false): EmployeeForm =
        employeeFormOf(query("new" to "1"), null, null, "", canManage, canAdmin)

    /** The popup as `?edit=<employee number>` draws it, carrying [view] so the sheet behind it is the same one. */
    private fun editForm(
        employeeNumber: String,
        view: EmployeeFilter = EmployeeFilter(),
        who: String = superAdmin,
        canManage: Boolean = true,
        canAdmin: Boolean = true,
    ): EmployeeForm {
        val row = EmployeeService.row(employeeNumber, who, canManage, canAdmin)
        val account = row?.let { EmployeeService.account(it.employeeNumber) }
        return employeeFormOf(
            query = Parameters.build {
                append("edit", employeeNumber)
                if (view.text.isNotEmpty()) append("q", view.text)
                if (view.position.isNotEmpty()) append("position", view.position)
                if (view.role.isNotEmpty()) append("role", view.role)
                if (view.sortValue.isNotEmpty()) append("sort", view.sortValue)
            },
            row = row,
            account = account,
            positionName = account?.let { EmployeeService.positionOf(it) }.orEmpty(),
            mayAdd = canManage,
            mayEdit = canAdmin,
        )
    }

    private fun occurrences(html: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(html).count()

    /** The opening tag whose attributes carry [id] — the whole attribute run of one element. */
    private fun tag(html: String, id: String): String =
        Regex("<[^>]*id=\"$id\"[^>]*>").find(html)?.value ?: error("no element with id=$id")

    /** The opening tag of the first element whose class attribute is exactly [klass]. */
    private fun tagOf(html: String, klass: String): String =
        Regex("<[^>]*class=\"$klass\"[^>]*>").find(html)?.value ?: error("no element with class=$klass")

    /** One row's markup, from its own id attribute to the `</tr>` that closes it. */
    private fun row(html: String, employeeNumber: String): String {
        val start = html.indexOf("id=\"ts-row-$employeeNumber\"")
            .takeIf { it >= 0 } ?: error("no row for $employeeNumber on the page")
        return html.substring(start, html.indexOf("</tr>", start))
    }

    /** The live region — everything a search, a picker or a row write replaces. */
    private fun region(html: String): String =
        html.substringAfter("id=\"team-board\"").substringBefore("id=\"employeeModal\"")

    /**
     * The Team sheet on its own — [region] cut short at the first app panel below it.
     *
     * Since Stage 63 a project category row carries the same two buttons a roster row does, in the same
     * classes, so a page-wide count of `🗑` or `icon-btns` is no longer a count of the sheet's rows. The
     * guarantee is unchanged; only its scope is now the sheet rather than the whole page.
     */
    private fun sheet(html: String): String =
        region(html).substringBefore("id=\"projectTypes\"")

    /** The toolbar's form: the search box, the two pickers and the count line. */
    private fun toolbar(html: String): String =
        html.substringAfter("id=\"tsFilter\"").substringBefore("</form>")

    /**
     * The headers' carriage — the fields it sends beside the `sort` the pressed header carries. `tag()`
     * stops at the opening tag, so the three hidden view fields live outside it and need this section.
     */
    private fun carriage(html: String): String =
        html.substringAfter("id=\"tsSort\"").substringBefore("</form>")

    /** The popup's own markup, from its id to the script that drives it. */
    private fun modal(html: String): String =
        html.substringAfter("id=\"employeeModal\"").substringBefore("/static/js/employee-modal.js")

    /** One column's header cell, from its `<th` to the `</th>` that closes it. */
    private fun header(html: String, klass: String): String {
        val start = html.indexOf("<th class=\"$klass\"").takeIf { it >= 0 } ?: error("no $klass header")
        return html.substring(start, html.indexOf("</th>", start) + "</th>".length)
    }

    /** The order that header is asking for, read off its own button. */
    private fun asked(html: String, klass: String): String =
        Regex("value=\"([^\"]*)\"").find(header(html, klass))?.groupValues?.get(1)
            ?: error("the $klass header is not a control")

    /** A row's bin, with its title and its `disabled`. */
    private fun bin(html: String, employeeNumber: String): String =
        Regex("<button[^>]*>🗑</button>").find(row(html, employeeNumber))?.value
            ?: error("no bin on $employeeNumber")

    /** The bins on the page that cannot press — one per greyed row, whatever the attribute spelling is. */
    private fun greyedBins(html: String): Int =
        Regex("<button[^>]*disabled[^>]*>🗑</button>").findAll(html).count()

    /**
     * A whole `<form>` element — its attributes *and* the fields inside it.
     *
     * `tag()` and a `Regex("…<form…>[^>]*>")` both stop at the opening tag, which is enough for a
     * `data-live-*` promise but not for the hidden fields that carry the reader's view, and a test that
     * looks for those in an opening tag passes only when the form is empty.
     */
    private fun formSection(html: String, opening: String): String {
        val start = html.indexOf(opening).takeIf { it >= 0 } ?: error("no form starting \"$opening\"")
        return html.substring(start, html.indexOf("</form>", start) + "</form>".length)
    }

    /** One row's pencil: the GET that opens the popup onto this sheet. */
    private fun openerForm(html: String, employeeNumber: String): String =
        formSection(
            row(html, employeeNumber),
            "<form action=\"/app/settings\" method=\"get\" class=\"opener-form ts-action\"",
        )

    /** One row's bin: the POST that takes the account away. */
    private fun deleteForm(html: String, employeeNumber: String): String =
        formSection(row(html, employeeNumber), "<form action=\"/app/settings/employee/delete\"")

    /** The popup's own write, read out of the page that carries it. */
    private fun saveForm(html: String): String =
        formSection(html, "<form action=\"/app/settings/employee/save\"")

    /** One select by the key it posts, read out of a section — the toolbar's come first on the page. */
    private fun selectNamed(html: String, name: String): String =
        Regex("<select name=\"$name\"[^>]*>.*?</select>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.value ?: error("no select named $name")

    /** The values one picker lists, in the order it prints them. */
    private fun options(select: String): List<String> =
        Regex("<option value=\"([^\"]*)\"").findAll(select).map { it.groupValues[1] }.toList()

    /** One "Your account" row: the value span that stands beside the given key. */
    private fun setRow(html: String, key: String): String =
        html.substringAfter("<span class=\"set-key\">$key</span>").substringBefore("</div>")

    /** The deepest form nesting on the page — 2 would be a form the browser silently drops. */
    private fun maxFormDepth(html: String): Int {
        var depth = 0
        var max = 0
        Regex("</?form\\b").findAll(html).forEach {
            if (it.value.startsWith("</")) depth-- else { depth++; max = maxOf(max, depth) }
        }
        assertEquals(0, depth, "the page's forms are not balanced")
        return max
    }

    private fun appCss(): String = File("src/main/resources/static/css/app.css").readText()

    // --- the sheet as the picture draws it ------------------------------------------------------------

    @Test
    fun `the team section is the sheet the picture reads, eight columns wide`() {
        val html = page()
        // "Team" is a small label above the card, not a page title — and it is the first of the page's
        // sections. A manager's page carries five: Team and Your account, plus the three settings *of the
        // app* that only management sees (Project types, Email notifications, Bucket defaults). The viewer's
        // page keeps the first two and nothing else — the password box was the third until Stage 99.
        assertTrue(html.contains("<h3 class=\"set-section\">Team</h3>"))
        assertEquals(5, occurrences(html, "class=\"set-section\""), "the page gained or lost a section")
        assertTrue(html.indexOf(">Team</h3>") < html.indexOf(">Your account</h3>"), "Team is not the page's first section")
        assertEquals(1, occurrences(html, "<table class=\"grid team-sheet\">"), "the roster printed a second table")
        columns.forEach { (klass, text) ->
            assertEquals(1, occurrences(html, "<th class=\"$klass\""), "$klass printed twice")
            val cell = header(html, klass)
            if (text.isEmpty()) {
                assertEquals("<th class=\"t-actions\"></th>", cell, "the sheet's last column headed itself")
            } else {
                assertTrue(cell.contains(">$text<"), "$klass does not say \"$text\": $cell")
            }
        }
        // `table-layout: fixed` takes its widths from the colgroup, so the columns that are sized are the
        // columns that are headed — same names, same order.
        assertEquals(
            columns.map { it.first },
            Regex("<col class=\"(t-[a-z]+)\"").findAll(html).map { it.groupValues[1] }.toList(),
            "the sheet's widths are not over its headers",
        )
        assertEquals(
            listOf(worker, positioned, manager, noDepartment, noAccount, adminAccess, superAdmin),
            Regex("id=\"ts-row-(EMP\\d+)\"").findAll(html).map { it.groupValues[1] }.toList(),
            "the default sheet is not the roster read down names A→Z",
        )
    }

    @Test
    fun `the name column is the person, and only their own row says it is theirs`() {
        val html = page()
        val tint = Regex("avatar sm (ll-a\\d)").find(row(html, worker))!!.groupValues[1]
        assertTrue(row(html, worker).contains("<span class=\"avatar sm $tint\">BT</span>"), "no avatar with the person's initials")
        assertTrue(row(html, worker).contains("<span class=\"ts-person-name\">Bat-Erdene Tudev</span>"), "the column prints something other than the roster's name")
        // A one-word name reduces to one letter rather than to two of the same.
        assertTrue(row(html, noAccount).contains(">N</span>"), "a one-word name was abbreviated past its first letter")
        assertEquals(1, occurrences(html, "class=\"chip ontrack ts-you\""), "the sheet marked two rows as the reader's")
        assertTrue(row(html, manager).contains("<span class=\"chip ontrack ts-you\">YOU</span>"), "the reader's own row is not marked")
        assertFalse(row(html, worker).contains("ts-you"), "the pill moved to somebody else's row")
        // The tint comes from the account, not the row, so the same person is the same colour on every
        // sheet in the app — including this one read by somebody else.
        assertEquals(
            tint,
            Regex("avatar sm (ll-a\\d)").find(row(adminPage(), worker))!!.groupValues[1],
            "one person, two colours",
        )
    }

    @Test
    fun `the role column prints what the account can actually do`() {
        val html = page()
        // Stage 110: the column prints the role the account is filed under, and a role is one of the
        // catalogue's names rather than the old three-level word. The tint follows the rights, not the word:
        // the two roles that hold *Manage roles & permissions* are the ones that read as a risk.
        assertTrue(row(html, worker).contains("<td class=\"t-role\"><span class=\"chip done\">Employee</span></td>"), "an ordinary account is not the catalogue's default role")
        assertTrue(row(html, manager).contains("<td class=\"t-role\"><span class=\"chip planning\">Project Manager</span></td>"), "a manager is not the admin's chip")
        assertTrue(row(html, superAdmin).contains("<td class=\"t-role\"><span class=\"chip atrisk\">Owner</span></td>"), "the super admin is not printed as Owner")
        // Two rows are deliberately two things at once, and the column answers the question the app asks
        // before a write: since Stage 110 the *role* is the answer and a position is only the job title
        // beside it, so a Viewer-level account filed under a Manager position prints Employee — which is
        // what the routes it is refused by say it is.
        assertTrue(row(html, positioned).contains("<td class=\"t-role\"><span class=\"chip done\">Employee</span></td>"), "the position's level was read as a role")
        assertTrue(row(html, adminAccess).contains("<td class=\"t-role\"><span class=\"chip atrisk\">Administrator</span></td>"), "an Administrator under a Manager position printed the job instead of the role")
        // A colleague with no login has no role at all — which is a gap in the row, not a failure of it.
        assertTrue(row(html, noAccount).contains("<td class=\"t-role\"><span class=\"ts-none\">—</span></td>"), "a person with no account was given a role")
        assertEquals(3, occurrences(html, "chip done"), "the Employee rows changed chip")
        assertEquals(1, occurrences(html, "chip planning"), "the Project Manager rows changed chip")
        assertEquals(2, occurrences(html, "chip atrisk"), "the rows holding the people risk changed chip")
    }

    @Test
    fun `the last four columns print the department, the job, the address and the count`() {
        val html = adminPage()
        assertTrue(row(html, worker).contains("<td class=\"t-department\">Projects</td>"), "the roster's department was not printed")
        assertTrue(row(html, noDepartment).contains("<td class=\"t-department\">—</td>"), "a blank department printed as an empty cell")
        // The catalog entry an account is filed under beats the roster's free-text word, and a row with
        // neither falls back to the same words the Access control page prints — so the column never blanks.
        assertTrue(row(html, positioned).contains("<td class=\"t-position\">Site Supervisor</td>"), "the roster's word beat the catalog link")
        assertTrue(row(html, noDepartment).contains("<td class=\"t-position\">Team Member</td>"), "a jobless row printed nothing instead of the fallback")
        // An account prints its stored address; a person without one prints the address their account
        // would be given, so the sheet and the popup cannot disagree about who this is.
        assertTrue(row(html, worker).contains("<span class=\"ts-email\">bat@murray.mn</span>"), "the stored address was not printed")
        assertTrue(row(html, noAccount).contains("<span class=\"ts-email\">nyambaapurev@murray.mn</span>"), "the sheet invented an address the account would not get")
        // Management counts everybody: three cards, one completed, so two open, and a ten where a text
        // sort would have read "10" ahead of "2".
        assertTrue(row(html, worker).contains("<td class=\"t-tasks\">2</td>"), "a completed card was counted as work")
        assertTrue(row(html, noDepartment).contains("<td class=\"t-tasks\">10</td>"), "the count column is not the open cards")
        assertTrue(row(html, noAccount).contains("<td class=\"t-tasks\">0</td>"), "nobody's cards read as a gap rather than a zero")
        assertTrue(row(html, manager).contains("<td class=\"t-tasks\">0</td>"), "an account with no cards invented some")
    }

    @Test
    fun `a roster row with no name prints its number rather than a blank`() {
        TeamRepository.upsert(Member("EMP0007", "", "Workshop", ""))
        val html = adminPage() // the reader the pencil is offered to, since that hint hangs on it
        assertTrue(row(html, "EMP0007").contains("<span class=\"ts-person-name\">—</span>"), "a nameless row printed an empty cell")
        assertTrue(row(html, "EMP0007").contains("<td class=\"t-emp\"><span class=\"code\">EMP0007</span></td>"), "the number is the row that is left")
        assertTrue(row(html, "EMP0007").contains("<td class=\"t-position\">Team Member</td>"))
        assertTrue(row(html, "EMP0007").contains("title=\"Create an account for EMP0007\""), "the pencil does not say who it is creating")
    }

    @Test
    fun `the workload table this sheet replaced stays off the page`() {
        val html = adminPage(form = createForm(canAdmin = true))
        // The old table's counts were buttons that opened a hidden sub-row, its emails were edited in
        // place, and its heading said "All user accounts". All three are now a column and a popup.
        listOf(
            "All user accounts", "count-btn", "count-zero", "acct-detail", "acct-email", "assignee-name",
            "data-kind", "/app/settings/email",
        ).forEach {
            assertFalse(html.contains(it), "$it is back on the page")
        }
        assertFalse(html.contains("onclick"), "the page grew an inline handler the CSP would drop")
        assertEquals(1, occurrences(html, "<script"), "the page ships more than the popup's own script")
        assertTrue(html.contains("src=\"/static/js/employee-modal.js\""), "the popup lost the script that closes it")
    }

    // --- the update contract --------------------------------------------------------------------------

    @Test
    fun `the search box and the two pickers are one live GET form whose companion is the count`() {
        val html = page()
        val form = tag(html, "tsFilter")
        assertTrue(form.startsWith("<form action=\"/app/settings\" method=\"get\""), "the pickers post instead of answering on their own address")
        assertTrue(
            form.contains("data-live-extra=\"#team-counts,#bucketDefaults\""),
            "a search leaves the count at the old number, or the bucket card's hidden view fields stale",
        )

        val box = tag(html, "teamSearch")
        assertTrue(box.contains("name=\"q\""), "the box posts something other than the search key")
        assertTrue(box.contains("data-livefilter=\"true\""), "the box does not send as the typing settles")
        assertTrue(box.contains("placeholder=\"Search name, email, employee #…\""), "the box does not say what it searches")
        assertFalse(box.contains("autofocus"), "an empty box takes the caret on every load")
        assertEquals(1, occurrences(html, "data-livefilter"), "a second field waits 400ms before it sends")
        // There is no Search button: the box sends itself, and Enter in it sends the pickers with it.
        assertEquals(0, occurrences(toolbar(html), "<button"), "the toolbar grew a submit button")
        // Two and only two self-submitting fields in *the toolbar* (position + role); the bucket panel's
        // category picker is a third on the page but belongs to a different form, so it is scoped out here.
        assertEquals(2, occurrences(toolbar(html), "data-autosubmit"), "the sheet grew a third self-submitting field")

        val positions = selectNamed(toolbar(html), "position")
        assertTrue(positions.contains("<option value=\"\" selected=\"selected\">All positions</option>"), "the picker has no way back to every job")
        // The list is the distinct positions across *every* row, so a search that leaves two people cannot
        // make the picker lose a job that still exists on the roster.
        assertEquals(
            listOf("", "Engineer", "Project Manager", "Site Supervisor", "Super Admin", "Team Member"),
            options(positions), "the picker is not the roster's jobs, in case-insensitive order",
        )
        val roles = selectNamed(toolbar(html), "role")
        assertTrue(roles.contains("<option value=\"\" selected=\"selected\">All roles</option>"))
        // Stage 110: the picker lists the role catalogue in its own order, not the distinct roles the current
        // rows happen to hold — a role nobody is filed under today is still one to file somebody under.
        assertEquals(
            listOf("", "Owner", "Administrator", "Project Manager", "Employee"),
            options(roles), "the role picker is not the catalogue, in its own order",
        )

        // The count is a field of the toolbar rather than a sibling of it — the one number that describes
        // exactly what these three controls leave — and it stays outside the region it is the companion to.
        assertTrue(toolbar(html).contains("id=\"team-counts\""), "the count left the form that answers for it")
        assertTrue(html.indexOf("id=\"team-counts\"") < html.indexOf("id=\"team-board\""), "the count moved under the sheet")
        assertFalse(region(html).contains("<span class=\"board-count\""), "the count is inside the live region")
        assertTrue(html.contains("<span class=\"board-count\" id=\"team-counts\">7 of 7 shown</span>"), "the count line is not N of M")
        // The default order is the sheet's own, so it does not put itself in the address.
        assertFalse(toolbar(html).contains("name=\"sort\""), "the default order rode along in the address")
    }

    @Test
    fun `the search box and the pickers are the only things the toolbar sends`() {
        val html = page()
        // The filter form must not carry a write's fields: the old page's in-place email editor and the
        // drill-down counts are gone, and a search that sent them would answer for a different sheet.
        // `lanes` is not a fourth control — it is the bucket panel's category carried along so the team's
        // own search cannot write the address (and, after a reload, the panel) back to the first category.
        assertEquals(
            listOf("lanes", "q", "position", "role"),
            Regex("<(?:input|select)[^>]*name=\"([^\"]+)\"").findAll(toolbar(html)).map { it.groupValues[1] }.toList(),
            "the toolbar sends something other than the three controls and the category riding with them",
        )
        // The order is the fourth field, and it only arrives when it is not the sheet's own default —
        // choosing a picker must not jump a Z→A list back to A→Z.
        val sorted = page(filter = EmployeeFilter(column = EmployeeColumn.ROLE, descending = true))
        assertEquals(
            listOf("lanes", "q", "position", "role", "sort"),
            Regex("<(?:input|select)[^>]*name=\"([^\"]+)\"").findAll(toolbar(sorted)).map { it.groupValues[1] }.toList(),
            "the order the reader set does not ride with the pickers",
        )
        assertTrue(toolbar(sorted).contains("<input type=\"hidden\" name=\"sort\" value=\"role:desc\">"), "the order rode as something other than what it is")
        assertFalse(toolbar(html).contains("method=\"post\""), "a search posted")
    }

    @Test
    fun `each header orders the sheet through a carriage of its own, not the toolbar's`() {
        val html = page()
        val carriage = tag(html, "tsSort")
        assertTrue(carriage.contains("class=\"col-sort-form\"") && carriage.contains("action=\"/app/settings\""), "the headers lost their own form")
        assertTrue(carriage.contains("data-live-target=\"#team-board\""), "a header click repaints the whole page")
        assertFalse(carriage.contains("data-live-extra"), "the carriage replaces a block the headers do not change")
        assertTrue(region(html).contains("id=\"tsSort\""), "the carriage is outside the block it rewrites")

        assertEquals(7, occurrences(html, "<button type=\"submit\" class=\"col-sort\""), "a header is not a control")
        assertEquals(7, occurrences(html, "form=\"tsSort\""), "a header belongs to something other than the carriage")
        // The default order is names A→Z, so the name header asks to be turned over and every other column
        // is asked for head-first: absolute values, so a reload cannot flip the sheet back again.
        assertEquals(
            listOf("name:desc", "emp", "position", "department", "role", "email", "tasks"),
            columns.take(7).map { asked(html, it.first) },
            "a header sent something other than the order it is asking for",
        )
        assertTrue(header(html, "t-name").contains("title=\"Sort by name — lowest first\""), "the header does not say which way it will go")
        assertTrue(header(html, "t-emp").contains("title=\"Sort by employee # — highest first\""))
        assertEquals(7, occurrences(html, "class=\"col-sort-glyph\""), "a header lost its glyph slot")
        assertEquals(1, occurrences(html, "<span class=\"col-sort-glyph\">▲</span>"), "only the column being read down says which way it runs")
        assertTrue(header(html, "t-name").contains("<span class=\"col-sort-glyph\">▲</span>"), "the glyph is not on the order the sheet holds")

        // The same header pressed again turns the column over rather than resetting it.
        val down = page(filter = EmployeeFilter(column = EmployeeColumn.NAME, descending = true))
        assertEquals("name", asked(down, "t-name"), "a reversed column offered to stay reversed")
        assertTrue(down.contains("<span class=\"col-sort-glyph\">▼</span>"), "the sheet does not say it is read backwards")
        assertTrue(toolbar(down).contains("<input type=\"hidden\" name=\"sort\" value=\"name:desc\">"), "choosing a picker jumps Z→A back to A→Z")

        // A key the sheet does not know is not a filter: the address a reader can type has to answer as the
        // ordinary page rather than as an empty or mis-ordered sheet.
        val junk = page(filter = employeeFilterOf(query("sort" to "nonsense")))
        assertEquals(listOf("name:desc", "emp"), listOf(asked(junk, "t-name"), asked(junk, "t-emp")), "an unknown order was taken as a column")
        assertFalse(toolbar(junk).contains("name=\"sort\""), "a junk order put itself in the address")
    }

    @Test
    fun `every control on the sheet answers the view the reader is standing on`() {
        val view = EmployeeFilter(text = "gan", position = "Site Supervisor", column = EmployeeColumn.DEPARTMENT, descending = true)
        val html = adminPage(filter = view)

        // The carriage is the headers' form, so it carries the three controls they answer for.
        assertTrue(carriage(html).contains("<input type=\"hidden\" name=\"q\" value=\"gan\">"), "a header click dropped the search")
        assertTrue(carriage(html).contains("<input type=\"hidden\" name=\"position\" value=\"Site Supervisor\">"))
        assertFalse(carriage(html).contains("name=\"sort\""), "the carriage states the order the header is asking for")

        val box = tag(html, "teamSearch")
        assertTrue(box.contains("value=\"gan\"") && box.contains("autofocus"), "a reloaded sheet lost the half-typed search and its caret")
        assertTrue(selectNamed(toolbar(html), "position").contains("<option value=\"Site Supervisor\" selected=\"selected\">"), "the picker does not show its own choice")
        // The picker answers for `position` itself, so that key has no hidden duplicate beside it.
        assertFalse(toolbar(html).contains("<input type=\"hidden\" name=\"position\""), "the toolbar states its own picker twice")

        // The openers are GETs onto this page, so theirs are the plain keys.
        val opener = openerForm(html, positioned)
        listOf("q" to "gan", "position" to "Site Supervisor", "sort" to "department:desc").forEach { (name, value) ->
            assertTrue(opener.contains("<input type=\"hidden\" name=\"$name\" value=\"$value\">"), "the pencil opened onto a different sheet without $name")
        }
        assertTrue(opener.contains("data-live-target=\"#employeeModal\""), "the pencil would navigate instead of opening")

        // A row write cannot use those names — its own `employeeNumber` is the row and the popup has its own
        // `position` and `role` — so the view travels prefixed.
        val delete = deleteForm(html, positioned)
        listOf("board_q" to "gan", "board_position" to "Site Supervisor", "board_sort" to "department:desc").forEach { (name, value) ->
            assertTrue(delete.contains("<input type=\"hidden\" name=\"$name\" value=\"$value\">"), "a row posted without $name")
        }
        assertFalse(delete.contains("name=\"q\""), "a row write states the search twice")
        val popup = saveForm(adminPage(filter = view, form = editForm(worker, view)))
        listOf("board_q", "board_position", "board_sort").forEach {
            assertTrue(popup.contains("name=\"$it\""), "the popup posted without $it")
        }

        // Clear answers the three controls and nothing else: the order is not one of them.
        val clear = tagOf(html, "board-reset")
        assertTrue(clear.contains("sort=department%3Adesc"), "Clear reset the order the reader set: $clear")
        assertFalse(clear.contains("q=gan"), "Clear left the search still applied")
    }

    @Test
    fun `a row that leaves the sheet takes the count with it, behind a confirm`() {
        val html = adminPage()
        val delete = deleteForm(html, worker)
        assertTrue(delete.contains("method=\"post\""), "a delete is a GET")
        assertTrue(delete.contains("data-live-target=\"#team-board\""), "deleting a row left its `<tr>` on the page")
        assertTrue(delete.contains("data-live-extra=\"#team-counts\""), "the count kept the deleted row in it")
        assertTrue(
            delete.contains("<input type=\"hidden\" name=\"employeeNumber\" value=\"$worker\">"),
            "the row does not say who it is deleting",
        )
        assertTrue(
            delete.contains("data-confirm=\"Delete the account $worker? Its sign-in and sessions go with it.\""),
            "the bin asks for nothing before it takes an account",
        )
        // The pencil is a swap of the popup only: the sheet has not changed, so it must not be repainted.
        val opener = openerForm(html, worker)
        assertTrue(opener.contains("data-live-target=\"#employeeModal\""), "the pencil would navigate instead of opening")
        assertFalse(opener.contains("data-live-extra"), "opening a form repaints the sheet")
        assertTrue(row(html, worker).contains("<input type=\"hidden\" name=\"edit\" value=\"$worker\">"), "the pencil does not say whose account to fill")
    }

    @Test
    fun `nothing on this page nests a form inside a form`() {
        // The parser drops the inner `<form>` rather than nesting it, which turns a control meant to swap a
        // block into a page load — so this is measured, and measured with the popup open too, because that
        // is the state with the most forms on the page at once. A Viewer's page now carries no form at all
        // (their only section is the account card), so the ceiling is "never more than one deep", not 1.
        val view = EmployeeFilter(text = "gan", role = "Manager", column = EmployeeColumn.ROLE, descending = true)
        listOf(
            page(),
            viewerPage(),
            adminPage(),
            page(form = createForm()),
            adminPage(form = createForm(canAdmin = true)),
            adminPage(form = editForm(worker)),
            page(filter = view),
            adminPage(filter = view, form = editForm(worker, view)),
        ).forEach { assertTrue(maxFormDepth(it) <= 1, "a form on this page sits inside another form") }
    }

    // --- the two rights the sheet prints ----------------------------------------------------------------

    @Test
    fun `only an administrator is offered the pencil`() {
        val html = adminPage()
        assertEquals(7, occurrences(sheet(html), "<button type=\"submit\" class=\"icon-btn ts-edit\""), "an administrator lost a pencil")
        assertEquals(
            7,
            occurrences(sheet(html), "<form action=\"/app/settings\" method=\"get\" class=\"opener-form ts-action\""),
            "a row is missing its opener",
        )
        // A row with no account behind it is not a reason not to print one: it opens the create filled.
        assertTrue(row(html, worker).contains("title=\"Change Bat-Erdene Tudev\""), "the pencil does not say whose account it changes")
        assertTrue(row(html, noAccount).contains("title=\"Create an account for Nyambaapurev\""), "the pencil on a login-less row does not say it creates one")
        assertTrue(row(html, worker).contains("aria-label=\"Change Bat-Erdene Tudev\""), "the pencil is unreadable to a screen reader")

        // The pencil is the admin's half of the row: a manager's sheet prints the bin column with one
        // button in it rather than two. (A Viewer is not shown the sheet at all since Stage 51 — see
        // `a viewer's settings page is their own account and the password form, nothing of the roster`.)
        listOf(page()).forEach {
            assertEquals(0, occurrences(sheet(it), "ts-edit"), "a reader who cannot change an account was shown the pencil")
            assertEquals(0, occurrences(sheet(it), "opener-form ts-action"), "a reader was offered a row's popup")
            assertEquals(7, occurrences(sheet(it), "<div class=\"icon-btns\">"), "the actions column lost its row")
        }
    }

    @Test
    fun `the bin prints the rule that refuses it, in the order the delete route applies them`() {
        val html = page() // a manager: management, but not an administrator
        assertEquals(7, occurrences(sheet(html), "🗑"), "a row lost its bin")
        val blocked = listOf(
            superAdmin to "The Super Admin account cannot be deleted.",
            manager to "You cannot delete your own account.",
            noAccount to "This person has no account to delete yet — the pencil creates one.",
            adminAccess to "Only somebody who manages roles can delete that account.",
        )
        blocked.forEach { (employeeNumber, why) ->
            val button = bin(html, employeeNumber)
            assertTrue(button.contains("title=\"$why\""), "$employeeNumber's bin does not say why: $button")
            assertTrue(button.contains("disabled"), "$employeeNumber's bin presses anyway")
            assertFalse(button.contains("data-confirm"), "a bin that cannot press still asks for confirmation")
        }
        // The super-admin rule is read before the self rule, and the bar that refuses an account holding the
        // people risk reads its *Manage roles & permissions* chip — so the row printing "Administrator" is the
        // row whose bin says it cannot be deleted.
        assertTrue(row(html, superAdmin).contains("cannot be deleted"))
        assertTrue(row(html, adminAccess).contains("chip atrisk\">Administrator"), "the column stopped printing the effective role")
        listOf(worker, positioned, noDepartment).forEach {
            val button = bin(html, it)
            assertFalse(button.contains("disabled"), "$it's bin is greyed for a manager who may delete it")
            assertTrue(button.contains("data-confirm=\"Delete the account $it? Its sign-in and sessions go with it.\""))
        }

        // An administrator keeps the same two protections — their own row and EMP0000 — and nothing else.
        // The count is of bins that cannot press, not of the word: kotlinx writes `disabled="disabled"`,
        // so a plain substring is twice the rows.
        val asAdmin = adminPage()
        assertEquals(2, greyedBins(asAdmin), "an administrator was refused a row they may delete")
        // A Viewer is not shown the roster at all since Stage 51, so there is no bin for them to be offered
        // — the whole Team sheet, and every action button in it, is management's alone.
        assertFalse(viewerPage().contains("🗑"), "a Viewer was shown the sheet's bins")
    }

    @Test
    fun `the add card is management's and it opens the popup without moving the sheet`() {
        val html = page()
        assertTrue(html.contains("<div class=\"card-panel team-add\">"), "the card the picture puts above the sheet is gone")
        val opener = formSection(html, "<form action=\"/app/settings\" method=\"get\" class=\"opener-form\"")
        assertTrue(opener.contains("data-live-target=\"#employeeModal\""), "+ Add employee would navigate instead of opening")
        assertTrue(opener.contains("<input type=\"hidden\" name=\"new\" value=\"1\">"), "the add control does not say it is a create")
        assertFalse(opener.contains("data-live-extra"), "opening the create repaints the sheet")
        assertTrue(html.contains("<button type=\"submit\" class=\"submit\">+ Add employee</button>"), "the button is not the picture's")
        assertTrue(html.contains("until you set a password on this sheet the number is the password"), "the card does not say how the account signs in first")

        // The card sits above the sheet it belongs to, so it opens onto the sheet the reader is looking at.
        val filtered = page(filter = EmployeeFilter(text = "gan", column = EmployeeColumn.DEPARTMENT, descending = true))
        val fromFiltered = formSection(filtered, "<form action=\"/app/settings\" method=\"get\" class=\"opener-form\"")
        listOf("q" to "gan", "sort" to "department:desc").forEach { (name, value) ->
            assertTrue(fromFiltered.contains("<input type=\"hidden\" name=\"$name\" value=\"$value\">"), "$name was left behind")
        }

        // An ordinary account is not offered the control at all — and the popup that would have been
        // filled has no create form on the page to open.
        val own = viewerPage()
        assertFalse(own.contains("team-add"), "a Viewer was offered the add card")
        assertFalse(own.contains("name=\"new\""), "a Viewer was offered a create form")
        assertFalse(own.contains("+ Add employee"), "the page promises a button this account would be refused")
    }

    // --- the popup --------------------------------------------------------------------------------------

    /**
     * Stage 51: Settings *is* the roster sheet for management, but for a Viewer it shrinks to the one
     * thing every account owns — their own account card. The Team heading, the search, the eight-column
     * roster, the add card, the account popup and (since Stage 99) the password box are all absent.
     * There is no password box left to lose: a password is set from the Team sheet now, and the app no
     * longer gates a reader on a system-assigned one.
     */
    @Test
    fun `a viewer's settings page is their own account, nothing of the roster and no password box`() {
        val html = viewerPage()
        assertFalse(html.contains(">Team<"), "the roster heading printed for a Viewer")
        assertFalse(html.contains("id=\"team-board\""), "the roster sheet printed for a Viewer")
        assertFalse(html.contains("team-add"), "the add-employee card printed for a Viewer")
        assertFalse(html.contains("id=\"employeeModal\""), "the account popup printed for a Viewer")
        assertFalse(html.contains("/static/js/employee-modal.js"), "the account popup's script shipped to a Viewer")
        assertTrue(html.contains(">Your account<"), "a Viewer lost their own account card")
        assertFalse(html.contains(">Change Password<"), "the retired password section printed for a Viewer")
        assertFalse(html.contains("/app/settings/password"), "the retired password route is still on the page")
        assertFalse(html.contains("name=\"currentPassword\""), "a password field is still on the page")
    }

    @Test
    fun `the page arrives with the popup drawn but shut`() {
        val html = page()
        assertFalse(tag(html, "employeeModal").contains("style"), "a popup nobody asked for came up open")
        // The form is on the page rather than fetched by the script, so a reader with no JavaScript gets
        // the same fields from the address that asks for them — and the swap has one node to write into.
        val save = saveForm(html)
        assertTrue(save.contains("data-live-target=\"#team-board\""), "a saved account would not appear on the sheet")
        assertTrue(save.contains("data-live-extra=\"#team-counts,#employeeModal\""), "the count or the form itself is left stale")
        assertFalse(modal(html).contains("<input type=\"hidden\" name=\"edit\""), "a closed popup is writing an existing account")
        assertTrue(html.contains("<span class=\"close\" data-employee-modal-close=\"true\">×</span>"), "the popup has no way to shut")
        assertTrue(html.contains("<button type=\"button\" class=\"ghost-btn\" data-employee-modal-close=\"true\">Cancel</button>"))
    }

    @Test
    fun `an administrator's add popup asks for the three fields a manager's may not touch`() {
        val asAdmin = adminPage(form = createForm(canAdmin = true))
        assertTrue(tag(asAdmin, "employeeModal").contains("style=\"display:block\""), "the add button opened nothing")
        assertTrue(asAdmin.contains("<h3>Add employee</h3>"))
        assertTrue(asAdmin.contains("They sign in with the number and the password you set here"), "the create does not say what the password is for")
        assertFalse(tag(asAdmin, "employeeNumber").contains("readonly"), "a create fixed the number it is asking for")
        assertTrue(tag(asAdmin, "employeeNumber").contains("placeholder=\"EMP0023\""), "the number field does not show the shape")
        // The picker lists the catalog — the entries the app can enforce — not the words rows fall back to.
        assertEquals(
            listOf("", "Engineer", "Project Manager", "Site Supervisor"),
            options(selectNamed(modal(asAdmin), "position")),
            "the popup's position picker is not the catalog, in order",
        )
        assertTrue(selectNamed(modal(asAdmin), "position").contains("<option value=\"\" selected=\"selected\">— Follow the roster —</option>"), "the popup forces a job the roster already has")
        // The role picker is the catalogue too, and a create opens on its default rather than on nothing:
        // the popup always states the role the account will be filed under (Stage 110).
        assertEquals(
            listOf("Owner", "Administrator", "Project Manager", "Employee"),
            options(selectNamed(modal(asAdmin), "role")),
            "the popup's role picker is not the catalogue, in its own order",
        )
        assertTrue(selectNamed(modal(asAdmin), "role").contains("<option value=\"Employee\" selected=\"selected\">Employee</option>"), "the popup opens on no role")
        val password = tag(asAdmin, "employeePassword")
        assertTrue(password.contains("placeholder=\"Blank means their employee number\""), "the password field does not say what blank means")
        assertTrue(password.contains("minlength=\"${MIN_PASSWORD_LENGTH}\""), "the field can type past what the service refuses")
        assertTrue(asAdmin.contains("<button type=\"submit\" class=\"submit\">Add employee</button>"))

        // A manager's popup asks for a number, a name and an address, and nothing the route would refuse.
        val asManager = page(form = createForm())
        listOf("position", "role", "password").forEach {
            assertFalse(modal(asManager).contains("name=\"$it\""), "a manager was offered the $it field")
        }
        // Stage 110: the caption names the role a reader without *Manage roles & permissions* is handing out,
        // because that is the thing they cannot choose and therefore have to be told.
        assertTrue(
            asManager.contains("They sign in with their employee number as the password, and start in the Employee role"),
            "the manager's create does not say what it makes",
        )
        assertTrue(tag(asManager, "employeeModal").contains("style=\"display:block\""), "management's add button opened nothing")
    }

    @Test
    fun `the pencil opens that account filled, with the two words it will not change`() {
        val html = adminPage(form = editForm(worker))
        assertTrue(html.contains("<h3>Change account</h3>"), "an edit is titled as an add")
        assertTrue(html.contains("Their name and employee number stay as the roster has them"), "the edit does not say what it will not rename")
        assertTrue(modal(html).contains("<input type=\"hidden\" name=\"edit\" value=\"$worker\">"), "the edit popup is writing a new account")
        val number = tag(html, "employeeNumber")
        assertTrue(number.contains("value=\"$worker\"") && number.contains("readonly"), "the number of the account being edited is editable")
        val name = tag(html, "employeeName")
        assertTrue(name.contains("value=\"Bat-Erdene Tudev\"") && name.contains("readonly"), "the edit let a person be renamed")
        assertTrue(tag(html, "employeeEmail").contains("value=\"bat@murray.mn\""), "the edit lost the stored address")
        assertTrue(selectNamed(modal(html), "position").contains("<option value=\"Engineer\" selected=\"selected\">Engineer</option>"), "the picker is not the catalog entry the account is filed under")
        assertTrue(tag(html, "employeePassword").contains("placeholder=\"Leave blank to keep the current password\""), "the edit does not say blank keeps the password")
        assertTrue(html.contains("<button type=\"submit\" class=\"submit\">Save account</button>"))

        // The pickers are filled from the *account* — the role it is filed under, the catalog entry its
        // `positionId` points at — so the popup writes the fields as they are, while the two columns print
        // the same two answers back. This row is an Administrator filed under a Site Supervisor job: one
        // word says what it may do, the other what it is called.
        val two = adminPage(form = editForm(adminAccess))
        assertTrue(selectNamed(modal(two), "role").contains("<option value=\"Administrator\" selected=\"selected\">Administrator</option>"), "the role picker was not the role the account holds")
        assertTrue(row(two, adminAccess).contains("chip atrisk\">Administrator"), "the column stopped printing the effective role")
    }

    @Test
    fun `a row with nobody signed in opens as the account it is waiting for`() {
        val html = adminPage(form = editForm(noAccount))
        assertFalse(modal(html).contains("name=\"edit\""), "the popup tried to change an account that is not there")
        assertFalse(tag(html, "employeeNumber").contains("readonly"), "a create was filled with its own number as a fixed field")
        assertTrue(tag(html, "employeeNumber").contains("value=\"$noAccount\""), "the popup lost the row it was opened from")
        assertTrue(tag(html, "employeeName").contains("value=\"Nyambaapurev\""), "the popup invented a name")
        assertTrue(html.contains("<h3>Add employee</h3>"), "creating an account is titled as changing one")
        assertTrue(html.contains("<button type=\"submit\" class=\"submit\">Add employee</button>"))
    }

    @Test
    fun `an id the sheet does not print opens nothing`() {
        // A hand-typed `?edit=`, or a pencil this reader has no right to, answers with the ordinary page —
        // the same answer a hand-typed lesson id gets, and the route decides again either way.
        val stranger = adminPage(form = editForm("EMP9999"))
        assertFalse(tag(stranger, "employeeModal").contains("style"), "a number that is not on the sheet opened a popup")
        val overReach = page(form = editForm(worker, who = manager, canAdmin = false))
        assertFalse(tag(overReach, "employeeModal").contains("style"), "a manager was served somebody else's account")
        assertFalse(overReach.contains("name=\"edit\""), "the refused popup still carries the row it was aimed at")
        // `?new=1` from an account that cannot add is the same nothing.
        assertFalse(page(worker, canManage = false, form = createForm(canManage = false))
            .contains("style=\"display:block\""), "a Viewer asked for a create and got one")
    }

    @Test
    fun `a refused save comes back with the words still typed and the reason under its field`() {
        val result = EmployeeService.save(
            EmployeeDraft(
                employeeNumber = worker, name = "", email = "new@murray.mn",
                position = "Engineer", role = "Boss", password = "ab",
            ),
            superAdmin, canManage = true, canAdmin = true,
        )
        assertFalse(result.saved, "the setup wrote something it should have refused")
        val html = adminPage(form = employeeFormOf(result, mayAdd = true, maySetRoles = true, filter = EmployeeFilter()))
        assertTrue(tag(html, "employeeModal").contains("style=\"display:block\""), "the popup closed on a refusal")
        assertTrue(tag(html, "employeeNumber").contains("value=\"$worker\""), "the typed number was thrown away")
        assertTrue(tag(html, "employeeEmail").contains("value=\"new@murray.mn\""), "the typed address was thrown away")
        // Four reasons, one under each field that caused it — nothing at the top of the page, and no field
        // the reader has to guess at, in the order the fields print.
        assertEquals(
            listOf(
                "There is already an account with that number.",
                "Write the name the sheet should print.",
                "Choose one of the roles on the list.",
                "A password is $MIN_PASSWORD_LENGTH characters or more.",
            ),
            Regex("<p class=\"field-err\">([^<]*)</p>").findAll(modal(html)).map { it.groupValues[1] }.toList(),
            "the reasons are not the fields' own, in the order the fields print",
        )

        // A permission the reader does not have is not one field's fault, so it owns the row's full width —
        // and their popup prints no Role picker at all, which is exactly why the reason cannot live there.
        val overReach = EmployeeService.save(
            EmployeeDraft(employeeNumber = "EMP0024", name = "New Person", role = "Employee"),
            manager, canManage = true, canAdmin = false,
        )
        assertFalse(overReach.saved)
        val refused = page(form = employeeFormOf(overReach, mayAdd = true, maySetRoles = false, filter = EmployeeFilter()))
        assertEquals(
            listOf("Only somebody who manages roles can set a role."),
            Regex("<p class=\"field-err full\">([^<]*)</p>").findAll(modal(refused)).map { it.groupValues[1] }.toList(),
            "the permission refusal is not printed as the form's own line",
        )
        assertFalse(modal(refused).contains("<p class=\"field-err\">Only somebody"), "the full-width refusal also printed inside a field")
        // An answer that has a reason in it is an open popup, whatever the opener said.
        val stillOpen = page(form = EmployeeForm(errors = mapOf(EmployeeField.NAME to "Write the name the sheet should print.")))
        assertTrue(tag(stillOpen, "employeeModal").contains("style=\"display:block\""), "a refusal was answered with a closed form")
        assertEquals(0, UserRepository.findAll().count { it.employeeNumber == "EMP0024" }, "a refused create wrote the account anyway")
    }

    @Test
    fun `the note under the heading says what happened to an account`() {
        assertTrue(page(note = PasswordStatus.USER_ADDED).contains("<div class=\"flash ok\">User added.</div>"), "an added account is not flashed green")
        assertTrue(
            page(note = PasswordStatus.CANNOT_DELETE_ADMIN).contains("<div class=\"flash err\">Only somebody who manages roles can delete that account.</div>"),
            "a refused delete is not flashed red beside the row that is still there",
        )
        assertFalse(page().contains("class=\"flash"), "the page prints a banner nobody asked for")
    }

    // --- what stayed on the page ------------------------------------------------------------------------

    @Test
    fun `under the sheet sits the reader's account card, and no password box`() {
        val html = page()
        // The order the approved scope settled: Team first, then the reader's own account — and since
        // Stage 99 the app panels follow the account card directly, because the password box between
        // them is gone.
        assertTrue(html.indexOf("id=\"team-board\"") < html.indexOf(">Your account<"), "the sheet is not the first section")
        assertTrue(html.indexOf(">Your account<") < html.indexOf(">Project types<"), "the app panels jumped above the account card")
        assertTrue(html.contains("<h3 class=\"set-section\">Your account</h3>"))
        assertEquals(manager, setRow(html, "Employee number").substringAfter(">").substringBefore("<"))
        assertEquals("Munkhtsetseg Otchontoor", setRow(html, "Display name").substringAfter(">").substringBefore("<"))
        assertEquals("Project Manager", setRow(html, "Position").substringAfter(">").substringBefore("<"))
        // Stage 110 renamed the card's fourth row from "Access level" to "Role" and prints the catalogue's
        // name for the account rather than the raw `access` word — here the same words the Position row
        // happens to use, because a position is a job title and a role is what the account may do, and this
        // fixture is a Project Manager in both.
        assertEquals("Project Manager", setRow(html, "Role").substringAfter(">").substringBefore("<"))
        assertFalse(html.contains(">Access level<"), "the retired access-word row is back on the card")

        // Stage 99 retired the self-service password box: no heading, no form, no fields, for any role.
        assertFalse(html.contains("Change Password"), "the retired password section is still printed")
        assertFalse(html.contains("/app/settings/password"), "the retired password route is still a form target")
        listOf("currentPassword", "newPassword", "confirmPassword").forEach {
            assertFalse(html.contains("name=\"$it\""), "the retired form's $it field is still printed")
        }
    }

    @Test
    fun `the sheet says which of the two empties it is`() {
        val filtered = adminPage(filter = EmployeeFilter(text = "zzz"))
        assertTrue(filtered.contains("Nobody matches this search."), "an empty cut is not told as one")
        assertTrue(filtered.contains("The roster is what the Team page prints"), "the filtered empty does not say what the sheet is")
        assertTrue(Regex("<a[^>]*>Show everybody</a>").containsMatchIn(filtered), "the reader is left on the empty view")
        assertEquals(0, occurrences(filtered, "<table class=\"grid team-sheet\""), "an empty cut printed a header over nothing")
        assertTrue(filtered.contains(">0 of 7 shown</span>"), "the count line counted the unfiltered sheet")
        assertFalse(filtered.contains("Nobody is on the sheet yet."), "a filtered empty claims the roster is empty")
        // The card above the sheet is still offered: an empty search is not an empty roster.
        assertTrue(filtered.contains("+ Add employee"))

        // `users.employee_number` is a foreign key onto `team_members`, so clearing the roster takes every
        // account with it and the sheet is left with nothing to print for anybody.
        TeamRepository.findAll().forEach { TeamRepository.delete(it.employeeNumber) }
        val fresh = adminPage()
        assertTrue(fresh.contains("Nobody is on the sheet yet."), "a fresh roster is not told as one")
        assertTrue(fresh.contains("the account they sign in with is filed against that row from here"), "the empty page does not say what a row is")
        assertFalse(fresh.contains("Show everybody"), "a fresh roster claims a filter emptied it")
        assertTrue(fresh.contains(">0 of 0 shown</span>"), "the count line invented rows")
        assertEquals(0, occurrences(fresh, "<th class=\"t-name\""), "an empty sheet drew its own headers")
        // Even empty, the toolbar prints: the pickers belong to the page, not to the rows.
        assertTrue(fresh.contains("<option value=\"\" selected=\"selected\">All positions</option>"), "the empty sheet took the pickers with it")
        assertFalse(sheet(fresh).contains("opener-form ts-action"), "an empty sheet invented a row's pencil")
    }

    @Test
    fun `the prefixed carriage and the draft keys read back what the forms write`() {
        val view = EmployeeFilter(text = "gan", position = "Engineer", role = "Manager", column = EmployeeColumn.EMAIL)
        // The plain keys are what a GET onto the page sends; the prefixed ones are what a row or the popup
        // sends. Both have to read back as the same sheet, or the answer lands on a different one.
        assertEquals(view, employeeFilterOf(query("q" to "gan", "position" to "Engineer", "role" to "Manager", "sort" to "email")), "an address did not read as the filter")
        assertEquals(
            view,
            employeeFilterOfForm(query("board_q" to "gan", "board_position" to "Engineer", "board_role" to "Manager", "board_sort" to "email")),
            "a posted view did not read back as the filter that wrote it",
        )
        assertEquals("email", employeeFilterOfForm(query("board_sort" to "email")).sortValue, "the order a header asked for did not read back")
        val draft = employeeDraftOf(query("edit" to worker, "employeeNumber" to worker, "name" to "x", "password" to "abcd"))
        assertTrue(draft.isEdit, "a popup carrying the row it edits reads as a create")
        assertEquals("abcd", draft.password, "the password key is not the one the form writes")
        assertEquals("", draft.email, "an absent field read as something other than blank")
        // A blank `edit` is a create, including for the popup filled from a roster row that has no account.
        assertFalse(employeeDraftOf(query("employeeNumber" to noAccount, "name" to "Nyambaapurev")).isEdit, "a create reads as an edit")
    }

    // --- the stylesheet the markup depends on -------------------------------------------------------------

    /**
     * The stylesheet rules this page's own markup relies on.
     *
     * None of them can be read off the rendered page: eight column shares that have to add up under
     * `table-layout: fixed`, a carriage form that has to be out of the layout entirely, and the pencil's
     * colour, which only matches because the button prints `icon-btn ts-edit`. Reading `app.css` is the
     * only way to hold any of the three to account.
     */
    @Test
    fun `the eight columns share the sheet's width exactly`() {
        val shares = Regex("""table\.team-sheet col\.(t-[a-z]+)\s*\{\s*width:\s*(\d+)%""")
            .findAll(appCss())
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        assertEquals(columns.map { it.first }.toSet(), shares.keys, "the grid no longer has the eight columns the page prints")
        assertEquals(100, shares.values.sum(), "the shares must add to 100 on a table-layout: fixed grid")
        // The name column carries an avatar, a full name and the YOU pill; the number is the bold
        // monospace; the role holds the widest pill; the two buttons sit at the end. Each share was
        // measured off the rendered sheet with the cell's padding, and the last four are what the
        // narrowest of those shapes needs.
        assertTrue(shares.getValue("t-name") >= 19, "the avatar, the name and the pill need the widest share")
        assertTrue(shares.getValue("t-emp") >= 10, "EMP00000 in the monospace face splits below that")
        assertTrue(shares.getValue("t-role") >= 11, "the Manager pill is the widest shape in the column")
        assertTrue(shares.getValue("t-actions") >= 10, "two buttons side by side spill past the column's edge")
        assertTrue(shares.getValue("t-tasks") >= 9, "a centred count and its heading need the room")
    }

    @Test
    fun `the stylesheet styles the classes this markup actually prints`() {
        val css = appCss()
        // The pencil is the one red control on the sheet, and it is qualified down to the table so no
        // other `.ts-edit` inherits it. It matches the markup only while the button prints both classes.
        assertTrue(css.contains("table.team-sheet .icon-btn.ts-edit"), "the pencil's colour rule no longer names the class the button prints")
        assertTrue(css.contains("html[data-theme=\"dark\"] table.team-sheet .icon-btn.ts-edit"), "the pencil has no dark-theme colour")
        Regex("""\.icon-btn:disabled\s*\{\s*opacity""").find(css) ?: error("a bin that cannot press is not greyed")
        // The carriage is three hidden fields and nothing else; left in the flow it would be a stray child
        // beside the table rather than a block the size of nothing.
        Regex("""form\.col-sort-form\s*\{\s*display:\s*none""").find(css) ?: error("the header carriage is in the layout")
        Regex("""form\.ts-action\s*\{\s*display:\s*inline-flex""").find(css) ?: error("the row's two forms do not sit side by side")
        Regex("""\.board-toolbar \.board-count\s*\{\s*margin-left:\s*auto""").find(css) ?: error("the count is not pushed to the right, where the picture counts")
        Regex("""\.field input\[readonly]\s*\{""").find(css) ?: error("an uneditable field is not told from an editable one")
        Regex("""\.ts-person-name\s*\{[^}]*text-overflow:\s*ellipsis""").find(css) ?: error("a long name would break the row's line")
        // `overflow-wrap: anywhere` is what keeps a long email inside its own column now that the
        // `.ts-person-name` ellipsis is only the name cell's own rule.
        Regex("""table\.team-sheet th, table\.team-sheet td\s*\{\s*overflow-wrap:\s*anywhere""").find(css) ?: error("nothing clips an address that is wider than its share")
    }

    // --- Stage 61: the three panels under the password form -------------------------------------------

    /** The whole Bucket defaults card, from its id to the Add lane form that closes it. */
    private fun bucketCard(html: String): String =
        html.substringAfter("id=\"bucketDefaults\"")

    @Test
    fun `the app-settings panels are management's, not a viewer's`() {
        val own = viewerPage()
        // A Viewer's page stops at their own account card: which categories exist, whether mail leaves the
        // box and which lanes a new board starts with are settings *of the app*, and none of them is theirs
        // to read as a control or change.
        listOf("Project types", "Project bucket settings", "Email notifications").forEach { heading ->
            assertFalse(own.contains(">${heading}<"), "the $heading panel printed for a Viewer")
        }
        assertFalse(own.contains("id=\"emailBoard\""), "the email card printed for a Viewer")
        assertFalse(own.contains("id=\"bucketDefaults\""), "the bucket card printed for a Viewer")
        assertFalse(own.contains("id=\"projectTypes\""), "the category card printed for a Viewer")
        assertFalse(own.contains("/app/settings/buckets/"), "a lane write route was offered to a Viewer")
        assertFalse(own.contains("/app/settings/types/"), "a category write route was offered to a Viewer")
        assertFalse(own.contains("/app/settings/email-test"), "the mail test was offered to a Viewer")
        // The management page carries all three, in the order the picture reads them, under the account card.
        val mgr = page()
        assertTrue(mgr.indexOf(">Your account<") < mgr.indexOf(">Project types<"), "the app panels jumped above the account card")
        assertEquals(
            listOf(">Project types<", ">Project bucket settings<", ">Email notifications<").sortedBy { mgr.indexOf(it) },
            listOf(">Project types<", ">Project bucket settings<", ">Email notifications<"),
            "the three panels are not in catalog, lanes, mail order",
        )
    }

    @Test
    fun `the project types panel prints the nine categories the app codes against, with how many projects each has`() {
        val html = page()
        assertEquals(9, occurrences(html, "class=\"set-row type-row\""), "the catalog is not the nine PROJECT_TYPES")
        // One row per category: the letter that leads its project numbers, the chip in its own colour, and the
        // count of live rows filed under that exact label — every PROJECT_TYPES word must appear as a chip.
        val chips = Regex("<span class=\"chip [^\"]*\">([^<]*)</span>").findAll(html).map { it.groupValues[1] }.toList()
        PROJECT_TYPES.forEach { assertTrue(chips.contains(it), "no row for the category $it") }
        // The crusher is the only seeded project and it is filed under Fabrication/Manufacturing, so that row
        // counts one and every other counts none — a default lane is only worth adding where projects exist.
        assertTrue(html.contains("<span class=\"type-count\">1 project</span>"), "the seeded project is not counted under its category")
        assertEquals(8, occurrences(html, "<span class=\"type-count\">no projects yet</span>"), "the empty categories are not all read as empty")
    }

    /** The category card alone, from its swap target to the lane card that follows it. */
    private fun typeCard(html: String): String =
        html.substringAfter("id=\"projectTypes\"").substringBefore("id=\"bucketDefaults\"")

    @Test
    fun `every category row offers the rename and the retirement, and both answer the card alone`() {
        val card = typeCard(page())
        // Two writes per row, in the app's own button pair: a GET onto the page carrying the label to open,
        // and a POST of the letter that keys the row. Nine of each, and every one of them swaps this card
        // rather than the page.
        assertEquals(9, occurrences(card, "<form action=\"/app/settings\" method=\"get\" class=\"opener-form ts-action\""), "a category has no pencil")
        assertEquals(9, occurrences(card, "<form action=\"/app/settings/types/delete\""), "a category has no retirement")
        // Every form in the card — the nine pencils, the nine bins and the add line — names the card as its
        // target, so nothing on it reloads the page.
        assertEquals(
            occurrences(card, "<form "),
            occurrences(card, "data-live-target=\"#projectTypes\""),
            "a category write would reload the page",
        )
        assertTrue(card.contains("name=\"edittype\""), "the pencil does not say which category to open")
        assertTrue(card.contains("name=\"code\""), "a retirement does not carry the letter it retires")
        // A category is retired from the create form's picker, so the press that lands is worth confirming —
        // and only that one: a pencil opens a field and can be cancelled.
        assertEquals(9, occurrences(card, "data-confirm"), "a category was retired without a confirm")
        assertEquals(0, occurrences(card, "disabled"), "a category write was printed that the route would refuse")
    }

    @Test
    fun `the add line asks for a letter and a name and posts them to the catalogue`() {
        val form = formSection(page(), "<form action=\"/app/settings/types/add\"")
        assertTrue(form.contains("data-live-target=\"#projectTypes\""), "an added category would reload the page")
        assertTrue(form.contains("name=\"code\"") && form.contains("maxlength=\"1\""), "the letter is not bounded to one character")
        assertTrue(form.contains("name=\"name\"") && form.contains("maxlength=\"60\""), "the name is not bounded the way the service bounds it")
        assertTrue(form.contains("+ Add type"), "the line has no button of its own")
    }

    @Test
    fun `the pencil opens one row's name as a field and leaves the letter alone`() {
        val card = typeCard(page(typeBeingEdited = "Site projects"))
        val rename = formSection(card, "<form action=\"/app/settings/types/rename\"")
        // The hidden `name` is what the route renames *from* — read off the catalogue, never from the field —
        // and the text starts with the words being changed so the reader edits them rather than retypes them.
        assertTrue(rename.contains("name=\"name\"") && rename.contains("value=\"Site projects\""), "the rename lost the label it renames")
        assertTrue(rename.contains("name=\"newname\"") && rename.contains("value=\"Site projects\""), "the field does not start with the words being changed")
        assertFalse(rename.contains("name=\"code\""), "a rename offered the letter as editable")
        assertTrue(rename.contains("data-live-target=\"#projectTypes\""), "a renamed category would reload the page")
        // One row's edit, not nine: the other eight still print their chip and their two buttons.
        assertEquals(1, occurrences(card, "class=\"set-row type-row editing\""), "more than one row opened")
        assertEquals(8, occurrences(card, "class=\"set-row type-row\""), "the rows beside the edit lost their print")
        assertEquals(8, occurrences(card, "<form action=\"/app/settings/types/delete\""), "a row beside the edit lost its buttons")
        assertTrue(card.contains(">Cancel<"), "the opened row cannot be closed without a write")
    }

    @Test
    fun `a category answer comes back as the card's own line, beside the reason`() {
        val refused = typeCard(page(typeNote = PasswordStatus.TYPE_REFUSED, typeDetail = "S is already in use."))
        assertTrue(
            refused.contains("<div class=\"flash err\">That category cannot be saved. (S is already in use.)</div>"),
            "a refused category has no line on the card it belongs to",
        )
        // A write that landed has no words of its own: the service's sentence already names the letter, the
        // label and how many projects moved with it.
        val added = typeCard(page(typeNote = PasswordStatus.TYPE_ADDED, typeDetail = "P will lead new Parts supply numbers."))
        assertTrue(
            added.contains("<div class=\"flash ok\">P will lead new Parts supply numbers.</div>"),
            "an added category prefixed the reason twice",
        )
    }

    @Test
    fun `the email panel reports the mode and the reader's address for management`() {
        val html = page() // a manager: reads the card, but is not an admin
        assertTrue(html.contains("id=\"emailBoard\""), "the mail card has no swap target")
        assertTrue(html.contains(">Delivery mode<"), "the card does not report whether mail can leave")
        assertTrue(html.contains(">Your address<"), "the card does not show the reader's own address")
        // The manager's account has no email stored, so the address row says what to do about it rather than
        // printing a blank, and — because there is nothing to address — there is no test button at all.
        assertTrue(html.contains("no email on file for $manager"), "a blank address printed silently")
        assertFalse(html.contains("/app/settings/email-test"), "a test with no address to send to was offered")
    }

    @Test
    fun `only an admin with an address is offered the test, and it is a live swap`() {
        // A manager who happens to have an address still gets no button: the press makes an outbound
        // connection, which is exactly the right /app/settings/email-test re-checks.
        val asManager = page(who = worker, canManage = true, canAdmin = false)
        assertFalse(asManager.contains("/app/settings/email-test"), "the outbound test printed for a non-admin")
        // An admin with an address on file gets it, addressed to themselves, swapping the card in place.
        val asAdmin = page(who = worker, canManage = true, canAdmin = true)
        val form = formSection(asAdmin, "<form action=\"/app/settings/email-test\"")
        assertTrue(form.contains("data-live-target=\"#emailBoard\""), "the test answer would reload the page")
        assertTrue(form.contains("Send test email to myself"), "the button does not say who it mails")
        assertTrue(form.contains("data-confirm=\"Send one test message to"), "an outbound send was not confirmed")
        assertTrue(asAdmin.contains(">bat@murray.mn<"), "the reader's stored address is not the one the test uses")
    }

    @Test
    fun `the bucket card lists exactly what the category's Settings list holds, and says so when it is empty`() {
        val html = page()
        assertTrue(html.contains("id=\"bucketDefaults\""), "the lane card has no swap target")
        // Settings carries two live regions (the Team roster and this list), so the list names itself:
        // app.js refreshes the region a form belongs to and matches the response by id. A category picker
        // that refreshed the first region left this list — and the hidden `lanes` field — on the old
        // category, so the next add wrote there while the select showed the new one.
        assertTrue(html.contains("id=\"bucketDefaultsResults\""), "the bucket region does not name itself")
        // …and the add line, which lives outside that region, is a named companion: its hidden `lanes` and
        // its *"Adds to … only"* label must move with the picker, or the next add writes the old category.
        // The toolbar's own hidden `lanes` moves too, so the team filter's next send cannot undo the choice.
        assertTrue(html.contains("data-live-extra=\"#bucketAdd,#bucketCategoryKey\""), "the category picker does not refresh the add line")
        assertTrue(html.contains("id=\"bucketAdd\""), "the add line has no id for that refresh")
        assertTrue(html.contains("id=\"bucketCategoryKey\""), "the team toolbar does not carry the category the picker chose")
        // Stage 100: the list is the whole board, so there are no fixed phase rows any more — the three phases
        // appear only where a category names them. A project's own page is still where a column is renamed or
        // removed, which is why nothing here is read-only.
        assertEquals(0, occurrences(html, "class=\"set-row lane-row\""), "an empty list printed a row")
        assertFalse(html.contains("class=\"set-row phase-row\""), "the retired fixed phase rows are back")
        assertFalse(html.contains("class=\"phase-fixed\""), "a fixed row still says why it has no remove")
        assertFalse(html.contains("Always on every board"), "the retired fixed-row note is back")
        assertFalse(html.contains("class=\"bucket-dot dot-0\">"), "an empty list printed a dot")
        assertFalse(html.contains("class=\"chip lane-chip\""), "the popup's retired chip row is on this page")
        assertFalse(html.substringAfter("id=\"bucketDefaults\"").contains("/app/settings/buckets/delete"), "an empty list has a remove button")
        // With the list empty the caption's consequence is the honest line, not a blank column: a board with no
        // column has nowhere to file a card, and the line has to say where one can come from. Since Stage 104 the
        // old "its projects cannot be created" is false — the create popup may name the first column — so the line
        // names that road instead, and the retired wording must not come back.
        assertTrue(html.contains("has no buckets yet — add one below"), "an empty list printed nothing")
        assertTrue(
            html.contains("name one while creating a project"),
            "the empty line does not say a project can still be made, one column at a time",
        )
        assertFalse(html.contains("its projects cannot be created"), "the Stage-100 wording is back")
        // Stage 77: the caption used to promise that a bucket typed on a project's own picker landed here too. It
        // does not — that write is behind the tick management alone are shown — so the card has to say so, because
        // a reader who widens nothing here would otherwise expect every project's private column to appear in this
        // list and find the page lying about what it holds.
        assertFalse(html.contains("picker lands here too"), "the card still promises every typed bucket widens it")
        assertTrue(
            html.contains("joins this list only when management tick the box under its name"),
            "the card does not say that a project's bucket joins the list only on management's tick",
        )
        // Stage 100: and it says what the list *is* — the whole board, in this order, with nothing added by the app.
        assertTrue(
            html.contains("project opens with — these and nothing else, in this order"),
            "the caption does not say the list is the whole board",
        )
        assertTrue(
            html.contains("name this project's first one while creating it"),
            "the caption does not say an empty category can still make a project, one column at a time",
        )
        assertFalse(
            html.contains("before a project of it can be created"),
            "the Stage-100 caption is back, telling management no project can be made",
        )
        // The category picker is one live GET onto this page carrying ?lanes=, and it lists all nine.
        val lanes = selectNamed(html.substringAfter("id=\"bucketDefaults\""), "lanes")
        assertTrue(lanes.contains("data-autosubmit=\"true\""), "choosing a category reloads the page")
        assertEquals(PROJECT_TYPES.toList(), options(lanes).filter { it.isNotEmpty() }, "the picker is not the nine categories")
        assertTrue(lanes.contains("value=\"${PROJECT_TYPES.first()}\" selected=\"selected\""), "the picker did not open on the first category")
    }

    @Test
    fun `an added lane prints the service receipt, not a status line in front of it`() {
        // Stage 98: the add route now carries the receipt on success too, because that sentence is the only thing
        // on this page that tells a reader the columns went onto the boards. It already names the bucket, the
        // category and how many boards took it, so it must print alone — "Default lane added. (…)" would say the
        // same thing twice and bury the count.
        TypeDefaultBucketRepository.insert("Fabrication/Manufacturing", "Commissioning")
        val html = page(
            bucketNote = PasswordStatus.LANE_ADDED,
            bucketDetail = "Commissioning is a Fabrication/Manufacturing bucket now — 2 Fabrication/Manufacturing " +
                "boards gained the column, and every new project will open with it.",
            bucketDefaultsType = "Fabrication/Manufacturing",
        )
        val card = bucketCard(html)
        assertFalse(card.contains("Default lane added."), "the receipt is prefixed by a line that says nothing")
        assertTrue(
            card.contains("2 Fabrication/Manufacturing boards gained the column"),
            "the count of boards that took the column never reached the panel",
        )
    }

    @Test
    fun `a seeded default lane appears with its own remove and both forms carry the chosen category`() {
        TypeDefaultBucketRepository.insert("Fabrication/Manufacturing", "Commissioning")
        val html = page(bucketDefaultsType = "Fabrication/Manufacturing")
        val card = bucketCard(html)
        assertTrue(card.contains("<span class=\"lane-name\">Commissioning</span>"), "the seeded lane is not on the list")
        assertFalse(card.contains("has no buckets yet"), "the empty line survived a lane being added")
        // The row's remove posts its own id and the category it came from, so the answer lands back on this
        // category rather than the default first one.
        val remove = formSection(card, "<form action=\"/app/settings/buckets/delete\"")
        assertTrue(remove.contains("data-live-target=\"#bucketDefaults\""), "a removed lane would reload the page")
        assertTrue(remove.contains("name=\"lanes\"") && remove.contains("value=\"Fabrication/Manufacturing\""), "the remove lost its category")
        assertTrue(Regex("name=\"id\"[^>]*value=\"\\d+\"").containsMatchIn(remove), "the remove does not carry the row id")
        assertTrue(remove.contains("data-confirm"), "a lane removal was not confirmed")
        // The add form below the list carries the same category hidden, so a new lane lands where it was typed.
        val add = formSection(html, "<form action=\"/app/settings/buckets/add\"")
        assertTrue(add.contains("data-live-target=\"#bucketDefaults\""), "an added lane would reload the page")
        assertTrue(add.contains("value=\"Fabrication/Manufacturing\""), "the add form does not know the category it is adding to")
        assertTrue(add.contains("name=\"name\"") && add.contains("maxlength=\""), "the lane name is not a bounded text field")
    }

    @Test
    fun `an extra lane prints as a dot and a name, and the add line asks for a name only`() {
        TypeDefaultBucketRepository.insert("Fabrication/Manufacturing", "Commissioning")
        TypeDefaultBucketRepository.insert("Fabrication/Manufacturing", "QAQC")
        val card = bucketCard(page(bucketDefaultsType = "Fabrication/Manufacturing"))
        // The picture's list: one dot and one name per lane, the remove at the row's right edge. The dot is the
        // row's position in the list, numbered from 0 since Stage 100 — the catalogue's first row is a new
        // board's first column, because the board is cut from this list and nothing else. Since Stage 80 that is
        // the fallback rather than the rule — these two rows were inserted with no colour chosen, and a row that
        // *has* one is the next test.
        assertEquals(2, occurrences(card, "class=\"set-row lane-row\""), "a lane row is not the row the picture draws")
        assertTrue(card.contains("class=\"bucket-dot dot-0\""), "the first lane has no dot")
        assertTrue(card.contains("class=\"bucket-dot dot-1\""), "the second lane repeats the first one's dot")
        // The add line is a name and a button, with the category riding along hidden — the lane typed while
        // Fabrication is on show must land on Fabrication.
        val add = formSection(card, "<form action=\"/app/settings/buckets/add\"")
        assertTrue(add.contains("New bucket name, e.g. QAQC"), "the add line does not say what it wants")
        assertTrue(add.contains("+ Add bucket"), "the add line has no button")
        // Stage 83: no swatch row on creation. The colour is the app's answer — the next place in the run, which
        // the new row's own dot prints — so the form must not carry a `colour` field a hand-built POST could set.
        assertFalse(add.contains("type=\"radio\" name=\"colour\""), "the add line is still asking for a colour")
        assertFalse(add.contains("class=\"colour-row\""), "the add line is still drawing the swatch row")
        // it says it in the open, at the box: the whole point of the picker above is that a default belongs
        // to ONE category, so the add line names the category the word will land on rather than leaving the
        // reader to infer it from a select three panels up and a hidden field they cannot see.
        assertTrue(
            add.contains("class=\"bucket-add-for\"") && add.contains("Adds to Fabrication/Manufacturing only"),
            "the add line does not name the category it adds to",
        )
    }

    @Test
    fun `a default that was given a colour wears it on the catalogue row`() {
        // The catalogue's dot and the column it later seeds wear the same shade, which is the promise the panel's
        // caption makes: this is the colour the next Fabrication project's board opens with.
        TypeDefaultBucketRepository.insert("Fabrication/Manufacturing", "Commissioning", colourSlot = 0)
        TypeDefaultBucketRepository.insert("Fabrication/Manufacturing", "QAQC")
        val card = bucketCard(page(bucketDefaultsType = "Fabrication/Manufacturing"))
        assertTrue(card.contains("class=\"bucket-dot dot-0\">"), "the chosen colour did not reach the row")
        assertTrue(card.contains("class=\"bucket-dot dot-1\">"), "the lane nobody coloured lost the colour of its place")
    }

    @Test
    fun `the app panels' classes are the ones the stylesheet styles`() {
        val css = appCss()
        Regex("""\.set-row \.type-letter""").find(css) ?: error("the category letter is not laid out")
        Regex("""\.set-row \.type-count""").find(css) ?: error("the project count is not the quiet value it should be")
        // Stage 100 retired the fixed phase rows, so the two rules that laid them out went with them.
        if (Regex("""\.set-row\.phase-row""").containsMatchIn(css)) error("the retired phase row is still styled")
        if (Regex("""\.phase-fixed""").containsMatchIn(css)) error("the retired fixed-row note is still styled")
        Regex("""\.set-row \.lane-name""").find(css) ?: error("an extra lane's name is not emphasised")
        Regex("""\.email-mode""").find(css) ?: error("the delivery mode is not set as the machine-readable line it is")
        // Stage 63: the row pair that replaced the plain lists, and the two inline writes under them.
        Regex("""\.set-row\.type-row, \.set-row\.lane-row""").find(css) ?: error("a category or lane row is not laid out as one line")
        Regex("""form\.type-add input\[name="code"\]""").find(css) ?: error("the letter field is not the short one it reads as")
        Regex("""\.bucket-dot\.dot-0""").find(css) ?: error("a lane dot has no colour")
        // Stage 75: the dot a board's column head wears is the same bucket's colour the catalogue row gives it,
        // and that is a specificity fact as much as a markup one. The box used to be sized under `.lane-row`,
        // which left the head's own sizing rule tying `.bucket-dot.dot-N` on weight and winning on order — so
        // every column painted the grey of an uncoloured dot and the three buckets lost what told them apart.
        // Hence: the size lives on the bare class, and nothing downstream of the colours re-paints the head.
        Regex("""^\.bucket-dot \{[^}]*\}""", RegexOption.MULTILINE).find(css)
            ?: error("the dot's box is not sized on the bare class the colour rules can beat")
        if (Regex("""\.kcol-head \.bucket-dot \{[^}]*background""").containsMatchIn(css)) {
            error("the column head's dot is painted over its own colour")
        }
        Regex("""label\.check-row""").find(css) ?: error("the mail switch's label is not the row it prints as")
        // The add line's scope sentence: it sits in the flex row beside the box and the button, so it is a laid
        // out item of that form and not a paragraph the panel stacks under it.
        Regex("""form\.bucket-add \.bucket-add-for""").find(css) ?: error("the add line's category word is not laid out with the box")
    }

    // --- Stage 62: the master switch for outbound mail ---------------------------------------------------

    /**
     * The switch that lets the installation email anyone at all belongs to *Manage email settings* alone
     * (Stage 110), layered on top of the operator's `data/.env`. The email card's read-only lines print for
     * anybody who manages employees — they are the ones asked why a colleague was never told — while the tick
     * box and its Save print only for a chip-holder, the same right `/app/settings/mail-switch` re-checks. A
     * Reader with neither is not shown the card at all.
     *
     * Since Stage 63 the chip-holder's half is a checkbox carrying the state rather than a button offering its
     * opposite, so the read-back is `checked` and the warning rides on the Save that would arm the mailer.
     */
    @Test
    fun `the outbound mail switch prints for management but only an admin may move it`() {
        val managerHtml = page() // a manager: reads the switch's state, cannot flip it
        assertTrue(managerHtml.contains(">Outbound mail<"), "the card does not report whether sending is enabled")
        assertTrue(managerHtml.contains("Switched off — PTS will not send"), "an unset switch is not read as off for a manager")
        assertFalse(managerHtml.contains("/app/settings/mail-switch"), "a manager was offered the switch that enables the whole mailer")
        assertFalse(managerHtml.contains("name=\"on\""), "a manager was offered the box that arms the mailer")

        // An admin gets the state as the control itself, so the read-only line steps out of its way.
        val admin = adminPage()
        val form = formSection(admin, "<form action=\"/app/settings/mail-switch\"")
        assertTrue(form.contains("data-live-target=\"#emailBoard\""), "flipping the switch would reload the page")
        assertTrue(form.contains("type=\"checkbox\"") && form.contains("name=\"on\""), "the switch is not a tick box")
        assertTrue(form.contains("value=\"true\""), "the box does not post the state it names")
        assertFalse(form.contains("checked"), "an unset switch is printed as already armed")
        assertTrue(form.contains("Enable email notifications"), "the box does not say what it enables")
        assertTrue(form.contains("Save settings"), "the box has no write of its own")
        assertTrue(form.contains("data-confirm"), "enabling the whole mailer was not confirmed")

        // A Viewer never reaches the email card, so the switch is off their page entirely.
        assertFalse(viewerPage().contains("Outbound mail"), "the switch state printed for a Viewer")
        assertFalse(viewerPage().contains("/app/settings/mail-switch"), "the switch write was offered to a Viewer")
    }

    @Test
    fun `once an admin enables sending the switch reports it on and offers to stop`() {
        MailSwitch.setEnabled(on = true, canAdmin = true)
        val admin = adminPage()
        val form = formSection(admin, "<form action=\"/app/settings/mail-switch\"")
        assertTrue(form.contains("checked"), "the box is not ticked once an admin has armed the mailer")
        // Pressing Save with the box ticked changes nothing, and unticking it stops the mailer — neither is a
        // write worth warning about, so the confirmation belongs only to the press that would arm it.
        assertFalse(form.contains("data-confirm"), "a press that cannot enable sending asks for confirmation")

        // A manager sees the state changed on their read-only line, but still gets no control.
        assertTrue(page().contains("Enabled — PTS can send"), "management does not see that sending is now enabled")
        assertFalse(page().contains("/app/settings/mail-switch"), "the switch write moved to a manager")
    }

    @Test
    fun `the mail-status flash line answers a switch press`() {
        assertTrue(
            page(who = superAdmin, canManage = true, canAdmin = true, mailStatus = PasswordStatus.MAIL_ENABLED)
                .contains("<div class=\"flash ok\">Outbound mail is now enabled"),
            "an enabled switch is not flashed green",
        )
        assertTrue(
            page(who = superAdmin, canManage = true, canAdmin = true, mailStatus = PasswordStatus.MAIL_DISABLED)
                .contains("<div class=\"flash err\">Outbound mail is now switched off"),
            "a disabled switch is not flashed red",
        )
        assertTrue(
            page(mailStatus = PasswordStatus.MAIL_REFUSED).contains("Only somebody who manages email settings can turn outbound mail"),
            "a refused switch press has no line of its own",
        )
    }
}
