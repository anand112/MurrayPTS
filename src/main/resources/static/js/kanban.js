/* kanban.js — the two task boards.

   On the **Assigned tasks** page the columns are statuses. Dragging a card into one posts that change to
   /app/tasks/status and the page reloads, exactly as it always has; the prev/next buttons do the same POST.

   On a **project's** page the columns are that project's lanes (V7), and a drop there refiles the card
   through /app/tasks/bucket instead. That one is done in place: the card node is moved into the column it was
   dropped on and both counts are rewritten, because a reader who is putting cards where they belong must not
   have the board restart under their hands every time they let one go. The Done/Reopen button on those cards
   is still a status change, so it keeps the reload the status board has always had — and that reload is what
   brings the sidebar's open-card count back in step.

   The lane half is bound on `document` rather than per column so that one set of listeners covers whichever
   column a drop lands on, and the card being dragged is remembered for the same reason: the drop target is a
   column, not a card, and by the time the reply arrives the card has already been moved out of wherever the
   drag started. Nothing here adds a lane or takes one off any more (Stage 35) — a project's board is the three
   lanes it was created with — and the columns are fixed-width tracks (app.css `.kanban-lanes`), so the only
   column that ever arrives or leaves is **Unsorted**, and it does so without moving anything else on the board.
   It is also the one column a card cannot be dropped on: it holds the cards that arrived before every card on a
   project had to be filed in a sub-category, and it empties as they are placed (Stage 48).
   A card's width is therefore written in the stylesheet rather than derived from the count of the work; its
   height is its own content, which is what the board is allowed to grow and shrink by (Stage 38). */
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

/* The card being moved, for the lane drop, which is told which column it arrived in and not which card left. */
var dragging = null;

function cardOf(node) {
  return node && node.closest ? node.closest('.kcard[draggable="true"]') : null;
}
function laneOf(node) {
  var col = node && node.closest ? node.closest('.kcol[data-lane]') : null;
  // **Unsorted is not a destination.** A card on a project has to be filed in one of its sub-categories, so a
  // drop there is a move the route refuses — and a column that took the card, highlighted, and then put it back
  // would be the board disagreeing with itself in front of the reader. Cards leave it by drag; none arrive.
  if (col && col.hasAttribute('data-unfiled')) return null;
  return col;
}

document.addEventListener('dragstart', function(e){
  var card = cardOf(e.target);
  if (!card) return;
  dragging = card;
  e.dataTransfer.setData('text/plain', card.getAttribute('data-id'));
});
document.addEventListener('dragend', function(){ dragging = null; });

document.addEventListener('dragover', function(e){
  var col = laneOf(e.target);
  if (!col) return;
  e.preventDefault();                       // nothing can be dropped on a column until it says otherwise
  col.classList.add('drag-over');
});
document.addEventListener('dragleave', function(e){
  var col = laneOf(e.target);
  if (col) col.classList.remove('drag-over');
});
document.addEventListener('drop', function(e){
  var col = laneOf(e.target);
  if (!col) return;
  e.preventDefault();
  col.classList.remove('drag-over');
  var card = dragging;
  dragging = null;
  if (!card) return;
  var bucket = col.getAttribute('data-lane');
  var id = card.getAttribute('data-id');
  if (!bucket || !id || card.parentNode === col) return;
  var fd = new FormData(); fd.append('id', id); fd.append('bucket', bucket);
  window.fetch('/app/tasks/bucket', { method: 'POST', body: fd, credentials: 'same-origin' })
    .then(function(){ file(card, col); })
    .catch(function(){ /* the card stays where it was; the board never claimed to have moved it */ });
});

/* Put the card in its new column, in front of the "Nothing here." line so that line's own rule — hidden
   while a card precedes it — keeps working, then let the two columns count what they hold now. A lane that has
   just lost its last card keeps its column: it is one of the project's lanes, and an empty one is still a lane.
   Unsorted is the opposite — a bucket for cards nobody has placed, drawn only while it holds one (Stage 37), so
   the last card leaving it takes the column with it and the board is back to its lanes. Neither move changes a
   card's width, because the columns are fixed-width tracks (app.css `.kanban-lanes`); what a column does change
   is its own height, which is simply the cards it holds (Stage 38), so the two columns here grow and shorten by
   one card each as the card moves. */
function file(card, col) {
  var from = card.parentNode;
  var note = col.querySelector('.kcol-empty');
  if (note) { col.insertBefore(card, note); } else { col.appendChild(card); }
  count(from); count(col);
  if (from.getAttribute('data-lane') === '0' && !from.querySelector('.kcard')) {
    if (from.parentNode) from.parentNode.removeChild(from);
  }
}

function count(col) {
  var label = col.querySelector('.kcol-count');
  if (!label) return;
  label.textContent = String(col.querySelectorAll('.kcard').length);
}

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
