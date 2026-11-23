/* lane-modal.js — the way out of a project board's bucket popup.

   Opening is not this file's business, and it could not be: the two openers live inside `#projectBoard`, which is a
   block this popup swaps and is therefore replaced under the script's feet. Each is a GET form onto the project page
   — the dashed `+ Add bucket` box as `?laneadd=1`, a column's `⋯` as `?laneedit=<id>` — and `app.js` answers either by
   following that URL and swapping `#projectLaneModal` for the copy the server drew already open. That is why a reader
   with scripts switched off gets the identical popup from a plain click, and why a refusal is simply the same page
   again with its reason under the field it came from. Closing after a write is the same arrangement reversed: each of
   this popup's forms names `#projectLaneModal` as a companion of the board it posts to, so a write that landed comes
   back with the popup shut and the board redrawn around it.

   What is left here is the three ways a reader gets out without posting anything, and the caret.

     [data-lane-modal-close]  the × and the Cancel buttons. Named apart from the backlog popup's
                              [data-bucket-modal-close] and the card popup's [data-modal-close] for the
                              reason that file gives: one click would otherwise be read as closing two
                              different popups by two different files.
     #projectLaneModal        the backdrop itself — a click that lands on it and not on its content
     #laneName                the name field, in both halves of the popup, and the one to write in first

   A popup the server opened on a fresh load is already visible when this file runs, and one that arrives mid-session
   is not a click this file is still handling but a node swapped in under the same id — so the keyboard is taken by
   watching for that swap, exactly as bucket-modal.js does for #bucketModal. */
(function () {
  var MODAL = 'projectLaneModal';

  function el(id) { return document.getElementById(id); }

  function isOpen(modal) { return !!modal && modal.style.display === 'block'; }

  function show(visible) {
    var modal = el(MODAL);
    if (!modal) return;
    modal.style.display = visible ? 'block' : 'none';
    if (visible) {
      var field = el('laneName');
      if (field && field.focus) field.focus({ preventScroll: true });
      return;
    }
    // A bookmark, a reload or the browser's Back would otherwise put this form back up on the screen. Only this
    // popup's own keys go: `laneadd` is the dashed box, `laneedit` a column's `⋯`, and `lanetyped` the word a
    // refusal handed back — it means nothing once the form is shut. `buckets` and `bucketnote` are the page's
    // sentence about the last write, `bucketsrc` says which half of the page asked for it, and `new`/`edit` belong
    // to the card popup, so none of them is ours to clear.
    var params = new URLSearchParams(window.location.search);
    if (!params.has('laneadd') && !params.has('laneedit')) return;
    params.delete('laneadd');
    params.delete('laneedit');
    params.delete('lanetyped');
    var query = params.toString();
    try {
      window.history.replaceState(null, '', window.location.pathname + (query ? '?' + query : ''));
    } catch (err) { /* a blocked history costs the address bar, nothing else */ }
  }

  document.addEventListener('click', function (e) {
    if (!e.target || !e.target.closest) return;
    if (e.target.closest('[data-lane-modal-close]')) { show(false); return; }
    if (e.target.id === MODAL) show(false);
  });

  var modalNode = el(MODAL);

  function noticeSwap() {
    var now = el(MODAL);
    if (now === modalNode) return;
    modalNode = now;
    // The swap came from an opener outside the popup, so nothing has the caret yet and the field the
    // reader is about to write in is the whole point of the screen.
    if (isOpen(now)) show(true);
  }

  if (window.MutationObserver && document.body) {
    new MutationObserver(noticeSwap).observe(document.body, { childList: true, subtree: true });
  }

  document.addEventListener('DOMContentLoaded', function () {
    modalNode = el(MODAL);
    if (isOpen(modalNode)) show(true);
  });
})();
