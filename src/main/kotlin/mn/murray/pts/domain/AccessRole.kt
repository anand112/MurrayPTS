package mn.murray.pts.domain

/**
 * The thirteen things a role may be allowed to do (Stage 110).
 *
 * The list is the picture the user attached to Access control: one chip per permission, in that order,
 * with those words. The `key` is what the database, the role form and a URL carry; the `label` is only
 * ever printed. Keeping the two apart is what lets the sheet say "Assign tasks to others" while a row
 * and a form say `assign_tasks`, and it is why a permission can be renamed without a migration.
 */
enum class Permission(val key: String, val label: String) {
    CREATE_PROJECTS("create_projects", "Create projects"),
    EDIT_PROJECT_DETAILS("edit_project_details", "Edit project details"),
    DELETE_PROJECTS("delete_projects", "Delete projects"),
    MANAGE_BUCKETS("manage_buckets", "Manage buckets"),
    EDIT_ANY_TASK("edit_any_task", "Edit any task"),
    ASSIGN_TASKS("assign_tasks", "Assign tasks to others"),
    VIEW_TEAMMATES_TASKS("view_teammates_tasks", "View teammates' tasks"),
    VIEW_FORECASTS("view_forecasts", "View Forecasts tab"),
    EDIT_FORECAST_AMOUNTS("edit_forecast_amounts", "Edit forecast amounts"),
    MANAGE_EMPLOYEES("manage_employees", "Manage employees"),
    MANAGE_PROJECT_TYPES("manage_project_types", "Manage project types"),
    MANAGE_EMAIL_SETTINGS("manage_email_settings", "Manage email settings"),
    MANAGE_ROLES("manage_roles", "Manage roles & permissions");

    companion object {
        /** The permission an address or a form value names; unknown and blank are no permission at all. */
        fun of(key: String?): Permission? =
            entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) }

        /** A set of form values as permissions, silently dropping the ones the app cannot name. */
        fun ofAll(keys: Iterable<String>): Set<Permission> = keys.mapNotNull(::of).toSet()
    }
}

/**
 * One row of the Roles & permissions table: a named set of [permissions].
 *
 * [sortOrder] is the catalogue's order — Owner, Administrator, Project Manager, Employee, then any role
 * somebody adds — and not the alphabet. [isDefault] marks the one role a new account starts in; the
 * migration seeds Employee as it, and the app keeps that promise on every create.
 */
data class AccessRole(
    val id: Int,
    val name: String,
    val permissions: Set<Permission>,
    val sortOrder: Int = 0,
    val isDefault: Boolean = false,
) {
    fun may(permission: Permission): Boolean = permission in permissions
    fun mayAny(permissions: Collection<Permission>): Boolean = permissions.any { it in this.permissions }
}

/** The four names the migration seeds. A custom role is any other name the sheet holds. */
object AccessRoles {
    const val OWNER = "Owner"
    const val ADMINISTRATOR = "Administrator"
    const val PROJECT_MANAGER = "Project Manager"
    const val EMPLOYEE = "Employee"

    val OWNER_PERMISSIONS: Set<Permission> = Permission.entries.toSet()
    val ADMINISTRATOR_PERMISSIONS: Set<Permission> = Permission.entries.toSet()

    /**
     * What a project manager may do, in the picture's order: everything about projects, boards and
     * cards, and the money sheet read and written — but not people, the type catalogue, email or roles.
     */
    val PROJECT_MANAGER_PERMISSIONS: Set<Permission> = setOf(
        Permission.CREATE_PROJECTS,
        Permission.EDIT_PROJECT_DETAILS,
        Permission.DELETE_PROJECTS,
        Permission.MANAGE_BUCKETS,
        Permission.EDIT_ANY_TASK,
        Permission.ASSIGN_TASKS,
        Permission.VIEW_TEAMMATES_TASKS,
        Permission.VIEW_FORECASTS,
        Permission.EDIT_FORECAST_AMOUNTS,
    )

    /** The default role is the reader's own work and their colleagues' cards: nothing that writes. */
    val EMPLOYEE_PERMISSIONS: Set<Permission> = setOf(Permission.VIEW_TEAMMATES_TASKS)

    /** The four seeds in catalogue order, as (name, permissions, default). */
    val SEEDS: List<Triple<String, Set<Permission>, Boolean>> = listOf(
        Triple(OWNER, OWNER_PERMISSIONS, false),
        Triple(ADMINISTRATOR, ADMINISTRATOR_PERMISSIONS, false),
        Triple(PROJECT_MANAGER, PROJECT_MANAGER_PERMISSIONS, false),
        Triple(EMPLOYEE, EMPLOYEE_PERMISSIONS, true),
    )
}
