package mn.murray.pts.service

import mn.murray.pts.config.AppConfig
import mn.murray.pts.repository.AlertLedgerRow
import mn.murray.pts.repository.AlertRepository
import mn.murray.pts.repository.ProjectDueRow
import mn.murray.pts.repository.TaskDueRow
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * What one sweep did, in the order the counts matter to an operator reading a log line.
 */
data class AlertSweepSummary(
    val alertDate: String,
    val mode: String,
    val projectsReviewed: Int,
    val tasksReviewed: Int,
    val itemsMatching: Int,
    val alertsAttempted: Int,
    val delivered: Int,
    val loggedOnly: Int,
    val failed: Int,
    val skippedAlreadySent: Int,
    val detail: List<String> = emptyList(),
) {
    /** One line for the log; the detail list is only for the admin endpoint. */
    fun render(): String =
        "due-date alerts [$alertDate] mode=$mode: reviewed ${projectsReviewed}p/${tasksReviewed}t, " +
            "$itemsMatching open item(s) in window, $alertsAttempted alert(s) attempted " +
            "($delivered delivered, $loggedOnly logged only, $failed failed), " +
            "$skippedAlreadySent already sent today"

    fun renderDetail(): String = (listOf(render()) + detail.map { "  $it" }).joinToString("\n")
}

/**
 * The "this is not finished and the due date is arriving" reminder.
 *
 * Rules, as agreed for launch:
 *  - a **project** is worth an alert while its status is not `Completed` **and** its progress is
 *    below 100 (the user asked for the 100% check explicitly; either signal alone closes the item);
 *  - a **task** is worth an alert while its status is not `Completed` — tasks carry no progress;
 *  - the item must have a parseable `yyyy-MM-dd` due date; blank or malformed dates are skipped,
 *    because guessing would mail the wrong people about the wrong deadline;
 *  - the window opens [AppConfig.alertLeadDays] days before the due date and never closes: an item
 *    that reaches its due date unfinished flips to overdue wording and keeps alerting daily until it
 *    is finished — the original complaint was that overdue work goes quiet;
 *  - recipients are the project's assigned manager plus its assignees, and for a task its assignee
 *    plus its project's manager;
 *  - one person gets one alert per item per calendar day, enforced by the ledger's UNIQUE index, so
 *    restarts and manual "run now" presses cannot double-mail.
 *
 * Delivery goes through [Mailer] only, and the mail server's presence is not a precondition for the
 * rules: with no `PTS_SMTP_HOST`, or `PTS_SMTP_DRY_RUN=true`, the same message text is rendered and
 * written to the log and to the ledger with `delivered = 0`. That is what makes this verifiable
 * before anyone has a mail server.
 */
object DueDateAlertService {

    const val KIND_PROJECT = "PROJECT"
    const val KIND_TASK = "TASK"

    private val log = LoggerFactory.getLogger(DueDateAlertService::class.java)
    private val dayFormat: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /** A sweep is blocking I/O over the whole roster; never let two run at once. */
    private val sweepLock = ReentrantLock()

    /** Most recent summary, for the admin page and for a boot log line. */
    @Volatile
    private var lastSummary: AlertSweepSummary? = null

    fun lastSummary(): AlertSweepSummary? = lastSummary

    /**
     * Today in the clock the ledger is written against. Pinned to UTC explicitly rather than
     * inherited from `-Duser.timezone`, so a container started without a TZ still sees one calendar
     * day per day and the dedupe key cannot straddle two days mid-sweep.
     */
    fun today(): LocalDate = LocalDate.now(ZoneOffset.UTC)

    /**
     * How this deployment would deliver, for the boot log and the admin trigger. Kept separate from
     * the sweep so [DueDateAlertScheduler] can say it without running anything.
     */
    fun currentMode(): String = when {
        !AppConfig.alertsEnabled -> "disabled"
        AppConfig.smtpDryRun -> "dry run (log only)"
        !AppConfig.smtpHost.isNotBlank() -> "log only (no PTS_SMTP_HOST)"
        // The .env could send, but the super admin's in-app switch has not enabled outbound mail.
        !MailSwitch.enabled() -> "switched off on Settings (a super admin must enable sending)"
        else -> "email via ${AppConfig.smtpHost}:${AppConfig.smtpPort} as ${AppConfig.smtpFrom.ifBlank { "(sender = login)" }}"
    }

    /**
     * The short form of [currentMode] used by the sweep and its ledger. Same three log-only states, condensed:
     * dry run beats a missing host, which beats the admin switch being off, because that is the order an
     * operator fixes them in.
     */
    private fun mode(): String = when {
        AppConfig.smtpDryRun -> "dry run (log only)"
        AppConfig.smtpHost.isBlank() -> "log only (no PTS_SMTP_HOST)"
        !MailSwitch.enabled() -> "switched off on Settings"
        else -> "email via ${AppConfig.smtpHost}:${AppConfig.smtpPort}"
    }

