package mn.murray.pts.repository

import mn.murray.pts.db.Notifications
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

/**
 * Data access for the bell in the page head: one inbox row per person, per event, per day.
 *
 * Nothing here decides *whether* someone deserves to hear about something — the wording, the
 * recipients and the "not yourself" rule live in `service/NotificationService`. This layer only
 * claims the day's row, reads one person's newest items, and marks them read.
 */

/** What has to be written for one inbox item; every field is server-built. */
data class NotificationDraft(
    val recipientEmpNo: String,
    val kind: String,
    val itemKind: String,
    val itemId: Int,
    val dedupeDay: String,
    val title: String,
    val body: String,
    val link: String,
    val actorEmpNo: String = "",
    val mailStatus: String = "",
)

/** One stored inbox row, as the dropdown renders it. */
data class NotificationRow(
    val id: Int,
    val kind: String,
    val itemKind: String,
    val itemId: Int,
    val title: String,
    val body: String,
    val link: String,
    val actorEmpNo: String,
    val createdAt: String,
    val readAt: String,
) {
    val isUnread: Boolean get() = readAt.isEmpty()
}

object NotificationRepository {

    /**
     * Claim one inbox row, returning its id, or null when that person already has this event for
     * this day. Claiming *before* the mail goes out is the point: like the alert ledger, the UNIQUE
     * index is the arbiter, so two overlapping writes — or a retry after a slow SMTP call — cannot
     * leave the same person with two copies of one afternoon's assignment.
     *
     * Only a UNIQUE violation is swallowed as "already notified". Anything else (a recipient who is
     * not on the roster, a locked file) is rethrown, because silently losing a notification is worse
     * than the stack trace the caller can log.
     */
    fun insert(draft: NotificationDraft): Int? = transaction {
        try {
            Notifications.insert { row ->
                row[recipientEmpNo] = draft.recipientEmpNo
                row[kind] = draft.kind
                row[Notifications.itemKind] = draft.itemKind
                row[itemId] = draft.itemId
                row[dedupeDay] = draft.dedupeDay
                row[title] = draft.title
                row[body] = draft.body
                row[link] = draft.link
                row[actorEmpNo] = draft.actorEmpNo
                row[mailStatus] = draft.mailStatus
                row[createdAt] = Instant.now().toString()
                row[readAt] = ""
            } get Notifications.id
        } catch (e: Exception) {
            // Exposed wraps the driver exception and SQLite reports it as text, so the whole
            // cause chain is what identifies "this row was already written today".
            val chain = generateSequence<Throwable>(e) { it.cause }.joinToString(" ") { it.message.orEmpty() }
            if ("UNIQUE constraint failed" in chain) null else throw e
        }
    }

    /** Records what the mailer did with the row claimed by [insert]. */
    fun setMailStatus(id: Int, status: String): Int = transaction {
        Notifications.update({ Notifications.id eq id }) { it[mailStatus] = status }
    }

    /** Newest first — what the dropdown shows, read and unread alike. */
    fun forRecipient(empNo: String, limit: Int = 20): List<NotificationRow> = transaction {
        Notifications.selectAll().where { Notifications.recipientEmpNo eq empNo }
            .orderBy(Notifications.id to SortOrder.DESC)
            .limit(limit.coerceIn(1, 50))
            .map(::mapRow)
    }

    /** The badge number. `read_at = ''` is the unread test, so this needs no extra column. */
    fun unreadCount(empNo: String): Int = transaction {
        Notifications.selectAll().where {
            (Notifications.recipientEmpNo eq empNo) and (Notifications.readAt eq "")
        }.count().toInt()
    }

    /**
     * Mark one row read, but only when it belongs to the person clicking. The route passes the
     * session's own employee number, so a hand-typed id from someone else's inbox changes nothing.
     */
    fun markRead(empNo: String, id: Int): Boolean = transaction {
        Notifications.update({
            (Notifications.id eq id) and (Notifications.recipientEmpNo eq empNo) and (Notifications.readAt eq "")
        }) {
            it[readAt] = Instant.now().toString()
        } > 0
    }

    /** Mark the whole inbox read; returns how many rows flipped. */
    fun markAllRead(empNo: String): Int = transaction {
        Notifications.update({
            (Notifications.recipientEmpNo eq empNo) and (Notifications.readAt eq "")
        }) {
            it[readAt] = Instant.now().toString()
        }
    }

    /** The stored target of one row, for the open route to validate before redirecting. */
    fun linkOf(empNo: String, id: Int): String? = transaction {
        Notifications.selectAll().where {
            (Notifications.id eq id) and (Notifications.recipientEmpNo eq empNo)
        }.firstOrNull()?.get(Notifications.link)
    }

    private fun mapRow(r: ResultRow) = NotificationRow(
        id = r[Notifications.id],
        kind = r[Notifications.kind],
        itemKind = r[Notifications.itemKind],
        itemId = r[Notifications.itemId],
        title = r[Notifications.title],
        body = r[Notifications.body],
        link = r[Notifications.link],
        actorEmpNo = r[Notifications.actorEmpNo],
        createdAt = r[Notifications.createdAt],
        readAt = r[Notifications.readAt],
    )

    /**
     * Drop inbox rows dated before [cutoffDay] (yyyy-MM-dd), read or unread. An inbox is a courtesy,
     * not an audit trail — the due-date ledger keeps the history — so it gets trimmed to keep the
     * SQLite file bounded. String compare is safe because `dedupe_day` is always this fixed-width
     * form, written by the service.
     */
    fun purgeOlderThan(cutoffDay: String): Int = transaction {
        Notifications.deleteWhere { Notifications.dedupeDay less cutoffDay }
    }
}
