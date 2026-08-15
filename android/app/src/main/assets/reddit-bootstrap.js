(() => {
  'use strict';

  const AUTH = /^\/(login|register)(\/|$)/i;
  const PASSTHROUGH = /\/comments\//i.test(location.pathname)
    || /^\/(settings|message|notifications)(\/|$)/i.test(location.pathname);
  if (AUTH.test(location.pathname) || PASSTHROUGH || window.__rvStartupBootstrap) return;
  window.__rvStartupBootstrap = true;

  const nativeFetch = window.fetch.bind(window);
  window.fetch = (input, init = {}) => {
    const url = typeof input === 'string' ? input : (input && input.url) || '';
    const redditApiRequest = url.startsWith('/')
      && (/\.json(?:\?|$)/i.test(url) || /\/api\//i.test(url) || /\/subreddits\/mine\//i.test(url));
    if (!redditApiRequest || init.signal) return nativeFetch(input, init);

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 7000);
    return nativeFetch(input, { ...init, signal: controller.signal })
      .finally(() => clearTimeout(timer));
  };

  const installGuard = () => {
    const host = document.getElementById('rv21-host');
    const root = host && host.shadowRoot;
    const app = root && root.querySelector('.app');
    if (!root || !app || app.childElementCount > 0 || root.getElementById('rv21-startup-guard')) return false;

    const guard = document.createElement('div');
    guard.id = 'rv21-startup-guard';
    guard.style.cssText = 'position:fixed;inset:0;z-index:2147483647;background:#000;color:#eee;display:flex;flex-direction:column;align-items:center;justify-content:center;font-family:system-ui,-apple-system,Roboto,sans-serif;text-align:center;padding:28px';
    guard.innerHTML = '<div style="font-size:18px;font-weight:800;margin-bottom:8px">Reddit Media</div><div id="rv21-guard-msg" style="font-size:12px;color:#999">Loading your feed…</div>';
    root.appendChild(guard);

    const observer = new MutationObserver(() => {
      if (app.childElementCount > 0) {
        observer.disconnect();
        guard.remove();
      }
    });
    observer.observe(app, { childList: true });

    setTimeout(() => {
      if (!guard.isConnected || app.childElementCount > 0) return;
      const msg = guard.querySelector('#rv21-guard-msg');
      if (msg) msg.textContent = 'Reddit is taking too long to respond. Tap anywhere to retry.';
      guard.style.cursor = 'pointer';
      guard.onclick = () => location.reload();
    }, 9000);
    return true;
  };

  let tries = 0;
  const guardPoll = setInterval(() => {
    tries++;
    if (installGuard() || tries > 40) clearInterval(guardPoll);
  }, 50);
})();
