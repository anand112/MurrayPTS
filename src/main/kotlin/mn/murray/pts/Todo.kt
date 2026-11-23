package mn.murray.pts
import mn.murray.pts.domain.*

import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h3
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.span

fun DIV.todoContent(session: UserSession) {
    val lists = DemoData.todoLists.filter { it.owner == session.employeeNumber }
    div("todo-board") {
        lists.forEachIndexed { index, list ->
            val items = DemoData.todos.filter { it.list == list.id && it.owner == session.employeeNumber }
            val open = items.count { !it.done }
            div(if (list.done) "card-panel todo-list done" else "card-panel todo-list") {
                id = "todo-list-${list.id}"
                attributes["data-list"] = "${list.id}"
                div("todo-head") {
                    form(action = "/app/todo/lists/${list.id}/toggle", method = FormMethod.post, classes = "todo-check") {
                        attributes["data-live-target"] = "#todo-list-${list.id}"
                        input(type = InputType.checkBox) {
                            id = "todo-list-toggle-${list.id}"
                            attributes["title"] = if (list.done) "Mark “${list.name}” as active" else "Mark “${list.name}” as done"
                            attributes["data-autosubmit"] = ""
                            if (list.done) attributes["checked"] = "checked"
                        }
                    }
                    h3 {
                        attributes["title"] = list.name
                        +list.name
                    }
                    span("todo-count") {
                        attributes["title"] = "$open open / ${items.size} total"
                        +"$open/${items.size}"
                    }
                    div("todo-head-actions") {
                        form(action = "/app/todo/lists/${list.id}/move", method = FormMethod.post, classes = "todo-move-form") {
                            button(type = ButtonType.submit, classes = "todo-move") {
                                attributes["name"] = "dir"
                                attributes["value"] = "up"
                                attributes["title"] = "Move “${list.name}” earlier"
                                if (index == 0) attributes["disabled"] = "disabled"
                                +"◀"
                            }
                            button(type = ButtonType.submit, classes = "todo-move") {
                                attributes["name"] = "dir"
                                attributes["value"] = "down"
                                attributes["title"] = "Move “${list.name}” later"
                                if (index == lists.size - 1) attributes["disabled"] = "disabled"
                                +"▶"
                            }
                        }
                        form(action = "/app/todo/lists/${list.id}/delete", method = FormMethod.post, classes = "todo-del-form") {
                            button(type = ButtonType.submit, classes = "todo-del") {
                                attributes["title"] = "Delete list “${list.name}” and its tasks"
                                attributes["data-confirm"] = "Delete list “${list.name}” and its tasks?"
                                +"×"
                            }
                        }
                    }
                }
                div("todo-due") {
                    form(action = "/app/todo/lists/${list.id}/due", method = FormMethod.post, classes = "todo-due-form") {
                        span("todo-due-label") { +"End date" }
                        input(type = InputType.date, name = "due") {
                            attributes["title"] = "End date for “${list.name}”"
                            attributes["aria-label"] = "End date for ${list.name}"
                            if (list.due.isNotEmpty()) value = list.due
                        }
                    }
                }
                if (items.isEmpty()) {
                    p("task-meta") { +"No tasks in this list yet." }
                }
                items.forEach { todo ->
                    div("todo-row") {
                        form(action = "/app/todo/${todo.id}/toggle", method = FormMethod.post, classes = "todo-check") {
                            attributes["data-live-target"] = "#todo-list-${list.id}"
                            input(type = InputType.checkBox) {
                                id = "todo-item-toggle-${todo.id}"
                                attributes["title"] = if (todo.done) "Mark as not done" else "Mark as done"
                                attributes["data-autosubmit"] = ""
                                if (todo.done) attributes["checked"] = "checked"
                            }
                        }
                        span(if (todo.done) "todo-title done" else "todo-title") { +todo.title }
                        form(action = "/app/todo/${todo.id}/due", method = FormMethod.post, classes = "todo-due-form") {
                            input(type = InputType.date, name = "due") {
                                attributes["title"] = "End date for “${todo.title}”"
                                attributes["aria-label"] = "End date for ${todo.title}"
                                if (todo.due.isNotEmpty()) value = todo.due
                            }
                        }
                        form(action = "/app/todo/${todo.id}/delete", method = FormMethod.post, classes = "todo-del-form") {
                            button(type = ButtonType.submit, classes = "todo-del") {
                                attributes["title"] = "Delete task"
                                attributes["data-confirm"] = "Delete task “${todo.title}”?"
                                +"×"
                            }
                        }
                    }
                }
                form(action = "/app/todo", method = FormMethod.post, classes = "todo-add") {
                    input(type = InputType.hidden, name = "list") { value = "${list.id}" }
                    input(type = InputType.text, name = "title") {
                        placeholder = "+ Add a task to ${list.name}"
                        required = true
                    }
                    input(type = InputType.date, name = "due") {
                        attributes["title"] = "End date (optional)"
                        attributes["aria-label"] = "Task end date"
                    }
                    button(type = ButtonType.submit, classes = "pin-btn") { +"Add" }
                }
            }
        }
        if (lists.isEmpty()) {
            div("card-panel todo-list") {
                p("task-meta") { +"No lists yet — write a name below and create your first one." }
            }
        }
    }
    div("card-panel") {
        h3 { +"New list" }
        form(action = "/app/todo/lists", method = FormMethod.post, classes = "todo-add") {
            input(type = InputType.text, name = "name") {
                placeholder = "e.g. This week"
                required = true
            }
            input(type = InputType.date, name = "due") {
                attributes["title"] = "End date (optional)"
                attributes["aria-label"] = "End date"
            }
            button(type = ButtonType.submit, classes = "submit") { +"Create list" }
        }
    }
    // Board drag-reordering + date-field save behaviour moved to /static/js/todo.js (Stage 7).
    // Markup contract: .todo-board > .todo-list[data-list], form.todo-due-form input[type=date],
    // form.todo-add.
    script(src = "/static/js/todo.js") {}
}
