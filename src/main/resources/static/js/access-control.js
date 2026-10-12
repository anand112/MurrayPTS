/* access-control.js — Access Control positions table: intercepts add/update/delete
   submits and swaps the table row via fetch, avoiding a full page reload. Extracted
   verbatim from AccessControl.kt in Stage 7b. Falls back to a native submit on error. */
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
