package mn.murray.pts.service

import mn.murray.pts.domain.Project
import mn.murray.pts.domain.TaskItem
import mn.murray.pts.repository.NotificationDraft
import mn.murray.pts.repository.NotificationRepository
import mn.murray.pts.repository.NotificationRow
import mn.murray.pts.repository.ProjectRepository
import mn.murray.pts.repository.TaskRepository
import mn.murray.pts.repository.TeamRepository
import mn.murray.pts.repository.UserRepository
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Who hears what, and where it lands: the bell in the page head, and — for the two events a person
 * acts on — the same message as an email.
 *
 * Three events raise something:
 *
 *  - **a task is assigned to you** ([taskAssigned]) — when a manager creates the card, and again
 *    only when an edit *moves* it to you;
 *  - **a project becomes yours** ([projectManagerAssigned]) — created with you as manager, or
 *    re-assigned to you;
 *  - **a due-date reminder goes out** ([dueDate]) — the sweep already mailed it; this puts the same
 *    sentence in the inbox so a user without mail still sees it.
 *
 * Two rules hold everywhere:
 *
 *  - **you are never told about your own action.** A manager assigning themselves a task writes no
 *    notification; the board they just posted to already shows it.
 *  - **once per person, per item, per day**, enforced by the UNIQUE key in `notifications` (see
 *    V4__notifications.sql). Six edits of one card this afternoon produce one inbox item, and the
 *    mail goes with it: the row is claimed *before* [Mailer] is called, so a retry cannot mail twice.
 *
 * Nothing here may break the write that caused it. Every event method is wrapped: a notification is
 * a courtesy, and a task save must not fail because an inbox row could not be written. The failure
 * is logged, never swallowed silently.
 */
object NotificationService {

    const val KIND_TASK_ASSIGNED = "TASK_ASSIGNED"
    const val KIND_PROJECT_MANAGER = "PROJECT_MANAGER"
    const val KIND_DUE_DATE = "DUE_DATE"

    const val ITEM_TASK = "TASK"
    const val ITEM_PROJECT = "PROJECT"

    /** What `mail_status` records when the event deliberately sends no mail (the due-date inbox copy). */
    private const val MAIL_NONE = "inbox only"

    private val log = LoggerFactory.getLogger(NotificationService::class.java)
    private val dayFormat: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    // --- events -------------------------------------------------------------

    /**
     * Tell [taskId]'s assignee the card is theirs. Reads the row it just wrote, so a create and a
     * re-assignment produce the same message from the same source of truth.
     *
     * **Since Stage 122 a card can have two people on it**, and both hear when the card is written. [only] is
     * the narrower case an edit needs: the people an edit has just *added* to the card, so putting a second
     * person on work that already had an owner mails that second person alone rather than mailing the first one
     * again. A create leaves it null and the card's whole [TaskItem.assignees] list is told. Each recipient is
     * delivered separately, which is what keeps the inbox's per-person dedupe meaningful.
     */
    fun taskAssigned(taskId: Int, actorEmpNo: String, only: Collection<String>? = null) = guard("task $taskId") {
        val task = TaskRepository.findById(taskId) ?: return@guard
        val recipients = (only ?: task.assignees).filter { it.isNotBlank() }.distinct()
        if (recipients.isEmpty()) return@guard
        val project = projectOf(task)
        val where = project?.let { "on ${it.code} - ${it.name}" } ?: "with no project"
        val due = if (task.due.isNotBlank()) " Due ${task.due}." else ""
        val title = "${taskCode(task.id)} ${task.title.take(60)}".trim()
        val body = "${actorName(actorEmpNo)} assigned this task to you $where.$due"
        val email = "${taskCode(task.id)} - ${task.title}" to
            "You were assigned a task in PTS.\n\n" +
                "  Task    : ${taskCode(task.id)} ${task.title}\n" +
                "  Project : ${project?.let { "${it.code} - ${it.name}" } ?: "-"}\n" +
                "  Due     : ${task.due.ifBlank { "-" }}\n" +
                "  Status  : ${task.status}\n\n" +
                "$body\n\n" +
                "Open PTS to update it. -- MURRAY PTS (automated; replies are not read)"
        recipients.forEach { recipient ->
            deliver(
                recipient = recipient, kind = KIND_TASK_ASSIGNED, itemKind = ITEM_TASK, itemId = task.id,
                title = title, body = body, link = taskLink(task, project), actor = actorEmpNo,
                email = email,
            )
        }
    }

    /** Tell the project's new manager it is theirs. Silently does nothing when none is set. */
    fun projectManagerAssigned(projectId: Int, actorEmpNo: String) = guard("project $projectId") {
        val recipient = ProjectRepository.managerOf(projectId) ?: return@guard
        val project = ProjectRepository.findById(projectId) ?: return@guard
        deliver(
            recipient = recipient, kind = KIND_PROJECT_MANAGER, itemKind = ITEM_PROJECT, itemId = project.id,
            title = "${project.code} ${project.name.take(60)}".trim(),
            body = buildString {
                append("${actorName(actorEmpNo)} made you the manager of this project.")
                if (project.due.isNotBlank()) append(" End date ${project.due}.")
            },
            link = "/app/projects/${project.id}", actor = actorEmpNo,
            email = "${project.code} - ${project.name}" to
                "You are the manager of a PTS project.\n\n" +
                    "  Project : ${project.code} ${project.name}\n" +
                    "  Type    : ${project.type.ifBlank { "-" }}\n" +
                    "  Status  : ${project.status}\n" +
                    "  Progress: ${project.progress}%\n" +
                    "  End date: ${project.due.ifBlank { "-" }}\n\n" +
                    "Open PTS to update it. -- MURRAY PTS (automated)",
        )
    }

