package mn.murray.pts.service

import mn.murray.pts.MIN_PASSWORD_LENGTH
import mn.murray.pts.PasswordStatus
import mn.murray.pts.domain.Permission
import mn.murray.pts.domain.User
import mn.murray.pts.domain.autoEmail
import mn.murray.pts.repository.PositionRepository
import mn.murray.pts.repository.RoleRepository
import mn.murray.pts.repository.TeamRepository
import mn.murray.pts.repository.UserRepository
import mn.murray.pts.security.Authorization

/**
 * The rules behind the Team sheet on Settings (Stage 46): who is on it, which of its seven columns it
 * is read down, what one account may do to a row, and what the add/edit popup is allowed to write.
 *
 * Three things are worth stating before the code, because each is a place where the picture and the
 * database underneath could quietly disagree:
 *
 *  * **A row is a person, not an account.** The sheet lists the roster first and then any account
 *    whose number is not on it — the same union the Access control table prints. A colleague with no
 *    account is a row with a pencil and no role: the popup opened there *creates* the login, it does
 *    not edit one.
 *  * **The role column prints what the account can actually do.** Since Stage 110 that is [Authorization.roleOf]
 *    — the role the account is filed under, with the chips it holds — and not the raw `access` column, because
 *    a sheet a reader acts on has to answer the question the app itself asks before every write.
 *  * **Nothing here re-decides an old rule.** The popup's save is a call into [UserService], whose
 *    rules are the ones the app has always applied: only an administrator sets a position or a role;
 *    the super admin alone sets another person's password (Stage 123); the Owner/Administrator pair alone
 *    sets a department (Stage 117); a new account added without that right starts as the catalogue's
 *    default role with its employee number as password and has to change it; `EMP0000` cannot be deleted
 *    and nobody can delete themselves.
 *
 * Rights are decided here and re-checked at every route, exactly as [LessonService] does: a button the
 * page did not print is not a permission the server granted, and a button it did print is not one
 * either.
 */
object EmployeeService {

    /**
     * The nine departments a person can be filed under (Stage 117), in the list the request gave.
     *
     * Until this stage a department was free text on the roster — "Projects", "Operations", "Workshop" — and
     * those words are still readable. What the popup writes from now on is one of these, and only the roles
     * holding every chip (*Owner* and *Administrator*) may choose it: a department decides who a person is
     * grouped with on the Team page, so it is an assignment, not a label the reader of the moment invents.
     */
    val DEPARTMENTS: List<String> = listOf(
        "Management",
        "Finance",
        "Human Resource",
        "HSE",
        "Production",
        "Engineering",
        "Sales",
        "Project",
        "Procurement",
    )

    /**
     * Every row on the sheet, for this reader, before any filter or order is applied.
     *
     * The four reads are done once for the whole sheet rather than once per row — the same reason the
     * workload table this replaced measured every project's progress up front: a count that is
     * re-derived per row can only drift from the one beside it. [canManage] and [canAdmin] come in from
     * the route for the same reason: asking what the reader may do inside the row loop would ask the
     * database the same question twenty-three times.
     */
    private fun rows(actor: String, canManage: Boolean, canAdmin: Boolean): List<EmployeeRow> {
        val positionsById = PositionRepository.findAll().associateBy { it.id }
        val teamByEmp = TeamRepository.findAll().associateBy { it.employeeNumber }
        val accountsByEmp = UserRepository.findAll().associateBy { it.employeeNumber }
        // A card with two people counts for each of them: the column asks how much open work a person holds,
        // and a shared card is held by both (Stage 122).
        val openByEmp = TaskService.assigned().filter { it.isOpen }
            .flatMap { it.assignees }
            .groupingBy { it }.eachCount()

        // The roster's own order first, then the accounts with no roster row. The sheet is sorted by the
        // column header, so this only decides which people exist at all.
        val numbers = teamByEmp.keys.toList() + accountsByEmp.keys.filter { it !in teamByEmp }
        return numbers.map { emp ->
            val account = accountsByEmp[emp]
            val member = teamByEmp[emp]
            val name = account?.name ?: member?.name.orEmpty()
            val role = account?.let { Authorization.roleOf(it) }
            EmployeeRow(
                employeeNumber = emp,
                name = name,
                department = member?.department.orEmpty(),
                // The position the account is filed under, or the roster's free-text one, or the same
                // fallback UserService.accessRows() prints — so this column and the Access control page
                // cannot name two different jobs for one person.
                position = account?.positionId?.let { positionsById[it]?.name }
                    ?: member?.position?.takeIf { it.isNotBlank() }
                    ?: "Team Member",
                role = role?.name.orEmpty(),
                roleOrder = role?.sortOrder ?: 0,
                email = account?.email?.takeIf { it.isNotBlank() } ?: autoEmail(name),
                // Management counts everybody's open cards; anyone else counts their own and prints a
                // dash elsewhere. null is that dash, and it is why the column sorts with the blanks last
                // instead of treating "not yours to see" as zero.
                openTasks = when {
                    canManage || emp == actor -> openByEmp[emp] ?: 0
                    else -> null
                },
                hasAccount = account != null,
                isSelf = emp == actor,
                mayEdit = canAdmin,
                deleteBlocked = deleteBlock(account, emp, actor, canManage, canAdmin),
            )
        }
    }

