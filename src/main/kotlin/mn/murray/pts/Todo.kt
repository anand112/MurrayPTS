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
        /* Stage 141: the board is a swap target in its own right. Moving a list, deleting one and
           creating one each change *which columns there are*, and in what order, which no single
           `#todo-list-N` swap can express: a deleted list is not in the response at all, so a form
           aimed at its own block would find nothing to replace and fall back to the navigation its
           markup describes. `todo.js` re-finds the board on every drag event for the same reason it
           has an id now - a block that arrives as markup is a new node, and a listener belongs to the
           node it was put on, which is the trap Stage 94 cured in `kanban.js`. */
        id = "todoBoard"
        lists.forEachIndexed { index, list ->
            val items = DemoData.todos.filter { it.list == list.id && it.owner == session.employeeNumber }
            val open = items.count { !it.done }
            div(if (list.done) "card-panel todo-list done" else "card-panel todo-list") {
                id = "todo-list-${list.id}"
                attributes["data-list"] = "${list.id}"
                // Draggable in the markup rather than set by a loop in `todo.js` at load: a column
                // swapped in from a response was never visited by that loop, and a column that cannot
                // be dragged is a board that has quietly lost its reordering.
                attributes["draggable"] = "true"
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
                            // Reordering moves two columns at once, so the swap is the board and not the
                            // list: the list that was nudged is still in the response, but its neighbour is
                            // the one that moved, and a block swap of either alone would leave the pair
                            // disagreeing about the order the server has just stored.
                            attributes["data-live-target"] = "#todoBoard"
                            button(type = ButtonType.submit, classes = "todo-move") {
                                id = "todo-move-up-${list.id}"
                                attributes["name"] = "dir"
                                attributes["value"] = "up"
                                attributes["title"] = "Move “${list.name}” earlier"
                                if (index == 0) attributes["disabled"] = "disabled"
                                +"◀"
                            }
                            button(type = ButtonType.submit, classes = "todo-move") {
                                id = "todo-move-down-${list.id}"
                                attributes["name"] = "dir"
                                attributes["value"] = "down"
                                attributes["title"] = "Move “${list.name}” later"
                                if (index == lists.size - 1) attributes["disabled"] = "disabled"
                                +"▶"
                            }
                        }
                        form(action = "/app/todo/lists/${list.id}/delete", method = FormMethod.post, classes = "todo-del-form") {
                            attributes["data-live-target"] = "#todoBoard"
                            // The list takes its tasks with it, so the sidebar's To-do figure is part of the
                            // same write, and the focus cannot come back to the × that is no longer there:
                            // the New list box is where a reader who has just cleared a list is heading.
                            attributes["data-live-extra"] = "#nav-pill-todo"
                            attributes["data-live-focus"] = "#todo-new-list-name"
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
                        attributes["data-live-target"] = "#todo-list-${list.id}"
                        span("todo-due-label") { +"End date" }
                        input(type = InputType.date, name = "due") {
                            // An id so the swap can hand the focus back. `todo.js` saves this field on focus
                            // loss, which is the one moment a reader is least expecting the page to move:
                            // a swap that dropped the caret would take the rest of the date with it.
                            id = "todo-due-list-${list.id}"
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
                            attributes["data-live-target"] = "#todo-list-${list.id}"
                            input(type = InputType.date, name = "due") {
                                id = "todo-due-task-${todo.id}"
                                attributes["title"] = "End date for “${todo.title}”"
                                attributes["aria-label"] = "End date for ${todo.title}"
                                if (todo.due.isNotEmpty()) value = todo.due
                            }
                        }
                        form(action = "/app/todo/${todo.id}/delete", method = FormMethod.post, classes = "todo-del-form") {
                            attributes["data-live-target"] = "#todo-list-${list.id}"
                            // One to-do fewer is the sidebar's business too, and the × that removed it is
                            // gone from the response, so the focus lands on the list's own tick instead.
                            attributes["data-live-extra"] = "#nav-pill-todo"
                            attributes["data-live-focus"] = "#todo-list-toggle-${list.id}"
                            button(type = ButtonType.submit, classes = "todo-del") {
                                attributes["title"] = "Delete task"
                                attributes["data-confirm"] = "Delete task “${todo.title}”?"
                                +"×"
                            }
                        }
                    }
                }
                form(action = "/app/todo", method = FormMethod.post, classes = "todo-add") {
                    // A task written is a task in this list and one more on the sidebar's To-do figure, and
                    // the box is emptied by the swap so the next one can be typed straight in - which is why
                    // the focus is named rather than left to whichever control the reader happened to press.
                    attributes["data-live-target"] = "#todo-list-${list.id}"
                    attributes["data-live-extra"] = "#nav-pill-todo"
                    attributes["data-live-focus"] = "#todo-add-title-${list.id}"
                    input(type = InputType.hidden, name = "list") { value = "${list.id}" }
                    input(type = InputType.text, name = "title") {
                        id = "todo-add-title-${list.id}"
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
    // `todo-new-list` is the class the pending dim is written against: `app.css` has no id selectors at
    // all, and a stylesheet that addressed this card by the id the swap needs would be the only place
    // in the file that did.
    div("card-panel todo-new-list") {
        id = "newListCard"
        h3 { +"New list" }
        form(action = "/app/todo/lists", method = FormMethod.post, classes = "todo-add") {
            // The card is the target and the board rides along: one response both empties this form and
            // carries the column it just created. The sidebar's To-do pill does *not* ride along, because
            // it counts the reader's to-dos and a new list holds none yet - a companion named for a number
            // that did not move is noise, and noise in this list is how a real one gets missed.
            attributes["data-live-target"] = "#newListCard"
            attributes["data-live-extra"] = "#todoBoard"
            attributes["data-live-focus"] = "#todo-new-list-name"
            input(type = InputType.text, name = "name") {
                id = "todo-new-list-name"
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
