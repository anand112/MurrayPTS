(function () {
    var root = document.documentElement;
    var theme = localStorage.getItem('pts-theme');
    if (theme) root.setAttribute('data-theme', theme);
    var accent = localStorage.getItem('pts-accent');
    if (accent) root.setAttribute('data-accent', accent);
    var themeBtn = document.getElementById('themeToggle');
    function paintTheme() {
        if (themeBtn) themeBtn.textContent = root.getAttribute('data-theme') === 'dark' ? '☀️' : '🌙';
    }
    if (themeBtn) themeBtn.addEventListener('click', function () {
        var next = root.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
        root.setAttribute('data-theme', next);
        localStorage.setItem('pts-theme', next);
        paintTheme();
    });
    paintTheme();
    var accentBtn = document.getElementById('accentToggle');
    var order = ['green', 'blue', 'amber'];
    if (accentBtn) accentBtn.addEventListener('click', function () {
        var current = root.getAttribute('data-accent') || 'green';
        var next = order[(order.indexOf(current) + 1) % order.length];
        root.setAttribute('data-accent', next);
        localStorage.setItem('pts-accent', next);
    });
})();