    /**
     * Why this reader may not take this row off the sheet, or null when they may.
     *
     * These are [UserService.deleteUser]'s four refusals, in its own order and with its own raw values —
     * the bar on a manager deleting an administrator reads the account's `access` column rather than the
     * effective role, because that is what the delete route compares. The sheet prints the answer as the
     * words on a greyed-out button, so the column stays lined up and the reason is there when anybody
     * wonders why it will not press.
     */
    private fun deleteBlock(
        account: User?,
        employeeNumber: String,
        actor: String,
        canManage: Boolean,
        canAdmin: Boolean,
    ): String? = when {
        !canManage -> ONLY_MANAGEMENT_DELETE
        // A roster row with no account behind it has nothing to take off the database: `deleteUser` would
        // answer USER_NOT_FOUND. The pencil is the control that has an answer for this row, and the title
        // says so rather than printing a bin that can only fail.
        account == null -> NO_ACCOUNT_YET
        employeeNumber == UserService.SUPER_ADMIN -> PasswordStatus.CANNOT_DELETE_SUPER.text
        employeeNumber == actor -> PasswordStatus.CANNOT_DELETE_SELF.text
        // The bar on deleting an administrator reads the target's *role*, not a legacy access word: since
        // Stage 110 the people-facing right is the chip, and an account holding Manage roles & permissions
        // is the one a reader without it may not take away.
        !canAdmin && account != null && Authorization.roleOf(account).may(Permission.MANAGE_ROLES) ->
            PasswordStatus.CANNOT_DELETE_ADMIN.text
        else -> null
    }

    /**
     * The sheet as this reader sees it: the rows the three controls leave, in the order the header
     * asks for, plus the count the toolbar prints beside them.
     *
     * [EmployeeSheet.positions] is gathered from *every* row rather than from the filtered ones, so the
     * position picker keeps listing the choices that exist on the roster. A picker that shrank to the
     * one entry the current search leaves would make the search box answer for it and its own name
     * would vanish from the address with it.
     */
    fun sheet(
        filter: EmployeeFilter = EmployeeFilter(),
        actor: String = "",
        canManage: Boolean = false,
        canAdmin: Boolean = false,
    ): EmployeeSheet {
        val all = rows(actor, canManage, canAdmin)
        val kept = all.filter { filter.matches(it) }
        return EmployeeSheet(
            rows = kept.sortedWith(filter.order),
            shown = kept.size,
            total = all.size,
            positions = all.map { it.position }.distinct().sortedBy { it.lowercase() },
        )
    }

    /**
     * One row, for the route behind `?edit=<employee number>`.
     *
     * Read through the same [rows] the sheet prints, so the popup cannot be filled with a name or a
     * role the sheet is not willing to show.
     */
    fun row(employeeNumber: String, actor: String, canManage: Boolean, canAdmin: Boolean): EmployeeRow? {
        val emp = employeeNumber.trim().uppercase()
        if (emp.isEmpty()) return null
        return rows(actor, canManage, canAdmin).firstOrNull { it.employeeNumber == emp }
    }

