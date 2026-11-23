/* lesson-modal.js — the way out of the lesson sheet's popup.

   Opening is not this file's business. The sheet has two openers for that one popup: `+ Add lesson`, on the
   toolbar beside the pickers, is a GET form onto `/app/lessons` as `?new=1`, and a click on a lesson's text
   in its own cell is the same kind of form as `?edit=<id>`. `app.js` answers either by fetching that URL and
   swapping only `#lessonModal` for the copy the server drew already open — which is why a reader with scripts
   switched off can still write a lesson, read somebody else's, or edit their own: their click loads the page
   that markup describes. Sending the form is the same arrangement the other way round: it names `#ll-board`
   as the block it rewrites and `#ll-counts,#lessonModal` as the companions of that send, so a lesson that
   landed comes back as a sheet with the row on it and a closed form, and one that was refused comes back
   open, with the words still typed and the reason under the field it came from.

   What is left here is the three ways a reader gets out without posting anything, and the caret.

     [data-lesson-modal-close]   the × and the Cancel and Close buttons. Named apart from the task popup's
                                 [data-modal-close] and the backlog column's [data-bucket-modal-close] on
                                 purpose: one click on this page would otherwise be read as closing three
                                 different popups by three different files.
     #lessonModal                the backdrop itself — a click that lands on it and not on its content
     #lessonText                 the text box, in the writable half of the popup, and the one to write in first

   A popup the server opened on a fresh load is already visible when this file runs, and one that arrives
   mid-session is not a click this file is still handling but a node swapped in under the same id — so the
   keyboard is taken by watching for that swap, exactly as bucket-modal.js does for #bucketModal. */
(function () {
  var MODAL = 'lessonModal';

  function el(id) { return document.getElementById(id); }

  function isOpen(modal) { return !!modal && modal.style.display === 'block'; }

  function show(visible) {
    var modal = el(MODAL);
    if (!modal) return;
    modal.style.display = visible ? 'block' : 'none';
    if (visible) {
      // The read-only half has no box to write in — it is the lesson, printed — so the caret only goes
      // where there is something to put in it.
      var field = el('lessonText');
      if (field && field.focus) field.focus({ preventScroll: true });
      return;
    }
    // A bookmark, a reload or the browser's Back would otherwise put this form back up on the screen.
    // Only this popup's own keys go: `?new=1` is the add opener and `?edit=` the click on a lesson's
    // text. `?project=`, `?employee=`, `?sort=` and `?archived=` are the sheet's own view, and closing a
    // form is no answer to any of them — the reader is still looking at the same sheet.
    var params = new URLSearchParams(window.location.search);
    if (!params.has('new') && !params.has('edit')) return;
    params.delete('new');
    params.delete('edit');
    var query = params.toString();
    try {
      window.history.replaceState(null, '', window.location.pathname + (query ? '?' + query : ''));
    } catch (err) { /* a blocked history costs the address bar, nothing else */ }
  }

  document.addEventListener('click', function (e) {
    if (!e.target || !e.target.closest) return;
    if (e.target.closest('[data-lesson-modal-close]')) { show(false); return; }
    if (e.target.id === MODAL) show(false);
  });

  var modalNode = el(MODAL);

  function noticeSwap() {
    var now = el(MODAL);
    if (now === modalNode) return;
    modalNode = now;
    // The swap came from an opener outside the popup, so nothing has the caret yet and the text box the
    // reader is about to write in is the whole point of the screen. A swap that left it closed — the
    // response to a lesson that landed — must not steal the caret from the sheet.
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
