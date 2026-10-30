/* team.js — Team page member-detail popup, per-member tasks toggle, quick-add shortcut, and the sheet's
   own re-count. Extracted verbatim from Team.kt memberDetailScript() in Stage 7b; the re-count is Stage 69.

   The server-rendered data lives in an inline <script type="application/json" id="memberDetailData"> island
   (not executable, so CSP-safe); this file reads it.

   Why the sheet asks again: every figure on a row is counted when the page is drawn, and nothing re-draws it
   afterwards. A card dragged on a board writes its new status to SQLite and that board reloads — but the Team
   page the reader came from is either still open in the other tab, or is the copy the browser kept for the Back
   button, and neither has any reason to ask the server again. So it goes on printing the counts from before the
   move, and the reader's F5 "fixes" a number that was never wrong where it was written. `recount()` fetches this
   very URL with `cache: 'no-store'`, swaps the one panel the figures live in, and puts back the two pieces of
   state that belong to the reader rather than to the server: which drill-downs they had open, and the popup they
   were looking at — re-rendered from the fresh island, so a popup cannot outlive the row it came from.

   It runs on `pageshow` with `persisted` — a document lifted out of the back/forward cache — and when the tab
   comes back on screen (`visibilitychange`, and `focus` for a second window over the same page). Both are
   guarded by a short quiet period, so flicking past the tab cannot spend a request. If the fetch fails or the
   answer carries no sheet, nothing is touched: the old numbers stay on the screen, which is no worse than the
   page the reader never refreshed, and the sheet never blanks itself out from under them. */
