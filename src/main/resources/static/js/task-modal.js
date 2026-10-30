/* task-modal.js — the task popup on the two boards, and the click on a card that opens it.
   Same shape as project-modal.js: the markup is server-rendered, CSP allows only 'self'
   scripts, so behaviour hangs off data-* markers instead of inline handlers.

     [data-task-card="<id>"]               a card the reader may open: any card for management, their own
                                         for everybody else. Clicking its body — anywhere that is not a
                                         control already on it — asks this page for that card's editor.
                                         It is also focusable, and Enter or Space does the same thing.
     #taskEditOpener                       ONE hidden GET form per board, rendered by taskKanban(). The
                                         click writes the card's id into its `edit` field and submits it,
                                         which is how one form serves forty cards and no href has to be
                                         written per card at render time.
     form[data-live-target="#taskModal"]   the three openers — `+ New task` in the page head (?new=1), a
                                         `+ Add task` line at the foot of a lane column (?new=1&into=<lane>),
                                         and a card click (?edit=<card>) — which app.js answers by fetching
                                         that URL and swapping #taskModal for the popup the server rendered.
                                         That is the whole reason no code here fills a form: the blank one, the
                                         one holding a card's values, and the one that already knows which
                                         lane it was called from are all drawn by the same renderer that drew
                                         the board, so a popup can never disagree with the row behind it.
     [data-modal-close]                    the × and Cancel buttons; the backdrop closes too
     select[data-board-toggle]             management's "where it lives" picker, whose two halves it switches
                                          between in place — on a popup and on the Team sheet's assign panel
                                          alike; see the board picker section below
     #taskModal [name=taskId]              0 while creating, the card's id while editing — the server puts
                                         it there, this script never touches it

   A card's status is no longer this file's business: management drags the card or sets Status in the
   popup, and both go through /app/tasks/status and kanban.js as they always did. (The arrows an
   assignee used to have on their own card were retired in Stage 103.)

   So three jobs are left: turn a click on a card into a request for its editor, hide the popup again,
   and give the keyboard to the popup when it lands — which it can only notice, because a node swapped in
   from a parsed response is not something a click handler is still running for. */
