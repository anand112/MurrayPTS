/* team.js — Team page member-detail popup, per-member tasks toggle, and quick-add
   shortcut. Extracted verbatim from Team.kt memberDetailScript() in Stage 7b. The
   server-rendered data lives in an inline <script type="application/json"
   id="memberDetailData"> island (not executable, so CSP-safe); this file reads it. */
(function () {
  var data = {};
  var tag = document.getElementById('memberDetailData');
  try { data = JSON.parse(tag.textContent || '{}'); } catch (e) { data = {}; }
  var pop = document.createElement('div');
  pop.className = 'mpop';
  pop.id = 'memberPop';
  pop.hidden = true;
  document.body.appendChild(pop);
  var COLORS = { 'Ongoing': '#1e8e3e', 'In progress': '#e0a800', 'On hold': '#e07000',
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
  pop.addEventListener('click', function (e) {
    if (e.target.closest('.mpop-x')) close();
  });
  document.addEventListener('click', function (e) {
    if (pop.hidden) return;
    if (pop.contains(e.target)) return;
    if (e.target.closest && e.target.closest('.team-table tbody tr[data-emp]')) return;
    close();
  });
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') close();
  });
})();
