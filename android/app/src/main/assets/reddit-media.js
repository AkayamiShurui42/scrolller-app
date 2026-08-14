(() => {
  'use strict';

  const HOME_URL = 'https://www.reddit.com/';
  const AUTH_PATH = /^\/(login|register)(\/|$)/i;
  const DETAIL_PATH = /\/comments\//i;
  const isAuthPage = AUTH_PATH.test(location.pathname);
  const isDetailPage = DETAIL_PATH.test(location.pathname);

  const AD_SELECTORS = [
    'shreddit-ad-post',
    'shreddit-comments-page-ad',
    '[data-promoted="true"]',
    '[promoted="true"]',
    '[is-promoted="true"]',
    '[data-testid="promoted-post"]',
    '[data-testid*="ad-container"]',
    '[data-testid*="advertisement"]',
    '[data-adclicklocation]',
    '.promotedlink',
    '.promoted',
    '[aria-label="Promoted"]',
    '[aria-label="Sponsored"]'
  ];

  function removeNativeAds(root = document) {
    for (const selector of AD_SELECTORS) {
      root.querySelectorAll?.(selector).forEach(node => node.remove());
    }
  }

  removeNativeAds();

  // Leave Reddit's authentication page native. Once Reddit finishes an SPA-style
  // login and changes route, reload once so Android's WebView performs a clean
  // document injection on the authenticated page.
  if (isAuthPage) {
    if (window.__crmAuthNativeV3) return;
    window.__crmAuthNativeV3 = true;

    const style = document.createElement('style');
    style.textContent = `
      #crm-auth-return{position:fixed;right:12px;bottom:18px;z-index:2147483647;border:0;border-radius:999px;background:#111;color:#fff;padding:11px 15px;font:700 13px system-ui;box-shadow:0 5px 24px #0008}
    `;
    document.documentElement.appendChild(style);

    const addReturn = () => {
      if (!document.body || document.getElementById('crm-auth-return')) return;
      const button = document.createElement('button');
      button.id = 'crm-auth-return';
      button.type = 'button';
      button.textContent = 'Media';
      button.onclick = () => location.replace(HOME_URL);
      document.body.appendChild(button);
    };
    addReturn();
    const authObserver = new MutationObserver(addReturn);
    authObserver.observe(document.documentElement, { childList: true, subtree: true });

    let lastPath = location.pathname;
    const authTimer = setInterval(() => {
      removeNativeAds();
      if (location.pathname !== lastPath && !AUTH_PATH.test(location.pathname)) {
        clearInterval(authTimer);
        authObserver.disconnect();
        location.reload();
      }
      lastPath = location.pathname;
    }, 250);
    window.addEventListener('pagehide', () => clearInterval(authTimer), { once: true });
    return;
  }

  if (window.__crimsonRedditNativeFeedV3) return;
  window.__crimsonRedditNativeFeedV3 = true;

  const FEED_POST_SELECTOR = 'shreddit-post, article[data-testid="post-container"], div[data-testid="post-container"]';
  const MEDIA_SELECTOR = [
    'video',
    'shreddit-player',
    'shreddit-video',
    'shreddit-gallery-carousel',
    'gallery-carousel',
    '[slot="post-media-container"]',
    '[data-testid="post-media-container"]',
    'img[src*="preview.redd.it"]',
    'img[src*="i.redd.it"]',
    'img[src*="external-preview.redd.it"]',
    'a[href*="v.redd.it"]',
    'a[href*="i.redd.it"]'
  ].join(',');

  const css = document.createElement('style');
  css.id = 'crm-native-style';
  css.textContent = `
    :root{--crm-vh:100dvh;--crm-panel:340px}
    html.crm-native-feed{scroll-snap-type:y mandatory!important;scroll-padding:0!important;background:#000!important;overscroll-behavior-y:contain}
    html.crm-native-feed body{background:#000!important;margin:0!important;padding:0!important}

    /* Ads stay suppressed even between mutation passes. */
    shreddit-ad-post,shreddit-comments-page-ad,[data-promoted="true"],[promoted="true"],[is-promoted="true"],[data-testid="promoted-post"],[data-testid*="ad-container"],[data-testid*="advertisement"],[data-adclicklocation],.promotedlink,.promoted{display:none!important}

    /* Remove the site chrome from the viewing surface; equivalent controls live in the drawer. */
    html.crm-native-feed header,
    html.crm-native-feed #left-sidebar-container,
    html.crm-native-feed reddit-sidebar-nav,
    html.crm-native-feed shreddit-header,
    html.crm-native-feed [data-testid="left-sidebar"],
    html.crm-native-feed [data-testid="right-sidebar"],
    html.crm-native-feed aside,
    html.crm-native-feed footer{display:none!important}

    /* Let Reddit's actual feed occupy the whole width. */
    html.crm-native-feed main,
    html.crm-native-feed [role="main"],
    html.crm-native-feed #main-content,
    html.crm-native-feed .main-container,
    html.crm-native-feed .grid-container{width:100%!important;max-width:none!important;margin:0!important;padding:0!important;grid-template-columns:minmax(0,1fr)!important}

    /* Native Reddit post cards are the pages. Their internal links/buttons stay untouched. */
    html.crm-native-feed .crm-native-post{box-sizing:border-box!important;width:100%!important;max-width:none!important;min-height:var(--crm-vh)!important;height:var(--crm-vh)!important;margin:0!important;border:0!important;border-radius:0!important;scroll-snap-align:start!important;scroll-snap-stop:always!important;background:#000!important;overflow:hidden!important;display:flex!important;flex-direction:column!important;justify-content:center!important;position:relative!important}
    html.crm-native-feed .crm-no-media{display:none!important}
    html.crm-native-feed .crm-native-post > *{max-width:100%!important}

    /* Media dominates the card without replacing Reddit's controls. */
    html.crm-native-feed .crm-native-post img[src*="preview.redd.it"],
    html.crm-native-feed .crm-native-post img[src*="i.redd.it"],
    html.crm-native-feed .crm-native-post img[src*="external-preview.redd.it"],
    html.crm-native-feed .crm-native-post video{max-height:calc(var(--crm-vh) - 142px)!important;width:100%!important;object-fit:contain!important;background:#000!important}
    html.crm-native-feed.crm-fill .crm-native-post img[src*="preview.redd.it"],
    html.crm-native-feed.crm-fill .crm-native-post img[src*="i.redd.it"],
    html.crm-native-feed.crm-fill .crm-native-post img[src*="external-preview.redd.it"],
    html.crm-native-feed.crm-fill .crm-native-post video{object-fit:cover!important}
    html.crm-native-feed .crm-native-post [slot="post-media-container"],
    html.crm-native-feed .crm-native-post [data-testid="post-media-container"]{max-height:calc(var(--crm-vh) - 132px)!important;overflow:hidden!important;background:#000!important}

    /* Keep metadata compact and remove long text bodies from mixed media posts. */
    html.crm-native-feed .crm-native-post shreddit-post-text-body,
    html.crm-native-feed .crm-native-post [slot="text-body"],
    html.crm-native-feed .crm-native-post [data-testid="post-content"] .md{max-height:4.5em!important;overflow:hidden!important}
    html.crm-native-feed .crm-native-post h1,
    html.crm-native-feed .crm-native-post h2,
    html.crm-native-feed .crm-native-post h3{display:-webkit-box!important;-webkit-line-clamp:2!important;-webkit-box-orient:vertical!important;overflow:hidden!important}

    /* Collapse empty separators and recommendation modules between posts. */
    html.crm-native-feed .crm-hidden-module{display:none!important}

    #crm-edge-menu,#crm-edge-save{position:fixed;right:6px;z-index:2147483645;width:42px;height:42px;border:0;border-radius:13px;background:#0d0d0dde;color:#fff;box-shadow:0 4px 18px #0008;backdrop-filter:blur(14px);font:800 16px system-ui}
    #crm-edge-menu{top:calc(50% - 50px)}
    #crm-edge-save{top:calc(50% + 2px);font-size:18px}
    #crm-edge-save.crm-saved{background:#fff;color:#000}

    #crm-shade{position:fixed;inset:0;z-index:2147483645;background:#0008;opacity:0;pointer-events:none;transition:opacity .18s}
    #crm-drawer{position:fixed;right:0;top:0;bottom:0;z-index:2147483646;width:min(88vw,var(--crm-panel));box-sizing:border-box;background:#0a0a0af7;color:#fff;transform:translateX(105%);transition:transform .2s ease;box-shadow:-12px 0 50px #000b;padding:14px 12px 24px;overflow-y:auto;overscroll-behavior:contain;font-family:system-ui,-apple-system,Roboto,sans-serif}
    html.crm-drawer-open #crm-drawer{transform:translateX(0)}
    html.crm-drawer-open #crm-shade{opacity:1;pointer-events:auto}
    #crm-drawer-head{display:flex;align-items:center;justify-content:space-between;gap:10px;margin-bottom:12px}
    #crm-drawer-title{font-size:18px;font-weight:800}
    #crm-close{width:40px;height:40px;border:0;border-radius:12px;background:#222;color:#fff;font-size:20px}
    .crm-nav{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-bottom:12px}
    .crm-btn{height:42px;border:0;border-radius:12px;background:#1c1c1c;color:#fff;font:700 13px system-ui;padding:0 10px}
    .crm-btn:active,.crm-sub-row:active{background:#303030}
    #crm-search-box{margin:12px 0;padding-top:12px;border-top:1px solid #262626}
    #crm-query,#crm-sub-filter{width:100%;box-sizing:border-box;border:0;outline:none;border-radius:12px;background:#1a1a1a;color:#fff;padding:11px 12px;font-size:16px}
    #crm-search-actions{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-top:8px}
    #crm-subs-head{display:flex;align-items:center;justify-content:space-between;gap:8px;margin:14px 0 8px;padding-top:12px;border-top:1px solid #262626}
    #crm-subs-title{font-size:14px;font-weight:800}
    #crm-sub-list{max-height:44vh;overflow-y:auto;scrollbar-width:none;border-radius:12px;background:#111}
    #crm-sub-list::-webkit-scrollbar{display:none}
    .crm-sub-row{width:100%;box-sizing:border-box;border:0;border-bottom:1px solid #202020;background:transparent;color:#fff;text-align:left;padding:12px;font:700 14px system-ui;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
    #crm-toast{position:fixed;left:50%;bottom:24px;z-index:2147483647;transform:translate(-50%,12px);background:#111e;color:#fff;padding:9px 13px;border-radius:999px;font:700 12px system-ui;opacity:0;pointer-events:none;transition:.18s;max-width:80vw;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;backdrop-filter:blur(14px)}
    #crm-toast.show{opacity:1;transform:translate(-50%,0)}
  `;
  document.documentElement.appendChild(css);

  let feedEnabled = !isDetailPage;
  if (feedEnabled) document.documentElement.classList.add('crm-native-feed');

  const menuButton = document.createElement('button');
  menuButton.id = 'crm-edge-menu';
  menuButton.type = 'button';
  menuButton.setAttribute('aria-label', 'Open menu');
  menuButton.textContent = '☰';

  const saveButton = document.createElement('button');
  saveButton.id = 'crm-edge-save';
  saveButton.type = 'button';
  saveButton.setAttribute('aria-label', 'Save current post');
  saveButton.textContent = '♡';

  const shade = document.createElement('div');
  shade.id = 'crm-shade';

  const drawer = document.createElement('aside');
  drawer.id = 'crm-drawer';
  drawer.innerHTML = `
    <div id="crm-drawer-head">
      <div id="crm-drawer-title">Reddit Media</div>
      <button id="crm-close" type="button" aria-label="Close menu">×</button>
    </div>
    <div class="crm-nav">
      <button class="crm-btn" id="crm-home" type="button">Home</button>
      <button class="crm-btn" id="crm-saved" type="button">Saved</button>
      <button class="crm-btn" id="crm-account" type="button">Account</button>
      <button class="crm-btn" id="crm-fit" type="button">Fit</button>
    </div>
    <div id="crm-search-box">
      <input id="crm-query" type="search" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="Search Reddit media">
      <div id="crm-search-actions">
        <button class="crm-btn" id="crm-global" type="button">Global</button>
        <button class="crm-btn" id="crm-subscribed-search" type="button">Subscribed</button>
      </div>
    </div>
    <div id="crm-subs-head">
      <div id="crm-subs-title">Subscriptions</div>
      <button class="crm-btn" id="crm-refresh-subs" type="button">Refresh</button>
    </div>
    <input id="crm-sub-filter" type="search" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="Filter subscriptions">
    <div id="crm-sub-list"></div>
  `;

  const toast = document.createElement('div');
  toast.id = 'crm-toast';

  const mountUi = () => {
    if (!document.body) return false;
    if (!document.getElementById(menuButton.id)) document.body.appendChild(menuButton);
    if (!document.getElementById(saveButton.id)) document.body.appendChild(saveButton);
    if (!document.getElementById(shade.id)) document.body.appendChild(shade);
    if (!document.getElementById(drawer.id)) document.body.appendChild(drawer);
    if (!document.getElementById(toast.id)) document.body.appendChild(toast);
    return true;
  };
  mountUi();

  const state = {
    currentPost: null,
    subscriptions: null,
    account: null,
    fill: false,
    searchScope: sessionStorage.getItem('crm-search-scope') || 'global'
  };

  let toastTimer = 0;
  function showToast(message) {
    toast.textContent = message;
    toast.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toast.classList.remove('show'), 1900);
  }

  function openDrawer() {
    document.documentElement.classList.add('crm-drawer-open');
    ensureSubscriptions().then(renderSubscriptions).catch(() => {});
  }
  function closeDrawer() {
    document.documentElement.classList.remove('crm-drawer-open');
  }

  menuButton.onclick = openDrawer;
  shade.onclick = closeDrawer;
  drawer.querySelector('#crm-close').onclick = closeDrawer;
  drawer.querySelector('#crm-home').onclick = () => { sessionStorage.removeItem('crm-search-scope'); location.href = '/'; };
  drawer.querySelector('#crm-fit').onclick = () => {
    state.fill = !state.fill;
    document.documentElement.classList.toggle('crm-fill', state.fill);
    drawer.querySelector('#crm-fit').textContent = state.fill ? 'Fill' : 'Fit';
  };

  async function fetchJson(url) {
    const response = await fetch(url, {
      credentials: 'include',
      cache: 'no-store',
      headers: { Accept: 'application/json' }
    });
    if (!response.ok) {
      const error = new Error(`Reddit request failed (${response.status})`);
      error.status = response.status;
      throw error;
    }
    return response.json();
  }

  async function getAccount() {
    if (state.account) return state.account;
    try {
      const data = await fetchJson('/api/me.json?raw_json=1');
      if (data?.data?.name) state.account = data.data;
    } catch (_) {}
    return state.account;
  }

  drawer.querySelector('#crm-account').onclick = async () => {
    const me = await getAccount();
    location.href = me?.name ? `/user/${encodeURIComponent(me.name)}/` : '/login/';
  };

  drawer.querySelector('#crm-saved').onclick = async () => {
    const me = await getAccount();
    if (!me?.name) {
      location.href = '/login/';
      return;
    }
    sessionStorage.removeItem('crm-search-scope');
    location.href = `/user/${encodeURIComponent(me.name)}/saved/`;
  };

  function runSearch(scope) {
    const query = drawer.querySelector('#crm-query').value.trim();
    if (!query) return;
    sessionStorage.setItem('crm-search-scope', scope);
    sessionStorage.setItem('crm-search-query', query);
    location.href = `/search/?q=${encodeURIComponent(query)}&type=link`;
  }
  drawer.querySelector('#crm-global').onclick = () => runSearch('global');
  drawer.querySelector('#crm-subscribed-search').onclick = () => runSearch('subscribed');
  drawer.querySelector('#crm-query').addEventListener('keydown', event => {
    if (event.key === 'Enter') {
      event.preventDefault();
      runSearch('global');
    }
  });

  async function ensureSubscriptions(force = false) {
    if (state.subscriptions && !force) return state.subscriptions;
    const names = [];
    let after = null;
    let pages = 0;
    do {
      const params = new URLSearchParams({ raw_json: '1', limit: '100' });
      if (after) params.set('after', after);
      const listing = await fetchJson(`/subreddits/mine/subscriber.json?${params}`);
      for (const child of listing?.data?.children || []) {
        const name = child?.data?.display_name;
        if (name) names.push(name);
      }
      after = listing?.data?.after || null;
      pages++;
    } while (after && pages < 30);
    state.subscriptions = [...new Set(names)].sort((a, b) => a.localeCompare(b));
    return state.subscriptions;
  }

  function renderSubscriptions() {
    const list = drawer.querySelector('#crm-sub-list');
    const filter = drawer.querySelector('#crm-sub-filter').value.trim().toLowerCase();
    list.replaceChildren();
    const names = (state.subscriptions || []).filter(name => !filter || name.toLowerCase().includes(filter));
    if (!names.length) {
      const row = document.createElement('div');
      row.className = 'crm-sub-row';
      row.textContent = state.subscriptions ? 'No matching subscriptions' : 'Sign in to load subscriptions';
      list.appendChild(row);
      return;
    }
    for (const name of names) {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'crm-sub-row';
      button.textContent = `r/${name}`;
      button.onclick = () => {
        sessionStorage.removeItem('crm-search-scope');
        location.href = `/r/${encodeURIComponent(name)}/`;
      };
      list.appendChild(button);
    }
  }

  drawer.querySelector('#crm-sub-filter').addEventListener('input', renderSubscriptions);
  drawer.querySelector('#crm-refresh-subs').onclick = async () => {
    try {
      await ensureSubscriptions(true);
      renderSubscriptions();
      showToast('Subscriptions refreshed');
    } catch (_) {
      showToast('Sign in to load subscriptions');
    }
  };

  function postSubreddit(post) {
    const attr = post.getAttribute?.('subreddit-prefixed-name') || post.getAttribute?.('subreddit-name') || post.dataset?.subreddit;
    if (attr) return attr.replace(/^r\//i, '');
    const link = post.querySelector?.('a[href^="/r/"], a[href*="reddit.com/r/"]');
    if (!link) return '';
    const match = link.getAttribute('href')?.match(/\/r\/([^/?#]+)/i);
    return match ? decodeURIComponent(match[1]) : '';
  }

  function postFullname(post) {
    const thing = post.getAttribute?.('thing-id') || post.getAttribute?.('fullname') || post.dataset?.fullname;
    if (thing?.startsWith('t3_')) return thing;
    const id = post.getAttribute?.('post-id') || post.dataset?.postId;
    if (id) return id.startsWith('t3_') ? id : `t3_${id}`;
    const href = post.getAttribute?.('permalink') || post.querySelector?.('a[href*="/comments/"]')?.getAttribute('href') || '';
    const match = href.match(/\/comments\/([a-z0-9]+)/i);
    return match ? `t3_${match[1]}` : '';
  }

  function isPromotedPost(post) {
    if (!post) return false;
    if (post.matches?.(AD_SELECTORS.join(','))) return true;
    if (post.getAttribute?.('promoted') === 'true' || post.getAttribute?.('is-promoted') === 'true') return true;
    const label = post.querySelector?.('[aria-label="Promoted"],[aria-label="Sponsored"]');
    return Boolean(label);
  }

  function hasPostMedia(post) {
    if (!post) return false;
    const type = (post.getAttribute?.('post-type') || post.getAttribute?.('data-post-type') || '').toLowerCase();
    if (/(image|video|gallery|gif)/.test(type)) return true;
    if (/(text|self)/.test(type)) return false;
    if (post.querySelector?.(MEDIA_SELECTOR)) return true;
    const mediaLink = post.querySelector?.('a[href*="i.redd.it"],a[href*="v.redd.it"],a[href*="preview.redd.it"]');
    return Boolean(mediaLink);
  }

  function isSubscribedSearchActive() {
    return /\/search\/?$/i.test(location.pathname) && sessionStorage.getItem('crm-search-scope') === 'subscribed';
  }

  function shouldShowBySubscription(post) {
    if (!isSubscribedSearchActive()) return true;
    if (!state.subscriptions) return true;
    const subreddit = postSubreddit(post).toLowerCase();
    return state.subscriptions.some(name => name.toLowerCase() === subreddit);
  }

  const postObserver = new IntersectionObserver(entries => {
    let best = null;
    let ratio = 0;
    for (const entry of entries) {
      if (entry.isIntersecting && entry.intersectionRatio > ratio) {
        best = entry.target;
        ratio = entry.intersectionRatio;
      }
    }
    if (best && ratio >= 0.45) {
      state.currentPost = best;
      updateSaveButton();
    }
  }, { threshold: [0.45, 0.65, 0.85] });

  function processPost(post) {
    if (!(post instanceof Element)) return;
    if (post.dataset.crmNativeProcessed === '1') return;
    post.dataset.crmNativeProcessed = '1';

    if (isPromotedPost(post)) {
      post.remove();
      return;
    }

    if (!hasPostMedia(post)) {
      post.classList.add('crm-no-media');
      // Images can arrive after the custom element is upgraded; one delayed retry
      // prevents media posts from being permanently hidden too early.
      setTimeout(() => {
        if (!post.isConnected) return;
        if (hasPostMedia(post)) {
          post.classList.remove('crm-no-media');
          post.classList.add('crm-native-post');
          postObserver.observe(post);
        }
      }, 900);
      return;
    }

    if (!shouldShowBySubscription(post)) {
      post.classList.add('crm-no-media');
      return;
    }

    post.classList.add('crm-native-post');
    postObserver.observe(post);
  }

  function hideNonFeedModules(root = document) {
    if (!feedEnabled) return;
    const selectors = [
      'recent-posts',
      'community-highlight-carousel',
      '[data-testid="community-recommendations"]',
      '[data-testid="recommended-posts"]',
      'shreddit-feed-ad',
      'shreddit-comments-page-ad'
    ];
    for (const selector of selectors) {
      root.querySelectorAll?.(selector).forEach(node => node.classList.add('crm-hidden-module'));
    }
  }

  async function processAll(root = document) {
    removeNativeAds(root);
    hideNonFeedModules(root);
    if (!feedEnabled) return;
    if (isSubscribedSearchActive() && !state.subscriptions) {
      try { await ensureSubscriptions(); } catch (_) {}
    }
    root.querySelectorAll?.(FEED_POST_SELECTOR).forEach(processPost);
  }

  const pageObserver = new MutationObserver(mutations => {
    removeNativeAds();
    for (const mutation of mutations) {
      for (const node of mutation.addedNodes) {
        if (!(node instanceof Element)) continue;
        if (node.matches?.(FEED_POST_SELECTOR)) processPost(node);
        node.querySelectorAll?.(FEED_POST_SELECTOR).forEach(processPost);
        hideNonFeedModules(node);
      }
    }
    mountUi();
  });
  pageObserver.observe(document.documentElement, { childList: true, subtree: true });

  function nativeSaveButton(post) {
    if (!post) return null;
    const candidates = [...post.querySelectorAll('button,[role="button"]')];
    return candidates.find(button => /^(save|unsave)$/i.test((button.getAttribute('aria-label') || button.textContent || '').trim())) || null;
  }

  function isCurrentSaved() {
    const post = state.currentPost;
    if (!post) return false;
    if (post.getAttribute?.('saved') === 'true' || post.getAttribute?.('is-saved') === 'true') return true;
    const native = nativeSaveButton(post);
    const label = (native?.getAttribute('aria-label') || native?.textContent || '').trim();
    return /^unsave$/i.test(label);
  }

  function updateSaveButton() {
    const saved = isCurrentSaved();
    saveButton.classList.toggle('crm-saved', saved);
    saveButton.textContent = saved ? '♥' : '♡';
    saveButton.setAttribute('aria-label', saved ? 'Unsave current post' : 'Save current post');
  }

  async function apiToggleSave(post, unsave) {
    const fullname = postFullname(post);
    if (!fullname) throw new Error('Post ID unavailable');
    const me = await getAccount();
    if (!me?.name) {
      location.href = '/login/';
      return;
    }
    const body = new URLSearchParams({ id: fullname });
    if (me.modhash) body.set('uh', me.modhash);
    const response = await fetch(unsave ? '/api/unsave' : '/api/save', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8' },
      body
    });
    if (!response.ok) throw new Error(`Save failed (${response.status})`);
    post.setAttribute('is-saved', unsave ? 'false' : 'true');
  }

  saveButton.onclick = async () => {
    const post = state.currentPost;
    if (!post) {
      showToast('Scroll to a post first');
      return;
    }
    const unsave = isCurrentSaved();
    const native = nativeSaveButton(post);
    if (native) {
      native.click();
      setTimeout(updateSaveButton, 450);
      showToast(unsave ? 'Unsaved' : 'Saved');
      return;
    }
    try {
      await apiToggleSave(post, unsave);
      updateSaveButton();
      showToast(unsave ? 'Unsaved' : 'Saved');
    } catch (error) {
      showToast(error.message || 'Could not save post');
    }
  };

  // Reddit is an SPA in several places. Keep the same script alive across native
  // route changes and re-evaluate whether the destination should be feed mode.
  let lastUrl = location.href;
  setInterval(() => {
    removeNativeAds();
    if (location.href === lastUrl) return;
    lastUrl = location.href;
    const detail = DETAIL_PATH.test(location.pathname);
    feedEnabled = !detail && !AUTH_PATH.test(location.pathname);
    document.documentElement.classList.toggle('crm-native-feed', feedEnabled);
    document.querySelectorAll('.crm-native-post,.crm-no-media').forEach(post => {
      post.classList.remove('crm-native-post', 'crm-no-media');
      delete post.dataset.crmNativeProcessed;
      postObserver.unobserve(post);
    });
    state.currentPost = null;
    setTimeout(() => processAll(), 150);
  }, 300);

  processAll();
})();
