/* settings-accounts.js — Settings page workload detail: toggles the sub-rows when a
   count button is clicked. Extracted verbatim from Settings.kt accountsScript() in Stage 7b. */
(function () {
  document.querySelectorAll('.count-btn').forEach(function (btn) {
    btn.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      var emp = btn.getAttribute('data-emp');
      var kind = btn.getAttribute('data-kind');
      var sub = document.querySelector('tr.acct-detail[data-emp="' + emp + '"]');
      if (!sub) return;
      var pane = sub.querySelector('.acct-pane[data-pane="' + kind + '"]');
      var wasOpen = !sub.hidden && !!pane && !pane.hidden;
      var row = btn.closest('tr');
      if (row) {
        row.querySelectorAll('.count-btn').forEach(function (b) { b.classList.remove('open'); });
      }
      sub.querySelectorAll('.acct-pane').forEach(function (p) { p.hidden = true; });
      if (wasOpen) {
        sub.hidden = true;
      } else {
        sub.hidden = false;
        if (pane) pane.hidden = false;
        btn.classList.add('open');
      }
    });
  });
})();
