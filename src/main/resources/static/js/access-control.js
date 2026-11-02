/* access-control.js — Access control's two in-place behaviours. Extracted verbatim from AccessControl.kt in
   Stage 7b; the role popup's half is Stage 110 and follows lane-modal.js exactly.

   1. The positions table intercepts add/update/delete submits and swaps the table row via fetch, avoiding a
      full page reload. Falls back to a native submit on error.

   2. The way out of the **Roles & permissions** popup. Opening is not this file's business, and it could not
      be: the two openers live inside `#rolesPanel`, which a save swaps and is therefore replaced under the
      script's feet. Each is a GET form onto Access control itself — **+ New role** as `?newrole=1`, a row's
      Edit as `?roleedit=<id>` — and `app.js` answers either by following that URL and swapping `#roleModal`
      for the copy the server drew already open. That is why a reader with scripts switched off gets the
      identical popup from a plain click, and why a save that was refused is simply the same page again with
      its sentence under the name box. Closing after a write that landed is the same arrangement reversed:
      the popup's form names `#rolesPanel` as its block and `#roleModal` as its companion, so the table moves
      and the form shuts off one response.

     [data-role-modal-close]  the × and the Cancel buttons — named apart from the lane popup's
                              [data-lane-modal-close] and the card popup's [data-modal-close], because one
                              click would otherwise be read as closing two different popups by two files.
     #roleModal               the backdrop itself: a click that lands on it and not on its content
     #roleName                the name box, the one field a role write can refuse, and the one to write in first
*/
(function () {
  document.addEventListener('submit', function (e) {
    var f = e.target;
    if (!f || !f.getAttribute) return;
    var a = f.getAttribute('action') || '';
    if (a !== '/app/positions' && a !== '/app/positions/update' && a !== '/app/positions/delete') return;
    e.preventDefault();
    fetch(a, { method: 'POST', body: new FormData(f), headers: { 'X-Requested-With': 'fetch' } })
      .then(function (r) { return r.status === 204 ? null : r.text(); })
      .then(function (html) {
        var tr = f.closest('tr');
        if (a === '/app/positions/delete') {
          if (tr) tr.remove();
        } else if (a === '/app/positions') {
          var tb = document.getElementById('positions-tbody');
          if (tb && html) {
            var tmp = document.createElement('tbody');
            tmp.innerHTML = html;
            if (tmp.firstElementChild) tb.appendChild(tmp.firstElementChild);
          }
          f.reset();
        } else {
          if (tr && html) {
            var tmp2 = document.createElement('tbody');
            tmp2.innerHTML = html;
            if (tmp2.firstElementChild) tr.replaceWith(tmp2.firstElementChild);
          }
        }
      })
      .catch(function () { f.submit(); });
  });
})();

(function () {
  var MODAL = 'roleModal';

  function el(id) { return document.getElementById(id); }

  function isOpen(modal) { return !!modal && modal.style.display === 'block'; }

  function show(visible) {
    var modal = el(MODAL);
    if (!modal) return;
    modal.style.display = visible ? 'block' : 'none';
    if (visible) {
      var field = el('roleName');
      if (field && field.focus) field.focus({ preventScroll: true });
      return;
    }
    // A bookmark, a reload or the browser's Back would otherwise put this form back up on the screen. Only
    // this popup's own keys go — `newrole` is **+ New role** and `roleedit` a row's Edit; every other query on
    // the page (`pw`, `who`) is the banner's answer to a people write and none of this popup's business.
    var params = new URLSearchParams(window.location.search);
    if (!params.has('newrole') && !params.has('roleedit')) return;
    params.delete('newrole');
    params.delete('roleedit');
    var query = params.toString();
    try {
      window.history.replaceState(null, '', window.location.pathname + (query ? '?' + query : ''));
    } catch (err) { /* a blocked history costs the address bar, nothing else */ }
  }

  document.addEventListener('click', function (e) {
    if (!e.target || !e.target.closest) return;
    if (e.target.closest('[data-role-modal-close]')) { show(false); return; }
    if (e.target.id === MODAL) show(false);
  });

  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape' && isOpen(el(MODAL))) show(false);
  });

  var modalNode = el(MODAL);

  function noticeSwap() {
    var now = el(MODAL);
    if (now === modalNode) return;
    modalNode = now;
    // The swap came from an opener outside the popup, so nothing has the caret yet and the name box is the
    // whole point of the screen — including when it arrives already holding a refusal's sentence.
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
