package mn.murray.pts.db

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/**
 * Exposed table definitions — the query-side mirror of `resources/db/migration/V*.sql`.
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
    // `due` is the project's END date — V3 kept the column name because the Calendar and
    // the due-date alert sweep both key off it. See V3__project_categories.sql.
    val due = text("due").default("")
    val startDate = text("start_date").default("")
    val client = varchar("client", 255).default("")
    val revenue = double("revenue").default(0.0)
    // How much of the contract has actually been invoiced (V11). Kept beside revenue, not derived from it:
    // what is billed and what was quoted are two different facts and management enters the first by hand.
    val invoiced = double("invoiced").default(0.0)
    // One of domain/PROJECT_TYPES (nine letter-coded categories since V3); stores the label.
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

/**
 * One lane on one project's board (V7) — Engineering, Procurement, Production by default, and whatever
 * else whoever runs the project adds. Deliberately **not** rows in [Buckets] with a project id bolted on:
 * `buckets` is the Other tasks board's column list, its names are assumed unique by the legacy importer,
 * and every read of it would then have to remember which project a lane belongs to. Two boards, two
 * tables, one foreign key each — see V7__project_task_buckets.sql.
 *
 * [position] is the append counter the checklist, notes, to-do items and buckets use, so two lanes added
 * in the same second still print in the order they were written.
 *
 * [colourSlot] (V17) is that lane's own place in the stylesheet's six-hue cycle, or null for *wear the colour of
 * my position*, which is what every lane written before Stage 80 does and what a board whose manager has never
 * opened the colour picker still looks like.
 */
object ProjectBuckets : Table("project_buckets") {
    val id = integer("id").autoIncrement()
    val projectId = integer("project_id").references(Projects.id, onDelete = ReferenceOption.CASCADE)
    val name = text("name")
    val position = integer("position").default(0)
    val colourSlot = integer("colour_slot").nullable()
    override val primaryKey = PrimaryKey(id)
}

/**
 * The extra lanes a project **category** adds to a new board (V12) — the catalogue Settings edits and
 * [mn.murray.pts.service.ProjectService.create] appends after the three phases a board opens with.
 *
 * Not rows of [ProjectBuckets] with a project id left blank: that table is a board, its rows are what a
 * card is filed against, and a template standing in it would be a lane somebody could drag a card onto.
 * Nothing points here from `tasks` — a created project's lanes are ordinary [ProjectBuckets] rows, and if
 * the catalogue changes afterwards the board does not, which is the whole point of a default.
 *
 * [type] is a category LABEL as PROJECT_TYPES spells it, and [position] counts from 3 because the three phase
 * lanes hold 0..2 on a fresh board. [colourSlot] (V17) is the hue this offer carries onto the board it seeds —
 * null for the positional shade, which is every row Settings wrote before Stage 80 and every row it writes
 * until somebody picks one.
 */
object TypeDefaultBuckets : Table("type_default_buckets") {
    val id = integer("id").autoIncrement()
    val type = text("type")
    val name = text("name")
    val position = integer("position").default(0)
    val colourSlot = integer("colour_slot").nullable()
    override val primaryKey = PrimaryKey(id)
}

/**
 * The project categories management edits on Settings (V14) — the list `PROJECT_TYPES` used to be.
 *
 * [code] is the letter, and it is the primary key rather than a column beside an id: a project number is
 * `<letter>-<tail>`, so two categories sharing a letter would hand out the same number to two projects.
 * [name] is the label `projects.type` stores, and there is no foreign key from that column to this one —
 * rows imported from the legacy JSON can carry a label this table has never seen, and the boards print
 * those with their raw text rather than dropping them.
 */
