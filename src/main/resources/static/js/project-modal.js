/* project-modal.js — the create-project popup on the Projects board.
   Same shape as calendar-modal.js: the page is server-rendered, CSP allows only 'self'
   scripts, so behaviour is attached to data-* markers rather than inline handlers.

     [data-project-modal-open]           button that shows the modal
     [data-modal-close]                  the × and Cancel buttons; a click on the backdrop also closes
     #projectType option[data-suggest]   the next free number for that category
     #projectType option[data-letter]    the category letter, for the placeholder and the hint
     #projectType[data-suggest-target]   the input the suggestions belong to
     #projectCode                        the typed number; data-autofilled marks a suggestion the
                                         user has never touched, so only those may be replaced

   The modal is not a separate route, so a rejected submit comes back as the same page with the
   modal already open and the values re-filled; nothing here needs to know about errors. */
(function () {
  var MODAL = 'projectModal';

  function el(id) { return document.getElementById(id); }

  function show(visible) {
    var modal = el(MODAL);
    if (!modal) return;
    modal.style.display = visible ? 'block' : 'none';
    if (visible) {
      var bad = modal.querySelector('.field-err');
      var focusTarget = bad ? bad.parentNode.querySelector('input, select') : el('projectType');
      if (focusTarget) focusTarget.focus();
    }
  }

  function selectedOption(select) { return select.options[select.selectedIndex]; }

  function codeInput(select) { return el(select.getAttribute('data-suggest-target') || 'projectCode'); }

  /* Keep the offered number in step with the chosen category, but never overtype a number the
     user wrote themselves — a hand-typed code loses data-autofilled as soon as it is edited. */
  function refreshCode() {
    var select = el('projectType');
    if (!select) return;
    var code = codeInput(select);
    if (!code) return;
    var option = selectedOption(select) || {};
    var letter = option.getAttribute ? (option.getAttribute('data-letter') || '') : '';
    var suggested = option.getAttribute ? (option.getAttribute('data-suggest') || '') : '';
    var autofilled = code.getAttribute('data-autofilled') === 'true';
    if (autofilled || code.value.trim() === '') {
      code.value = suggested;
      code.setAttribute('data-autofilled', 'true');
    }
    if (letter) {
      code.placeholder = letter + '-01';
      var hint = el('projectCodeHint');
      if (hint) hint.textContent = letter + '-… — the number is yours to change.';
    }
  }

  document.addEventListener('click', function (e) {
    if (!e.target || !e.target.closest) return;
    if (e.target.closest('[data-project-modal-open]')) { show(true); return; }
    if (e.target.closest('[data-modal-close]')) { show(false); return; }
    if (e.target.id === MODAL) show(false);
  });

  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') show(false);
  });

  document.addEventListener('change', function (e) {
    if (e.target && e.target.id === 'projectType') refreshCode();
  });

  document.addEventListener('input', function (e) {
    if (e.target && e.target.id === 'projectCode') e.target.removeAttribute('data-autofilled');
  });

  document.addEventListener('DOMContentLoaded', refreshCode);
})();