    /**
     * Run one sweep. Called from the scheduler coroutine and from the admin trigger; a second caller
     * waits its turn instead of racing the dedupe — by then the ledger already holds the day's rows,
     * so the waiting run reports "already sent today" for everything rather than mailing twice.
     */
    fun runSweep(now: LocalDate = today()): AlertSweepSummary {
        sweepLock.lock()
        return try {
            sweep(now).also {
                lastSummary = it
                log.info(it.render())
            }
        } finally {
            sweepLock.unlock()
        }
    }

    // --- the sweep ---------------------------------------------------------

    private fun sweep(today: LocalDate): AlertSweepSummary {
        val lead = AppConfig.alertLeadDays.coerceAtLeast(0)
        val horizon = today.plusDays(lead.toLong())
        val alertDate = today.format(dayFormat)
        val mode = mode()

        val sent = AlertRepository.keysForDay(alertDate).toMutableSet()
        val names = AlertRepository.displayNames()
        val emails = AlertRepository.emailsByEmpNo()
        val projects = AlertRepository.openProjects()
        val tasks = AlertRepository.openTasks()

        var items = 0
        var attempted = 0
        var delivered = 0
        var loggedOnly = 0
        var failed = 0
        var skipped = 0
        val detail = mutableListOf<String>()

        projects.forEach { p ->
            val due = parseDue(p.due) ?: return@forEach
            if (p.progress >= 100) return@forEach            // "finished 100%" — nothing to say
            if (due.isAfter(horizon)) return@forEach
            val recipients = (listOf(p.managerEmpNo) + p.assigneeEmpNos).filter { it.isNotBlank() }.distinct()
            if (recipients.isEmpty()) {
                detail += "$KIND_PROJECT ${p.id} ${p.code} in window but has no manager or assignees to tell"
                return@forEach
            }
            items++
            recipients.forEach { emp ->
                val outcome = alert(
                    kind = KIND_PROJECT,
                    itemId = p.id,
                    recipient = emp,
                    alertDate = alertDate,
                    due = due,
                    subjectOf = { labelTiming(p.label(), due, today).first },
                    bodyOf = { projectBody(p, emp, names[emp], due, today) },
                    sent = sent,
                    email = emails[emp].orEmpty(),
                )
                when (outcome) {
                    Outcome.SKIPPED -> skipped++
                    Outcome.DELIVERED -> delivered++
                    Outcome.LOGGED -> loggedOnly++
                    Outcome.FAILED -> failed++
                }
                if (outcome != Outcome.SKIPPED) {
                    attempted++
                    detail += "$KIND_PROJECT ${p.id} ${p.code} -> $emp ${outcome.tag}"
                }
            }
        }

        tasks.forEach { t ->
            val due = parseDue(t.due) ?: return@forEach
            if (due.isAfter(horizon)) return@forEach
            // Both people on the card hear, and so does the project's manager (Stage 122).
            val recipients = (t.assigneeEmpNos + t.projectManagerEmpNo).filter { it.isNotBlank() }.distinct()
            if (recipients.isEmpty()) return@forEach            // nobody owns it: nothing to alert
            items++
            recipients.forEach { emp ->
                val outcome = alert(
                    kind = KIND_TASK,
                    itemId = t.id,
                    recipient = emp,
                    alertDate = alertDate,
                    due = due,
                    subjectOf = { labelTiming(t.label(), due, today).first },
                    bodyOf = { taskBody(t, emp, names[emp], due, today) },
                    sent = sent,
                    email = emails[emp].orEmpty(),
                )
                when (outcome) {
                    Outcome.SKIPPED -> skipped++
                    Outcome.DELIVERED -> delivered++
                    Outcome.LOGGED -> loggedOnly++
                    Outcome.FAILED -> failed++
                }
                if (outcome != Outcome.SKIPPED) {
                    attempted++
                    detail += "$KIND_TASK ${t.id} ${t.title.take(40)} -> $emp ${outcome.tag}"
                }
            }
        }

        return AlertSweepSummary(
            alertDate = alertDate,
            mode = mode,
            projectsReviewed = projects.size,
            tasksReviewed = tasks.size,
            itemsMatching = items,
            alertsAttempted = attempted,
            delivered = delivered,
            loggedOnly = loggedOnly,
            failed = failed,
            skippedAlreadySent = skipped,
            detail = detail,
        )
    }

    private enum class Outcome(val tag: String) {
        SKIPPED("already sent today"),
        DELIVERED("delivered"),
        LOGGED("logged only"),
        FAILED("failed"),
    }

