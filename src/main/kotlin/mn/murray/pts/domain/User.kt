package mn.murray.pts.domain

/**
 * User account domain model (was `AccessRow`).
 *
 * NOTE: the `password` field here carries the *legacy plaintext* value read from
 * the JSON store during import only. Once persisted, passwords live hashed in the
 * `users.password_hash` column (see security/PasswordHasher.kt). New code should
 * never read or write this field directly outside the importer.
 */

val ACCESS_LEVELS = listOf("Viewer", "Manager", "Admin")

@kotlinx.serialization.Serializable
data class AccessRow(
    val employeeNumber: String,
    val name: String,
    val position: String,
    val access: String,
    val password: String = "",
    val email: String = "",
    /** The role the account is filed under (Stage 110), for display. Empty on a roster row with no account. */
    val role: String = "",
) {
    /** Effective password: the stored one, or the employee number when none has been set yet. */
    fun effectivePassword(): String = password.ifEmpty { employeeNumber }

    /** Email to display: the stored one when set, otherwise generated from the name. */
    fun displayEmail(): String = email.ifBlank { autoEmail(name) }
}

/** Auto-generated company email: firstname.lastname@murray.mn (keeps letters, digits and hyphens). */
fun autoEmail(name: String): String {
    val local = name.trim().lowercase().replace(Regex("[^a-z0-9-]+"), ".").trim('.')
    return "$local@murray.mn"
}

/**
 * DB-backed user account (v2). Unlike the legacy [AccessRow], the password is stored as a
 * bcrypt [passwordHash] and access is decided by the [roleId] it is filed under (V21, Stage 110).
 *
 * [access] and [positionId] are kept as labels: `access` mirrors the role's old three-way word for rows
 * written before V21 (`Owner`/`Administrator` → Admin, `Project Manager` → Manager, else Viewer), and
 * [positionId] is the job title printed on the sheet. Neither decides a right any more — a role does.
 */
data class User(
    val employeeNumber: String,
    val name: String,
    val passwordHash: String,
    val access: String,                 // legacy label, written in step with roleId
    val email: String,
    val positionId: Int?,
    val isSuperAdmin: Boolean,
    val mustChangePassword: Boolean,
    val createdAt: String,
    val updatedAt: String,
    /** The assigned role's id; null falls back to the default role (see RoleRepository.roleFor). */
    val roleId: Int? = null,
)