    /** The choices the popup's Role picker lists: the role catalogue, in its own order (V21). */
    fun roles(): List<String> = RoleRepository.findAll().map { it.name }

    /** The role a new account starts in — the catalogue's default, Employee as seeded. */
    fun defaultRoleName(): String = RoleRepository.defaultRole().name

    /** The choices the popup's Position picker lists, with the automatic one left to the view. */
    fun positionNames(): List<String> = PositionRepository.findAll().map { it.name }.sortedBy { it.lowercase() }

    /** The choices the popup's Department picker lists, in the order the business named them (Stage 117). */
    fun departments(): List<String> = DEPARTMENTS

    /**
     * File one person's roster row under a department — the Department select on Access control's People
     * sheet (Stage 118).
     *
     * This is the same assignment the popup makes, narrowed to the one field: only a role holding **every**
     * chip may ask (the route's guard), the word has to be one of the nine, and the number has to be on the
     * roster already. Anything else writes nothing and answers false — a blank is not a way to clear a
     * department, because the old free-text words are replaced by choosing one, never by erasing them.
     */
    fun setDepartment(employeeNumber: String, department: String): Boolean {
        val emp = employeeNumber.trim().uppercase()
        val wanted = department.trim()
        if (emp.isEmpty() || wanted !in DEPARTMENTS) return false
        val member = TeamRepository.findByEmpNo(emp) ?: return false
        if (member.department != wanted) TeamRepository.upsert(member.copy(department = wanted))
        return true
    }

    /**
     * One account by number, for the popup behind `?edit=<employee number>`.
     *
     * The popup's Role and Position pickers are filled from the *account* — its own `access` value and the
     * catalog entry its `positionId` points at — while the sheet's two columns print what those add up to.
     * They are different questions: the picker writes a field, and the column answers what the account can do.
     */
    fun account(employeeNumber: String): User? =
        employeeNumber.trim().uppercase().takeIf { it.isNotEmpty() }?.let { UserRepository.findByEmpNo(it) }

    /** The catalog position [account] is filed under, as the picker names it. Blank means it follows the roster. */
    fun positionOf(account: User): String =
        account.positionId?.let { PositionRepository.findById(it)?.name }.orEmpty()

