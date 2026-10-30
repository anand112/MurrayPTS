/* archive-modal.js — getting out of a project board's Archive panel.

   Opening is not this file's business either, and could not be: the 🗄 that opens it lives in the page header, and
   it is a GET form onto the project page carrying `?archive=1`. `app.js` follows that URL and swaps `#projectArchive`
   for the copy the server drew already open, which is why a reader with scripts switched off gets the identical panel
   from a plain click, and why a Restore or a permanent delete from inside it comes back as the same panel still up —
   the redirect those writes build carries the key again, and the page the swap reads is the page that says whether
   the block is visible.

   What is left here is the two ways out that post nothing, and they are the two the popup beside this one already
   has: a click on the backdrop, and the Escape key. The panel's own ✕ is deliberately NOT one of them — it is a
   real form onto the same URL with no key on it, so it works with or without scripts and never needs this file.

   The node is looked up by id on every event rather than held in a variable, because this panel is swapped out from
   under its own script: `#projectArchive` after a Restore is a different element than the one that loaded this file,
   and a cached reference would hide a panel that has been replaced and leave the new one on the screen.

   Hiding is followed by the URL being tidied, exactly as lane-modal.js does for its own popup's keys: a panel that
   has been shut while `?archive=1` still sits in the address bar comes straight back up on reload or on the browser's
   Back, which is the one thing a close must not do. Only `archive` goes. `buckets`, `bucketnote` and `bucketsrc` are
   the page's sentence about the last bucket write, `showdone` is which columns are open, and `laneedit` / `laneadd`
   belong to the bucket popup — none of them is ours to clear. */
(function () {
  var MODAL = 'projectArchive';

  function el(id) { return document.getElementById(id); }

  function isOpen(modal) { return !!modal && modal.style.display === 'block'; }

  function show(visible) {
    var modal = el(MODAL);
    if (!modal) return;
    modal.style.display = visible ? 'block' : 'none';
    if (visible) return;
    var params = new URLSearchParams(window.location.search);
    if (!params.has('archive')) return;
    params.delete('archive');
    var query = params.toString();
    try {
      window.history.replaceState(null, '', window.location.pathname + (query ? '?' + query : ''));
    } catch (err) { /* a blocked history costs the address bar, nothing else */ }
  }

  document.addEventListener('click', function (e) {
    if (e.target && e.target.id === MODAL) show(false);
  });

  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape' && isOpen(el(MODAL))) show(false);
  });
})();
