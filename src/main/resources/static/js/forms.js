/* forms.js — what every form on an app page does the same way, loaded by appPage() after app.js.
   Nothing here changes what a form posts: field names, values and the server's own validation are untouched.

     * the browser's own checks (`required`, `type=email`, `minlength`) answer as a line under the field —
       the same `.field-err` the server prints — instead of a bubble, and the first one takes the caret;
     * an error the server printed is tied to its field (`aria-invalid`, `aria-describedby`), and on a page
       that came back with one, the first such field takes the caret;
     * a sent form's submit button is disabled until the answer arrives, so a double click is one send;
     * a full-page POST that comes back refused gets back what was typed (passwords excepted), from a copy
       kept for that one load in sessionStorage;
     * a save that lands says so: a short "Saved." line, unless the page already printed its own;
     * Ctrl/Cmd+Enter in a textarea sends its form, since plain Enter there is a new line. */
(function () {
  var KEEP_KEY = 'pts-form-sent';
  var NO_KEEP = /^(password|hidden|file|submit|button|checkbox|radio)$/;

  function fieldBox(input) { return input.closest('.field') || input.parentNode; }

  function link(input, message) {
    if (!message.id) message.id = (input.id || input.name || 'field') + '-err-' + Math.random().toString(36).slice(2, 7);
    input.setAttribute('aria-invalid', 'true');
    var ids = (input.getAttribute('aria-describedby') || '').split(/\s+/).filter(Boolean);
    if (ids.indexOf(message.id) < 0) ids.push(message.id);
    input.setAttribute('aria-describedby', ids.join(' '));
  }

  function ownerOf(err) {
    return err.parentNode ? err.parentNode.querySelector('input:not([type="hidden"]), select, textarea') : null;
  }

  function linkServerErrors(root) {
    var errs = (root || document).querySelectorAll('.field-err:not([data-client-err])');
    for (var i = 0; i < errs.length; i++) {
      var input = ownerOf(errs[i]);
      if (input && !input.hasAttribute('aria-invalid')) link(input, errs[i]);
      errs[i].setAttribute('role', 'alert');
    }
  }

  function clearClientError(input) {
    var box = fieldBox(input);
    var mine = box ? box.querySelector('[data-client-err]') : null;
    if (mine) mine.parentNode.removeChild(mine);
    if (!box || !box.querySelector('.field-err')) input.removeAttribute('aria-invalid');
  }

  /* ---- the browser's own checks, as inline lines ------------------------------------------------- */
  var firstInvalid = null;
  document.addEventListener('invalid', function (e) {
    var input = e.target;
    if (!input || !input.form) return;
    e.preventDefault();
    var box = fieldBox(input);
    var line = box.querySelector('[data-client-err]');
    if (!line) {
      line = document.createElement('p');
      line.className = 'field-err';
      line.setAttribute('data-client-err', 'true');
      line.setAttribute('role', 'alert');
      box.appendChild(line);
    }
    line.textContent = input.validationMessage;
    link(input, line);
    if (!firstInvalid) {
      firstInvalid = input;
      window.setTimeout(function () {
        if (firstInvalid && firstInvalid.focus) firstInvalid.focus();
        firstInvalid = null;
      }, 0);
    }
  }, true);

  function revalidate(e) {
    var input = e.target;
    if (!input || !input.form || !input.hasAttribute || !input.hasAttribute('aria-invalid')) return;
    if (input.validity && !input.validity.valid) return;
    clearClientError(input);
  }
  document.addEventListener('input', revalidate);
  document.addEventListener('change', revalidate);

  /* ---- sending: one press is one send ------------------------------------------------------------ */
  function buttons(form) {
    return Array.prototype.filter.call(form.querySelectorAll('button[type="submit"], button:not([type]), input[type="submit"]'),
      function (b) { return !b.disabled; });
  }

  function markSending(form) {
    var list = buttons(form);
    form.setAttribute('aria-busy', 'true');
    // After the event, not during it: a button disabled inside its own submit event leaves the data set,
    // and the server would never hear which one was pressed.
    window.setTimeout(function () {
      list.forEach(function (b) { b.disabled = true; b.setAttribute('data-pts-sending', 'true'); });
    }, 0);
    return list;
  }

  function release(form) {
    form.removeAttribute('aria-busy');
    var list = form.querySelectorAll('[data-pts-sending]');
    for (var i = 0; i < list.length; i++) { list[i].disabled = false; list[i].removeAttribute('data-pts-sending'); }
  }

  function isRealForm(form) { return !!form.querySelector('.submit'); }

  function snapshot(form) {
    var values = {};
    Array.prototype.forEach.call(form.elements, function (el) {
      if (!el.name || NO_KEEP.test(el.type) || el.disabled) return;
      if (el.multiple) return;
      values[el.name] = el.value;
    });
    return values;
  }

  document.addEventListener('submit', function (e) {
    var form = e.target;
    if (!form || (form.method || 'get').toLowerCase() !== 'post' || form.hasAttribute('data-pts-no-busy')) return;
    if (e.defaultPrevented) {
      // app.js is posting it in place. It says when the answer has landed (pts:sent), so the buttons
      // come back then — and, if the form is gone or its popup shut, that is a save that landed.
      if (!form.getAttribute('data-live-target')) return;
      markSending(form);
      return;
    }
    markSending(form);
    if (!isRealForm(form)) return;
    try {
      sessionStorage.setItem(KEEP_KEY, JSON.stringify({
        path: location.pathname, action: form.getAttribute('action') || '', values: snapshot(form),
      }));
    } catch (err) { /* storage blocked: the form simply comes back the way the server drew it */ }
  });

  document.addEventListener('pts:sent', function (e) {
    var form = (e.detail && e.detail.form) || e.target;
    if (!form || !form.getAttribute) return;
    release(form);
    if (!e.detail || !e.detail.ok || !isRealForm(form)) return;
    // A refusal swaps in a fresh copy of the popup, open, with its message — so "the sent form is gone" is not
    // enough: a save that landed leaves no popup open and no server error on the page it came back to.
    var inModal = !!form.closest('.modal');
    var openNow = document.querySelector('.modal[style*="block"]');
    if (inModal ? openNow : form.isConnected) return;
    if (document.querySelector('.field-err:not([data-client-err])')) return;
    announce('Saved.');
  });

  // The back-forward cache hands a page back exactly as it was left, disabled buttons included.
  window.addEventListener('pageshow', function () {
    var forms = document.querySelectorAll('form[aria-busy]');
    for (var i = 0; i < forms.length; i++) release(forms[i]);
  });

  /* ---- the answer ------------------------------------------------------------------------------- */
  var toast = null;
  var toastTimer = null;
  function announce(text) {
    if (!toast) {
      toast = document.createElement('div');
      toast.className = 'pts-toast';
      toast.setAttribute('role', 'status');
      toast.setAttribute('aria-live', 'polite');
      document.body.appendChild(toast);
    }
    toast.textContent = text;
    toast.classList.remove('on');
    void toast.offsetWidth;
    toast.classList.add('on');
    if (toastTimer) window.clearTimeout(toastTimer);
    toastTimer = window.setTimeout(function () { toast.classList.remove('on'); }, 3200);
  }

  function restoreAfterLoad() {
    var sent = null;
    try {
      var raw = sessionStorage.getItem(KEEP_KEY);
      if (raw) { sessionStorage.removeItem(KEEP_KEY); sent = JSON.parse(raw); }
    } catch (err) { sent = null; }
    var refused = document.querySelector('.field-err, .flash.err');
    if (!sent) return;
    if (!refused) {
      if (!document.querySelector('.flash.ok')) announce('Saved.');
      return;
    }
    var form = null;
    var forms = document.querySelectorAll('form[method="post"], form[method="POST"]');
    for (var i = 0; i < forms.length; i++) {
      if ((forms[i].getAttribute('action') || '') === sent.action) { form = forms[i]; break; }
    }
    if (!form) return;
    // A form the server drew back blank lost everything, pickers included; one it refilled keeps its own
    // answer, and only boxes it left empty take the typed copy.
    var blank = Array.prototype.every.call(form.elements, function (el) {
      return !el.name || el.tagName === 'SELECT' || NO_KEEP.test(el.type) || !el.value;
    });
    Object.keys(sent.values || {}).forEach(function (name) {
      var el = form.elements[name];
      if (!el || el.length > 1 && !el.tagName) return;
      if (NO_KEEP.test(el.type) || el.readOnly || el.disabled) return;
      if (el.tagName === 'SELECT') {
        if ((blank || el.selectedIndex <= 0) && sent.values[name]) el.value = sent.values[name];
      } else if (!el.value) {
        el.value = sent.values[name];
      }
    });
  }

  function focusFirstError() {
    var open = document.querySelector('.modal[style*="block"]');
    if (open) return; // dialog.js gives the open popup the caret, its first error included
    var errs = document.querySelectorAll('.field-err');
    for (var i = 0; i < errs.length; i++) {
      if (errs[i].closest('.modal')) continue;
      var input = ownerOf(errs[i]);
      if (input && input.focus) {
        input.focus({ preventScroll: true });
        input.scrollIntoView({ block: 'center' });
        return;
      }
    }
  }

  document.addEventListener('keydown', function (e) {
    if (e.key !== 'Enter' || !(e.ctrlKey || e.metaKey)) return;
    var t = e.target;
    if (!t || t.tagName !== 'TEXTAREA' || !t.form) return;
    e.preventDefault();
    t.form.requestSubmit ? t.form.requestSubmit() : t.form.submit();
  });

  if (window.MutationObserver && document.body) {
    new MutationObserver(function () { linkServerErrors(document); })
      .observe(document.body, { childList: true, subtree: true });
  }

  function ready() {
    restoreAfterLoad();
    linkServerErrors(document);
    focusFirstError();
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', ready); else ready();
})();
