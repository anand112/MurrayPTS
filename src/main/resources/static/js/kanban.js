/* kanban.js — the two task boards.

   On the **Assigned tasks** page the columns are statuses. Dragging a card into one posts that change to
   /app/tasks/status and the page reloads, exactly as it always has. That drag is the only way a card walks this
   board now — Stage 103 retired the prev/next buttons that used to do the same POST.

   On a **project's** page the columns are that project's lanes (V7), and a drop there refiles the card
   through /app/tasks/bucket instead. That one is done in place: the card node is moved into the column it was
   dropped on and both counts are rewritten, because a reader who is putting cards where they belong must not
   have the board restart under their hands every time they let one go. The Done/Reopen button on those cards
   is still a status change, so it keeps the reload the status board has always had — and that reload is what
   brings the sidebar's open-card count back in step.

   The lane half is bound on `document` rather than per column so that one set of listeners covers whichever
   column a drop lands on, and the card being dragged is remembered for the same reason: the drop target is a
   column, not a card, and by the time the reply arrives the card has already been moved out of wherever the
   drag started. Adding a lane and taking one off are the board's own forms, not this script's work (Stage 74):
   a column's `⋯` and the dashed `+ Add bucket` box each open the page's popup, which posts to the routes and
   comes back as a repainted `#projectBoard`. Since **Stage 75** that is the project page's board at all hours —
   it is cut by buckets whether or not the columns hold cards — so the columns here are the project's own set:
   the three it opened with, renamed or removed by its manager like any other, plus the extras it has added.
   The columns are fixed-width tracks (app.css `.kanban-lanes`), so the only column that ever arrives or leaves
   is **Unsorted**, and it does so without moving anything else on the board.
   It is also the one column a card cannot be dropped on: it holds the cards that arrived before every card on a
   project had to be filed in a sub-category, and it empties as they are placed (Stage 48).
   A card's width is therefore written in the stylesheet rather than derived from the count of the work; its
   height is its own content, which is what the board is allowed to grow and shrink by (Stage 38).
   Since **Stage 80** a column's finished cards are wrapped in a `.kcol-done` block that the page's own
   `▸ Completed N` line opens and shuts — a GET onto the project page carrying `showdone`, so the server answers
   with the board already folded the way the reader left it and this script is not involved in the toggle at all.
   What did change here is where a card lives: a card's column is no longer its parent node, so both halves of a
   move ask `closest('.kcol')`, and a card filed into a column goes in above its completed run rather than at the
   bottom of it. */
/* The **status** columns: a drop here is a status change, so it posts it and the board reloads.
   Since **Stage 94** these three listeners sit on `document` like the lane half below, and the columns are found
   with `closest('.kcol[data-status]')` rather than collected at load. The reason is Stage 93: the Other tasks
   board is a live region, so the Bucket picker answers by replacing `#otherBoard`
   with a copy parsed out of the response. A node the browser hands back from `importNode` carries its markup and
   nothing else — a listener attached to the old column died with it — so a board bound at load goes dead the first
   time the reader narrows it: the card lifts, no column highlights, and the drop does nothing. Bound on `document`
   the same drop works whichever column the page is showing now, including one that was not on the page when this
   script ran. The project page's `#projectBoard` is swapped the same way (a lane added, renamed or taken off), and
   its cards' `✓ Done` / `↺ Reopen` are `.kmove-btn` — the same trap, cured by the same change below. */
function statusCol(node) {
  return node && node.closest ? node.closest('.kcol[data-status]') : null;
}

