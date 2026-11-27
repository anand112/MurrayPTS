/* app.js — shared, delegated behaviours for every authenticated app page. Loaded by
   appPage() alongside theme.js in Stage 7b. These replace the inline event-handler
   attributes (onchange="…", onclick="return confirm(…)") that the legacy views emitted,
   which a strict Content-Security-Policy (script-src without 'unsafe-inline') would block.

   Markup contract:
     [data-autosubmit]  — a form control that re-sends its own form on `change`.
     [data-livefilter]  — a text input that re-sends its own form once typing settles, so a search box
                          filters as you write without one request per keystroke.
     [data-live-region] — the block those two controls exist to re-render. When the page has one, a send
                          is fetched and swapped into place **instead of navigating**, which is the only
                          way the reader keeps the caret, the focus and the place they were scrolled to
                          while typing — the toolbar holding the box is never replaced. The same form
                          without a live region, after a failed refresh, or with JavaScript switched off
                          entirely, is just an ordinary GET form: the browser loads the page the markup
                          describes, and the restore block further down puts those three things back.
                          **Everything inside one must be bound on `document`.** The swap hands back a node parsed
                          out of the response, and a listener belongs to the node it was put on, not to the HTML —
                          so any script that collects this block's elements at page load (`querySelectorAll` +
                          `addEventListener`) works until the first swap and is dead afterwards. That is how
                          `kanban.js` lost the Other tasks board's drag in Stage 93, and why Stage 94 delegated it.
     [data-confirm="…"] — an element that asks for confirmation on `click`; the message
                          lives in the attribute so it can still be server-rendered.
     A pressed submit button carries its own `name`/`value` on the send, on the query string for a GET
                          and in the body for a post — the part of a form's data set a plain `FormData`
                          leaves out. That is what lets one form hold several *different* sends: the
                          forecast sheet's ‹ and › month controls are two buttons of the same filter form
                          whose only difference is the month they name. A send with no submitter keeps the
                          values the controls already hold.
     [data-live-target="#id"] — a form that re-renders ONE block of the page rather than a whole board:
                          it posts to its own action, and the element whose id the selector names is
                          swapped for the copy in the response (a card's step list, `#task-steps-12`).
                          The response is the whole page because the only renderer is the route that drew
                          this page, and only that one block is touched, so nothing else moves — no
                          reload, no jump, no lost scroll. Focus inside the block is handed back to the
                          new copy by element id, so a list can be ticked or written one line at a time
                          without the mouse. A block that cannot be swapped — it is no longer on the
                          page, or a send has already failed — leaves the browser to post the form the
                          ordinary way, which reloads the page onto exactly the same markup.
     [data-live-extra="#id,#id2"] — with a block form **or a filter form**: other blocks the same send
                          changes, swapped from the same response, so a number that describes the block
                          cannot stay behind it. A companion that is not on this page is skipped, not a failure.
     [data-live-url]    — on a GET block form whose block **is** the board: its send is a view change and
                          not a popup, so the fetched URL is written into history the way a filter form's
                          is. Without it the address bar would keep a filter the press has just dropped.
     [data-live-focus]  — on a block form: the id that should hold the focus once the new copy is in
                          place. `swapBlock` hands the focus back to whichever control had it, which is
                          right for a tick and wrong for a write that removes or empties the control the
                          reader pressed — a delete, whose × is gone from the response, and an add, whose
                          box the swap has just cleared for the next line. Naming the destination is what
                          lets a list be written one task at a time without the page restarting.
     [data-confirm]     — a control that asks the reader first, and is not pressed when they say no.

   Delegation on `document` also covers rows inserted later via fetch (the board on every refresh), which
   per-element inline handlers could not. */