    /**
     * Put one due-date reminder in the recipient's inbox. No mail is sent here — the sweep mailed it
     * a moment ago, and [DueDateAlertService]'s ledger is what keeps that to once a day.
     */
    fun dueDate(recipientEmpNo: String, itemKind: String, itemId: Int, title: String, body: String) =
        guard("due-date $itemKind $itemId") {
            deliver(
                recipient = recipientEmpNo, kind = KIND_DUE_DATE, itemKind = itemKind, itemId = itemId,
                title = title, body = body.lines().firstOrNull().orEmpty().take(200),
                link = if (itemKind == ITEM_PROJECT) "/app/projects/$itemId" else taskLink(itemId),
                actor = "", email = null,
            )
        }

    // --- reads, for the bell -------------------------------------------------

    fun inbox(empNo: String, limit: Int = 20): List<NotificationRow> =
        NotificationRepository.forRecipient(empNo, limit)

    /** The badge number; 0 renders no badge at all. */
    fun unreadCount(empNo: String): Int = NotificationRepository.unreadCount(empNo)

    fun markRead(empNo: String, id: Int): Boolean = NotificationRepository.markRead(empNo, id)

    fun markAllRead(empNo: String): Int = NotificationRepository.markAllRead(empNo)

    /**
     * Where a row points, or null when it does not point inside the app. `link` is written by this
     * service only, so the check is a guard against a hand-edited row turning the bell into an open
     * redirect — the route redirects to whatever this returns.
     */
    fun safeLink(empNo: String, id: Int): String? =
        NotificationRepository.linkOf(empNo, id)?.takeIf { it.startsWith("/app/") }

    /** Trim old inbox rows; the scheduler calls it, because an inbox is not an audit trail. */
    fun purgeOlderThan(daysToKeep: Int): Int {
        if (daysToKeep <= 0) return 0
        val cutoff = DueDateAlertService.today().minusDays(daysToKeep.toLong()).format(dayFormat)
        val removed = NotificationRepository.purgeOlderThan(cutoff)
        if (removed > 0) log.info("Notifications: removed {} row(s) before {}", removed, cutoff)
        return removed
    }

    // --- the one write path ---------------------------------------------------

    /**
     * Claim the day's row, then mail it when the event asks for mail. Returning early on a null id
     * is the dedupe: whoever holds the row owns the mail, so the loser of the race sends nothing.
     */
    private fun deliver(
        recipient: String,
        kind: String,
        itemKind: String,
        itemId: Int,
        title: String,
        body: String,
        link: String,
        actor: String,
        email: Pair<String, String>?,
    ) {
        if (recipient.isBlank() || recipient == actor) return
        val id = NotificationRepository.insert(
            NotificationDraft(
                recipientEmpNo = recipient, kind = kind, itemKind = itemKind, itemId = itemId,
                dedupeDay = DueDateAlertService.today().format(dayFormat),
                title = title.take(200), body = body.take(2000), link = link, actorEmpNo = actor,
            )
        ) ?: return
        val status = email?.let { mail(recipient, kind, it) } ?: MAIL_NONE
        NotificationRepository.setMailStatus(id, status)
    }

    /**
     * Mail one event to the address on the recipient's account. The status it returns is what the
     * inbox row keeps, so "the mail never left the box" is visible without opening `logs/pts.log`.
     */
    private fun mail(recipient: String, kind: String, email: Pair<String, String>): String {
        val address = UserRepository.findByEmpNo(recipient)?.email.orEmpty()
        val result = Mailer.send(address, "[PTS] ${email.first}", email.second)
        val status = when (result) {
            is MailResult.Sent -> "sent"
            is MailResult.Failed -> "failed"
            is MailResult.LoggedOnly -> if (address.isBlank()) "no-address" else "logged"
        }
        log.info("Notification mail for {} ({}): {}", recipient, kind, status)
        return status
    }

    private fun guard(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            // A notification that cannot be written must never undo the save that caused it.
            log.warn("Could not notify about {}: {}", what, "${e.javaClass.simpleName}: ${e.message ?: e}")
        }
    }

    /** The roster name when the actor still has one, so the line reads like a person, not an id. */
    private fun actorName(empNo: String): String =
        empNo.takeIf { it.isNotBlank() }?.let { TeamRepository.findByEmpNo(it)?.name } ?: "A manager"

    private fun taskCode(id: Int): String = "T-${id.toString().padStart(5, '0')}"

    /**
     * The page a card is on. No board's URL carries a card id, so a link points at the board and the reader
     * finds the card there: a project card on its project page, a backlog card on the Other tasks board
     * (Stage 47 moved that board off Assigned tasks, and a notice that still pointed at the old page would
     * land on a board that does not print the card), and anything else on the assigned board.
     */
    private fun taskLink(task: TaskItem, project: Project?): String = when {
        project != null -> "/app/projects/${project.id}"
        task.isBacklog -> "/app/other-tasks"
        else -> "/app/assigned-tasks"
    }

    /**
     * The same answer for a due-date reminder, which knows an id and not the row: the sweep raises one for a
     * project card and a backlog card alike from [ITEM_TASK] items alone. A card that has gone off the
     * database since falls back to the assigned board, which is what a link to nothing points at anyway.
     */
    private fun taskLink(itemId: Int): String =
        if (TaskRepository.findById(itemId)?.isBacklog == true) "/app/other-tasks" else "/app/assigned-tasks"

    /** [TaskItem] carries the project's *name*; the row behind it is what a link and a code need. */
    private fun projectOf(task: TaskItem): Project? =
        task.project.takeIf { it.isNotBlank() }?.let { name ->
            ProjectRepository.idByName()[name]?.let { ProjectRepository.findById(it) }
        }
}