document.addEventListener('dragover', function (e) {
  var col = statusCol(e.target);
  if (!col) return;
  e.preventDefault();                        // a column accepts the card only once it says so
  col.classList.add('drag-over');
});
document.addEventListener('dragleave', function (e) {
  var col = statusCol(e.target);
  if (!col) return;
  // A pointer crossing from the column onto one of its cards fires `dragleave` with the card as the related
  // target. That is a move *within* the column, not a leaving of it, so the highlight stays.
  if (e.relatedTarget && col.contains(e.relatedTarget)) return;
  col.classList.remove('drag-over');
});
document.addEventListener('drop', function (e) {
  var col = statusCol(e.target);
  if (!col) return;
  e.preventDefault();
  col.classList.remove('drag-over');
  var id = e.dataTransfer.getData('text/plain');
  var status = col.getAttribute('data-status');
  if (!id || !status) return;
  var fd = new FormData(); fd.append('id', id); fd.append('status', status);
  fetch('/app/tasks/status', { method: 'POST', body: fd, redirect: 'manual' }).then(function () { location.reload(); });
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
  if (!bucket || !id || card.closest('.kcol') === col) return;
  var fd = new FormData(); fd.append('id', id); fd.append('bucket', bucket);
  window.fetch('/app/tasks/bucket', { method: 'POST', body: fd, credentials: 'same-origin' })
    .then(function(){ file(card, col); })
    .catch(function(){ /* the card stays where it was; the board never claimed to have moved it */ });
});

/* Put the card in its new column, in front of the finished run so that a card dragged out of the folded cards
   lands among the live ones rather than at the bottom of the column, and in front of the "Nothing here." line
   otherwise, so that line's own rule — hidden while a card precedes it — keeps working. Then let the two columns
   count what they hold now. A lane that has just lost its last card keeps its column: it is one of the project's
   lanes, and an empty one is still a lane.
   Unsorted is the opposite — a bucket for cards nobody has placed, drawn only while it holds one (Stage 37), so
   the last card leaving it takes the column with it and the board is back to its lanes. Neither move changes a
   card's width, because the columns are fixed-width tracks (app.css `.kanban-lanes`); what a column does change
   is its own height, which is simply the cards it holds (Stage 38), so the two columns here grow and shorten by
   one card each as the card moves.
   Since **Stage 80** a card's column is no longer its parent: the finished cards of a column sit inside a
   `.kcol-done` block, so both halves of a move ask `closest('.kcol')` rather than `parentNode`. The count is
   unaffected either way — `count()` already asks for every `.kcard` under the column, which is the total the
   head prints and what the server prints for a column with folded cards.
   Since **Stage 94** the status half is bound the same way as the lane half — on `document`, with the column found
   by `closest('.kcol[data-status]')` — and so is the `.kmove-btn` press (since **Stage 103** only a project card
   still prints one). Neither half collects nodes from the page
   any more, because a page's board is no longer the board that was there when this script ran: Stage 93 made the
   Other tasks page's `#otherBoard` a live region (its Bucket picker narrows it in place) and the project page's
   `#projectBoard` has been swapped by its lane writes since Stage 74. A swapped block arrives as markup, and a
   listener belongs to the node it was put on, not to the HTML it was made from — so binding at load left a
   narrowed board with cards that lift, columns that never highlight and drops that do nothing. */
function file(card, col) {
  var from = card.closest('.kcol');
  var anchor = col.querySelector('.kdone-btn') || col.querySelector('.kcol-empty');
  if (anchor) { col.insertBefore(card, anchor); } else { col.appendChild(card); }
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

/* A project card's `✓ Done` / `↺ Reopen` is the no-drag path to the same write — the status boards' prev/next
   arrows were retired in **Stage 103**, so this button is what is left of the class — and since **Stage 94** it is
   bound on `document` for the same reason the columns above are: the button lives
   *inside* the board, so a board that was swapped in place has a fresh set of them with no listeners at all. A
   press disables the button so a double-click cannot post the step twice, exactly as before. */
document.addEventListener('click', function (e) {
  var btn = e.target && e.target.closest ? e.target.closest('.kmove-btn') : null;
  if (!btn) return;
  e.preventDefault(); e.stopPropagation();
  var id = btn.getAttribute('data-id');
  var status = btn.getAttribute('data-status');
  if (!id || !status) return;
  btn.disabled = true;
  var fd = new FormData(); fd.append('id', id); fd.append('status', status);
  fetch('/app/tasks/status', { method: 'POST', body: fd, redirect: 'manual' }).then(function () { location.reload(); });
});
