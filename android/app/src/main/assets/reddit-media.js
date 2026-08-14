(() => {
  'use strict';

  const AUTH_PATH = /^\/(login|register)(\/|$)/i;
  const COMMENT_PATH = /\/comments\//i;
  const POST_SELECTOR = 'shreddit-post, article[data-testid="post-container"], [data-testid="post-container"]';
  const SHELL_ID = 'crm-shell-v14';
  const STYLE_ID = 'crm-style-v14';
  const MEDIA_VALUES = new Set(['all', 'image', 'video']);
  const SORT_VALUES = new Set(['best', 'hot', 'new', 'top', 'rising']);
  const TIME_VALUES = new Set(['hour', 'day', 'week', 'month', 'year', 'all']);
  const SCOPE_VALUES = new Set(['global', 'subscribed']);

  const stored = (key, allowed, fallback) => {
    const value = localStorage.getItem(key);
    return allowed.has(value) ? value : fallback;
  };

  if (AUTH_PATH.test(location.pathname)) {
    if (window.__crmAuthWatch14) return;
    window.__crmAuthWatch14 = true;
    const timer = setInterval(() => {
      if (!AUTH_PATH.test(location.pathname)) {
        clearInterval(timer);
        location.reload();
      }
    }, 350);
    addEventListener('pagehide', () => clearInterval(timer), { once: true });
    return;
  }

  if (window.__crmNative14) return;
  window.__crmNative14 = true;

  const state = {
    media: stored('crm.media', MEDIA_VALUES, 'all'),
    sort: stored('crm.sort', SORT_VALUES, 'best'),
    topTime: stored('crm.topTime', TIME_VALUES, 'day'),
    scope: stored('crm.scope', SCOPE_VALUES, 'global'),
    subs: null,
    subsLoading: false,
    drawerOpen: false
  };

  const adSelectors = [
    'shreddit-ad-post',
    'shreddit-comments-page-ad',
    '[data-promoted="true"]',
    '[promoted="true"]',
    '[data-testid="promoted-post"]',
    '[data-testid*="ad-container"]',
    '[data-testid*="advertisement"]',
    '.promotedlink',
    '.promoted',
    '[aria-label="Promoted"]',
    '[aria-label="Sponsored"]'
  ];

  function saveState(key, value) {
    localStorage.setItem(key, value);
  }

  function removeAds(root = document) {
    for (const sel of adSelectors) root.querySelectorAll?.(sel).forEach(el => el.remove());
    root.querySelectorAll?.('shreddit-post').forEach(post => {
      const promoted = (post.getAttribute('promoted') || post.getAttribute('data-promoted') || '').toLowerCase();
      if (promoted === 'true' || post.hasAttribute('promoted')) post.remove();
    });
  }

  function isFeedPage() {
    return !COMMENT_PATH.test(location.pathname)
      && !/^\/(settings|message|notifications)(\/|$)/i.test(location.pathname)
      && !/^\/subreddits\/mine(\/|$)/i.test(location.pathname);
  }

  function isSearchPage() {
    return /^\/search\/?$/i.test(location.pathname);
  }

  function ensureStyles() {
    if (document.getElementById(STYLE_ID)) return;
    const style = document.createElement('style');
    style.id = STYLE_ID;
    style.textContent = `
      :root{--crm-rail:44px;--crm-top:92px;--crm-bg:#080808;--crm-panel:#101010;--crm-line:#242424;--crm-fg:#f5f5f5;--crm-muted:#9a9a9a}
      html.crm-shell-page body{padding-left:var(--crm-rail)!important;padding-top:var(--crm-top)!important;box-sizing:border-box!important;background:#000!important}
      html.crm-shell-page body>header,html.crm-shell-page shreddit-header,html.crm-shell-page #left-sidebar-container,html.crm-shell-page [data-testid="left-sidebar"],html.crm-shell-page shreddit-nav-drawer{display:none!important}
      html.crm-feed-page,html.crm-feed-page body{scroll-snap-type:y mandatory!important;scroll-padding-top:var(--crm-top)!important;background:#000!important}
      html.crm-feed-page main,html.crm-feed-page shreddit-feed,html.crm-feed-page [role="main"]{max-width:none!important;width:100%!important;margin:0!important;padding:0!important}
      html.crm-feed-page shreddit-post.crm-media-post,html.crm-feed-page article.crm-media-post,html.crm-feed-page [data-testid="post-container"].crm-media-post{box-sizing:border-box!important;width:100%!important;max-width:100%!important;height:calc(100dvh - var(--crm-top))!important;min-height:calc(100dvh - var(--crm-top))!important;max-height:calc(100dvh - var(--crm-top))!important;margin:0!important;border-radius:0!important;scroll-snap-align:start!important;scroll-snap-stop:always!important;overflow:hidden!important;background:#000!important;display:flex!important;flex-direction:column!important}
      html.crm-feed-page .crm-nonmedia,html.crm-feed-page .crm-pending{display:none!important}
      html.crm-feed-page shreddit-post.crm-media-post [slot="post-media-container"],html.crm-feed-page shreddit-post.crm-media-post [data-testid="post-media"],html.crm-feed-page shreddit-post.crm-media-post shreddit-media-lightbox,html.crm-feed-page shreddit-post.crm-media-post shreddit-player,html.crm-feed-page shreddit-post.crm-media-post shreddit-gallery,html.crm-feed-page shreddit-post.crm-media-post gallery-carousel,html.crm-feed-page article.crm-media-post [data-testid="post-media"]{flex:1 1 auto!important;min-height:0!important;max-height:none!important;overflow:hidden!important;display:flex!important;align-items:center!important;justify-content:center!important;background:#000!important}
      html.crm-feed-page shreddit-post.crm-media-post [slot="text-body"],html.crm-feed-page shreddit-post.crm-media-post [data-post-click-location="text-body"],html.crm-feed-page article.crm-media-post [data-testid="post-content"] p{display:none!important}
      html.crm-feed-page shreddit-post.crm-media-post img,html.crm-feed-page shreddit-post.crm-media-post video,html.crm-feed-page article.crm-media-post img,html.crm-feed-page article.crm-media-post video{max-width:100%!important;max-height:100%!important;width:auto!important;height:auto!important;object-fit:contain!important}
      html.crm-fill.crm-feed-page shreddit-post.crm-media-post [slot="post-media-container"] img,html.crm-fill.crm-feed-page shreddit-post.crm-media-post [slot="post-media-container"] video,html.crm-fill.crm-feed-page article.crm-media-post [data-testid="post-media"] img,html.crm-fill.crm-feed-page article.crm-media-post [data-testid="post-media"] video{width:100%!important;height:100%!important;object-fit:cover!important}
      #${SHELL_ID}{position:fixed;inset:0;z-index:2147483600;pointer-events:none;font-family:system-ui,-apple-system,Roboto,sans-serif;color:var(--crm-fg)}
      #crm-rail{position:absolute;left:0;top:0;bottom:0;width:var(--crm-rail);background:#080808f5;border-right:1px solid var(--crm-line);display:flex;flex-direction:column;align-items:center;gap:4px;padding:5px 0;box-sizing:border-box;pointer-events:auto}
      #crm-rail a,#crm-rail button{width:36px;height:36px;border:0;border-radius:10px;background:transparent;color:#eee;text-decoration:none;display:grid;place-items:center;font-size:17px;font-weight:700}
      #crm-rail a:active,#crm-rail button:active{background:#242424}#crm-rail .crm-spacer{flex:1}
      #crm-top{position:absolute;left:var(--crm-rail);right:0;top:0;height:var(--crm-top);background:#080808f2;border-bottom:1px solid var(--crm-line);display:flex;flex-direction:column;gap:5px;padding:6px 7px;box-sizing:border-box;pointer-events:auto;backdrop-filter:blur(16px)}
      .crm-top-row{display:flex;align-items:center;gap:5px;min-width:0;height:37px}
      #crm-search-form{display:flex;min-width:0;flex:1}#crm-search-input{min-width:0;flex:1;height:37px;border:1px solid #2b2b2b;border-radius:10px;background:#171717;color:#fff;padding:0 10px;font-size:14px;outline:none}#crm-search-input:focus{border-color:#777}
      .crm-select{min-width:0;height:37px;border:1px solid #2b2b2b;border-radius:10px;background:#151515;color:#eee;padding:0 8px;font-size:12px;font-weight:700;outline:none}
      #crm-scope-select{width:94px;flex:0 0 94px}#crm-media-select{flex:1 1 36%}#crm-sort-select{flex:1 1 32%}#crm-top-select{flex:1 1 32%}#crm-more{width:42px;flex:0 0 42px;height:37px;border:1px solid #2b2b2b;border-radius:10px;background:#151515;color:#eee;font-size:17px;font-weight:800}
      #crm-drawer{position:absolute;left:var(--crm-rail);top:0;bottom:0;width:min(84vw,330px);background:#0c0c0cf9;border-right:1px solid #303030;box-shadow:12px 0 40px #000b;transform:translateX(calc(-100% - 4px));transition:transform .18s ease;pointer-events:auto;display:flex;flex-direction:column;overflow:hidden}
      #${SHELL_ID}.crm-open #crm-drawer{transform:translateX(0)}
      #crm-drawer-head{height:50px;display:flex;align-items:center;justify-content:space-between;padding:0 10px 0 13px;border-bottom:1px solid var(--crm-line);font-weight:800}#crm-close{width:34px;height:34px;border:0;border-radius:10px;background:#1e1e1e;color:#fff;font-size:18px}
      #crm-drawer-scroll{overflow-y:auto;padding:10px 10px 24px}.crm-section{margin:0 0 14px}.crm-section-title{font-size:11px;letter-spacing:.07em;text-transform:uppercase;color:#858585;margin:7px 5px}.crm-nav{display:grid;grid-template-columns:1fr 1fr;gap:6px}.crm-nav a,.crm-nav button{min-height:40px;border:0;border-radius:11px;background:#171717;color:#fff;text-decoration:none;display:flex;align-items:center;justify-content:center;padding:0 8px;font-size:13px;font-weight:700;text-align:center}
      .crm-options{display:grid;grid-template-columns:repeat(3,1fr);gap:6px}.crm-options button{height:37px;border:0;border-radius:10px;background:#171717;color:#bbb;font-size:12px;font-weight:700}.crm-options button.active{background:#eee;color:#000}.crm-options button:disabled{opacity:.35}
      #crm-drawer-time{width:100%;height:39px;border:0;border-radius:10px;background:#171717;color:#fff;padding:0 10px;margin-top:6px}#crm-sub-filter{width:100%;height:38px;box-sizing:border-box;border:0;border-radius:10px;background:#171717;color:#fff;padding:0 10px;outline:none;margin-bottom:5px}#crm-sub-list{max-height:42vh;overflow-y:auto}#crm-sub-list a{display:block;padding:9px 8px;border-bottom:1px solid #1c1c1c;color:#eee;text-decoration:none;font-size:13px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}#crm-sub-status{padding:8px;color:#888;font-size:12px}
      #crm-backdrop{position:absolute;left:var(--crm-rail);right:0;top:0;bottom:0;background:#0008;opacity:0;pointer-events:none;transition:opacity .18s}#${SHELL_ID}.crm-open #crm-backdrop{opacity:1;pointer-events:auto}
      @media(min-width:800px){:root{--crm-rail:50px}#crm-top{padding-left:10px;padding-right:10px}.crm-top-row:first-child{max-width:850px}}
    `;
    document.documentElement.appendChild(style);
  }

  function buildShell() {
    if (document.getElementById(SHELL_ID) || !document.body) return;
    const shell = document.createElement('div');
    shell.id = SHELL_ID;
    shell.innerHTML = `
      <div id="crm-backdrop"></div>
      <nav id="crm-rail" aria-label="Media navigation">
        <button id="crm-menu" type="button" aria-label="Open menu">☰</button>
        <a href="/" aria-label="Home">⌂</a>
        <button id="crm-focus-search" type="button" aria-label="Search">⌕</button>
        <a href="/user/me/saved/" aria-label="Saved / Favorites">★</a>
        <button id="crm-open-subs" type="button" aria-label="Subscriptions">≡</button>
        <div class="crm-spacer"></div>
        <a href="/user/me/" aria-label="Profile">●</a>
      </nav>
      <div id="crm-top">
        <div class="crm-top-row">
          <form id="crm-search-form"><input id="crm-search-input" type="search" placeholder="Search Reddit media" autocomplete="off" autocapitalize="off" spellcheck="false"></form>
          <select id="crm-scope-select" class="crm-select" aria-label="Search scope"><option value="global">Global</option><option value="subscribed">Subscribed</option></select>
        </div>
        <div class="crm-top-row">
          <select id="crm-media-select" class="crm-select" aria-label="Media filter"><option value="all">All media</option><option value="image">Images</option><option value="video">Video</option></select>
          <select id="crm-sort-select" class="crm-select" aria-label="Sort"><option value="best">Best</option><option value="hot">Hot</option><option value="new">New</option><option value="top">Top</option><option value="rising">Rising</option></select>
          <select id="crm-top-select" class="crm-select" aria-label="Top time"><option value="hour">Hour</option><option value="day">Day</option><option value="week">Week</option><option value="month">Month</option><option value="year">Year</option><option value="all">All time</option></select>
          <button id="crm-more" type="button" aria-label="More navigation">☰</button>
        </div>
      </div>
      <aside id="crm-drawer">
        <div id="crm-drawer-head"><span>Reddit Media</span><button id="crm-close" type="button">×</button></div>
        <div id="crm-drawer-scroll">
          <section class="crm-section"><div class="crm-section-title">Navigate</div><div class="crm-nav">
            <a href="/">Home</a><a href="/r/popular/">Popular</a><a href="/user/me/saved/">Saved / Favorites</a><a href="/subreddits/mine/">Subscriptions page</a><a href="/user/me/">Profile</a><a href="/settings/account/">Account settings</a>
          </div></section>
          <section class="crm-section"><div class="crm-section-title">Search scope</div><div class="crm-options" id="crm-scope-options"><button data-scope="global" type="button">Global</button><button data-scope="subscribed" type="button">Subscribed</button></div></section>
          <section class="crm-section"><div class="crm-section-title">Media type</div><div class="crm-options" id="crm-media-options"><button data-media="all" type="button">All media</button><button data-media="image" type="button">Images</button><button data-media="video" type="button">Video</button></div></section>
          <section class="crm-section"><div class="crm-section-title">Sort</div><div class="crm-options" id="crm-sort-options"><button data-sort="best" type="button">Best</button><button data-sort="hot" type="button">Hot</button><button data-sort="new" type="button">New</button><button data-sort="top" type="button">Top</button><button data-sort="rising" type="button">Rising</button></div><select id="crm-drawer-time" aria-label="Top time range"><option value="hour">Top: Hour</option><option value="day">Top: Day</option><option value="week">Top: Week</option><option value="month">Top: Month</option><option value="year">Top: Year</option><option value="all">Top: All time</option></select></section>
          <section class="crm-section"><div class="crm-section-title">Subscriptions</div><input id="crm-sub-filter" type="search" placeholder="Filter subscriptions" autocomplete="off" spellcheck="false"><div id="crm-sub-status">Open this section to load your subscribed communities.</div><div id="crm-sub-list"></div></section>
          <section class="crm-section"><div class="crm-section-title">Display</div><div class="crm-options"><button id="crm-fit" type="button">Fit media</button><button id="crm-fill" type="button">Fill media</button></div></section>
        </div>
      </aside>`;
    document.body.appendChild(shell);

    const searchInput = shell.querySelector('#crm-search-input');
    const scopeSelect = shell.querySelector('#crm-scope-select');
    const mediaSelect = shell.querySelector('#crm-media-select');
    const sortSelect = shell.querySelector('#crm-sort-select');
    const topSelect = shell.querySelector('#crm-top-select');
    const drawerTime = shell.querySelector('#crm-drawer-time');

    shell.querySelector('#crm-menu').addEventListener('click', () => setDrawer(true));
    shell.querySelector('#crm-more').addEventListener('click', () => setDrawer(true));
    shell.querySelector('#crm-close').addEventListener('click', () => setDrawer(false));
    shell.querySelector('#crm-backdrop').addEventListener('click', () => setDrawer(false));
    shell.querySelector('#crm-focus-search').addEventListener('click', () => { searchInput.focus(); searchInput.select(); });
    shell.querySelector('#crm-open-subs').addEventListener('click', () => { setDrawer(true); loadSubscriptions(); setTimeout(() => shell.querySelector('#crm-sub-filter').focus(), 60); });
    shell.querySelector('#crm-search-form').addEventListener('submit', e => {
      e.preventDefault();
      const q = searchInput.value.trim();
      if (q) runSearch(q);
    });

    scopeSelect.addEventListener('change', () => setScope(scopeSelect.value));
    mediaSelect.addEventListener('change', () => setMedia(mediaSelect.value));
    sortSelect.addEventListener('change', () => setSort(sortSelect.value));
    topSelect.addEventListener('change', () => setTopTime(topSelect.value));
    drawerTime.addEventListener('change', () => setTopTime(drawerTime.value));

    shell.querySelectorAll('[data-scope]').forEach(btn => btn.addEventListener('click', () => setScope(btn.dataset.scope)));
    shell.querySelectorAll('[data-media]').forEach(btn => btn.addEventListener('click', () => setMedia(btn.dataset.media)));
    shell.querySelectorAll('[data-sort]').forEach(btn => btn.addEventListener('click', () => setSort(btn.dataset.sort)));

    shell.querySelector('#crm-fit').addEventListener('click', () => document.documentElement.classList.remove('crm-fill'));
    shell.querySelector('#crm-fill').addEventListener('click', () => document.documentElement.classList.add('crm-fill'));
    shell.querySelector('#crm-sub-filter').addEventListener('input', renderSubscriptions);
    updateShellState();
  }

  function setDrawer(open) {
    state.drawerOpen = open;
    const shell = document.getElementById(SHELL_ID);
    if (!shell) return;
    shell.classList.toggle('crm-open', open);
    if (open) loadSubscriptions();
  }

  function setScope(value) {
    if (!SCOPE_VALUES.has(value)) return;
    state.scope = value;
    saveState('crm.scope', value);
    updateShellState();
    if (isSearchPage()) {
      processAllPosts();
      requestAnimationFrame(ensureVisiblePost);
    }
  }

  function setMedia(value) {
    if (!MEDIA_VALUES.has(value)) return;
    state.media = value;
    saveState('crm.media', value);
    updateShellState();
    processAllPosts();
    requestAnimationFrame(ensureVisiblePost);
  }

  function setSort(value) {
    if (!SORT_VALUES.has(value)) return;
    if (isSearchPage() && value === 'rising') value = 'hot';
    state.sort = value;
    saveState('crm.sort', value);
    updateShellState();
    navigateSort();
  }

  function setTopTime(value) {
    if (!TIME_VALUES.has(value)) return;
    state.topTime = value;
    saveState('crm.topTime', value);
    updateShellState();
    if (state.sort === 'top') navigateSort();
  }

  function updateShellState() {
    const shell = document.getElementById(SHELL_ID);
    if (!shell) return;
    shell.querySelector('#crm-scope-select').value = state.scope;
    shell.querySelector('#crm-media-select').value = state.media;
    shell.querySelector('#crm-sort-select').value = state.sort;
    shell.querySelector('#crm-top-select').value = state.topTime;
    shell.querySelector('#crm-drawer-time').value = state.topTime;
    shell.querySelector('#crm-top-select').style.visibility = state.sort === 'top' ? 'visible' : 'hidden';
    shell.querySelector('#crm-drawer-time').style.display = state.sort === 'top' ? 'block' : 'none';

    const risingTop = shell.querySelector('#crm-sort-select option[value="rising"]');
    if (risingTop) risingTop.disabled = isSearchPage();
    shell.querySelectorAll('[data-sort="rising"]').forEach(b => b.disabled = isSearchPage());

    shell.querySelectorAll('[data-scope]').forEach(b => b.classList.toggle('active', b.dataset.scope === state.scope));
    shell.querySelectorAll('[data-media]').forEach(b => b.classList.toggle('active', b.dataset.media === state.media));
    shell.querySelectorAll('[data-sort]').forEach(b => b.classList.toggle('active', b.dataset.sort === state.sort));
  }

  function runSearch(q) {
    if (state.sort === 'rising') {
      state.sort = 'hot';
      saveState('crm.sort', state.sort);
      updateShellState();
    }
    const params = new URLSearchParams({
      q,
      type: 'link',
      sort: state.sort === 'best' ? 'relevance' : state.sort
    });
    if (state.sort === 'top') params.set('t', state.topTime);
    location.assign('/search/?' + params.toString());
  }

  function navigateSort() {
    const path = location.pathname;

    if (isSearchPage()) {
      if (state.sort === 'rising') {
        state.sort = 'hot';
        saveState('crm.sort', state.sort);
        updateShellState();
      }
      const params = new URLSearchParams(location.search);
      params.set('sort', state.sort === 'best' ? 'relevance' : state.sort);
      if (state.sort === 'top') params.set('t', state.topTime);
      else params.delete('t');
      params.delete('after');
      params.delete('before');
      location.assign('/search/?' + params.toString());
      return;
    }

    const sub = path.match(/^\/r\/([^/]+)(?:\/(?:best|hot|new|top|rising))?\/?$/i);
    if (sub) {
      const base = `/r/${encodeURIComponent(decodeURIComponent(sub[1]))}/`;
      const nextPath = state.sort === 'best' ? base : `${base}${state.sort}/`;
      const params = new URLSearchParams();
      if (state.sort === 'top') params.set('t', state.topTime);
      location.assign(nextPath + (params.toString() ? '?' + params.toString() : ''));
      return;
    }

    if (path === '/' || /^\/(best|hot|new|top|rising)\/?$/i.test(path)) {
      const params = new URLSearchParams();
      if (state.sort !== 'best') params.set('sort', state.sort);
      if (state.sort === 'top') params.set('t', state.topTime);
      location.assign('/' + (params.toString() ? '?' + params.toString() : ''));
    }
  }

  function declaredType(post) {
    return [
      post.getAttribute('post-type'),
      post.getAttribute('content-type'),
      post.getAttribute('post-hint'),
      post.getAttribute('domain'),
      post.getAttribute('content-href'),
      post.getAttribute('url')
    ].filter(Boolean).join(' ').toLowerCase();
  }

  function classifyPost(post) {
    const declared = declaredType(post);
    if (/\b(video|videogif|hosted:video|v\.redd\.it|redgifs|streamable)\b/.test(declared)) return 'video';
    if (/\b(image|gallery|photo|i\.redd\.it|preview\.redd\.it|imgur)\b/.test(declared)) return 'image';

    const mediaRoot = post.querySelector(
      '[slot="post-media-container"], [data-testid="post-media"], shreddit-media-lightbox, shreddit-player, shreddit-gallery, gallery-carousel'
    );

    if (mediaRoot) {
      if (
        mediaRoot.matches?.('shreddit-player') ||
        mediaRoot.querySelector?.('video, shreddit-player, [data-testid*="video"], [slot*="video"]')
      ) return 'video';

      if (
        mediaRoot.matches?.('shreddit-gallery, gallery-carousel') ||
        mediaRoot.querySelector?.('shreddit-gallery, gallery-carousel, img, picture')
      ) return 'image';
    }

    if (/\b(text|self|link|poll)\b/.test(declared)) return 'none';
    return 'pending';
  }

  function getSubredditName(post) {
    const attrs = [
      post.getAttribute('subreddit-prefixed-name'),
      post.getAttribute('subreddit-name'),
      post.getAttribute('subreddit')
    ].filter(Boolean);
    if (attrs.length) return attrs[0].replace(/^r\//i, '').toLowerCase();
    const link = post.querySelector('a[href^="/r/"], a[href*="reddit.com/r/"]');
    const m = link?.getAttribute('href')?.match(/\/r\/([^/?#]+)/i);
    return m ? decodeURIComponent(m[1]).toLowerCase() : '';
  }

  function postMatchesSubscribed(post) {
    if (!isSearchPage() || state.scope !== 'subscribed') return true;
    if (!Array.isArray(state.subs)) {
      loadSubscriptions().then(processAllPosts);
      return false;
    }
    const name = getSubredditName(post);
    return !!name && state.subs.some(s => s.name.toLowerCase() === name);
  }

  function processPost(post) {
    if (!(post instanceof Element)) return;
    const type = classifyPost(post);
    const scopeOk = postMatchesSubscribed(post);
    const mediaOk = (type === 'image' || type === 'video') && (state.media === 'all' || state.media === type);

    post.dataset.crmMediaType = type;
    post.classList.toggle('crm-pending', type === 'pending');
    post.classList.toggle('crm-media-post', type !== 'pending' && mediaOk && scopeOk);
    post.classList.toggle('crm-nonmedia', type !== 'pending' && !(mediaOk && scopeOk));
  }

  function getPosts(root = document) {
    const primary = [...(root.querySelectorAll?.('shreddit-post') || [])];
    if (primary.length) return primary;
    return [...(root.querySelectorAll?.('article[data-testid="post-container"], [data-testid="post-container"]') || [])];
  }

  function processAllPosts(root = document) {
    document.documentElement.classList.toggle('crm-feed-page', isFeedPage());
    document.documentElement.classList.add('crm-shell-page');
    removeAds(root);
    for (const post of getPosts(root)) processPost(post);
  }

  function ensureVisiblePost() {
    if (!isFeedPage()) return;
    const visible = getPosts().filter(post => post.classList.contains('crm-media-post'));
    if (!visible.length) return;
    const topOffset = parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--crm-top')) || 92;
    const current = visible.find(post => post.getBoundingClientRect().bottom > topOffset + 8) || visible[0];
    if (current.getBoundingClientRect().top < topOffset - 4 || current.getBoundingClientRect().top > innerHeight - 40) {
      current.scrollIntoView({ block: 'start', behavior: 'auto' });
    }
  }

  async function loadSubscriptions() {
    if (Array.isArray(state.subs)) {
      renderSubscriptions();
      return state.subs;
    }
    if (state.subsLoading) return null;

    state.subsLoading = true;
    const shell = document.getElementById(SHELL_ID);
    const status = shell?.querySelector('#crm-sub-status');
    if (status) status.textContent = 'Loading subscriptions…';

    const found = [];
    let after = '';
    try {
      for (let page = 0; page < 20; page++) {
        const qs = new URLSearchParams({ limit: '100', raw_json: '1' });
        if (after) qs.set('after', after);
        const res = await fetch('/subreddits/mine/subscriber.json?' + qs, {
          credentials: 'include',
          headers: { accept: 'application/json' }
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const json = await res.json();
        for (const child of json?.data?.children || []) {
          const d = child?.data;
          if (d?.display_name) found.push({ name: d.display_name, title: d.title || '' });
        }
        after = json?.data?.after || '';
        if (!after) break;
      }

      found.sort((a, b) => a.name.localeCompare(b.name));
      state.subs = found;
      if (status) status.textContent = found.length ? `${found.length} subscribed communities` : 'No subscriptions found.';
      renderSubscriptions();
      if (isSearchPage() && state.scope === 'subscribed') {
        processAllPosts();
        requestAnimationFrame(ensureVisiblePost);
      }
      return found;
    } catch (err) {
      state.subs = [];
      if (status) status.textContent = 'Sign in to load subscriptions, or use “Subscriptions page”.';
      return [];
    } finally {
      state.subsLoading = false;
    }
  }

  function renderSubscriptions() {
    const shell = document.getElementById(SHELL_ID);
    if (!shell || !Array.isArray(state.subs)) return;
    const list = shell.querySelector('#crm-sub-list');
    const filter = shell.querySelector('#crm-sub-filter').value.trim().toLowerCase();
    list.replaceChildren();
    for (const sub of state.subs
      .filter(s => !filter || s.name.toLowerCase().includes(filter) || s.title.toLowerCase().includes(filter))
      .slice(0, 500)) {
      const a = document.createElement('a');
      a.href = `/r/${encodeURIComponent(sub.name)}/`;
      a.textContent = `r/${sub.name}${sub.title ? ' — ' + sub.title : ''}`;
      list.appendChild(a);
    }
  }

  function onRouteChange() {
    setTimeout(() => {
      processAllPosts();
      updateShellState();
      requestAnimationFrame(ensureVisiblePost);
    }, 80);
  }

  ensureStyles();
  if (document.body) buildShell();
  else addEventListener('DOMContentLoaded', buildShell, { once: true });
  processAllPosts();

  const observer = new MutationObserver(mutations => {
    const touched = new Set();

    for (const mutation of mutations) {
      const targetPost = mutation.target instanceof Element ? mutation.target.closest?.(POST_SELECTOR) : null;
      if (targetPost) touched.add(targetPost);

      for (const node of mutation.addedNodes) {
        if (node.nodeType !== 1) continue;
        removeAds(node);

        if (node.matches?.(POST_SELECTOR)) touched.add(node);
        const parentPost = node.closest?.(POST_SELECTOR);
        if (parentPost) touched.add(parentPost);
        node.querySelectorAll?.(POST_SELECTOR).forEach(post => touched.add(post));
      }
    }

    for (const post of touched) processPost(post);
  });

  observer.observe(document.documentElement, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ['post-type', 'content-type', 'post-hint', 'domain', 'content-href', 'url', 'src']
  });

  const originalPush = history.pushState.bind(history);
  const originalReplace = history.replaceState.bind(history);
  history.pushState = (...args) => {
    const result = originalPush(...args);
    onRouteChange();
    return result;
  };
  history.replaceState = (...args) => {
    const result = originalReplace(...args);
    onRouteChange();
    return result;
  };

  addEventListener('popstate', onRouteChange);
  setInterval(() => removeAds(), 1800);
})();