object ProjectTypes : Table("project_types") {
    val code = text("code")
    val name = text("name")
    val position = integer("position").default(0)
    override val primaryKey = PrimaryKey(code)
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
    /**
     * Which lane this card sits on when its board is a **project's** (V7). Null means unfiled: the card is
     * still on the project, and its page gives it an Unsorted column rather than hiding it. It is a second
     * column next to [bucketId] because that one is the Other tasks board's column list — an assigned card
     * has never used it, and one name pointing at two boards is how a card ends up on the wrong board.
     */
    val projectBucketId = integer("project_bucket_id")
        .references(ProjectBuckets.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val started = text("started").default("")
    /**
     * Whether this card is off every board, in the Archive (V18).
     *
     * A flag on the row rather than a copy of the row, on purpose: the card's plan ([TaskChecklistItems]) and its
     * log ([TaskNotes]) hang off this id, and the promise the archive panel makes — *restore it as it left* — only
     * holds if those children were never moved. Existing rows read false, so upgrading changes no board.
     */
    val archived = bool("archived").default(false)
    /**
     * Which lane the card was filed in when it was archived, remembered as a **bare integer with no foreign key**
     * (V18).
     *
     * It cannot be a reference: on the path that matters most — a bucket removed from the board — the lane row is
     * deleted, and an FK column would be nulled by `ON DELETE SET NULL` in the same breath that the archive is
     * created, destroying exactly the memory it exists to keep. So this is a snapshot and restore reads it as a
     * hint: that lane still on the project means the card goes back into it; gone means the project's first lane.
     */
    val archivedLaneId = integer("archived_lane_id").nullable()
    /** The archived lane's *name*, for the panel's "was in X" line after that row is gone (V18). */
    val archivedLane = text("archived_lane").nullable()
    /** The day the card left the board, `yyyy-MM-dd`, the format every date in this app prints (V18). */
    val archivedOn = text("archived_on").nullable()
    override val primaryKey = PrimaryKey(id)
}

/**
 * The tickable steps under one task card (V5). Management writes the list, the assignee works
 * down it — and since Stage 89 adds to it too; the task's own `status` column stays the only thing
 * that moves a card between columns.
 * `label` rather than `text` because the latter is a type keyword, and `position` is the append
 * counter used by [TodoItems] and [Buckets] too. Cascade with the card: no orphaned steps.
 *
 * [preCompleted] is V15's memory: where this step stood the moment before the card was moved into
 * **Completed**, which ticks the whole plan. NULL means no completion is outstanding — the ordinary
 * state of a step on a card that is being worked — so it is nullable rather than defaulting to false,
 * because false would be a claim that the step was unticked when somebody finished the card.
 */
object TaskChecklistItems : Table("task_checklist_items") {
    val id = integer("id").autoIncrement()
    val taskId = integer("task_id").references(Tasks.id, onDelete = ReferenceOption.CASCADE)
    val label = text("label")
    val done = bool("done").default(false)
    val position = integer("position").default(0)
    val preCompleted = bool("pre_completed").nullable()
    override val primaryKey = PrimaryKey(id)
}

/**
 * The running log under one task card (V6). One row per note so two people writing on the same
 * card cannot overtype each other the way a single shared column would. [authorEmpNo] deliberately
 * has **no foreign key** — like [Notifications.actorEmpNo], a note is history and should still name
 * its writer after that account is deleted. [position] is the append counter the checklist, to-do
 * items and buckets all use, and [created] is a `yyyy-MM-dd` UTC day: the app shows no clock time,
 * so it stores none.
 */
object TaskNotes : Table("task_notes") {
    val id = integer("id").autoIncrement()
    val taskId = integer("task_id").references(Tasks.id, onDelete = ReferenceOption.CASCADE)
    val body = text("body")
    val authorEmpNo = text("author_emp_no")
    val position = integer("position").default(0)
    val created = text("created").default("")
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

/**
 * V8: one project's money for one month. `month` is the line's own month and the key the
 * ledger is summed by; `invoiced_month` is a label saying when the invoice is raised, and it
 * moves nothing. `invoice_status` is management's stated label, not a figure derived from the
 * amounts beside it — see V8__forecast_ledger.sql.
 */
object ProjectForecasts : Table("project_forecasts") {
    val id = integer("id").autoIncrement()
    val projectId = integer("project_id")
        .references(Projects.id, onDelete = ReferenceOption.CASCADE)
    val month = text("month")
    val forecastAmount = double("forecast_amount").default(0.0)
    val invoicedAmount = double("invoiced_amount").default(0.0)
    /** When the invoice is raised. Descriptive only — it never moves the money between lines. */
    val invoicedMonth = text("invoiced_month").default("")
    val invoiceStatus = text("invoice_status").default("Not invoiced")
    override val primaryKey = PrimaryKey(id)
}

/**
 * One person's lesson about one project (V9).
 *
 * `title` and `summary` are the V1 pair the importer and the demo seeder still fill in; the page
 * reads [lesson], which V9 backfilled from them and which every write since sets directly. They
 * stay filled together because `title` is NOT NULL with no default — see the migration's note on
 * why the table was widened rather than rebuilt.
 */
object Lessons : Table("lessons") {
    val id = integer("id").autoIncrement()
    val title = text("title")
    val projectId = integer("project_id")
        .references(Projects.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val summary = text("summary").default("")
    val lesson = text("lesson").default("")
    /** Who logged it. Null on a row V9 carried over, and null when the account has since gone. */
    val employeeNumber = varchar("employee_number", 32)
        .references(TeamMembers.employeeNumber, onDelete = ReferenceOption.SET_NULL).nullable()
    /** The card the lesson came from, when there was one. */
    val taskId = integer("task_id")
        .references(Tasks.id, onDelete = ReferenceOption.SET_NULL).nullable()
    /** 'YYYY-MM-DD', or '' for a row that arrived undated. */
    val loggedOn = text("logged_on").default("")
    val discussed = bool("discussed").default(false)
    val archived = bool("archived").default(false)
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

/**
 * Due-date alert ledger (V2). One row per (kind, item, recipient, day): the UNIQUE index is what
 * makes "at most one alert per person per item per day" hold across restarts and manual "run now"
 * presses. `delivered = false` rows are the failed sends and the log-only dry runs, kept on purpose
 * so an operator can see what the scheduler actually attempted.
 */
object DueDateAlerts : Table("due_date_alerts") {
    val id = integer("id").autoIncrement()
    val kind = varchar("kind", 16)                        // PROJECT | TASK
    val itemId = integer("item_id")
    val recipientEmpNo = varchar("recipient_emp_no", 32)
        .references(TeamMembers.employeeNumber, onDelete = ReferenceOption.CASCADE)
    val alertDate = text("alert_date")                    // yyyy-MM-dd in the app clock (UTC)
    val subject = text("subject").default("")
    val body = text("body").default("")
    val due = text("due").default("")
    val delivered = bool("delivered").default(false)
    val channel = varchar("channel", 16).default("email") // email | log
    val error = text("error").default("")
    val sentAt = text("sent_at")
    override val primaryKey = PrimaryKey(id)
}

/**
 * In-app notifications (V4) — the bell in the page head. One row per person, per event, per day:
 * the UNIQUE key is what stops six edits of one card from becoming six inbox items, mirroring how
 * [DueDateAlerts] makes six sweeps become six emails at one. `read_at` is empty while unread, so a
 * badge count needs no join and no boolean to flip. `link` is written by the server only; the open
 * route re-checks that it is an `/app/` path before redirecting. See `NotificationService`.
 */
object Notifications : Table("notifications") {
    val id = integer("id").autoIncrement()
    val recipientEmpNo = varchar("recipient_emp_no", 32)
        .references(TeamMembers.employeeNumber, onDelete = ReferenceOption.CASCADE)
    val kind = varchar("kind", 24)                        // TASK_ASSIGNED | PROJECT_MANAGER | DUE_DATE
    val itemKind = varchar("item_kind", 16).default("")   // TASK | PROJECT
    val itemId = integer("item_id").default(0)
    val dedupeDay = text("dedupe_day")                    // yyyy-MM-dd in the app clock (UTC)
    val title = text("title")
    val body = text("body").default("")
    val link = text("link").default("")
    val actorEmpNo = varchar("actor_emp_no", 32).default("")   // no FK: history outlives the account
    val mailStatus = varchar("mail_status", 16).default("")    // sent | logged | failed | no-address
    val createdAt = text("created_at")
    val readAt = text("read_at").default("")
    override val primaryKey = PrimaryKey(id)
}

/**
 * The app-wide settings a super admin owns from within the app (V13) — a switch, not a secret.
 *
 * Deliberately separate from `data/.env`: the SMTP host, dry-run flag and password are deployment config an
 * operator edits and restarts, and a password must never be typed into a web form or stored here. What lives
 * in this table is the one decision the app itself makes and has to remember across a restart — see
 * [mn.murray.pts.service.MailSwitch], which reads a missing key as OFF so the system cannot email anyone
 * until an admin turns that switch on. One row per [key]; [value] is free text whose meaning is the service's.
 */
object AppSettings : Table("app_settings") {
    val key = text("key")
    val value = text("value")
    override val primaryKey = PrimaryKey(key)
}
