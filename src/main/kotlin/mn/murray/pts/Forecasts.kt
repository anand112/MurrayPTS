package mn.murray.pts

import kotlinx.html.DIV
import kotlinx.html.div
import kotlinx.html.h3
import kotlinx.html.p

fun DIV.forecastsContent() {
    div("fc-grid") {
        DemoData.forecasts.forEach { forecast ->
            div("card-panel fc-card") {
                div("fc-period") { +forecast.period }
                h3 { +forecast.project }
                div("fc-hours") { +forecast.hours }
                p("task-meta") { +forecast.note }
            }
        }
    }
}
