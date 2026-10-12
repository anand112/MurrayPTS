package mn.murray.pts
import mn.murray.pts.domain.*

import kotlinx.html.DIV
import kotlinx.html.div
import kotlinx.html.h3
import kotlinx.html.p

fun DIV.lessonsContent() {
    DemoData.lessons.forEach { lesson ->
        div("card-panel") {
            h3 { +lesson.title }
            div("task-meta") { +lesson.project }
            p("lesson-text") { +lesson.summary }
        }
    }
}
