/* kanban.js — optimistic drag and drop for the status and project-lane boards.

   The boards are live regions: their columns and cards can be replaced without this script running again, so every
   listener is delegated from `document`. A drop moves the card and rewrites the affected counts before the POST is
   sent. The route still owns the write and its permissions; a failed request reloads the authoritative board, while a
   successful one re-fetches only this board in the background so server ordering, Completed folds and checklist
   progress agree with the database without a page flash. */
(function () {
  var dragging = null;
  var hovered = null;
  var placeholder = null;
  var dragClickBlock = null;
  var ghostFrame = 0;
  var scrollFrame = 0;
  var pointer = null;
  var pendingRefresh = null;
  var refreshTokens = Object.create(null);
  var writes = Object.create(null);

  function cardOf(node) {
    return node && node.closest ? node.closest('.kcard[draggable="true"]') : null;
  }

  function statusCol(node) {
    return node && node.closest ? node.closest('.kcol[data-status]') : null;
  }

  function laneOf(node) {
    var col = node && node.closest ? node.closest('.kcol[data-lane]') : null;
    // Unsorted is a source only. The service refuses arrivals there, so the board must not offer it as a target.
    if (col && col.hasAttribute('data-unfiled')) return null;
    return col;
  }

  function columnOf(node) {
    return statusCol(node) || laneOf(node);
  }

  function targetColumn(e) {
    if (!dragging) return null;
    var col = columnOf(e.target);
    // A status write never changes board membership. In particular, the two Assigned boards and the separate
    // Other-tasks bucket boards must never hand a card to one another's server-rendered columns.
    if (!col || col.closest('[data-kanban-board]') !== dragging.board) return null;
    return col;
  }

  function directChild(col, className) {
    if (!col) return null;
    for (var i = 0; i < col.children.length; i += 1) {
      if (col.children[i].classList.contains(className)) return col.children[i];
    }
    return null;
  }

  function completed(card) {
    return card.classList.contains('s-completed');
  }

  function setStatus(card, status) {
    var slug = {
      'Not started': 's-notstarted',
      'In progress': 's-inprogress',
      'On hold': 's-onhold',
      Blocked: 's-blocked',
      Completed: 's-completed'
    }[status];
    if (!slug) return;
    ['s-notstarted', 's-inprogress', 's-onhold', 's-blocked', 's-completed'].forEach(function (name) {
      card.classList.remove(name);
    });
    card.classList.add(slug);
  }

  function stopAutoScroll() {
    if (scrollFrame) window.cancelAnimationFrame(scrollFrame);
    scrollFrame = 0;
    pointer = null;
  }

  function clearDrop() {
    if (hovered) hovered.classList.remove('drag-over');
    hovered = null;
    if (placeholder && placeholder.parentNode) placeholder.parentNode.removeChild(placeholder);
    placeholder = null;
    stopAutoScroll();
  }

  function endDrag() {
    clearDrop();
    document.body.classList.remove('kanban-dragging');
    dragging = null;
    if (pendingRefresh) {
      var board = pendingRefresh;
      pendingRefresh = null;
      refreshBoard(board);
    }
  }

  function makePlaceholder() {
    var node = document.createElement('div');
    node.className = 'kcard-drop';
    node.setAttribute('aria-hidden', 'true');
    node.textContent = 'Drop here';
    node.style.height = Math.max(64, dragging.height || 80) + 'px';
    return node;
  }

  /* The server does not save a card position. The indicator therefore sits at the existing landing rule: before a
     Completed fold or the empty line, otherwise at the end. A completed card on a lane board belongs in the fold;
     when that fold is shut the toggle itself is the honest visible indicator. */
  function landingPlace(card, col) {
    if (completed(card) && col.hasAttribute('data-lane')) {
      var done = directChild(col, 'kcol-done');
      if (done && !done.hasAttribute('hidden')) return { parent: done, before: null };
      var toggle = directChild(col, 'kcol-done-toggle');
      if (toggle) return { parent: col, before: toggle };
    }
    return {
      parent: col,
      before: directChild(col, 'kcol-done-toggle') || directChild(col, 'kcol-empty') || directChild(col, 'kcol-add')
    };
  }

  function showPlaceholder(col) {
    if (!placeholder) placeholder = makePlaceholder();
    var place = landingPlace(dragging.card, col);
    var alreadyThere = placeholder.parentNode === place.parent &&
      ((place.before && placeholder.nextSibling === place.before) || (!place.before && place.parent.lastChild === placeholder));
    if (!alreadyThere) place.parent.insertBefore(placeholder, place.before || null);
  }

  function edgeSpeed(position, start, end) {
    var edge = 56;
    if (position < start + edge) return Math.ceil((start + edge - position) / edge * 14);
    if (position > end - edge) return Math.ceil((position - (end - edge)) / edge * 14);
    return 0;
  }

  function autoScroll() {
    scrollFrame = 0;
    if (!dragging || !pointer) return;
    var dx = 0;
    var dy = edgeSpeed(pointer.y, 0, window.innerHeight || 0);
    if (pointer.board && pointer.scrollable) {
      dx = edgeSpeed(pointer.x, pointer.left, pointer.right);
      if (dx) pointer.board.scrollLeft += dx;
    }
    if (!dx) {
      dx = edgeSpeed(pointer.x, 0, window.innerWidth || 0);
      var root = document.scrollingElement || document.documentElement;
      if (dx && root) root.scrollLeft += dx;
    }
    if (dy) {
      var page = document.scrollingElement || document.documentElement;
      if (page) page.scrollTop += dy;
    }
    if (dx || dy) scrollFrame = window.requestAnimationFrame(autoScroll);
  }

  function watchEdges(e, col) {
    if (typeof e.clientX !== 'number' || typeof e.clientY !== 'number') return;
    var board = col.closest('.kanban');
    var rect = board && board.getBoundingClientRect ? board.getBoundingClientRect() : null;
    pointer = {
      x: e.clientX,
      y: e.clientY,
      board: board,
      left: rect ? rect.left : 0,
      right: rect ? rect.right : 0,
      scrollable: !!(board && board.scrollWidth > board.clientWidth)
    };
    if (!scrollFrame) scrollFrame = window.requestAnimationFrame(autoScroll);
  }

  function count(col) {
    if (!col) return;
    var label = col.querySelector('.kcol-count');
    var cards = col.querySelectorAll('.kcard');
    if (label) label.textContent = String(cards.length);
    // Status columns render this line only when they start empty. Create it when the last card leaves so the same
    // message appears without waiting for the background refresh.
    if (!cards.length && !directChild(col, 'kcol-empty') && !col.hasAttribute('data-unfiled')) {
      var empty = document.createElement('p');
      empty.className = 'kcol-empty';
      empty.textContent = 'Nothing here.';
      col.insertBefore(empty, directChild(col, 'kcol-add'));
    }
  }

  function ensureDoneGroup(col) {
    if (directChild(col, 'kcol-done')) return;
    var board = col.closest('[data-kanban-board]') || col.parentNode;
    var open = [];
    var columns = board ? board.querySelectorAll('.kcol[data-lane]') : [];
    for (var i = 0; i < columns.length; i += 1) {
      var done = directChild(columns[i], 'kcol-done');
      if (done && !done.hasAttribute('hidden')) open.push(columns[i].getAttribute('data-lane'));
    }
    open.push(col.getAttribute('data-lane'));

    var form = document.createElement('form');
    form.className = 'kcol-done-toggle';
    form.setAttribute('method', 'get');
    form.setAttribute('action', location.pathname);
    if (col.closest('#projectBoard')) form.setAttribute('data-live-target', '#projectBoard');
    open.forEach(function (id) {
      var field = document.createElement('input');
      field.type = 'hidden';
      field.name = 'showdone';
      field.value = id;
      form.appendChild(field);
    });
    var button = document.createElement('button');
    button.type = 'submit';
    button.className = 'kdone-btn';
    form.appendChild(button);
    var done = document.createElement('div');
    done.className = 'kcol-done';
    done.setAttribute('hidden', 'true');
    col.insertBefore(form, directChild(col, 'kcol-empty') || directChild(col, 'kcol-add'));
    col.insertBefore(done, directChild(col, 'kcol-empty') || directChild(col, 'kcol-add'));
  }

  function syncCompleted(col) {
    if (!col || !col.hasAttribute('data-lane')) return;
    var done = directChild(col, 'kcol-done');
    var toggle = directChild(col, 'kcol-done-toggle');
    var cards = done ? done.querySelectorAll('.kcard') : [];
    if (!cards.length) {
      if (toggle && toggle.parentNode) toggle.parentNode.removeChild(toggle);
      if (done && done.parentNode) done.parentNode.removeChild(done);
      return;
    }
    ensureDoneGroup(col);
    done = directChild(col, 'kcol-done');
    toggle = directChild(col, 'kcol-done-toggle');
    var button = toggle.querySelector('.kdone-btn');
    var word = cards.length === 1 ? 'card' : 'cards';
    button.textContent = (done.hasAttribute('hidden') ? '▸' : '▾') + ' Completed ' + cards.length;
    button.setAttribute('title', (done.hasAttribute('hidden') ? 'Show the ' : 'Hide the ') + cards.length +
      ' completed ' + word + ' in this column');
  }

  function reducedMotion() {
    return !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
  }

  function settle(card) {
    if (reducedMotion()) return;
    card.classList.add('kcard-settle');
    window.setTimeout(function () { card.classList.remove('kcard-settle'); }, 240);
  }

  /* Move in one DOM operation, then update only the two affected columns. Completed cards stay in the completed run;
     the server refresh after a successful POST is the final authority for ordering and for a newly-created fold. */
  function file(card, col) {
    var from = card.closest('.kcol');
    if (from === col) return false;
    if (completed(card) && col.hasAttribute('data-lane')) {
      ensureDoneGroup(col);
      directChild(col, 'kcol-done').appendChild(card);
    } else {
      var anchor = directChild(col, 'kcol-done-toggle') || directChild(col, 'kcol-empty') || directChild(col, 'kcol-add');
      col.insertBefore(card, anchor || null);
    }
    syncCompleted(from);
    syncCompleted(col);
    count(from);
    count(col);
    if (from && from.getAttribute('data-lane') === '0' && !from.querySelector('.kcard') && from.parentNode) {
      from.parentNode.removeChild(from);
    }
    // Reparenting a focused card blurs it in the browser. Put focus back before the quiet refresh captures it,
    // without scrolling the page or the horizontal board away from the landing the reader just chose.
    if (dragging && dragging.focusId) {
      var focusedCard = cardsById(boardOf(card), dragging.focusId);
      if (focusedCard && focusedCard.focus) focusedCard.focus({ preventScroll: true });
    }
    settle(card);
    return true;
  }

  function cardsById(root, id) {
    var all = root.querySelectorAll('.kcard[data-id]');
    for (var i = 0; i < all.length; i += 1) {
      if (all[i].getAttribute('data-id') === id) return all[i];
    }
    return null;
  }

  function boardOf(card) {
    return card.closest('[data-kanban-board]');
  }

  /* A successful write is followed by a quiet GET of the current page. Only this board is replaced; the toolbar,
     popup, scroll position and focus stay put. This is what keeps Completed folding and status-driven checklist
     progress exactly as the server would render them. */
  function refreshBoard(board) {
    if (!board || !document.documentElement.contains(board)) return;
    if (dragging) {
      pendingRefresh = board;
      return;
    }
    var boards = document.querySelectorAll('[data-kanban-board]');
    var index = 0;
    while (index < boards.length && boards[index] !== board) index += 1;
    if (index === boards.length) return;
    // Each board has its own reply order: a fast second board must not cancel the first board's refresh.
    var key = String(index);
    var token = (refreshTokens[key] || 0) + 1;
    refreshTokens[key] = token;

    window.fetch(location.href, { credentials: 'same-origin' })
      .then(function (response) {
        if (!response.ok) throw new Error('board refresh failed');
        return response.text();
      })
      .then(function (html) {
        if (refreshTokens[key] !== token) return;
        if (dragging) {
          pendingRefresh = board;
          return;
        }
        if (!document.documentElement.contains(board)) return;
        var parsed = new DOMParser().parseFromString(html, 'text/html');
        var nextBoards = parsed.querySelectorAll('[data-kanban-board]');
        var next = nextBoards[index];
        if (!next) throw new Error('the response carried no matching board');
        // Capture at the landing moment, not when the request began: a reader who scrolled or moved focus while
        // the GET was in flight must not be pulled back to where they were when it started.
        var active = document.activeElement;
        var activeCard = active && active.closest ? active.closest('.kcard') : null;
        var focusId = activeCard && board.contains(activeCard) ? activeCard.getAttribute('data-id') : null;
        var x = window.scrollX || 0;
        var y = window.scrollY || 0;
        var left = board.scrollLeft || 0;
        var imported = document.importNode(next, true);
        board.parentNode.replaceChild(imported, board);
        imported.scrollLeft = left;
        if (window.scrollTo) window.scrollTo(x, y);
        if (focusId) {
          var again = cardsById(imported, focusId);
          if (again && again.focus) again.focus({ preventScroll: true });
        }
      })
      .catch(function () { if (refreshTokens[key] === token) location.reload(); });
  }

  function sendMove(card, url, field, value) {
    var id = card.getAttribute('data-id');
    var board = boardOf(card);
    var boards = document.querySelectorAll('[data-kanban-board]');
    var boardIndex = 0;
    while (boardIndex < boards.length && boards[boardIndex] !== board) boardIndex += 1;
    var previous = writes[id] || Promise.resolve();
    // Serialise writes for one card so a quick second drop cannot reach the server before the first one.
    var request = previous.catch(function () {}).then(function () {
      var fd = new FormData();
      fd.append('id', id);
      fd.append(field, value);
      return window.fetch(url, { method: 'POST', body: fd, credentials: 'same-origin', redirect: 'manual' });
    });
    writes[id] = request;
    request
      .then(function (response) {
        if (response.type !== 'opaqueredirect' && !response.ok) throw new Error('move failed');
      })
      .then(function () {
        // An earlier reply may already have replaced this board. Resolve its current node by stable page order so
        // the next successful drop still receives its authoritative refresh.
        var current = boardIndex < boards.length ?
          document.querySelectorAll('[data-kanban-board]')[boardIndex] : boardOf(card);
        refreshBoard(current);
      })
      .catch(function () { location.reload(); })
      .then(function () { if (writes[id] === request) delete writes[id]; });
  }

  document.addEventListener('dragstart', function (e) {
    var card = cardOf(e.target);
    if (!card || dragging) return;
    var control = e.target.closest('button, a, input, select, textarea, label, [contenteditable="true"]');
    // Other-tasks deliberately makes its whole-card opener the native drag handle. The explicit marker is the
    // only exception; Done/Reopen, links and form controls still answer their own click and cannot start a drag.
    if (control && !control.hasAttribute('data-drag-handle')) {
      e.preventDefault();
      return;
    }
    var rect = card.getBoundingClientRect ? card.getBoundingClientRect() : null;
    var active = document.activeElement;
    var focused = active && active.closest ? active.closest('.kcard') : null;
    dragging = {
      card: card, id: card.getAttribute('data-id'), height: rect ? rect.height : 80, board: boardOf(card),
      focusId: focused === card ? card.getAttribute('data-id') : null
    };
    dragClickBlock = null;
    // The browser paints the drag image when this handler returns. The drag-image class therefore goes on now,
    // before setData; the original is dimmed on the next frame so the image itself stays clear.
    card.classList.add('kcard-drag');
    if (e.dataTransfer) {
      e.dataTransfer.setData('text/plain', dragging.id);
      if ('effectAllowed' in e.dataTransfer) e.dataTransfer.effectAllowed = 'move';
    }
    document.body.classList.add('kanban-dragging');
    ghostFrame = window.requestAnimationFrame(function () {
      ghostFrame = 0;
      if (dragging && dragging.card === card) card.classList.add('kcard-ghost');
    });
  });

  document.addEventListener('dragover', function (e) {
    if (!dragging) return;
    var col = targetColumn(e);
    if (!col) {
      if (hovered) clearDrop();
      return;
    }
    e.preventDefault();
    if (e.dataTransfer && 'dropEffect' in e.dataTransfer) e.dataTransfer.dropEffect = 'move';
    if (hovered !== col) {
      if (hovered) hovered.classList.remove('drag-over');
      hovered = col;
      col.classList.add('drag-over');
    }
    showPlaceholder(col);
    watchEdges(e, col);
  });

  document.addEventListener('dragleave', function (e) {
    var col = columnOf(e.target);
    if (!col) return;
    // Crossing onto a card or any other descendant is still inside this column.
    if (e.relatedTarget && col.contains(e.relatedTarget)) return;
    col.classList.remove('drag-over');
    if (hovered === col) clearDrop();
  });

  document.addEventListener('drop', function (e) {
    if (!dragging) return;
    var card = dragging.card;
    var id = dragging.id;
    // Remember the task, not the DOM node: a very fast quiet refresh may already have replaced the card before
    // the browser emits its release click. Drops outside a valid column are guarded too.
    dragClickBlock = { id: id, at: Date.now() };
    var col = targetColumn(e);
    if (!col) {
      endDrag();
      return;
    }
    e.preventDefault();
    var transfer = e.dataTransfer ? e.dataTransfer.getData('text/plain') : '';
    if (transfer && transfer !== id) {
      endDrag();
      return;
    }

    var status = col.getAttribute('data-status');
    var bucket = col.getAttribute('data-lane');
    var sameColumn = card.closest('.kcol') === col;
    var moved = !sameColumn && file(card, col);
    if (moved && status) setStatus(card, status);
    endDrag();

    if (moved && status) sendMove(card, '/app/tasks/status', 'status', status);
    else if (moved && bucket) sendMove(card, '/app/tasks/bucket', 'bucket', bucket);
  });

  document.addEventListener('dragend', function (e) {
    if (ghostFrame) window.cancelAnimationFrame(ghostFrame);
    ghostFrame = 0;
    var card = cardOf(e.target) || (dragging && dragging.card);
    if (card) {
      card.classList.remove('kcard-drag');
      card.classList.remove('kcard-ghost');
    }
    endDrag();
  });

  // A fresh press is a new intention, even when it follows a drag whose release click the browser suppressed.
  document.addEventListener('pointerdown', function () { dragClickBlock = null; }, true);

  // Native drag usually suppresses the click, but not in every browser. Swallow only the click belonging to the
  // dragged card; the next ordinary click still opens its popup.
  document.addEventListener('click', function (e) {
    if (!dragClickBlock) return;
    if (Date.now() - dragClickBlock.at > 400) {
      dragClickBlock = null;
      return;
    }
    var card = cardOf(e.target);
    if (card && card.getAttribute('data-id') === dragClickBlock.id) {
      e.preventDefault();
      e.stopImmediatePropagation();
      dragClickBlock = null;
    }
  }, true);

  /* The project card's Done/Reopen button is still a status write with the existing full-page redirect. */
  document.addEventListener('click', function (e) {
    var btn = e.target && e.target.closest ? e.target.closest('.kmove-btn') : null;
    if (!btn) return;
    e.preventDefault();
    e.stopPropagation();
    var id = btn.getAttribute('data-id');
    var status = btn.getAttribute('data-status');
    if (!id || !status) return;
    btn.disabled = true;
    var fd = new FormData();
    fd.append('id', id);
    fd.append('status', status);
    fetch('/app/tasks/status', { method: 'POST', body: fd, redirect: 'manual' }).then(function () { location.reload(); });
  });
}());
