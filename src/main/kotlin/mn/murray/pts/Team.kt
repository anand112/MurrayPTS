package mn.murray.pts

import kotlinx.html.DIV
import kotlinx.html.TD
import kotlinx.html.div
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr

fun DIV.teamContent() {
    val projects = DemoData.projects
    div("card-panel") {
        table("grid team-table") {
            thead {
                tr {
                    th { +"ID" }
                    th { +"Name" }
                    th { +"Department" }
                    th { +"Position" }
                    th(classes = "num") { +"Ongoing" }
                    th(classes = "num") { +"On hold" }
                    th(classes = "num") { +"Not started" }
                    th(classes = "num") { +"Blocked" }
                    th(classes = "num") { +"Completed" }
                    th(classes = "num") { +"Total" }
                }
            }
            tbody {
                DemoData.team.forEach { member ->
                    val mine = projects.filter { it.manager == member.name }
                    fun count(status: String) = mine.count { it.status == status }
                    tr {
                        td { span("code") { +member.employeeNumber } }
                        td {
                            div("team-name") {
                                span("avatar sm") { +initials(member.name) }
                                span("pname") { +member.name }
                            }
                        }
                        td { +member.department }
                        td { +member.position }
                        td("num") { statusCount("ontrack", count("Ongoing")) }
                        td("num") { statusCount("onhold", count("On hold")) }
                        td("num") { statusCount("planning", count("Not started")) }
                        td("num") { statusCount("atrisk", count("Blocked")) }
                        td("num") { statusCount("done", count("Completed")) }
                        td("num") { span("count-total") { +"${mine.size}" } }
                    }
                }
            }
        }
    }
}

private fun TD.statusCount(kind: String, n: Int) {
    span(if (n == 0) "count zero" else "count $kind") { +"$n" }
}

private fun initials(name: String): String =
    name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString("")