(function () {
  var MODAL = 'taskModal';
  var OPENER = 'taskEditOpener';

  function el(id) { return document.getElementById(id); }

  /** The first field the reader can actually write in: greyed inputs are not where the caret goes. */
  function firstWritable(modal) {
    var bad = modal.querySelector('.field-err');
    if (bad) {
      var owner = bad.parentNode.querySelector('input:not([type="hidden"]), select, textarea');
      if (owner) return owner;
    }
    var nodes = modal.querySelectorAll('input, select, textarea');
    for (var i = 0; i < nodes.length; i++) {
      // A field of the half this card is not on is printed but put away (see the board picker below), so
      // the caret must not go to something the reader cannot see.
      if (nodes[i].closest && nodes[i].closest('[hidden]')) continue;
      if (nodes[i].type !== 'hidden' && !nodes[i].disabled) return nodes[i];
    }
    return null;
  }

  function focusPopup(modal) {
    var target = firstWritable(modal);
    if (target && target.focus) target.focus({ preventScroll: true });
  }

  function isOpen(modal) {
    return !!modal && modal.style.display === 'block';
  }

  function show(visible) {
    var modal = el(MODAL);
    if (!modal) return;
    modal.style.display = visible ? 'block' : 'none';
    if (visible) {
      focusPopup(modal);
      return;
    }
    // Closing says so in the address bar. The page came from `?edit=<card>` or `?new=1` — a bookmark, a
    // reload, or the page a rejected submit came back as — and a reload would otherwise put somebody's
    // form back up on the reader's screen. Only the query goes: the path is the board itself. A popup
    // opened by a click never put its id in the bar, so this normally has nothing to do.
    if (/[?&](edit|new)=/.test(window.location.search)) {
      try { window.history.replaceState(null, '', window.location.pathname); } catch (err) { /* not fatal */ }
    }
  }

  /* --- a click on a card ----------------------------------------------------------
     Fill the board's one opener with this card's id and submit it, which is a GET onto this page as
     `?edit=<card>` that app.js answers by swapping #taskModal in place. An opener that is not on the
     page (a board of somebody else's cards, which renders none) or a card with no id leaves the reader
     exactly where they were — there is no half-open popup and nothing is posted. */
  function openEditor(card) {
    var form = el(OPENER);
    if (!form) return;
    var field = form.querySelector('[name="edit"]');
    if (!field) return;
    var id = card.getAttribute('data-task-card') || '0';
    if (id === '0') return;
    field.value = id;
    // requestSubmit fires the submit event, so app.js's live-target interception still runs; plain
    // submit() on a very old browser navigates to the same URL the form describes.
    if (form.requestSubmit) { form.requestSubmit(); } else { form.submit(); }
  }

  /* The popup arrives by app.js replacing #taskModal with the copy from a fetched page, so this script
     cannot be told when it lands — it watches for the element under that id changing identity instead.
     Same id, different node means a popup just came in, and it came in already displayed because the
     server rendered it that way. All that is left is the keyboard. */
  var modalNode = el(MODAL);

  function noticeSwap() {
    var now = el(MODAL);
    if (now === modalNode) return;
    modalNode = now;
    if (!isOpen(now)) return;
    // app.js puts the caret back by id after it swaps a block, so a step or a note written inside the
    // popup comes back with the cursor still in the box it was typed in — which is what lets a list be
    // written one line at a time. Take the keyboard only when it is not already inside the popup, i.e.
    // when the swap was asked for by an opener outside it; then the first field is the whole point.
    var here = document.activeElement;
    if (here && now.contains(here)) return;
    focusPopup(now);
  }

  /* --- the board picker -----------------------------------------------------------
     Management's popup prints both halves of the card's address — the Other tasks board's Column, and a
     project's Project plus Sub-category — and shows one of them, because changing which world a card lives in
     is a decision the reader makes in the popup they are already in, not one that deserves a page reload. So
     picking the other answer only puts the two field groups the other way round: the fields keep their values,
     the board behind the popup does not move, and nothing is fetched.

     The two halves are marked `data-board-half` rather than styled, and which one is up is the `hidden`
     attribute the server already set for the board the card is on. With JavaScript off the half the server
     picked is the one that shows, and the form still posts a single `board` field, which is what
     /app/assigned-tasks/save actually reads to decide which half of the post to take — so the toggle is
     presentation, never the rule. The same refusal is answered with the page itself, so a reader who picks
     *Other tasks* and leaves the column unchosen gets the other half up on the way back, with the reason
     printed under it.

     Two pages use it: the card popup, and — since Stage 50 — the Team sheet's + Assign a task panel, which
     asks the same question of a card that does not exist yet. Everything else in this file is the popup's
     and does nothing on that panel: it prints no `[data-task-card]` to click and no `#taskModal` to close. */
  document.addEventListener('change', function (e) {
    var pick = e.target && e.target.closest ? e.target.closest('select[data-board-toggle]') : null;
    if (!pick) return;
    // The popup is the usual host, and the Team page's + Assign a task panel is the other: same picker, same
    // two halves, same `hidden` attribute the server set, so one handler serves both. A panel is a plain form
    // on a page rather than a swapped-in popup, so the scope is the form the picker actually sits in.
    var scope = pick.closest('.modal') || pick.closest('form');
    if (!scope) return;
    var toOther = pick.value === 'other';
    var fields = scope.querySelectorAll('[data-board-half]');
    for (var i = 0; i < fields.length; i++) {
      var mine = fields[i].getAttribute('data-board-half') === 'other';
      if (mine === toOther) { fields[i].removeAttribute('hidden'); } else { fields[i].setAttribute('hidden', 'hidden'); }
    }
  });

  document.addEventListener('click', function (e) {
    if (!e.target || !e.target.closest) return;
    if (e.target.closest('[data-modal-close]')) { show(false); return; }
    if (e.target.id === MODAL) { show(false); return; }
    // The opener is this file's own form; a click inside it must not restart the request it is making.
    if (e.target.closest('#' + OPENER)) return;
    var card = e.target.closest('[data-task-card]');
    if (!card) return;
    // Not the controls on the card: a tick box, a move arrow or the × on a step is clicked for what it
    // does, and opening an editor over the card would hide the very thing that just changed. Since **Stage 104**
    // this line also carries the card's own project chip — an `<a href="/app/projects/…">` on a status board —
    // which is followed, not opened over; the same rule that keeps a button from restarting its own request is
    // what lets the chip be a link without a handler of its own.
    if (e.target.closest('button, a, input, select, textarea, label')) return;
    openEditor(card);
  });

  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') { show(false); return; }
    if (e.key !== 'Enter' && e.key !== ' ') return;
    var here = document.activeElement;
    if (!here || !here.closest) return;
    // Only when the card itself holds the keyboard — Enter in a field of the popup, or on an arrow
    // button, means something else entirely.
    if (here !== here.closest('[data-task-card]')) return;
    e.preventDefault();  // Space would otherwise scroll the page
    openEditor(here);
  });

  if (window.MutationObserver && document.body) {
    new MutationObserver(noticeSwap).observe(document.body, { childList: true, subtree: true });
  }

  // A popup the server opened on the very first load — a bookmarked ?edit=, or the page a rejected
  // submit came back as — gets the same treatment, once the document is ready.
  document.addEventListener('DOMContentLoaded', function () {
    modalNode = el(MODAL);
    if (isOpen(modalNode)) focusPopup(modalNode);
  });
})();
