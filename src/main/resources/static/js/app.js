/* app.js — shared, delegated behaviours for every authenticated app page. Loaded by
   appPage() alongside theme.js in Stage 7b. These replace the inline event-handler
   attributes (onchange="…", onclick="return confirm(…)") that the legacy views emitted,
   which a strict Content-Security-Policy (script-src without 'unsafe-inline') would block.

   Markup contract:
     [data-autosubmit]  — a form control that submits its own form on `change`.
     [data-confirm="…"] — an element that asks for confirmation on `click`; the message
                          lives in the attribute so it can still be server-rendered.

   Delegation on `document` also covers rows inserted later via fetch (e.g. the
   positions table), which per-element inline handlers could not. */
(function () {
  document.addEventListener('change', function (e) {
    var el = e.target;
    if (el && el.matches && el.matches('[data-autosubmit]') && el.form) {
      el.form.requestSubmit();
    }
  });
  document.addEventListener('click', function (e) {
    var el = e.target && e.target.closest ? e.target.closest('[data-confirm]') : null;
    if (!el) return;
    var msg = el.getAttribute('data-confirm');
    if (msg && !window.confirm(msg)) {
      e.preventDefault();
      e.stopPropagation();
    }
  });
})();
