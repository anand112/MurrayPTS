package mn.murray.pts
import mn.murray.pts.domain.*

import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.script
import kotlinx.html.span
import kotlinx.html.unsafe
import mn.murray.pts.repository.NotificationRow
import mn.murray.pts.service.NotificationService
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The bell in the top-right of every authenticated page, and the dropdown it opens.
 *
 * It is the in-app half of [NotificationService]: the same assignment and due-date messages that go
 * out as email are rendered here, so a person with no address on file still hears about their work.
 * The panel is server-rendered on the page it appears on — there is no fetch endpoint for it — which
 * is why it shows the newest 20 items rather than a paginated feed, and why "open" is a plain link.
 *
 * Links go through `/app/notifications/{id}/open` rather than straight at the target: that route is
 * what marks the row read, and it is the only place the stored `link` is trusted (see
 * [NotificationService.safeLink]). Escape and outside-click live in `/static/js/notifications.js`,
 * driven by `data-notif-*` markers because the CSP forbids inline handlers.
 */
fun DIV.notificationBell(session: UserSession, section: String) {
    val items = NotificationService.inbox(session.employeeNumber)
    val unread = NotificationService.unreadCount(session.employeeNumber)
    // Where "Mark all read" returns to. The nav path for this section is always inside /app/, so the
    // route's own prefix check can never be surprised by it.
    val back = navItems(session.employeeNumber).firstOrNull { it.id == section }?.path ?: "/app/projects"

    div("notif") {
        id = "notif"
        button(type = ButtonType.button, classes = "notif-btn") {
            attributes["data-notif-toggle"] = "true"
            attributes["aria-haspopup"] = "true"
            attributes["aria-expanded"] = "false"
            attributes["aria-label"] = "Notifications"
            attributes["title"] = "Notifications"
            unsafe { +BELLE_SVG }
            if (unread > 0) {
                span("notif-badge") { +if (unread > 99) "99+" else "$unread" }
            }
        }
        div("notif-panel") {
            attributes["data-notif-panel"] = "true"
            attributes["hidden"] = "true"
            div("notif-head") {
                span("notif-head-title") { +"Notifications" }
                if (unread > 0) {
                    form(action = "/app/notifications/read-all", method = FormMethod.post) {
                        input(type = InputType.hidden, name = "back") { value = back }
                        button(type = ButtonType.submit, classes = "submit btn-sm") {
                            +"Mark all read"
                        }
                    }
                }
            }
            if (items.isEmpty()) {
                div("notif-empty") { +"Nothing yet. Task assignments, projects you take on, and due-date reminders appear here." }
            } else {
                div("notif-list") {
                    items.forEach { row ->
                        notifItem(row)
                    }
                }
            }
        }
    }
    script(src = "/static/js/notifications.js") {}
}

/**
 * One inbox row. Unread rows carry the class the stylesheet dots and tints; clicking any of them is
 * a visit to the open route, which flips the read flag on the way through to the target.
 */
private fun DIV.notifItem(row: NotificationRow) {
    a(classes = if (row.isUnread) "notif-item unread" else "notif-item") {
        href = "/app/notifications/${row.id}/open"
        span("notif-mark") { }
        div("notif-text") {
            div("notif-item-title") { +row.title }
            if (row.body.isNotBlank()) div("notif-body") { +shortBody(row.body) }
            div("notif-time") { +relativeTime(row.createdAt) }
        }
    }
}

/** The dropdown is a headline, not a letter — the full text stays in the row and in the email. */
private fun shortBody(body: String): String =
    body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim().take(140)

private val DAY = DateTimeFormatter.ofPattern("d MMM")

/**
 * "4h ago" rather than a timestamp: an inbox item is judged by how stale it is, and the panel has no
 * room for a date. Anything older than a week falls back to the calendar day, because "18d ago"
 * tells you less than "13 Oct" about which week to go back and finish the work.
 *
 * A row it cannot parse prints no time rather than throwing — the bell renders on every page, and a
 * hand-edited row must not blank the app.
 */
private fun relativeTime(instant: String): String {
    val then = try {
        Instant.parse(instant)
    } catch (e: Exception) {
        return ""
    }
    val mins = java.time.Duration.between(then, Instant.now()).seconds.floorDiv(60).coerceAtLeast(0)
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        mins < 24 * 60 -> "${mins / 60}h ago"
        mins < 7 * 24 * 60 -> "${mins / 1440}d ago"
        else -> DAY.format(then.atZone(ZoneOffset.UTC))
    }
}

private const val BELLE_SVG =
    """<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 8a6 6 0 1 0-12 0c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.7 21a2 2 0 0 1-3.4 0"/></svg>"""
