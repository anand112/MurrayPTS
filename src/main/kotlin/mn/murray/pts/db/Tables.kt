package mn.murray.pts.db

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/**
 * Exposed table definitions — the query-side mirror of `resources/db/migration/V1__init.sql`.
 *
 * Flyway owns DDL; these objects own DML. Keep the two in sync: if you add a column in
 * a V2__*.sql migration, add the matching column here. Consolidated into one file so the
 * whole schema is readable top-to-bottom in the same order as the SQL.
 *
 * Booleans map to SQLite INTEGER 0/1. Timestamps are ISO-8601 TEXT (UTC).
 */

object Positions : Table("positions") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 255).uniqueIndex()
    val permissionLevel = varchar("permission_level", 32).default("Viewer")
    val description = text("description").default("")
    override val primaryKey = PrimaryKey(id)
}

object TeamMembers : Table("team_members") {
    val employeeNumber = varchar("employee_number", 32)
    val name = varchar("name", 255)
    val department = varchar("department", 255).default("")
    val position = varchar("position", 255).default("")
    override val primaryKey = PrimaryKey(employeeNumber)
}

object Users : Table("users") {
    val employeeNumber = varchar("employee_number", 32)
        .references(TeamMembers.employeeNumber, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 255)
    val passwordHash = varchar("password_hash", 255).default("")
    val access = varchar("access", 16).default("Viewer")
    val email = varchar("email", 255).default("")
    val positionId = integer("position_id")
        .references(Positions.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val isSuperAdmin = bool("is_super_admin").default(false)
    val mustChangePassword = bool("must_change_password").default(false)
    val createdAt = text("created_at")
    val updatedAt = text("updated_at")
    override val primaryKey = PrimaryKey(employeeNumber)
}

object Projects : Table("projects") {
    val id = integer("id").autoIncrement()
    val code = varchar("code", 32).uniqueIndex()
    val name = varchar("name", 255)
    val status = varchar("status", 32).default("Not started")
    val progress = integer("progress").default(0)
    val managerEmpNo = varchar("manager_emp_no", 32)
        .references(TeamMembers.employeeNumber, onDelete = ReferenceOption.SET_NULL).nullable()
    val due = text("due").default("")
    val type = varchar("type", 128).default("")
    val createdAt = text("created_at")
    val updatedAt = text("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object ProjectAssignees : Table("project_assignees") {
    val projectId = integer("project_id").references(Projects.id, onDelete = ReferenceOption.CASCADE)
    val employeeNumber = varchar("employee_number", 32)
        .references(TeamMembers.employeeNumber, onDelete = ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(projectId, employeeNumber)
}

object Buckets : Table("buckets") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 128)
    val position = integer("position").default(0)
    override val primaryKey = PrimaryKey(id)
}

object Tasks : Table("tasks") {
    val id = integer("id").autoIncrement()
    val kind = varchar("kind", 16).default("ASSIGNED")   // ASSIGNED | OTHER
    val title = text("title")
    val projectId = integer("project_id")
        .references(Projects.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val due = text("due").default("")
    val priority = varchar("priority", 16).default("Medium")
    val assigneeEmpNo = varchar("assignee_emp_no", 32)
        .references(TeamMembers.employeeNumber, onDelete = ReferenceOption.SET_NULL).nullable()
    val status = varchar("status", 32).default("Not started")
    val bucketId = integer("bucket_id")
        .references(Buckets.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val started = text("started").default("")
    override val primaryKey = PrimaryKey(id)
}

object Forecasts : Table("forecasts") {
    val id = integer("id").autoIncrement()
    val projectId = integer("project_id")
        .references(Projects.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val period = text("period").default("")
    val hours = text("hours").default("")
    val note = text("note").default("")
    override val primaryKey = PrimaryKey(id)
}

object Lessons : Table("lessons") {
    val id = integer("id").autoIncrement()
    val title = text("title")
    val projectId = integer("project_id")
        .references(Projects.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val summary = text("summary").default("")
    override val primaryKey = PrimaryKey(id)
}

object TodoLists : Table("todo_lists") {
    val id = integer("id").autoIncrement()
    val name = text("name")
    val done = bool("done").default(false)
    val ownerEmpNo = varchar("owner_emp_no", 32)
        .references(TeamMembers.employeeNumber, onDelete = ReferenceOption.CASCADE)
    val due = text("due").default("")
    val position = integer("position").default(0)
    override val primaryKey = PrimaryKey(id)
}

object TodoItems : Table("todo_items") {
    val id = integer("id").autoIncrement()
    val title = text("title")
    val done = bool("done").default(false)
    val listId = integer("list_id").references(TodoLists.id, onDelete = ReferenceOption.CASCADE)
    val ownerEmpNo = varchar("owner_emp_no", 32)
        .references(TeamMembers.employeeNumber, onDelete = ReferenceOption.CASCADE)
    val due = text("due").default("")
    val position = integer("position").default(0)
    override val primaryKey = PrimaryKey(id)
}

object Pins : Table("pins") {
    val employeeNumber = varchar("employee_number", 32)
        .references(TeamMembers.employeeNumber, onDelete = ReferenceOption.CASCADE)
    val projectId = integer("project_id").references(Projects.id, onDelete = ReferenceOption.CASCADE)
    val position = integer("position").default(0)
    override val primaryKey = PrimaryKey(employeeNumber, projectId)
}

object Sessions : Table("sessions") {
    val id = varchar("id", 64)
    val employeeNumber = varchar("employee_number", 32)
        .references(Users.employeeNumber, onDelete = ReferenceOption.CASCADE)
    val createdAt = text("created_at")
    val expiresAt = text("expires_at")
    val userAgent = text("user_agent").default("")
    val ip = text("ip").default("")
    override val primaryKey = PrimaryKey(id)
}
