/* todo.js — To-do board behaviour. Extracted verbatim from Todo.kt boardDragScript()
   and dueDateScript() in Stage 7b; the board-drag IIFE runs first to match the
   original call order (boardDragScript() then dueDateScript()). */

/* Horizontal drag-and-drop reordering of the list columns; order is saved to the server on drop.

   Bound on `document`, reading the board fresh at every event rather than once at load. **Stage 141** made
   the board a swap target —moving a list, deleting one and creating one each replace `#todoBoard` —and a
   to-do tick has replaced a single `#todo-list-N` since **Stage 136**. A node the browser hands back from
   `importNode` carries its markup and nothing else, so a listener put on the old column died with it: bound
   per column at load, the reordering went dead the first time anything on this page was written. That is the
   trap **Stage 94** cured in `kanban.js`, cured the same way —`closest()` at event time, no collection kept.
   The columns are draggable from the markup now for the same reason: a load-time `col.draggable = true` never
   visits a column that arrived in a later response. */
(function () {
  function boardOf(node) {
    return node && node.closest ? node.closest('.todo-board') : null;
  }
  /* A column is a `.todo-list` the board holds *directly*. The parent check is what keeps a drag over some
     other part of the page from reordering a board the pointer is not on, and keeps the nested copy of a
     block still being parsed out of it. */
  function listOf(node) {
    var col = node && node.closest ? node.closest('.todo-list') : null;
    if (!col) return null;
    var board = boardOf(col);
    return board && col.parentNode === board ? col : null;
  }
  function columns(board) {
    return Array.prototype.slice.call(board.querySelectorAll(':scope > .todo-list'));
  }
  function ids(board) {
    return columns(board).map(function (c) { return c.getAttribute('data-list'); }).filter(Boolean);
  }
  function save(board) {
    var body = 'order=' + encodeURIComponent(ids(board).join(','));
    fetch('/app/todo/lists/reorder', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: body
    }).catch(function () { location.reload(); });
  }
  function restore(board, csv) {
    var byId = {};
    columns(board).forEach(function (c) { byId[c.getAttribute('data-list')] = c; });
    csv.split(',').forEach(function (id) { if (byId[id]) board.appendChild(byId[id]); });
  }

  var dragging = null;
  var dropped = false;
  var orderAtStart = '';

  document.addEventListener('dragstart', function (e) {
    var col = listOf(e.target);
    if (!col) return;
    var board = boardOf(col);
    // One column is nothing to reorder —the same rule the load-time `cols.length < 2` guard used to apply,
    // asked at the moment it matters instead of at the moment the script ran.
    if (columns(board).length < 2) return;
    // A press on a control inside the column is that control's own business: the tick, the arrows, the
    // date box and the add field all sit in a draggable column, and none of them may start a drag.
    if (e.target.closest && e.target.closest('input, button, select, textarea, a, form')) {
      e.preventDefault();
      return;
    }
    dragging = col;
    dropped = false;
    orderAtStart = ids(board).join(',');
    col.classList.add('dragging');
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', col.getAttribute('data-list') || '');
  });
  document.addEventListener('dragend', function (e) {
    var col = listOf(e.target) || dragging;
    if (!col) return;
    col.classList.remove('dragging');
    var board = boardOf(col);
    dragging = null;
    // A drag let go outside the board changed nothing on the server, so the order it started with is put
    // back —from the columns as they are now, which after a swap are not the columns this drag lifted.
    if (!dropped && orderAtStart && board) {
      restore(board, orderAtStart);
      orderAtStart = '';
    }
  });
  document.addEventListener('dragover', function (e) {
    if (!dragging) return;
    var board = boardOf(e.target);
    // Only the board the lifted column belongs to may be reordered, so a pointer that wanders over the
    // page behind it leaves the columns where the reader put them.
    if (!board || board !== boardOf(dragging)) return;
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    var r = board.getBoundingClientRect();
    if (e.clientX > r.right - 70) board.scrollLeft += 14;
    else if (e.clientX < r.left + 70) board.scrollLeft -= 14;
    var target = null;
    var now = columns(board);
    for (var i = 0; i < now.length; i++) {
      var c = now[i];
      if (c === dragging) continue;
      var cr = c.getBoundingClientRect();
      if (e.clientX < cr.left + cr.width / 2) { target = c; break; }
    }
    if (target) board.insertBefore(dragging, target);
    else board.appendChild(dragging);
  });
  document.addEventListener('drop', function (e) {
    if (!dragging) return;
    var board = boardOf(e.target);
    if (!board || board !== boardOf(dragging)) return;
    e.preventDefault();
    dropped = true;
    save(board);
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
