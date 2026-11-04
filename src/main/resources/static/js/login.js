/* login.js — the password box's show/hide button (Stage 121).

   The document is marked `js` first, because base.css keeps the button hidden until it is there: with
   scripting off a plain password box is the whole feature, rather than a button that cannot reveal
   anything. The button is type="button", so pressing it never submits the sign-in form; it keeps its
   state in aria-pressed and its name in aria-label, and it puts the caret back where it was so revealing
   the word does not send the reader to the end of it. */
(function () {
    document.documentElement.classList.add('js');
    var field = document.getElementById('password');
    var toggle = document.getElementById('passwordToggle');
    if (!field || !toggle) return;

    function label(showing) {
        toggle.textContent = showing ? 'Hide' : 'Show';
        toggle.setAttribute('aria-label', showing ? 'Hide password' : 'Show password');
        toggle.setAttribute('aria-pressed', showing ? 'true' : 'false');
    }

    toggle.addEventListener('click', function () {
        var showing = field.type === 'text';
        var start = field.selectionStart;
        var end = field.selectionEnd;
        field.type = showing ? 'password' : 'text';
        field.focus();
        try { field.setSelectionRange(start, end); } catch (e) { /* not every input type keeps a caret */ }
        label(!showing);
    });
})();