    /**
     * Write one account: a new one when the draft carries no number to edit, an existing one when it
     * does — and answer with either nothing to say or the field each refusal belongs under.
     *
     * Nothing is applied until every field has been checked, because a popup that wrote the email and
     * then refused the password would leave the row half-changed and the reader looking at a form that
     * says it failed. What is applied is a call into [UserService], never a repository write from here:
     * the roster row, the hashed password, the session revocation and the auto-generated email are its
     * rules, and duplicating any one of them is how two pages start disagreeing about the same account.
     */
    fun save(
        draft: EmployeeDraft,
        actor: String,
        canManage: Boolean,
        canAdmin: Boolean,
        canSetRoles: Boolean = canAdmin,
        canSetDepartment: Boolean = false,
        /**
         * The super admin alone (Stage 123): another person's password, on a create or an edit. A word posted
         * by anybody else is refused on the form's own line, because the popup prints them no box for it.
         */
        canSetPassword: Boolean = false,
    ): EmployeeResult {
        val editing = draft.edit.trim().uppercase()
        val target = (editing.ifEmpty { draft.employeeNumber }).trim().uppercase()
        val existing = if (target.isEmpty()) null else UserRepository.findByEmpNo(target)

        // A popup opened on a number that no longer has an account is a page that has been open since
        // somebody else deleted it. It is not a create: the reader named a row, and a silent create
        // would answer with an account they did not ask for.
        if (editing.isNotEmpty() && existing == null) {
            return EmployeeResult.refused(draft, EmployeeField.ROW to ROW_GONE)
        }
        if (editing.isNotEmpty() && !canAdmin) {
            return EmployeeResult.refused(draft, EmployeeField.PERMISSION to NOT_YOURS_EDIT)
        }
        if (editing.isEmpty() && !canManage) {
            return EmployeeResult.refused(draft, EmployeeField.PERMISSION to NOT_YOURS_ADD)
        }

        val errors = LinkedHashMap<EmployeeField, String>()
        if (target.isEmpty()) errors += EmployeeField.EMP_NO to BLANK_EMP
        // A create may not take a number somebody else already signs in with. An edit has already found
        // its account above, so the same read answers both and no uniqueness test is needed twice.
        if (editing.isEmpty() && existing != null) errors += EmployeeField.EMP_NO to EMP_TAKEN
        if (draft.name.isBlank()) errors += EmployeeField.NAME to BLANK_NAME
        if (draft.employeeNumber.contains(" ") || target.any { !it.isLetterOrDigit() }) {
            errors += EmployeeField.EMP_NO to EMP_SHAPE
        }

        // The role is the *Manage roles & permissions* chip; the position and the password are *Manage
        // employees*. A reader holding only the second may add and rename people but not decide what they
        // can do. A hand-built POST that carries a field the reader was never shown is refused on the form's
        // own line rather than under that field, because the popup prints no picker for it — a reason parked
        // under a field that is not on the page is a refusal with nowhere to print. What a reader *may*
        // write is then checked against the catalogue it names, under its own field.
        val role = draft.role.trim()
        val position = draft.position.trim()
        val password = draft.password.trim()
        if (role.isNotEmpty() && !canSetRoles) {
            errors += EmployeeField.PERMISSION to NOT_YOURS_ROLE
        } else if (role.isNotEmpty() && RoleRepository.findByName(role) == null) {
            errors += EmployeeField.ROLE to UNKNOWN_ROLE
        }
        if ((position.isNotEmpty() || password.isNotEmpty()) && !canAdmin) {
            errors += EmployeeField.PERMISSION to NOT_YOURS_PROFILE
        } else {
            if (position.isNotEmpty() && PositionRepository.findByName(position) == null) {
                errors += EmployeeField.POSITION to UNKNOWN_POSITION
            }
            // Stage 123: holding *Manage employees* no longer reaches somebody else's password — that is the
            // super admin's alone. The check sits after the chip check above so the reason names the right the
            // reader is missing rather than the one they hold.
            if (password.isNotEmpty() && !canSetPassword) {
                errors += EmployeeField.PERMISSION to NOT_YOURS_PASSWORD
            } else if (password.isNotEmpty() && password.length < MIN_PASSWORD_LENGTH) {
                errors += EmployeeField.PASSWORD to "A password is $MIN_PASSWORD_LENGTH characters or more."
            }
        }
        // Stage 117: the department is the Owner/Administrator pair's, independently of the two people chips.
        // Somebody who may only manage employees adds the person with no department rather than choosing one,
        // and a hand-posted value is refused on the form's own line for the reason the picker is not printed.
        // A create must name one; an edit may leave the picker alone, which is how an old free-text department
        // survives an address change instead of being silently cleared.
        val department = draft.department.trim()
        if (department.isNotEmpty() && !canSetDepartment) {
            errors += EmployeeField.PERMISSION to NOT_YOURS_DEPARTMENT
        } else if (department.isNotEmpty() && department !in DEPARTMENTS) {
            errors += EmployeeField.DEPARTMENT to UNKNOWN_DEPARTMENT
        } else if (department.isEmpty() && canSetDepartment && editing.isEmpty()) {
            errors += EmployeeField.DEPARTMENT to DEPARTMENT_REQUIRED
        }
        if (errors.isNotEmpty()) return EmployeeResult.refused(draft, errors)

        if (existing == null) {
            return when (
                UserService.addUser(
                    employeeNumber = target,
                    name = draft.name.trim(),
                    email = draft.email,
                    requestedPosition = position,
                    requestedRole = role,
                    newPassword = password,
                    canSetRoles = canSetRoles,
                    canSetProfile = canAdmin,
                    requestedDepartment = department,
                    canSetDepartment = canSetDepartment,
                    canSetPassword = canSetPassword,
                )
            ) {
                PasswordStatus.USER_ADDED -> EmployeeResult(values = draft)
                PasswordStatus.USER_EXISTS -> EmployeeResult.refused(draft, EmployeeField.EMP_NO to EMP_TAKEN)
                PasswordStatus.TOO_SHORT -> EmployeeResult.refused(draft, EmployeeField.PASSWORD to PASSWORD_SHORT)
                else -> EmployeeResult.refused(draft, EmployeeField.EMP_NO to BLANK_EMP)
            }
        }

        // An edit. The name is not among the fields written: see [EmployeeField.NAME]'s note in the view —
        // projects and cards name their people in words, so renaming here would leave them pointing at a
        // name that no longer exists.
        UserService.updateEmail(target, draft.email)
        UserService.updateUserProfile(
            employeeNumber = target, positionName = position, roleName = role, departmentName = department,
        )
        if (password.isNotEmpty()) {
            val status = UserService.setPasswordByAdmin(target, password)
            if (status != PasswordStatus.CHANGED) {
                return EmployeeResult.refused(draft, EmployeeField.PASSWORD to PASSWORD_SHORT)
            }
        }
        return EmployeeResult(values = draft)
    }