(function () {
  // `app.css` scopes the off-canvas drawer to `html.js` (Stage 119), so the class goes on before the
  // first paint: with scripting off the hamburger would open nothing, and the sidebar stays in the
  // flow instead — a smaller shell, but one that still navigates.
  document.documentElement.classList.add('js');

  // The remembered desktop rail goes on in the same breath, for the same reason: a reader who chose the narrow
  // icon column must not see the full sidebar for a frame on every page load. Stage 130's legacy `hidden` value
  // means the same arrangement now, so an existing browser moves to the rail without losing its choice.
  try {
    var rememberedSidebar = localStorage.getItem('pts-sidebar');
    if (rememberedSidebar === 'rail' || rememberedSidebar === 'hidden') document.body.classList.add('sidebar-rail');
  } catch (err) {
    // Storage blocked (private window, cleared site data) costs the memory, not the control: the toggle still
    // works on this page, and every navigation link remains available in either arrangement.
  }

  function liveBox() { return document.querySelector('[data-livefilter]'); }
  function liveRegion() { return document.querySelector('[data-live-region]'); }

  function trim(value) { return value.replace(/^\s+|\s+$/g, ''); }

  // A GET form that exists to re-render a board: one live text box and/or any number of pickers.
  function isFilterForm(form) {
    if (!form || !form.querySelector) return false;
    if ((form.method || 'get').toLowerCase() !== 'get') return false;
    return !!(form.querySelector('[data-livefilter]') || form.querySelector('[data-autosubmit]'));
  }

  /* ------------------------------------------------------------------ *
   *  In-place refresh — the search never restarts the page
   * ------------------------------------------------------------------ */

  var generation = 0;         // responses may arrive out of order; only the newest one may land
  var refreshBroken = false;  // one failed refresh and this page navigates, as its markup always allowed

  function boardUrl(form, submitter) {
    var params = new URLSearchParams(new FormData(form));
    // A form's own submit button is not part of its FormData, and the month controls on the forecast
    // sheet *are* submit buttons: whichever one was pressed is the answer to "which month", so the
    // send that has no submitter keeps the month already in the address and the one that does carries
    // the value the reader chose.
    if (submitter && submitter.name) params.set(submitter.name, submitter.value);
    // Trim the term the way the route trims it for matching, so the address bar reads `?q=haul`
    // rather than `?q=haul+` after a pause mid-phrase.
    if (params.has('q')) params.set('q', trim(params.get('q')));
    var url = new URL(form.getAttribute('action') || location.pathname, location.href);
    url.search = params.toString();
    return url;
  }

  /* Settings draws TWO live regions — the team sheet's roster and the bucket catalogue — so a filter form
     has to refresh the one it belongs to, not whichever region happens to come first in the document. The
     bucket category picker sits inside the card that holds its list, so climbing to that card finds the
     bucket region; the team toolbar stands above its board with no such ancestor, so it falls back to the
     page's first region exactly as every single-region page always did.

     This is not cosmetic: a category picker that swapped the roster left the bucket list, the hidden
     `lanes` field and the *"Adds to … only"* label on the category the reader had just left, and the next
     **+ Add bucket** wrote to that old category while the select showed the new one (Stage 82 fix). */
  function regionFor(form) {
    var node = form;
    while (node && node !== document.body) {
      if (node.querySelector) {
        var found = node.querySelector('[data-live-region]');
        if (found) return found;
      }
      node = node.parentElement;
    }
    return liveRegion();
  }

  /** Replace `region` with the same board in `html`; false when the response is not a board. */
  function showResults(html, region) {
    var parsed = new DOMParser().parseFromString(html, 'text/html');
    // Match by id when the region has one: a two-region page's response carries both, and the first
    // region in it may be the other board.
    var next = (region && region.id ? parsed.getElementById(region.id) : null) ||
               parsed.querySelector('[data-live-region]');
    region = region || liveRegion();
    if (!next || !region || !region.parentNode) return false;
    region.parentNode.replaceChild(document.importNode(next, true), region);
    return true;
  }

  function fallBack(form, submitter) {
    refreshBroken = true;
    // The submitter rides along, or the fall-back send loses the very value that made this one
    // correct: a month control that cannot fetch is not allowed to quietly load a different month.
    form.requestSubmit(submitter && submitter.form === form ? submitter : undefined);
  }

  function refresh(form, submitter) {
    var url = boardUrl(form, submitter);
    var region = regionFor(form);
    var token = ++generation;
    document.body.classList.add('pts-refreshing');
    window.fetch(url.href, { credentials: 'same-origin' })
      .then(function (response) {
        if (!response.ok) throw new Error('board load returned ' + response.status);
        return response.text();
      })
      .then(function (html) {
        if (token !== generation) return;        // a newer query owns the board by now
        if (!showResults(html, region)) throw new Error('the response carried no board region');
        // A filter form may name companions too, exactly as a block form does: the lesson sheet's
        // "3 active, 0 archived" line sits on the toolbar beside the pickers that change it, and a
        // count left on the whole sheet while the board under it shows five rows is the same
        // half-updated page `data-live-extra` exists to prevent. Ones the form does not name are
        // untouched, so every page written before this is unaffected.
        extraIds(form).forEach(function (extra) { swapBlock(html, extra); });
        // replaceState, not pushState: a back stack of half-typed phrases would trap the reader,
        // while the URL still stays shareable, bookmarkable and reloadable.
        window.history.replaceState(null, '', url.href);
      })
      .catch(function () { if (token === generation) fallBack(form, submitter); })
      .then(function () { if (token === generation) document.body.classList.remove('pts-refreshing'); });
  }

  /* ------------------------------------------------------------------ *
   *  One block at a time — a card's step list
   * ------------------------------------------------------------------ */

  /* Ticking a step, adding one or removing one writes a single row and comes back as the same page.
     Swapping only `#task-steps-<task>` is what keeps the editor still: the popup stays open on the card
     being worked on, the add box keeps its focus so a list can be written one line at a time, and — the
     reason this exists at all — nothing about the page restarts. The card behind the popup carries that
     list's percentage, so it rides along as a companion. */
  function targetId(form) {
    var selector = trim((form.getAttribute && form.getAttribute('data-live-target')) || '');
    return selector.charAt(0) === '#' ? selector.slice(1) : '';
  }

  /* A block-form may name companions in `data-live-extra`: other ids on the page that the same send
     changes, so they land with the block the reader acted on rather than on the next reload. Ticking a
     step moves that card's step list *and* the number the card prints below it *and* the project page's
     progress bar, and one response carries all three — a bar or a chip left on the old percentage next to
     a box that has already moved is exactly the half-updated page this mechanism exists to avoid. A
     companion that is not on this page (the general Assigned tasks board has no project bar, and a popup
     opened from a page with no card behind it has no card body) is skipped, and the send is not a
     failure. */
  function extraIds(form) {
    var ids = [];
    trim((form.getAttribute && form.getAttribute('data-live-extra')) || '').split(',').forEach(function (part) {
      var p = trim(part);
      if (p.charAt(0) === '#') ids.push(p.slice(1));
    });
    return ids;
  }

  // One counter per block, not one for the page: two cards can be in flight at once, and a slow
  // answer about `#task-steps-4` must still land after a fast one about `#task-steps-9`.
  var targetGenerations = {};

  /** Replace the block `id` with the one in `html`; false when neither side has it. */
  function swapBlock(html, id) {
    var parsed = new DOMParser().parseFromString(html, 'text/html');
    var next = parsed.getElementById(id);
    var block = document.getElementById(id);
    if (!next || !block || !block.parentNode) return false;
    // Focus is not part of either node, so it is remembered by id and given back to the new copy.
    // A checkbox and the add box both carry an id for exactly this reason.
    var active = document.activeElement;
    var keep = active && active.id && block.contains(active) ? active.id : '';
    block.parentNode.replaceChild(document.importNode(next, true), block);
    if (keep) {
      var again = document.getElementById(keep);
      if (again && again.focus) again.focus({ preventScroll: true });
    }
    return true;
  }

  function sendToBlock(form, submitter) {
    var id = targetId(form);
    var token = (targetGenerations[id] || 0) + 1;
    targetGenerations[id] = token;
    var block = document.getElementById(id);
    if (block) block.classList.add('pts-busy');
    var method = ((form.method || 'get') + '').toUpperCase();
    var url = method === 'GET' ? boardUrl(form, submitter).href : (form.getAttribute('action') || location.pathname);
    var init = { method: method, credentials: 'same-origin' };
    if (method !== 'GET') init.body = new FormData(form);
    // A pressed button is not in its form's data set, so the one control that only a click can name has
    // to be added by hand — on the query string for a GET, which `boardUrl` already did, and on the body
    // for a post, which is what this line is for.
    if (submitter && submitter.name && init.body) init.body.set(submitter.name, submitter.value);
    window.fetch(url, init)
      .then(function (response) {
        if (!response.ok) throw new Error('block load returned ' + response.status);
        return response.text();
      })
      .then(function (html) {
        if (token !== targetGenerations[id]) return;
        if (!swapBlock(html, id)) throw new Error('the response carried no #' + id);
        // Companions come from the same response and the same token, so the block and the numbers that
        // describe it are never from two different moments. Missing ones are simply not swapped.
        extraIds(form).forEach(function (extra) { swapBlock(html, extra); });
        // Last, so that it wins over the focus `swapBlock` already handed back to the control the reader
        // pressed: a form that names a destination means that control is not where the reader is going.
        // `preventScroll`, as everywhere else here — a swap that jumped the page to the focused control
        // would be a reload by another name.
        var wantFocus = trim(form.getAttribute('data-live-focus') || '');
        if (wantFocus.charAt(0) === '#') {
          var focusNext = document.getElementById(wantFocus.slice(1));
          if (focusNext && focusNext.focus) focusNext.focus({ preventScroll: true });
        }
        // A block that is the board itself is not a popup but a view: the reader's next reload has to
        // land on what they are looking at, so the URL the response was fetched for takes the place of
        // the one they came from — a filter it dropped does not survive in the address bar.
        if (method === 'GET' && form.hasAttribute('data-live-url')) {
          window.history.replaceState(null, '', url);
        }
      })
      .catch(function () { if (token === targetGenerations[id]) fallBack(form, submitter); })
      .then(function () {
        if (token !== targetGenerations[id]) return;
        var again = document.getElementById(id);
        if (again) again.classList.remove('pts-busy');
      });
  }

  /* ------------------------------------------------------------------ *
   *  Navigation repair — for every send that is not swapped in place
   * ------------------------------------------------------------------ */

  /* A plain reload loses three things the reader expects to keep: the focus, because `autofocus` is not
     honoured on every kind of load; the caret, because when it *is* applied it parks it at position 0, so
     typing the rest of a phrase inserts it in front of what is already written; and the scroll position,
     because the page jumps back to its top. `rememberPosition` leaves {path, y, focus} in sessionStorage
     just before such a form is sent and the next load puts all three back. Keyed on the path, because a
     stale jump onto an unrelated page is worse than no jump — and consumed once, because a bookmark should
     not inherit somebody else's scroll bar. */
  var POSITION_KEY = 'pts-keep-position';

  function rememberPosition() {
    var box = liveBox();
    // The debounced send, Enter and the Search button all arrive with the box focused — which is the only
    // case worth handing back, and it counts even when the reader has just deleted the last character,
    // because an unfocused box means the next thing they type goes nowhere.
    var keepFocus = !!(box && document.activeElement === box);
    try {
      sessionStorage.setItem(POSITION_KEY, JSON.stringify({ path: location.pathname, y: window.scrollY, focus: keepFocus }));
    } catch (err) {
      // Storage blocked (private window, cleared site data) costs the focus, the caret and the scroll,
      // nothing more — the filter itself still works.
    }
  }

  var saved = null;
  try {
    var raw = sessionStorage.getItem(POSITION_KEY);
    if (raw) {
      sessionStorage.removeItem(POSITION_KEY);
      saved = JSON.parse(raw);
      if (!saved || saved.path !== location.pathname) saved = null;
    }
  } catch (err) {
    saved = null;
  }

  var touched = false; // the reader has clicked: focus and caret are theirs from here

  function markTouched() { touched = true; }

  function applySavedScroll() {
    if (!saved || !saved.y || touched) return;
    window.scrollTo(0, saved.y); // clamped, so a board that filtered down to two rows stops where it
                                 // stops instead of showing a screenful of blank space.
  }

  function restoreFocus(box) {
    if (touched || !saved || !saved.focus || !box) return;
    if (document.activeElement !== box) box.focus({ preventScroll: true });
  }

  function placeCaretAtEnd(box) {
    if (touched || !box || !box.value) return;
    // Only the broken case — a caret sitting in front of the text, with nothing selected. Repeat-safe:
    // once the caret is at the end the test no longer matches, so a later autofocus cannot undo it and a
    // reader who Tabbed here deliberately keeps the choice they made after this one.
    if (box.selectionStart === 0 && box.selectionEnd === 0) {
      var end = box.value.length;
      box.setSelectionRange(end, end);
    }
  }

  // `pointerdown`, deliberately, and not `keydown`: a keystroke that arrives before the box has been
  // handed back never reaches it, and counting it as "the reader placed the caret" would skip the repair.
  document.addEventListener('pointerdown', markTouched, true);
  document.addEventListener('focusin', function (e) {
    var el = e.target;
    if (!el || !el.matches || !el.matches('[data-livefilter]')) return;
    placeCaretAtEnd(el);
    // Focusing scrolls the box into view, which lands on top of anything restored before it, so the
    // scroll gets a second pass in a later task — after the browser has finished moving.
    window.setTimeout(applySavedScroll, 0);
  });
  window.addEventListener('load', function () {
    restoreFocus(liveBox());
    placeCaretAtEnd(liveBox());
    applySavedScroll();
    // And once more after the current task, because the browser applies its own autofocus (and its own
    // scroll-into-view) after the load event, on top of everything done above.
    window.setTimeout(function () {
      var box = liveBox();
      restoreFocus(box);
      applySavedScroll();
      window.setTimeout(function () { placeCaretAtEnd(liveBox()); }, 0);
    }, 0);
  });

  /* One listener covers every way a filter form is sent — the debounced timer, a picker's change, the
     Search button, and Enter in the text field, which submits by itself and never reaches the handlers.
     With a live region on the page it is a fetch; without one it is the navigation the markup describes. */
  document.addEventListener('submit', function (e) {
    var form = e.target;
    var submitter = e.submitter || null;   // the button that was pressed, when one was
    if (form.getAttribute && form.getAttribute('data-live-target')) {
      // A block-form: swap that one block if it is still on the page and nothing has failed yet,
      // otherwise let the browser post it and load the page the markup describes.
      if (!refreshBroken && window.fetch && window.DOMParser && document.getElementById(targetId(form))) {
        e.preventDefault();
        sendToBlock(form, submitter);
      }
      return;
    }
    if (!isFilterForm(form)) return;
    if (!refreshBroken && liveRegion() && window.fetch && window.DOMParser) {
      e.preventDefault();
      refresh(form, submitter);
      return;
    }
    rememberPosition();
  });

  document.addEventListener('change', function (e) {
    var el = e.target;
    if (el && el.matches && el.matches('[data-autosubmit]') && el.form) {
      el.form.requestSubmit();
    }
  });
  // One timer, because a page has one live search box: each keystroke replaces the pending send, so only
  // the last one fires. requestSubmit (not submit) keeps the hidden fields in the form — which is how the
  // category tab and the sort pickers ride along with the text.
  var pending = null;
  document.addEventListener('input', function (e) {
    var el = e.target;
    if (!el || !el.matches || !el.matches('[data-livefilter]') || !el.form) return;
    // Mid-composition the text is not yet what the reader means (an input method is still working),
    // so wait for the committed input event instead of searching a half-built word.
    if (e.isComposing) return;
    if (pending) window.clearTimeout(pending);
    pending = window.setTimeout(function () {
      pending = null;
      // Type a character and delete it again and the box reads exactly what the board already shows;
      // asking the server for that again would be a request to change nothing.
      if (el.name && new URLSearchParams(location.search).get(el.name) === trim(el.value)) return;
      el.form.requestSubmit();
    }, 400);
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

  /* A plain Projects cell is a shortcut to the same project its ID and name links open. Delegation keeps
     it alive after a filter or pin swaps the sheet. Real links and controls keep their own behaviour, and
     a modified press or a selection is left to the browser rather than turned into navigation. */
  function openProjectRow(e) {
    if (e.defaultPrevented || e.button !== 0 || e.ctrlKey || e.metaKey || e.shiftKey || e.altKey) return;
    var target = e.target;
    if (!target || !target.closest) return;
    var row = target.closest('table.projects-table tbody tr');
    if (!row || target.closest('a, button, form, input, select, textarea, label')) return;
    var selection = window.getSelection ? window.getSelection() : null;
    if (selection && !selection.isCollapsed) return;
    var link = row.querySelector('a.row-link[href]');
    if (link && link.href) window.location.href = link.href;
  }
  document.addEventListener('click', openProjectRow);

  /* ------------------------------------------------------------------ *
   *  Small-screen navigation — the hamburger and the drawer's own close button
   * ------------------------------------------------------------------ */

  /* Below 900px `app.css` turns the sidebar into an off-canvas drawer and prints `#navToggle`; above
     it the button is display:none and the sidebar is simply the sidebar. The whole state is one class
     on <body> — `nav-open` — so the slide is a CSS transition and the script only decides when it is
     on. The drawer is full width there (the breakpoint's rule), the backdrop closes it, Escape closes
     it, and since Stage 128 the panel's own top-right `#navClose` button closes it the same way, so
     there is one close path, not four variants of one. A keyboard reader gets focus moved into the
     drawer on opening and back to the button on closing — from whichever control ended the session. A
     window that grows past the breakpoint closes it quietly, without moving focus away from the desktop
     layout the reader has just been handed. */
  var navToggle = document.getElementById('navToggle');
  var navDrawer = document.getElementById('appSidebar');
  var navBackdrop = document.getElementById('navBackdrop');
  // The drawer's own close button (Stage 128), printed inside #appSidebar and shown there only while the
  // drawer is open. It is the same action as the backdrop, Escape and the hamburger, so it calls the same
  // function — including the focus move back to the hamburger, which is where a reader who just closed the
  // menu is standing. It is deliberately *not* the drawer's first focusable element: opening moves focus to
  // the logo link, and a close button in that seat would greet a keyboard reader with an offer to leave.
  var navClose = document.getElementById('navClose');
  // The bottom bar's *Menu* tab (Stage 134): the same disclosure printed where a thumb already rests, at the
  // other end of the screen from the hamburger. It carries no state of its own and no second copy of this code —
  // it calls the same `setNav`, so the drawer, the backdrop, Escape and the panel's own close button all answer
  // a press on it exactly as they answer the hamburger. Its `aria-expanded` is kept in step for the same reason
  // the hamburger's is: two controls on one state, one of them lying, is the defect the attribute exists to
  // prevent. `openedBy` is what a close returns focus to, so a keyboard reader who opened the drawer from the
  // bottom of the screen is not teleported to its top when they shut it.
  var mtabMenu = document.getElementById('mtabMenu');
  if (navToggle && navDrawer) {
    var openedBy = navToggle;
    function navOpen() { return document.body.classList.contains('nav-open'); }
    function setNav(open) {
      document.body.classList.toggle('nav-open', open);
      navToggle.setAttribute('aria-expanded', open ? 'true' : 'false');
      navToggle.setAttribute('aria-label', open ? 'Close navigation' : 'Open navigation');
      if (mtabMenu) {
        mtabMenu.setAttribute('aria-expanded', open ? 'true' : 'false');
        mtabMenu.setAttribute('aria-label', open ? 'Close menu' : 'Open menu');
      }
    }
    function openNav(from) {
      openedBy = from || navToggle;
      setNav(true);
      // The drawer's first link, not the panel: the thing a reader wants next is where to go.
      var first = navDrawer.querySelector('a, button');
      if (first) first.focus();
    }
    function closeNav() {
      if (!navOpen()) return;
      setNav(false);
      openedBy.focus();
    }
    navToggle.addEventListener('click', function () {
      if (navOpen()) closeNav(); else openNav(navToggle);
    });
    if (mtabMenu) mtabMenu.addEventListener('click', function () {
      if (navOpen()) closeNav(); else openNav(mtabMenu);
    });
    if (navBackdrop) navBackdrop.addEventListener('click', closeNav);
    if (navClose) navClose.addEventListener('click', closeNav);
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' || e.key === 'Esc') closeNav();
    });
    // A link inside the drawer answers by navigating, and the drawer must not sit open over the page
    // that answers — the back-forward cache hands that DOM back exactly as it was left.
    navDrawer.addEventListener('click', function (e) {
      var link = e.target && e.target.closest ? e.target.closest('a') : null;
      if (link) setNav(false);
    });
    if (window.matchMedia) {
      var wide = window.matchMedia('(min-width: 901px)');
      var leave = function (e) { if (e.matches) setNav(false); };
      if (wide.addEventListener) wide.addEventListener('change', leave);
      else if (wide.addListener) wide.addListener(leave);
    }
  }

  /* ------------------------------------------------------------------ *
   *  The desktop sidebar — a narrow rail by choice, remembered by this browser
   * ------------------------------------------------------------------ */

  /* Above 900px the same corner button switches the 240px sidebar between its full column and a 64px icon rail.
     It never leaves the panel, so there is one always-visible control and no second opener competing with the
     phone's hamburger. The rail state is one class on <body>; the stylesheet owns the geometry and the arrow,
     while this file owns the per-browser choice and the accessible name.
     Only a press changes the arrangement. `pts-sidebar` stores `rail`, with absence meaning the full column;
     the pre-paint read above also accepts Stage 130's old `hidden` value. Below 900px the shared drawer close
     path is untouched, and the stylesheet restores every full-sidebar label regardless of the remembered state. */
  if (navClose && navDrawer) {
    var SIDEBAR_KEY = 'pts-sidebar';

    function isWide() { return !!(window.matchMedia && window.matchMedia('(min-width: 901px)').matches); }
    function sidebarRail() { return document.body.classList.contains('sidebar-rail'); }

    function nameSidebarControls() {
      navClose.setAttribute('aria-expanded', isWide() && sidebarRail() ? 'false' : 'true');
      navClose.setAttribute('aria-label', isWide() ? (sidebarRail() ? 'Expand sidebar' : 'Collapse sidebar') : 'Close navigation');
    }

    function setSidebar(rail) {
      document.body.classList.toggle('sidebar-rail', rail);
      nameSidebarControls();
      try {
        if (rail) localStorage.setItem(SIDEBAR_KEY, 'rail');
        else localStorage.removeItem(SIDEBAR_KEY);  // the full column is the default, not a second value
      } catch (err) {
        // Storage blocked, so this page forgets the choice. The control still works while it is open.
      }
    }

    // `navClose` already runs `closeNav` above. On a wide page that finds no drawer and returns; here the same
    // press folds or expands the rail. Focus stays on this still-visible button in both directions.
    function toggleSidebar() {
      if (!isWide()) return;
      setSidebar(!sidebarRail());
    }
    navClose.addEventListener('click', toggleSidebar);

    if (window.matchMedia) {
      var desktop = window.matchMedia('(min-width: 901px)');
      var rename = function () { nameSidebarControls(); };
      if (desktop.addEventListener) desktop.addEventListener('change', rename);
      else if (desktop.addListener) desktop.addListener(rename);
    }
    nameSidebarControls();
  }

})();
