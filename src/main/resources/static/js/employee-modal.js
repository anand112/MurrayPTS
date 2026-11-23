/* employee-modal.js — the way out of the Team sheet's popup.

   Opening is not this file's business. The sheet has two openers for that one popup: `+ Add employee`, in
   the card above the toolbar, is a GET form onto `/app/settings` as `?new=1`, and a row's pencil is the same
   kind of form as `?edit=<employee number>`. `app.js` answers either by fetching that URL and swapping only
   `#employeeModal` for the copy the server drew already open — which is why a reader with scripts switched
   off can still add an account or change one: their click loads the page that markup describes. Sending the
   form is the same arrangement the other way round: it names `#team-board` as the block it rewrites and
   `#team-counts,#employeeModal` as the companions of that send, so an account that landed comes back as a
   sheet with the row on it and a closed form, and one that was refused comes back open, with the words still
   typed and the reason under the field it came from.

   What is left here is the three ways a reader gets out without posting anything, and the caret.

     [data-employee-modal-close]   the × and the Cancel button. Named apart from the task popup's
                                   [data-modal-close], the backlog column's [data-bucket-modal-close] and the
                                   lesson sheet's [data-lesson-modal-close] on purpose: one click on a page
                                   that carries two popups would otherwise be read as closing both.
     #employeeModal                the backdrop itself — a click that lands on it and not on its content
     #employeeNumber               the first box of a create; on an edit the number and the name are the
                                   row's own and cannot be typed, so the caret goes to the email instead

   A popup the server opened on a fresh load is already visible when this file runs, and one that arrives
   mid-session is not a click this file is still handling but a node swapped in under the same id — so the
   keyboard is taken by watching for that swap, exactly as lesson-modal.js does for #lessonModal. */
(function () {
  var MODAL = 'employeeModal';

  function el(id) { return document.getElementById(id); }

  function isOpen(modal) { return !!modal && modal.style.display === 'block'; }

  /** The one box worth starting in: the first field on the page that a reader can actually type in. */
  function firstField() {
    var number = el('employeeNumber');
    if (number && !number.readOnly) return number;
    return el('employeeEmail');
  }

  function show(visible) {
    var modal = el(MODAL);
    if (!modal) return;
    modal.style.display = visible ? 'block' : 'none';
    if (visible) {
      var field = firstField();
      if (field && field.focus) field.focus({ preventScroll: true });
      return;
    }
    // A bookmark, a reload or the browser's Back would otherwise put this form back up on the screen.
    // Only this popup's own keys go: `?new=1` is the add opener and `?edit=` the pencil on a row.
    // `?q=`, `?position=`, `?role=` and `?sort=` are the sheet's own view and `?note=` the line that says
    // an account was just added or taken away — closing a form is no answer to any of them, because the
    // reader is still looking at the same sheet.
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
    if (e.target.closest('[data-employee-modal-close]')) { show(false); return; }
    if (e.target.id === MODAL) show(false);
  });

  var modalNode = el(MODAL);

  function noticeSwap() {
    var now = el(MODAL);
    if (now === modalNode) return;
    modalNode = now;
    // The swap came from an opener outside the popup, so nothing has the caret yet and the box the reader
    // is about to write in is the whole point of the screen. A swap that left it closed — the response to
    // an account that landed — must not steal the caret from the sheet.
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