    private const val ROW_GONE = "That account is no longer on the sheet."
    private const val NOT_YOURS_EDIT = "Only somebody who manages employees can change an account."
    private const val NOT_YOURS_ADD = "Only somebody who manages employees can add an account."
    private const val NOT_YOURS_ROLE = "Only somebody who manages roles can set a role."
    private const val NOT_YOURS_PROFILE = "Only somebody who manages employees can set a position or a password."
    private const val NOT_YOURS_PASSWORD = "Only the super admin can set another person's password."
    private const val NOT_YOURS_DEPARTMENT = "Only an Owner or Administrator can set a department."
    private const val BLANK_EMP = "Every account is signed in by its employee number."
    private const val EMP_TAKEN = "There is already an account with that number."
    private const val EMP_SHAPE = "An employee number is letters and digits, like EMP0023."
    private const val BLANK_NAME = "Write the name the sheet should print."
    private const val UNKNOWN_ROLE = "Choose one of the roles on the list."
    private const val UNKNOWN_POSITION = "That position is no longer in the catalog."
    private const val UNKNOWN_DEPARTMENT = "Choose one of the departments on the list."
    private const val DEPARTMENT_REQUIRED = "Choose a department from the list."
    private const val PASSWORD_SHORT = "That password is too short."
    private const val ONLY_MANAGEMENT_DELETE = "Only somebody who manages employees can delete an account."
    private const val NO_ACCOUNT_YET = "This person has no account to delete yet — the pencil creates one."
}

/**
 * The seven columns the sheet is read down, each with the key its header puts in the address and the
 * words the header prints.
 *
 * The keys are short on purpose: they are what every form on the page carries back and forth, and
 * `sort=role:desc` reads as an answer in the address bar where `sort=employeeColumnOrdinal=4` does not.
 */
enum class EmployeeColumn(val key: String, val label: String) {
    NAME("name", "Name"),
    EMPLOYEE("emp", "Employee #"),
    POSITION("position", "Position"),
    DEPARTMENT("department", "Department"),
    ROLE("role", "Role"),
    EMAIL("email", "Email"),
    TASKS("tasks", "Open tasks");

    companion object {
        /** The column an address names; an unknown or missing key is the sheet's own default order. */
        fun of(key: String?): EmployeeColumn {
            val wanted = key?.trim()?.lowercase()?.substringBefore(':')
            return entries.firstOrNull { it.key == wanted } ?: NAME
        }
    }
}

/**
 * What the sheet is cut down to and which way it is read.
 *
 * The three controls are the picture's — a search box, a position picker and a role picker — and the
 * fourth is any one of the seven headers. One column and its direction share a single address key
 * because they are one answer to one question: a header click that changed the column but silently
 * reset the direction would be two things done by one press. So `sort=tasks:desc` is the whole order,
 * and a bare `sort=tasks` — what the header of a column you are not yet sorted by sends — is ascending.
 *
 * [descending] reverses the column's own keys but never the blanks: a department nobody filled in, an
 * account with no role, or a count this reader may not see lands at the end of **both** orders, for
 * the reason the lesson sheet gives for its undated carry-overs — a row with no value in it has no
 * place in that order, and hiding it altogether is worse than parking it.
 */
