/* kanban.js — Assigned Tasks board: drag-and-drop between status columns and the
   prev/next "move" buttons. Both POST to /app/tasks/status and reload. Extracted
   verbatim from AssignedTasks.kt kanbanScript() in Stage 7b. */
document.querySelectorAll('.kcol[data-status]').forEach(function(col){
  col.addEventListener('dragover', function(e){ e.preventDefault(); col.classList.add('drag-over'); });
  col.addEventListener('dragleave', function(){ col.classList.remove('drag-over'); });
  col.addEventListener('drop', function(e){
    e.preventDefault(); col.classList.remove('drag-over');
    var id = e.dataTransfer.getData('text/plain');
    var status = col.getAttribute('data-status');
    if(!id || !status) return;
    var fd = new FormData(); fd.append('id', id); fd.append('status', status);
    fetch('/app/tasks/status', { method:'POST', body: fd, redirect:'manual' }).then(function(){ location.reload(); });
  });
});
document.querySelectorAll('.kcard[draggable="true"]').forEach(function(card){
  card.addEventListener('dragstart', function(e){ e.dataTransfer.setData('text/plain', card.getAttribute('data-id')); });
});
document.querySelectorAll('.kmove-btn').forEach(function(btn){
  btn.addEventListener('click', function(e){
    e.preventDefault(); e.stopPropagation();
    var id = btn.getAttribute('data-id');
    var status = btn.getAttribute('data-status');
    if(!id || !status) return;
    btn.disabled = true;
    var fd = new FormData(); fd.append('id', id); fd.append('status', status);
    fetch('/app/tasks/status', { method:'POST', body: fd, redirect:'manual' }).then(function(){ location.reload(); });
  });
});
