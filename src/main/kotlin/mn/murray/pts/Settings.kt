package mn.murray.pts

import kotlinx.html.DIV
import kotlinx.html.div
import kotlinx.html.p
import kotlinx.html.span

fun DIV.settingsContent(session: UserSession) {
    val user = DemoData.userFor(session.employeeNumber)
    div("card-panel") {
        div("set-row") {
            span("set-key") { +"Employee number" }
            span { +session.employeeNumber }
        }
        div("set-row") {
            span("set-key") { +"Display name" }
            span { +user.name }
        }
        div("set-row") {
            span("set-key") { +"Position" }
            span { +user.position }
        }
        div("set-row") {
            span("set-key") { +"Access level" }
            span { +user.access }
        }
        p("task-meta") {
            +"Use the 🎨 button in the sidebar to cycle the accent colour and 🌙 for dark mode. Preferences are stored in your browser."
        }
    }
}
