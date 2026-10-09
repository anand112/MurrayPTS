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
import kotlinx.html.input
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.unsafe

fun DIV.todoContent(session: UserSession) {
    val lists = DemoData.todoLists.filter { it.owner == session.employeeNumber }
    div("todo-board") {
        lists.forEachIndexed { index, list ->
            val items = DemoData.todos.filter { it.list == list.id && it.owner == session.employeeNumber }
            val open = items.count { !it.done }
            div(if (list.done) "card-panel todo-list done" else "card-panel todo-list") {
                attributes["data-list"] = "${list.id}"
                div("todo-head") {
                    form(action = "/app/todo/lists/${list.id}/toggle", method = FormMethod.post, classes = "todo-check") {
                        input(type = InputType.checkBox) {
                            attributes["title"] = if (list.done) "Mark “${list.name}” as active" else "Mark “${list.name}” as done"
                            attributes["onchange"] = "this.form.requestSubmit()"
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
                            input(type = InputType.checkBox) {
                                attributes["title"] = if (todo.done) "Mark as not done" else "Mark as done"
                                attributes["onchange"] = "this.form.requestSubmit()"
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
    boardDragScript()
    dueDateScript()
}

/**
 * Date fields save on focus loss instead of on change: with native date inputs Chrome fires
 * `change` after every single keystroke while typing a year (e.g. "2" is padded to 0002), so an
 * immediate submit reloads the page mid-typing and the rest of the digits are lost. Submitting
 * on focusout keeps the whole typed value, and the submit guard refuses implausible years.
 */
private fun DIV.dueDateScript() {
    unsafe {
        +(
            """<script>
(function () {
  function isDueInput(el) {
    return !!(el && el.closest && el.closest('form.todo-due-form') && el.type === 'date');
  }
  function markDirty(e) { if (isDueInput(e.target)) e.target.setAttribute('data-pts-dirty', '1'); }
  document.addEventListener('input', markDirty);
  document.addEventListener('change', markDirty);
  document.addEventListener('focusout', function (e) {
    var t = e.target;
    if (!isDueInput(t) || !t.hasAttribute('data-pts-dirty')) return;
    t.removeAttribute('data-pts-dirty');
    t.form.requestSubmit();
  });
  document.addEventListener('submit', function (e) {
    var f = e.target;
    if (!f || !f.matches || !f.matches('form.todo-due-form, form.todo-add')) return;
    var inp = f.querySelector('input[type="date"]');
    if (inp && inp.value && !(parseInt(inp.value.slice(0, 4), 10) >= 1000)) e.preventDefault();
  });
})();
</script>"""
        )
    }
}

/** Horizontal drag-and-drop reordering of the list columns; order is saved to the server on drop. */
private fun DIV.boardDragScript() {
    unsafe {
        +(
            """<script>
(function () {
  var board = document.querySelector('.todo-board');
  if (!board) return;
  var cols = Array.prototype.slice.call(board.querySelectorAll(':scope > .todo-list'));
  if (cols.length < 2) return;
  var dragging = null;
  var dropped = false;
  var orderAtStart = '';

  function ids() {
    return Array.prototype.slice.call(board.querySelectorAll(':scope > .todo-list'))
      .map(function (c) { return c.getAttribute('data-list'); })
      .filter(Boolean);
  }
  function save() {
    var body = 'order=' + encodeURIComponent(ids().join(','));
    fetch('/app/todo/lists/reorder', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: body
    }).catch(function () { location.reload(); });
  }
  function restore(csv) {
    var byId = {};
    cols.forEach(function (c) { byId[c.getAttribute('data-list')] = c; });
    csv.split(',').forEach(function (id) { if (byId[id]) board.appendChild(byId[id]); });
  }

  cols.forEach(function (col) {
    col.draggable = true;
    col.addEventListener('dragstart', function (e) {
      if (e.target.closest && e.target.closest('input, button, select, textarea, a, form')) {
        e.preventDefault();
        return;
      }
      dragging = col;
      dropped = false;
      orderAtStart = ids().join(',');
      col.classList.add('dragging');
      e.dataTransfer.effectAllowed = 'move';
      e.dataTransfer.setData('text/plain', col.getAttribute('data-list') || '');
    });
    col.addEventListener('dragend', function () {
      dragging = null;
      col.classList.remove('dragging');
      if (!dropped && orderAtStart) {
        restore(orderAtStart);
        orderAtStart = '';
      }
    });
  });

  board.addEventListener('dragover', function (e) {
    if (!dragging) return;
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    var r = board.getBoundingClientRect();
    if (e.clientX > r.right - 70) board.scrollLeft += 14;
    else if (e.clientX < r.left + 70) board.scrollLeft -= 14;
    var target = null;
    var now = Array.prototype.slice.call(board.querySelectorAll(':scope > .todo-list'));
    for (var i = 0; i < now.length; i++) {
      var c = now[i];
      if (c === dragging) continue;
      var cr = c.getBoundingClientRect();
      if (e.clientX < cr.left + cr.width / 2) { target = c; break; }
    }
    if (target) board.insertBefore(dragging, target);
    else board.appendChild(dragging);
  });

  board.addEventListener('drop', function (e) {
    if (!dragging) return;
    e.preventDefault();
    dropped = true;
    save();
  });
})();
</script>"""
        )
    }
}
