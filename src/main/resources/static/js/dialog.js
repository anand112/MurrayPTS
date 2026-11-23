/* dialog.js — the one behaviour every popup shares, loaded by appPage() on every app page.

   The per-popup scripts still decide what a popup holds, which field takes the caret, and what a close clears from
   the address bar. Everything a reader expects to be the same on every popup lives here instead:

     * opening moves focus inside, and Tab / Shift+Tab stay inside while it is open;
     * Escape, a click on the backdrop and the × all close it through one path — a press of the popup's own
       close marker, so each file's own close (and its address-bar cleanup) runs exactly once;
     * closing hands focus back to the control that opened it;
     * the page behind does not scroll while a popup is up;
     * a close plays a short exit (`.is-closing`, --dur-1) before the marker is pressed for real.

   A popup is any `.modal`; it is open when its inline `display` is `block`, which is what the server prints and
   what every per-popup script sets. A close marker is any element inside it carrying `data-modal-close` or a
   `data-<name>-modal-close` attribute. */
(function () {
  var FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), ' +
    'select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';
  var CLOSER = /^data-([a-z]+-)?modal-close$/;

  var current = null;      // the popup that is open, if any
  var opener = null;       // the control focused (or pressed) just before it opened
  var openerId = '';
  var lastOutside = null;  // the last control outside any popup the reader touched
  var releasing = false;   // a close-marker press this file is replaying after the exit animation

  function isOpen(modal) { return !!modal && modal.isConnected && modal.style.display === 'block'; }

  function openModal() {
    var all = document.querySelectorAll('.modal');
    for (var i = all.length - 1; i >= 0; i--) if (isOpen(all[i])) return all[i];
    return null;
  }

  function visible(node) {
    if (node.closest('[hidden]')) return false;
    return !!(node.offsetWidth || node.offsetHeight || node.getClientRects().length);
  }

  function focusables(modal) {
    return Array.prototype.filter.call(modal.querySelectorAll(FOCUSABLE), visible);
  }

  function isCloser(node) {
    if (!node || !node.attributes) return false;
    for (var i = 0; i < node.attributes.length; i++) if (CLOSER.test(node.attributes[i].name)) return true;
    return false;
  }

  function closerFrom(target) {
    var node = target;
    while (node && node.nodeType === 1) {
      if (isCloser(node)) return node;
      if (node.classList.contains('modal')) return null;
      node = node.parentNode;
    }
    return null;
  }

  function firstCloser(modal) {
    var nodes = modal.querySelectorAll('*');
    for (var i = 0; i < nodes.length; i++) if (isCloser(nodes[i])) return nodes[i];
    return null;
  }

  /** ARIA the server markup does not carry, filled in once per popup node. */
  function describe(modal) {
    var content = modal.querySelector('.modal-content') || modal;
    content.setAttribute('role', 'dialog');
    content.setAttribute('aria-modal', 'true');
    if (!content.hasAttribute('tabindex')) content.setAttribute('tabindex', '-1');
    var heading = content.querySelector('h1, h2, h3');
    if (heading) {
      if (!heading.id) heading.id = (modal.id || 'pts-dialog') + '-title';
      content.setAttribute('aria-labelledby', heading.id);
    }
    var x = content.querySelector('.close');
    if (x && x.tagName !== 'BUTTON') {
      x.setAttribute('role', 'button');
      x.setAttribute('tabindex', '0');
      x.setAttribute('aria-label', 'Close');
    }
    return content;
  }

  /** The field with an error first, then the first one a reader can write in, then the dialog itself. */
  function focusInside(modal) {
    if (modal.contains(document.activeElement) && document.activeElement !== modal) return;
    var bad = modal.querySelector('.field-err');
    var owner = bad && bad.parentNode ? bad.parentNode.querySelector('input:not([type="hidden"]), select, textarea') : null;
    var target = owner;
    if (!target) {
      var list = focusables(modal).filter(function (n) { return !n.classList.contains('close'); });
      for (var i = 0; i < list.length; i++) {
        if (/^(INPUT|SELECT|TEXTAREA)$/.test(list[i].tagName) && !list[i].readOnly) { target = list[i]; break; }
      }
      if (!target) target = list[0];
    }
    (target || describe(modal)).focus({ preventScroll: true });
  }

  function opened(modal) {
    current = modal;
    opener = lastOutside && lastOutside.isConnected ? lastOutside : null;
    openerId = opener && opener.id ? opener.id : '';
    describe(modal);
    document.body.classList.add('pts-modal-open');
    // The per-popup script may already have placed the caret on its own field; give it the current task.
    window.setTimeout(function () { if (current === modal && isOpen(modal)) focusInside(modal); }, 0);
  }

  function closed() {
    current = null;
    document.body.classList.remove('pts-modal-open');
    var here = document.activeElement;
    if (here && here !== document.body && here.isConnected && !here.closest('.modal')) return;
    var back = opener && opener.isConnected ? opener : (openerId ? document.getElementById(openerId) : null);
    opener = null;
    if (back && back.focus) back.focus({ preventScroll: true });
  }

  function sync() {
    var now = openModal();
    if (now === current) return;
    if (current && !now) { closed(); return; }
    if (current && now) { current = now; describe(now); window.setTimeout(function () { if (isOpen(now)) focusInside(now); }, 0); return; }
    opened(now);
  }

  /** Every way out ends here: play the exit, then press the popup's own close marker for real. */
  function requestClose(modal, marker) {
    marker = marker || firstCloser(modal);
    if (!marker || modal.classList.contains('is-closing')) return;
    var done = false;
    function finish() {
      if (done) return;
      done = true;
      modal.classList.remove('is-closing');
      releasing = true;
      try { marker.click(); } finally { releasing = false; }
      // A popup whose script never hid it (no handler for this marker) is hidden here, so no exit can hang.
      if (isOpen(modal)) modal.style.display = 'none';
      sync();
    }
    modal.classList.add('is-closing');
    modal.addEventListener('animationend', function end(e) {
      if (e.target !== modal) return;
      modal.removeEventListener('animationend', end);
      finish();
    });
    window.setTimeout(finish, 400);
  }

  document.addEventListener('focusin', function (e) {
    var t = e.target;
    if (t && t.closest && !t.closest('.modal')) lastOutside = t;
    // Focus that escaped the open popup (a script, a click on the page edge) is brought back in.
    if (current && isOpen(current) && t && !current.contains(t)) focusInside(current);
  });
  document.addEventListener('pointerdown', function (e) {
    var t = e.target && e.target.closest ? e.target.closest(FOCUSABLE) : null;
    if (t && !t.closest('.modal')) lastOutside = t;
  }, true);

  window.addEventListener('click', function (e) {
    if (releasing) return;
    var modal = openModal();
    if (!modal) return;
    var marker = e.target === modal ? null : closerFrom(e.target);
    if (e.target !== modal && !marker) return;
    e.preventDefault();
    e.stopPropagation();
    requestClose(modal, marker);
  }, true);

  window.addEventListener('keydown', function (e) {
    var modal = openModal();
    if (!modal) return;
    if (e.key === 'Escape' || e.key === 'Esc') {
      e.preventDefault();
      e.stopPropagation();
      requestClose(modal);
      return;
    }
    if ((e.key === 'Enter' || e.key === ' ') && e.target && e.target.classList &&
        e.target.classList.contains('close') && e.target.tagName !== 'BUTTON') {
      e.preventDefault();
      e.stopPropagation();
      requestClose(modal, e.target);
      return;
    }
    if (e.key !== 'Tab') return;
    var list = focusables(modal);
    if (!list.length) { e.preventDefault(); return; }
    var first = list[0];
    var last = list[list.length - 1];
    var here = document.activeElement;
    if (e.shiftKey && (here === first || !modal.contains(here) || here === modal.querySelector('[role="dialog"]'))) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && (here === last || !modal.contains(here))) {
      e.preventDefault();
      first.focus();
    }
  }, true);

  if (window.MutationObserver && document.body) {
    new MutationObserver(sync).observe(document.body, {
      childList: true, subtree: true, attributes: true, attributeFilter: ['style'],
    });
  }
  document.addEventListener('DOMContentLoaded', sync);
  sync();
})();
