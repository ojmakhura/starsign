// The StarSign portal remembers light/dark in localStorage ("starsign.theme"). When Keycloak is
// served on the portal's origin (e.g. https://<domain>/auth), use that choice instead of only the
// OS setting that keycloak.v2's own dark-mode script follows.
(function () {
  var choice;
  try {
    choice = localStorage.getItem('starsign.theme');
  } catch (e) {
    return;
  }
  if (choice !== 'light' && choice !== 'dark') {
    return;
  }
  var root = document.documentElement;
  var apply = function () {
    var dark = root.classList.contains('pf-v5-theme-dark');
    if (choice === 'dark' && !dark) root.classList.add('pf-v5-theme-dark');
    if (choice === 'light' && dark) root.classList.remove('pf-v5-theme-dark');
  };
  apply();
  new MutationObserver(apply).observe(root, { attributes: true, attributeFilter: ['class'] });
})();