data class EmployeeFilter(
    val text: String = "",
    val position: String = "",
    val role: String = "",
    val column: EmployeeColumn = EmployeeColumn.NAME,
    val descending: Boolean = false,
) {
    val isActive: Boolean get() = text.isNotEmpty() || position.isNotEmpty() || role.isNotEmpty()

    /** What the three controls leave: the search reads name, number and address; the pickers are exact. */
    fun matches(row: EmployeeRow): Boolean {
        if (position.isNotEmpty() && row.position != position) return false
        if (role.isNotEmpty() && row.role != role) return false
        if (text.isEmpty()) return true
        val needle = text.trim().lowercase()
        return row.name.lowercase().contains(needle) ||
            row.employeeNumber.lowercase().contains(needle) ||
            row.email.lowercase().contains(needle)
    }

    /** The order the sheet prints, with the valueless rows kept at the end either way. */
    val order: Comparator<EmployeeRow>
        get() {
            val byColumn = compareBy<EmployeeRow> { sortKey(it) }
                .thenBy { it.name.lowercase() }
                .thenBy { it.employeeNumber }
            return compareBy<EmployeeRow> { it.hollow(column) }.then(if (descending) byColumn.reversed() else byColumn)
        }

    /**
     * This row's place in the current column, as one comparable key.
     *
     * One shape for all seven columns rather than seven comparators: a role is padded to `00`/`01`/`02`
     * and a count to six digits, so the string order is the rank order and the numeric one, and the
     * header of any column means the same thing as the header of any other — text A→Z, numbers small→
     * large, the glyph on the column it is read down.
     */
    private fun sortKey(row: EmployeeRow): String = when (column) {
        EmployeeColumn.NAME -> row.name.lowercase()
        EmployeeColumn.EMPLOYEE -> row.employeeNumber.lowercase()
        EmployeeColumn.POSITION -> row.position.lowercase()
        EmployeeColumn.DEPARTMENT -> row.department.lowercase()
        EmployeeColumn.ROLE -> row.roleOrder.toString().padStart(2, '0')
        EmployeeColumn.EMAIL -> row.email.lowercase()
        EmployeeColumn.TASKS -> (row.openTasks ?: 0).toString().padStart(6, '0')
    }

    /** The value this order puts in the address, or empties out when it is the sheet's default. */
    val sortValue: String
        get() = if (column == EmployeeColumn.NAME && !descending) {
            ""
        } else {
            column.key + if (descending) ":desc" else ""
        }

    /**
     * What the header of [column] will carry next: the order it is *asking* for.
     *
     * A column that is not the current one is asked for head-first, ascending. The one that is gets its
     * direction turned over — which is why the control has to send the pair in a single value: a header
     * that changed the column but left the direction behind would be two answers to one press, and one
     * submit button has one value to give.
     */
    fun sortValueFor(column: EmployeeColumn): String =
        if (column == this.column && !this.descending) "${column.key}:desc" else column.key

    /** The glyph on that header: the direction it holds now, and nothing on a column that is not the order. */
    fun glyphFor(column: EmployeeColumn): String = when {
        column != this.column -> ""
        descending -> "▼"
        else -> "▲"
    }

    /** The view as an address. [extra] pairs ride on the end, already encoded, for a route's own key. */
    fun queryFor(path: String = "/app/settings", extra: List<String> = emptyList()): String = buildString {
        append(path)
        val keys = mutableListOf<String>()
        if (text.isNotEmpty()) keys += "q=${java.net.URLEncoder.encode(text, "UTF-8")}"
        if (position.isNotEmpty()) keys += "position=${java.net.URLEncoder.encode(position, "UTF-8")}"
        if (role.isNotEmpty()) keys += "role=${java.net.URLEncoder.encode(role, "UTF-8")}"
        if (sortValue.isNotEmpty()) keys += "sort=${java.net.URLEncoder.encode(sortValue, "UTF-8")}"
        keys += extra
        if (keys.isNotEmpty()) append('?').append(keys.joinToString("&"))
    }

    /** The same view with the three controls cleared and the order kept — what `Clear` loads. */
    fun cleared(): EmployeeFilter = copy(text = "", position = "", role = "")

    companion object {
        /** The sheet as an address or a form's `board_*` map states it. */
        fun of(query: Map<String, String?>): EmployeeFilter {
            val sort = query["sort"].orEmpty().trim()
            return EmployeeFilter(
                text = query["q"].orEmpty().trim(),
                position = query["position"].orEmpty().trim(),
                role = query["role"].orEmpty().trim(),
                column = EmployeeColumn.of(sort),
                descending = sort.substringAfter(':', "").equals("desc", ignoreCase = true),
            )
        }
    }
}

