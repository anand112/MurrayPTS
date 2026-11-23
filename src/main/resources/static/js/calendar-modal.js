/* calendar-modal.js — Calendar task-detail modal. Extracted from Calendar.kt in Stage 7b.
   The original used global openTaskModal()/closeTaskModal() functions wired via inline
   onclick attributes; those are CSP-hostile, so this version uses delegated listeners
   driven by data-modal-open / data-modal-close markers on the server-rendered elements.
   Clicking the backdrop (#taskModal itself) still closes the modal. */
(function () {
  function setText(id, value) {
    var el = document.getElementById(id);
    if (el) el.textContent = value;
  }
  function openModal(source) {
    setText('modalTaskTitle', source.getAttribute('data-task-title'));
    setText('modalProject', source.getAttribute('data-task-project') || '—');
    setText('modalAssignee', source.getAttribute('data-task-assignee'));
    setText('modalStarted', source.getAttribute('data-task-started') || '—');
    setText('modalDue', source.getAttribute('data-task-due') || '—');
    setText('modalPriority', source.getAttribute('data-task-priority'));
    setText('modalStatus', source.getAttribute('data-task-status'));
    var modal = document.getElementById('taskModal');
    if (modal) modal.style.display = 'block';
  }
  function closeModal() {
    var modal = document.getElementById('taskModal');
    if (modal) modal.style.display = 'none';
  }
  document.addEventListener('click', function (e) {
    var opener = e.target.closest ? e.target.closest('[data-modal-open]') : null;
    if (opener) { openModal(opener); return; }
    var closer = e.target.closest ? e.target.closest('[data-modal-close]') : null;
    if (closer) closeModal();
  });
})();