    /**
     * Render, deliver, and ledger one person for one item. The ledger is written whether or not the
     * mail left the box — `delivered = 0` with an `error` is the row an operator needs next morning.
     */
    private fun alert(
        kind: String,
        itemId: Int,
        recipient: String,
        alertDate: String,
        due: LocalDate,
        subjectOf: () -> String,
        bodyOf: () -> String,
        sent: MutableSet<String>,
        email: String,
    ): Outcome {
        val key = AlertRepository.key(kind, itemId, recipient)
        if (key in sent) return Outcome.SKIPPED
        // Claim the key before attempting: a slow SMTP call must not let a parallel reader decide
        // the same alert is still owed.
        sent += key

        val subject = subjectOf()
        val body = bodyOf()
        val result = Mailer.send(email, subject, body)
        val (delivered, error) = when (result) {
            is MailResult.Sent -> true to ""
            is MailResult.LoggedOnly -> false to result.reason
            is MailResult.Failed -> false to result.error
        }
        val channel = if (Mailer.live) "email" else "log"
        val recorded = AlertRepository.record(
            kind = kind,
            itemId = itemId,
            recipientEmpNo = recipient,
            alertDate = alertDate,
            subject = subject,
            body = body,
            due = due.format(dayFormat),
            delivered = delivered,
            channel = channel,
            error = error,
            sentAt = java.time.Instant.now().toString(),
        )
        if (!recorded) {
            // Someone else's row won the UNIQUE race; theirs is the one that counts.
            log.info("Due-date alert {}:{} for {} already recorded by a concurrent sweep", kind, itemId, recipient)
        }
        // The same sentence in the bell, so a reminder is not lost on someone with no mail on file.
        // Guarded and deduped inside NotificationService: an inbox failure cannot end the sweep.
        NotificationService.dueDate(
            recipientEmpNo = recipient,
            itemKind = if (kind == KIND_PROJECT) NotificationService.ITEM_PROJECT else NotificationService.ITEM_TASK,
            itemId = itemId,
            title = subject,
            body = body,
        )
        return when {
            result is MailResult.Sent -> Outcome.DELIVERED
            result is MailResult.Failed -> Outcome.FAILED
            else -> Outcome.LOGGED
        }
    }

    // --- wording -----------------------------------------------------------