/**
 * One row of the sheet: the person, the four things the picture prints beside their name, and the two
 * answers to "may this reader do that here?".
 *
 * [role] is empty for a colleague with no account, [email] is never empty because
 * [mn.murray.pts.domain.autoEmail] supplies the address the account would get, and [openTasks] is null
 * when counting that person's cards is not this reader's to do — which the sheet prints as a dash and
 * the order keeps at the end rather than reading as zero.
 */
data class EmployeeRow(
    val employeeNumber: String,
    val name: String,
    val department: String,
    val position: String,
    val role: String,
    val email: String,
    val openTasks: Int?,
    val hasAccount: Boolean,
    val isSelf: Boolean,
    val mayEdit: Boolean,
    /** Null when the trash is live; the words to print on it when a rule says somebody else may. */
    val deleteBlocked: String?,
    /** The role's place in the catalogue — what the ROLE column sorts by. No account sorts as zero. */
    val roleOrder: Int = 0,
) {
    /** Whether this row simply has nothing in [column] — the blanks-last rule the order asks for. */
    fun hollow(column: EmployeeColumn): Boolean = when (column) {
        EmployeeColumn.DEPARTMENT -> department.isBlank()
        EmployeeColumn.ROLE -> role.isBlank()
        EmployeeColumn.TASKS -> openTasks == null
        else -> false
    }
}

/** The rows the sheet prints, and the two numbers its count line reads: "18 of 23 shown". */
data class EmployeeSheet(
    val rows: List<EmployeeRow>,
    val shown: Int,
    val total: Int,
    val positions: List<String>,
) {
    val isEmpty: Boolean get() = rows.isEmpty()
}

/** The popup as its form sent it, kept as typed so a refusal can hand the words back. */
data class EmployeeDraft(
    /** The account being edited, or blank when this is a create. */
    val edit: String = "",
    val employeeNumber: String = "",
    val name: String = "",
    val email: String = "",
    val position: String = "",
    val role: String = "",
    val password: String = "",
    /** One of [EmployeeService.DEPARTMENTS], or blank to leave the roster's department alone. */
    val department: String = "",
) {
    val isEdit: Boolean get() = edit.isNotBlank()

    companion object {
        private val KEYS =
            listOf("edit", "employeeNumber", "name", "email", "position", "role", "password", "department")

        fun of(form: Map<String, String?>): EmployeeDraft = EmployeeDraft(
            edit = form["edit"].orEmpty().trim(),
            employeeNumber = form["employeeNumber"].orEmpty(),
            name = form["name"].orEmpty(),
            email = form["email"].orEmpty(),
            position = form["position"].orEmpty(),
            role = form["role"].orEmpty(),
            password = form["password"].orEmpty(),
            department = form["department"].orEmpty(),
        )

        /** The keys a route reads off the form, so the reader and this shape cannot drift apart. */
        fun keys(): List<String> = KEYS
    }
}

/** Which control a refusal belongs under. The message prints there, not at the top of the page. */
enum class EmployeeField { EMP_NO, NAME, EMAIL, POSITION, ROLE, DEPARTMENT, PASSWORD, ROW, PERMISSION }

/** `saved` on success; field-keyed `errors` and the typed values when the popup has to stay up. */
data class EmployeeResult(
    val saved: Boolean = true,
    val errors: Map<EmployeeField, String> = emptyMap(),
    val values: EmployeeDraft,
) {
    val isEdit: Boolean get() = values.isEdit
    fun error(field: EmployeeField): String? = errors[field]

    companion object {
        fun refused(values: EmployeeDraft, vararg errors: Pair<EmployeeField, String>) =
            EmployeeResult(false, errors.toMap(), values)

        fun refused(values: EmployeeDraft, errors: Map<EmployeeField, String>) =
            EmployeeResult(false, errors, values)
    }
}
