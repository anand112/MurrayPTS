/* todo.js — To-do board behaviour. Extracted verbatim from Todo.kt boardDragScript()
   and dueDateScript() in Stage 7b; the board-drag IIFE runs first to match the
   original call order (boardDragScript() then dueDateScript()). */

/* Horizontal drag-and-drop reordering of the list columns; order is saved to the server on drop. */
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

/* Date fields save on focus loss instead of on change: with native date inputs Chrome fires
   `change` after every single keystroke while typing a year (e.g. "2" is padded to 0002), so an
   immediate submit reloads the page mid-typing and the rest of the digits are lost. Submitting
   on focusout keeps the whole typed value, and the submit guard refuses implausible years. */
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