    /** `yyyy-MM-dd`, or null for blank/legacy-shaped dates. Silent skip is deliberate: see [DueDateAlertService]. */
    private fun parseDue(raw: String): LocalDate? =
        raw.trim().takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it, dayFormat) }.getOrNull() }

    /** Days until the due date: negative once it is overdue, zero on the day itself. */
    fun daysRemaining(due: LocalDate, today: LocalDate): Long = ChronoUnit.DAYS.between(today, due)

    /** `1 day` / `3 days`, because "1 day(s)" in a manager's inbox reads like a bug. */
    private fun days(n: Long): String = "$n ${if (n == 1L) "day" else "days"}"

    /**
     * `[subject, timing phrase]` for one item — the overdue/upcoming switch the tests assert on.
     * The phrase never repeats "not finished": both call sites already say that, and the first
     * rendered message on real data read "is not finished and … and it is not finished yet".
     */
    fun labelTiming(label: String, due: LocalDate, today: LocalDate): Pair<String, String> {
        val remaining = daysRemaining(due, today)
        val dueText = due.format(dayFormat)
        return when {
            remaining < 0 -> {
                val late = -remaining
                "[PTS] OVERDUE by ${days(late)} - $label (due $dueText)" to
                    "is overdue: it was due on $dueText, ${days(late)} ago"
            }
            remaining == 0L ->
                "[PTS] Due today - $label" to "is due today, $dueText"
            else ->
                "[PTS] Due in ${days(remaining)} - $label (due $dueText)" to
                    "is due on $dueText, in ${days(remaining)}"
        }
    }

    private fun ProjectDueRow.label(): String = "${code.ifBlank { "Project $id" }} - ${name.take(60)}"

    private fun TaskDueRow.label(): String {
        val parent = projectCode.ifBlank { if (projectId != null) "Project ${projectId}" else "" }
        val title = title.take(60)
        return if (parent.isBlank()) "Task $id - $title" else "$parent / Task $id - $title"
    }

    private fun projectBody(p: ProjectDueRow, recipient: String, recipientName: String?, due: LocalDate, today: LocalDate): String {
        val timing = labelTiming(p.label(), due, today).second
        val role = when (recipient) {
            p.managerEmpNo -> "assigned manager"
            in p.assigneeEmpNos -> "listed as an assignee"
            else -> "connected to this project"
        }
        return buildString {
            line("Hi ${recipientName ?: recipient},")
            line()
            line("Project ${p.code} ${if (p.name.isBlank()) "" else "- ${p.name} "}is not finished and $timing.")
            line()
            line("  Project  : ${p.code}")
            line("  Name     : ${p.name.ifBlank { "-" }}")
            line("  Type     : ${p.type.ifBlank { "-" }}")
            line("  Status   : ${p.status}")
            line("  Progress : ${p.progress}%")
            line("  Due date : ${due.format(dayFormat)}")
            line("  You are  : $role")
            line()
            line("Please open PTS and update the progress, the status, or the due date.")
            line("Setting the status to Completed, or the progress to 100%, stops these reminders.")
            line()
            footer(today)
        }.trim()
    }

    private fun taskBody(t: TaskDueRow, recipient: String, recipientName: String?, due: LocalDate, today: LocalDate): String {
        val timing = labelTiming(t.label(), due, today).second
        val role = when {
            recipient in t.assigneeEmpNos && recipient == t.projectManagerEmpNo ->
                "the task assignee and this project's manager"
            recipient in t.assigneeEmpNos -> "the task assignee"
            else -> "the manager of the project this task belongs to"
        }
        return buildString {
            line("Hi ${recipientName ?: recipient},")
            line()
            line("Task #${t.id} \"${t.title}\" is not finished and $timing.")
            line()
            line("  Task     : ${t.title}")
            line("  Project  : ${if (t.projectId == null) "-" else "${t.projectCode.ifBlank { "Project ${t.projectId}" }} - ${t.projectName.ifBlank { "-" }}"}}")
            line("  Status   : ${t.status}")
            line("  Priority : ${t.priority}")
            line("  Due date : ${due.format(dayFormat)}")
            line("  You are  : $role")
            line()
            line("Please open PTS and mark the task Completed, or update its due date.")
            line()
            footer(today)
        }.trim()
    }

    private fun StringBuilder.line(text: String = "") {
        if (text.isEmpty()) append('\n') else append(text).append('\n')
    }

    private fun StringBuilder.footer(today: LocalDate) {
        append("This reminder goes out at most once a day per person per item, from ")
        append("${days(AppConfig.alertLeadDays.toLong())} before the due date until the item is finished.\n")
        append("\n-- MURRAY PTS, ${today.format(dayFormat)} (automated; replies are not read)\n")
    }

    // --- operator helpers ---------------------------------------------------

    /**
     * Subject for the operator's one-message delivery test (`POST /app/alerts/test`). Prefixed like a
     * real alert so it is obvious in a mailbox which feature produced it.
     */
    fun testSubject(): String = "[PTS] Outbound mail test"

    /**
     * Body for [testSubject]: the resolved mail configuration, minus the password, so an operator can
     * tell from the delivered message alone which host and sender address produced it. Reading it in
     * the inbox is the point — a 200 response only proves the app reached the server, not that the
     * message survived the rest of the way.
     */
    fun testBody(recipient: String): String = buildString {
        line("This is a delivery test from MURRAY PTS, sent to $recipient.")
        line()
        line("If you can read this in the mailbox, PTS can reach its users with due-date alerts.")
        line()
        line("  mode    : ${currentMode()}")
        line("  server  : ${AppConfig.smtpHost.ifBlank { "(PTS_SMTP_HOST not set)" }}:${AppConfig.smtpPort}")
        line("  security: ${AppConfig.smtpSecurity}")
        line("  login   : ${AppConfig.smtpUsername.ifBlank { "(anonymous)" }}")
        line("  sender  : ${AppConfig.smtpFrom.ifBlank { "(PTS_SMTP_FROM not set)" }}")
        line("  sent at : ${Instant.now()}")
        line()
        line("This test bypasses the due_date_alerts ledger, so it cannot make tomorrow's real")
        line("alerts go quiet. -- MURRAY PTS (automated; replies are not read)")
    }.trim()

    /**
     * Send one [testBody] straight through [Mailer]. Deliberately not recorded in the ledger: the
     * UNIQUE key there is a per-day alert budget, and spending it on a diagnostic would suppress the
     * real reminder for that person on that day.
     */
    fun runDeliveryTest(recipient: String): MailResult = Mailer.send(recipient, testSubject(), testBody(recipient))

    /** The address on file for an account, or blank. Default target for [runDeliveryTest]. */
    fun emailFor(employeeNumber: String): String = AlertRepository.emailsByEmpNo()[employeeNumber].orEmpty()

    /** Recent ledger rows, newest first — what the admin view reads. */
    fun recent(limit: Int = 50): List<AlertLedgerRow> = AlertRepository.recent(limit)

    /** Ledger trimming hook, called by the scheduler so the audit trail cannot grow without bound. */
    fun purgeLedgerOlderThan(daysToKeep: Int = 90): Int {
        if (daysToKeep <= 0) return 0
        val cutoff = today().minusDays(daysToKeep.toLong()).format(dayFormat)
        val removed = AlertRepository.purgeOlderThan(cutoff)
        if (removed > 0) log.info("Due-date alert ledger: removed {} row(s) before {}", removed, cutoff)
        return removed
    }
}
