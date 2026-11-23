/* bucket-modal.js — the way out of the backlog's bucket popup.

   Opening is not this file's business. The page has two openers for that one popup: `+ Add bucket`, the small
   button in the page head (since Stage 97 its only control there), is a GET form onto this page as `?newbucket=1`, and
   a column's `⋮` is the same
   kind of form as `?bucket=<id>`. `app.js` answers either by fetching that URL and swapping only `#bucketModal`
   for the copy the server drew already open — which is why a reader with scripts switched off can still add,
   rename or empty a column: their click loads the page that markup describes. Closing after a send is the same
   arrangement the other way round: each of the popup's forms names `#bucketModal` as a companion of the board it
   posts to, so a write that landed comes back as a closed popup and one that was refused comes back open with
   its reason under the field it came from.

   What is left here is the three ways a reader gets out without posting anything, and the caret.

     [data-bucket-modal-close]   the × and the two Cancel buttons. Named apart from the task popup's
                                 [data-modal-close] on purpose: one click on this page would otherwise be
                                 read as closing two different popups by two different files.
     #bucketModal                the backdrop itself — a click that lands on it and not on its content
     #bucketName                 the name field, in both halves of the popup, and the one to write in first

   A popup the server opened on a fresh load is already visible when this file runs, and one that arrives
   mid-session is not a click this file is still handling but a node swapped in under the same id — so the
   keyboard is taken by watching for that swap, exactly as task-modal.js does for #taskModal. */
(function () {
  var MODAL = 'bucketModal';

  function el(id) { return document.getElementById(id); }

  function isOpen(modal) { return !!modal && modal.style.display === 'block'; }

  function show(visible) {
    var modal = el(MODAL);
    if (!modal) return;
    modal.style.display = visible ? 'block' : 'none';
    if (visible) {
      var field = el('bucketName');
      if (field && field.focus) field.focus({ preventScroll: true });
      return;
    }
    // A bookmark, a reload or the browser's Back would otherwise put this form back up on the screen.
    // Only this popup's own keys go: `?newbucket=1` is the add opener. On Other tasks, `?bucket=` opens a
    // column's menu; on Assigned tasks (Stage 124) it is the personal board's filter and must stay, like
    // `?project=`. `?edit=` and `?new=` belong to the card popup and `?show=` is the shared board's filter.
    var params = new URLSearchParams(window.location.search);
    var bucketIsOpener = window.location.pathname !== '/app/assigned-tasks';
    if (!params.has('newbucket') && !(bucketIsOpener && params.has('bucket'))) return;
    params.delete('newbucket');
    if (bucketIsOpener) params.delete('bucket');
    var query = params.toString();
    try {
      window.history.replaceState(null, '', window.location.pathname + (query ? '?' + query : ''));
    } catch (err) { /* a blocked history costs the address bar, nothing else */ }
  }

  document.addEventListener('click', function (e) {
    if (!e.target || !e.target.closest) return;
    if (e.target.closest('[data-bucket-modal-close]')) { show(false); return; }
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