(function () {
  var SHEET = 'teamSheet';           // the panel Team.kt wraps the caption and the table in
  var ISLAND = 'memberDetailData';   // the payload the popup and this file's own counts read
  var QUIET_MS = 3000;               // a tab flicked past is not a reader coming back

  var data = {};
  function readIsland() {
    var tag = document.getElementById(ISLAND);
    try { data = JSON.parse((tag && tag.textContent) || '{}'); } catch (e) { data = {}; }
  }
  readIsland();

  var pop = document.createElement('div');
  pop.className = 'mpop';
  pop.id = 'memberPop';
  pop.hidden = true;
  document.body.appendChild(pop);
  // One hue per stage word. Stage 96 retired the 'Ongoing' arm: projects and cards now spell the
  // running stage the same way, so this table is keyed by that one word for both lists below.
  var COLORS = { 'In progress': '#e0a800', 'On hold': '#e07000',
                 'Not started': '#8a9199', 'Blocked': '#d93025', 'Completed': '#1e8e3e' };
  var MONTHS = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
  function fmtDate(iso) {
    var p = (iso || '').split('-');
    if (p.length !== 3) return iso || '';
    var m = parseInt(p[1], 10);
    return p[2] + ' ' + (MONTHS[m - 1] || p[1]) + ' ' + p[0];
  }
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function initials(name) {
    return (name || '').split(' ').map(function (w) { return w.charAt(0); }).slice(0, 2).join('');
  }
  function render(emp) {
    var d = data[emp];
    if (!d) return null;
    var h = '';
    h += '<div class="mpop-head"><div class="avatar sm">' + esc(initials(d.name)) + '</div>' +
         '<div class="mpop-id"><div class="mpop-name">' + esc(d.name) + '</div>' +
         '<div class="mpop-sub">' + esc(d.position) + (d.department ? ' · ' + esc(d.department) : '') +
         ' · ' + esc(emp) + (d.email ? ' · ' + esc(d.email) : '') + '</div></div>' +
         '<button type="button" class="mpop-x" aria-label="Close">×</button></div>';
    h += '<div class="mpop-body">';
    h += '<div class="mpop-sechead">Projects (' + d.projects.length + ')</div>';
    if (!d.projects.length) h += '<div class="mpop-empty">No projects yet.</div>';
    d.projects.forEach(function (p) {
      var c = COLORS[p.status] || '#8a9199';
      h += '<div class="mpop-row"><div class="mpop-main">' +
           '<div class="mpop-title"><span class="code">' + esc(p.code) + '</span> ' + esc(p.name) + '</div>' +
           '<div class="mpop-meta"><span class="mdot" style="background:' + c + '"></span>' + esc(p.status) +
           ' · due ' + esc(fmtDate(p.due)) +
           '<span class="mprog"><span class="mprog-bar" style="width:' + (p.progress || 0) + '%"></span></span>' +
           (p.progress || 0) + '%</div></div>' +
           '<span class="mrole' + (p.role === 'Manager' ? ' mgr' : '') + '">' + esc(p.role) + '</span></div>';
    });
    h += '<div class="mpop-sechead">Tasks (' + d.tasks.length + ')</div>';
    if (!d.tasks.length) h += '<div class="mpop-empty">No tasks assigned.</div>';
    d.tasks.forEach(function (t) {
      var c = COLORS[t.status] || '#8a9199';
      var code = t.id > 0 ? '<span class="tcode m">T-' + ('00000' + t.id).slice(-5) + '</span> ' : '';
      h += '<div class="mpop-row"><div class="mpop-main">' +
           '<div class="mpop-title">' + code + esc(t.title) + '</div>' +
           '<div class="mpop-meta"><span class="mdot" style="background:' + c + '"></span>' + esc(t.status) +
           (t.context ? ' · ' + esc(t.context) : '') +
           (t.other ? ' · other' : '') +
           (t.due ? ' · due ' + esc(fmtDate(t.due)) : '') +
           ' · ' + esc(t.priority) + '</div></div></div>';
    });
    h += '</div>';
    return h;
  }
  var current = null;
  function close() { pop.hidden = true; current = null; }
  /* The member's own row, not the drill-down underneath it, which carries the same data-emp. */
  function rowOf(emp) {
    return document.querySelector('.team-table tbody tr[data-emp="' + emp + '"]:not(.member-tasks)');
  }
  function place(row) {
    var r = row.getBoundingClientRect();
    var sx = window.pageXOffset, sy = window.pageYOffset;
    var pw = pop.offsetWidth, ph = pop.offsetHeight;
    var left = r.right + sx + 10;
    if (left + pw > sx + window.innerWidth - 12) left = r.left + sx - pw - 10;
    if (left < sx + 12) left = sx + 12;
    var top = r.top + sy + 6;
    if (top + ph > sy + window.innerHeight - 12) top = sy + window.innerHeight - ph - 12;
    if (top < sy + 12) top = sy + 12;
    pop.style.left = left + 'px';
    pop.style.top = top + 'px';
  }

  /* Bound controls. One function, because a swapped sheet is a set of brand new nodes and a listener does not
     travel with a node the parser handed over — so this runs at load and again after every re-count. */
  function bind() {
    document.querySelectorAll('.team-table tbody tr[data-emp]').forEach(function (row) {
      row.addEventListener('click', function () {
        var emp = row.getAttribute('data-emp');
        if (current === emp && !pop.hidden) { close(); return; }
        var html = render(emp);
        if (!html) return;
        pop.innerHTML = html;
        pop.hidden = false;
        current = emp;
        place(row);
      });
    });
    document.querySelectorAll('.tasks-toggle').forEach(function (btn) {
      btn.addEventListener('click', function (e) {
        e.preventDefault(); e.stopPropagation();
        var emp = btn.getAttribute('data-emp');
        var sub = document.querySelector('tr.member-tasks[data-emp="' + emp + '"]');
        if (!sub) return;
        sub.hidden = !sub.hidden;
        btn.classList.toggle('open', !sub.hidden);
      });
    });
    document.querySelectorAll('.quick-add').forEach(function (btn) {
      btn.addEventListener('click', function (e) {
        e.preventDefault(); e.stopPropagation();
        var emp = btn.getAttribute('data-emp');
        var sel = document.getElementById('assignMember');
        var title = document.getElementById('assignTitle');
        if (!sel) return;
        sel.value = emp;
        var panel = document.getElementById('assignPanel');
        if (panel) panel.scrollIntoView({ behavior: 'smooth', block: 'start' });
        setTimeout(function () { if (title) title.focus(); }, 300);
      });
    });
  }

  /* ---------------- the sheet re-counts itself (Stage 69) ---------------- */

  var paintedAt = Date.now();
  var refetching = false;

  /** Which drill-downs the reader has opened — the only state on this page that is theirs, not the server's. */
  function openDrills() {
    var emps = [];
    document.querySelectorAll('tr.member-tasks[data-emp]').forEach(function (sub) {
      if (!sub.hidden) emps.push(sub.getAttribute('data-emp'));
    });
    return emps;
  }
  function reopen(emps) {
    emps.forEach(function (emp) {
      var sub = document.querySelector('tr.member-tasks[data-emp="' + emp + '"]');
      if (!sub) return;                        // that member is off the sheet now; nothing to put back
      sub.hidden = false;
      var btn = document.querySelector('.tasks-toggle[data-emp="' + emp + '"]');
      if (btn) btn.classList.add('open');
    });
  }

  /** Replace #teamSheet with the panel in `doc`, refresh the island, and hand the reader their page back. */
  function paint(doc) {
    var live = document.getElementById(SHEET);
    var next = doc.getElementById(SHEET);
    if (!live || !next || !live.parentNode) return false;
    var was = openDrills();
    live.parentNode.replaceChild(document.importNode(next, true), live);
    reopen(was);
    // The island sits outside the panel, so it is copied over by itself — the popup's Projects and Tasks lists
    // are counted from it, and a fresh table under a stale popup is the half-updated page this whole file exists
    // to prevent.
    var nextTag = doc.getElementById(ISLAND);
    var tag = document.getElementById(ISLAND);
    if (tag && nextTag) tag.textContent = nextTag.textContent;
    readIsland();
    bind();
    paintedAt = Date.now();
    if (current) {
      var html = render(current);
      var row = rowOf(current);
      if (!html || !row) close(); else { pop.innerHTML = html; place(row); }
    }
    return true;
  }

  function recount() {
    if (refetching || !window.fetch || !window.DOMParser) return;
    if (!document.getElementById(SHEET)) return;
    refetching = true;
    // This very URL, so a sheet filtered or scrolled to something keeps showing that same thing, and
    // `no-store`, because the one answer wanted here is what the server says *now* — a copy out of the
    // browser's own cache would re-paint the very numbers this request exists to replace.
    window.fetch(location.href, { credentials: 'same-origin', cache: 'no-store' })
      .then(function (r) {
        if (!r.ok) throw new Error('team reload returned ' + r.status);
        return r.text();
      })
      .then(function (html) {
        if (!paint(new DOMParser().parseFromString(html, 'text/html'))) throw new Error('no sheet in the answer');
      })
      .catch(function () { /* the numbers already on the screen stay; nothing is ever blanked */ })
      .then(function () { refetching = false; });
  }

  window.addEventListener('pageshow', function (e) {
    if (e.persisted) recount();     // out of the back/forward cache: this document predates the move
  });
  function comeBack() {
    if (document.visibilityState !== 'visible') return;
    if (Date.now() - paintedAt < QUIET_MS) return;
    recount();
  }
  document.addEventListener('visibilitychange', comeBack);
  // A second window over the same page never changes this document's visibility, so it needs its own cue.
  window.addEventListener('focus', comeBack);

  bind();
})();
