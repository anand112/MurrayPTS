/* notifications.js — the bell in the page head and the dropdown it opens.

   Markup contract (rendered by notificationBell() in Notifications.kt, on every authenticated page):
     [data-notif-toggle] — the bell button
     [data-notif-panel]  — the dropdown, server-rendered with `hidden`
     #notif              — the wrapper, so a click inside it is not an "outside" click

   There is no fetch here: the panel's contents are rendered with the page, and each row is a plain
   link to /app/notifications/{id}/open, which marks it read on the way to its target. That keeps the
   whole feature inside the app's no-inline-JS CSP and out of the CSRF question the rest of the app
   still has — opening an inbox item is a GET navigation, and marking the lot read is a form post.

   Like the card menu on the task board, the panel closes on outside click, on Escape, and on scroll,
   because it is absolutely positioned under the bell and would otherwise drift off it. */
(function () {
  var TOGGLE = '[data-notif-toggle]';
  var PANEL = '[data-notif-panel]';

  function panel() { return document.querySelector(PANEL); }

  function isOpen() {
    var p = panel();
    return !!p && !p.hidden;
  }

  function setOpen(open) {
    var p = panel();
    if (!p) return;
    p.hidden = !open;
    var t = document.querySelector(TOGGLE);
    if (t) t.setAttribute('aria-expanded', open ? 'true' : 'false');
  }

  document.addEventListener('click', function (e) {
    var toggle = e.target && e.target.closest ? e.target.closest(TOGGLE) : null;
    if (toggle) {
      setOpen(!isOpen());
      return;
    }
    // Anything inside the panel (a row, the "Mark all read" button) is not a dismissal — the row
    // navigates and the form submits on its own.
    if (e.target && e.target.closest && (e.target.closest(PANEL) || e.target.closest('#notif'))) return;
    if (isOpen()) setOpen(false);
  });

  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape' && isOpen()) {
      setOpen(false);
      var t = document.querySelector(TOGGLE);
      if (t) t.focus();
    }
  });

  window.addEventListener('resize', function () {
    if (isOpen()) setOpen(false);
  });
})();
