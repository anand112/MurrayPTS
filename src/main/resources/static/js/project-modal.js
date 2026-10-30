/* project-modal.js — the create-project popup on the Projects board.
   Same shape as calendar-modal.js: the page is server-rendered, CSP allows only 'self'
   scripts, so behaviour is attached to data-* markers rather than inline handlers.

     [data-project-modal-open]           button that shows the modal
     [data-modal-close]                  the × and Cancel buttons; a click on the backdrop also closes
     #projectType option[data-suggest]   the next free number for that category, offered as the placeholder
     #projectType option[data-letter]    the category letter, for the lead, the placeholder and the hint
     #projectType[data-suggest-target]   the input those hints belong to
     #projectCode                        the project number. Since Stage 92 the box opens with the category's
                                         letter and dash already in it (`G-`), and that lead follows the
                                         category picker; the reader types only the number after it. The
                                         next free number is still offered as the placeholder, so the lead is
                                         a convenience and not a claim about what the number will be.
     #projectCode[data-prefix]           the lead the box is showing. The script updates it whenever it
                                         rewrites the lead, so a value that does not begin with it is the
                                         reader's own — a refused submit's code — and is left alone.

     #projectBucketPresets               one `.bucket-preset-row` per category, marked `[data-preset-for]`, each
     holding that category's Settings buckets as read-only names. Only the row for the chosen category is shown;
     the server paints the `.on` for the category the popup opens with, and [showPresetFor] moves it when the
     reader changes the picker. (Stage 104)
     #newProjectBucketList               the chips the reader added. Each is a `.bucket-pending-chip` carrying a
     hidden `new_bucket` and a `[data-bucket-drop]` ×; the server prints them back on a refused create, so this
     script never has to know what was already posted.
     #newProjectBucketName               the Add bucket box. It posts under the same `new_bucket` name as the
     chips, so a reader who types a name and presses Create rather than Add still gets that column.
     [data-bucket-add]                   the box's `+ Add bucket` button; Enter in the box does the same thing.
     #newProjectBucketSaid               the line under the box that says why a name did not become a chip.

   The create popup adds buckets since **Stage 104** and chooses none: the rows under the category's name are what
   the board opens with, and every chip is a column appended after them, this project's own. Nothing here writes
   the category's catalogue — there is no `also_type` tick on this form to click — and the name rules a typed
   bucket has to pass are the server's (too long, twice on the board, already one of the category's); this script
   only stops the obvious repeat in front of the reader rather than letting them build a chip list the create will
   refuse wholesale.

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
      // Stand on the input that was refused. `:not([type=hidden])` is not decoration: the Buckets field holds a
      // hidden `new_bucket` per chip (Stage 104), and a refused create whose first error is there would
      // otherwise try to focus a field no browser can show a caret in.
      var bad = modal.querySelector('.field-err');
      var focusTarget = bad ? bad.parentNode.querySelector('input:not([type=hidden]), select, textarea') : el('projectType');
      if (focusTarget) focusTarget.focus();
    }
  }

  function selectedOption(select) { return select.options[select.selectedIndex]; }

  function codeInput(select) { return el(select.getAttribute('data-suggest-target') || 'projectCode'); }

  /* Keep the category's letter and dash at the head of the number box, and its next free number in the
     grey placeholder. The lead is the one thing the reader never has to type (Stage 92); the number after
     it is theirs, and a category change carries it across untouched. */
  function refreshCodeHints() {
    var select = el('projectType');
    if (!select) return;
    var code = codeInput(select);
    if (!code) return;
    var option = selectedOption(select) || {};
    var letter = option.getAttribute ? (option.getAttribute('data-letter') || '') : '';
    var suggested = option.getAttribute ? (option.getAttribute('data-suggest') || '') : '';
    if (!letter) return;
    var prefix = letter + '-';
    var previous = code.getAttribute('data-prefix') || '';
    var value = code.value == null ? '' : code.value;
    if (!value.trim() || value === previous) {
      // Empty, or holding nothing but the lead it was given: put this category's lead in it.
      code.value = prefix;
    } else if (previous !== prefix) {
      // The category changed under a value already in the box. The initial it carried belonged to the
      // category that was showing; the number after the dash is the reader's and survives the change.
      var tail = value;
      if (/^[A-Za-z]/.test(tail)) {
        var dash = tail.indexOf('-');
        tail = dash === -1 ? tail.slice(1) : tail.slice(dash + 1);
      }
      code.value = prefix + tail;
    }
    // Where previous === prefix and the value is the reader's own, it is a refused submit's code coming back
    // exactly as typed — the message under it may be about its initial — so it is not rewritten.
    code.setAttribute('data-prefix', prefix);
    code.placeholder = suggested || (letter + '-01');
    var hint = el('projectCodeHint');
    if (hint) hint.textContent = letter + '-… — type the number after the dash; the letter follows the category.';
  }

  /* ---- the Add bucket line (Stage 104) ---- */

  /* Show the preset row the picker is on and hand it back. The server marked the `.on` for the category the
     popup opened with, so this is a no-op until the reader changes it — and with scripts off the row shown is
     still the true one for the category the form will post. */
  function showPresetFor(select) {
    var box = el('projectBucketPresets');
    if (!box) return null;
    var type = (selectedOption(select) || {}).value || '';
    var rows = box.querySelectorAll('.bucket-preset-row');
    var wanted = null;
    for (var i = 0; i < rows.length; i++) {
      var on = rows[i].getAttribute('data-preset-for') === type;
      rows[i].className = 'bucket-preset-row' + (on ? ' on' : '');
      if (on) wanted = rows[i];
    }
    return wanted;
  }

  /* The names the shown row carries. The "none listed yet" line is a `.bucket-preset.none`, not a bucket, so it
     is skipped rather than becoming a name the reader cannot add. */
  function presetNames(row) {
    var out = [];
    if (!row) return out;
    var spans = row.querySelectorAll('.bucket-preset');
    for (var i = 0; i < spans.length; i++) {
      if ((' ' + spans[i].className + ' ').indexOf(' none ') !== -1) continue;
      out.push((spans[i].textContent || '').trim().toLowerCase());
    }
    return out;
  }

  function pendingNames() {
    var out = [];
    var chips = document.querySelectorAll('#newProjectBucketList .bucket-pending-chip');
    for (var i = 0; i < chips.length; i++) {
      out.push((chips[i].getAttribute('data-bucket-pending') || '').toLowerCase());
    }
    return out;
  }

  function say(message) {
    var line = el('newProjectBucketSaid');
    if (line) line.textContent = message || '';
  }

  /* Turn the box's name into a chip. The two refusals here are the two the reader would otherwise only hear
     about after the whole create came back rejected: a name the category already offers, and a name already on
     this board twice. Everything else — length, characters, what the board really ends up with — is the
     service's rule, printed under this field the same way. */
  function addBucket() {
    var box = el('newProjectBucketName');
    var list = el('newProjectBucketList');
    if (!box || !list) return;
    var name = (box.value || '').trim();
    if (!name) { say(''); return; }
    var lower = name.toLowerCase();
    var select = el('projectType');
    if (select && presetNames(showPresetFor(select)).indexOf(lower) !== -1) {
      box.value = '';
      say(name + ' is already one of this category\'s buckets — the board gets it from Settings.');
      return;
    }
    if (pendingNames().indexOf(lower) !== -1) {
      box.value = '';
      say(name + ' is already on this board.');
      return;
    }
    var chip = document.createElement('span');
    chip.className = 'bucket-pending-chip';
    chip.setAttribute('data-bucket-pending', name);
    var hidden = document.createElement('input');
    hidden.type = 'hidden';
    hidden.name = 'new_bucket';
    hidden.value = name;
    chip.appendChild(hidden);
    chip.appendChild(document.createTextNode(name));
    var drop = document.createElement('button');
    drop.type = 'button';
    drop.className = 'bucket-pending-drop';
    drop.setAttribute('data-bucket-drop', 'true');
    drop.setAttribute('aria-label', 'Remove ' + name + ' from this board');
    drop.appendChild(document.createTextNode('×'));
    chip.appendChild(drop);
    list.appendChild(chip);
    box.value = '';
    say('');
    box.focus();
  }

  document.addEventListener('click', function (e) {
    if (!e.target || !e.target.closest) return;
    if (e.target.closest('[data-bucket-add]')) { addBucket(); return; }
    var drop = e.target.closest('[data-bucket-drop]');
    if (drop) {
      // The × takes the chip, and with it the hidden `new_bucket` the chip carried, because they are the same
      // node. The reader keeps the name they were refused nothing here — they removed a column they added.
      if (drop.parentNode && drop.parentNode.parentNode) drop.parentNode.parentNode.removeChild(drop.parentNode);
      say('');
      return;
    }
    if (e.target.closest('[data-project-modal-open]')) { show(true); return; }
    if (e.target.closest('[data-modal-close]')) { show(false); return; }
    if (e.target.id === MODAL) show(false);
  });

  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') { show(false); return; }
    // Enter inside the Add bucket box is a press of the button beside it. Left alone it would submit the whole
    // create form — and a create whose board is refused on a name the reader meant to add twice would be a
    // popup that came back with one column missing and no way to tell them so.
    if (e.key === 'Enter' && e.target && e.target.id === 'newProjectBucketName') {
      e.preventDefault();
      addBucket();
    }
  });

  document.addEventListener('change', function (e) {
    if (e.target && e.target.id === 'projectType') {
      refreshCodeHints();
      // The list of columns this board opens with belongs to the category, so it changes with it — and the
      // sentence under the box was answering the category that was showing, not this one.
      showPresetFor(e.target);
      say('');
    }
  });

  document.addEventListener('DOMContentLoaded', function () { refreshCodeHints(); });
})();
