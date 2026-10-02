package mn.murray.pts

import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h3
import kotlinx.html.input
import kotlinx.html.p
import kotlinx.html.span

fun DIV.todoContent() {
    DemoData.todoLists.forEach { list ->
        val items = DemoData.todos.filter { it.list == list.id }
        div("card-panel todo-list") {
            div("todo-head") {
                h3 { +list.name }
                span("todo-count") { +"${items.count { !it.done }} open / ${items.size}" }
                form(action = "/app/todo/lists/${list.id}/delete", method = FormMethod.post, classes = "todo-del-form") {
                    button(type = ButtonType.submit, classes = "todo-del") {
                        attributes["title"] = "Delete list “${list.name}” and its tasks"
                        +"×"
                    }
                }
            }
            if (items.isEmpty()) {
                p("task-meta") { +"No tasks in this list yet." }
            }
            items.forEach { todo ->
                div("todo-row") {
                    form(action = "/app/todo/${todo.id}/toggle", method = FormMethod.post, classes = "todo-toggle") {
                        button(type = ButtonType.submit, classes = if (todo.done) "todo-box done" else "todo-box") {
                            attributes["title"] = if (todo.done) "Mark as not done" else "Mark as done"
                            +(if (todo.done) "✓" else "")
                        }
                    }
                    span(if (todo.done) "todo-title done" else "todo-title") { +todo.title }
                    form(action = "/app/todo/${todo.id}/delete", method = FormMethod.post, classes = "todo-del-form") {
                        button(type = ButtonType.submit, classes = "todo-del") {
                            attributes["title"] = "Delete task"
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
                button(type = ButtonType.submit, classes = "pin-btn") { +"Add" }
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
            button(type = ButtonType.submit, classes = "submit") { +"Create list" }
        }
    }
}
