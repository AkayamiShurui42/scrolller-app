(() => {
  'use strict';

  if (window.__crimsonRedditMediaV1) return;
  window.__crimsonRedditMediaV1 = true;

  const LOGIN_PATH = /^\/(login|register|account|settings\/privacy)/i;
  const isLoginPage = LOGIN_PATH.test(location.pathname);

  function removeNativeAds(root = document) {
    const selectors = [
      'shreddit-ad-post',
      'shreddit-comments-page-ad',
      '[data-promoted="true"]',
      '[promoted="true"]',
      '[data-testid="promoted-post"]',
      '[data-testid*="ad-container"]',
      '[data-testid*="advertisement"]',
      '[data-adclicklocation]',
      '.promotedlink',
      '.promoted',
      '[aria-label="Promoted"]',
      '[aria-label="Sponsored"]'
    ];
    for (const selector of selectors) {
      root.querySelectorAll?.(selector).forEach(node => node.remove());
    }
  }

  removeNativeAds();
  const adObserver = new MutationObserver(mutations => {
    for (const mutation of mutations) {
      for (const node of mutation.addedNodes) {
        if (node.nodeType === 1) removeNativeAds(node);
      }
    }
  });
  adObserver.observe(document.documentElement, { childList: true, subtree: true });

  if (isLoginPage) {
    const style = document.createElement('style');
    style.textContent = `
      #crm-return{position:fixed;right:12px;bottom:18px;z-index:2147483647;border:0;border-radius:999px;background:#111;color:#fff;padding:11px 14px;font:600 13px system-ui;box-shadow:0 4px 24px #0008}
    `;
    document.documentElement.appendChild(style);
    const button = document.createElement('button');
    button.id = 'crm-return';
    button.type = 'button';
    button.textContent = 'Media';
    button.addEventListener('click', () => { location.href = 'https://www.reddit.com/'; });
    document.body.appendChild(button);
    return;
  }

  const css = document.createElement('style');
  css.id = 'crm-style';
  css.textContent = `
    html.crm-active,html.crm-active body{margin:0!important;padding:0!important;overflow:hidden!important;background:#000!important;color:#fff!important}
    #crm-root{position:fixed;inset:0;z-index:2147483600;background:#000;color:#fff;font-family:system-ui,-apple-system,Roboto,sans-serif;touch-action:pan-y}
    #crm-feed{position:absolute;inset:0;overflow-y:auto;overflow-x:hidden;scroll-snap-type:y mandatory;overscroll-behavior-y:contain;background:#000;scrollbar-width:none}
    #crm-feed::-webkit-scrollbar{display:none}
    .crm-post{position:relative;width:100%;height:100%;min-height:100%;scroll-snap-align:start;scroll-snap-stop:always;background:#000;overflow:hidden}
    .crm-media-wrap{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;background:#000}
    .crm-media,.crm-gallery img,.crm-gallery video{width:100%;height:100%;object-fit:contain;background:#000;display:block}
    #crm-root.crm-fill .crm-media,#crm-root.crm-fill .crm-gallery img,#crm-root.crm-fill .crm-gallery video{object-fit:cover}
    .crm-gallery{position:absolute;inset:0;display:flex;overflow-x:auto;overflow-y:hidden;scroll-snap-type:x mandatory;scrollbar-width:none}
    .crm-gallery::-webkit-scrollbar{display:none}
    .crm-gallery-item{position:relative;flex:0 0 100%;height:100%;scroll-snap-align:start}
    .crm-gallery-count{position:absolute;right:12px;top:12px;border-radius:999px;padding:5px 8px;background:#0009;font-size:12px;backdrop-filter:blur(10px)}
    #crm-peek{position:absolute;right:10px;top:10px;z-index:20;width:38px;height:38px;border:0;border-radius:999px;background:#0008;color:#fff;font-size:22px;display:grid;place-items:center;backdrop-filter:blur(12px);transition:opacity .18s}
    #crm-root.crm-chrome #crm-peek{opacity:0;pointer-events:none}
    #crm-toolbar{position:absolute;left:8px;right:8px;top:8px;z-index:19;display:flex;align-items:center;gap:7px;padding:7px;border-radius:16px;background:#090909d9;backdrop-filter:blur(18px);opacity:0;pointer-events:none;transform:translateY(-8px);transition:opacity .18s,transform .18s}
    #crm-root.crm-chrome #crm-toolbar{opacity:1;pointer-events:auto;transform:none}
    .crm-icon{height:38px;min-width:38px;border:0;border-radius:11px;background:#222;color:#fff;padding:0 10px;font-size:16px}
    .crm-icon:active,.crm-scope:active{background:#333}
    #crm-search-panel{position:absolute;left:8px;right:8px;top:61px;z-index:18;border-radius:16px;background:#090909ed;backdrop-filter:blur(20px);padding:9px;display:none;gap:8px;align-items:center}
    #crm-root.crm-search-open #crm-search-panel{display:grid;grid-template-columns:minmax(0,1fr) auto}
    #crm-query{width:100%;box-sizing:border-box;border:0;outline:none;border-radius:12px;background:#1b1b1b;color:#fff;padding:11px 12px;font-size:16px}
    #crm-go{height:42px;border:0;border-radius:12px;background:#fff;color:#000;padding:0 14px;font-weight:700}
    #crm-scopes{grid-column:1/-1;display:grid;grid-template-columns:1fr 1fr;gap:6px}
    .crm-scope{height:34px;border:0;border-radius:10px;background:#1d1d1d;color:#aaa;font-weight:600}
    .crm-scope.active{background:#fff;color:#000}
    .crm-meta{position:absolute;left:12px;right:12px;bottom:12px;z-index:10;opacity:0;pointer-events:none;transition:opacity .18s;display:flex;align-items:flex-end;justify-content:space-between;gap:12px}
    #crm-root.crm-chrome .crm-post.crm-current .crm-meta{opacity:1}
    .crm-text{max-width:min(82vw,620px);padding:8px 10px;border-radius:12px;background:#0008;backdrop-filter:blur(12px)}
    .crm-sub{font-size:13px;font-weight:700}
    .crm-title{font-size:12px;color:#ccc;margin-top:3px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
    .crm-status{position:absolute;left:50%;top:50%;z-index:8;transform:translate(-50%,-50%);padding:10px 13px;border-radius:12px;background:#111d9;color:#ddd;font-size:13px;text-align:center;max-width:78%;backdrop-filter:blur(12px)}
    #crm-root:not(.crm-loading) .crm-status.crm-loading-only{display:none}
    .crm-hidden{display:none!important}
    #crm-empty{position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);z-index:7;color:#bbb;text-align:center;max-width:78%;font-size:14px}
  `;
  document.documentElement.appendChild(css);
  document.documentElement.classList.add('crm-active');

  const root = document.createElement('main');
  root.id = 'crm-root';
  root.innerHTML = `
    <div id="crm-feed" aria-label="Reddit media feed"></div>
    <button id="crm-peek" type="button" aria-label="Show controls">⌕</button>
    <div id="crm-toolbar">
      <button class="crm-icon" id="crm-home" type="button" aria-label="Home">⌂</button>
      <button class="crm-icon" id="crm-search" type="button" aria-label="Search">⌕</button>
      <button class="crm-icon" id="crm-fit" type="button" aria-label="Toggle fit or fill">Fit</button>
      <button class="crm-icon" id="crm-mute" type="button" aria-label="Toggle video sound">Mute</button>
      <button class="crm-icon" id="crm-account" type="button" aria-label="Reddit login">Account</button>
    </div>
    <div id="crm-search-panel">
      <input id="crm-query" type="search" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="Search Reddit media">
      <button id="crm-go" type="button">Go</button>
      <div id="crm-scopes">
        <button class="crm-scope active" data-scope="global" type="button">Global</button>
        <button class="crm-scope" data-scope="subscribed" type="button">Subscribed</button>
      </div>
    </div>
    <div id="crm-empty" class="crm-hidden"></div>
  `;
  document.body.appendChild(root);

  const feed = root.querySelector('#crm-feed');
  const empty = root.querySelector('#crm-empty');
  const queryInput = root.querySelector('#crm-query');
  const scopeButtons = [...root.querySelectorAll('.crm-scope')];
  const muteButton = root.querySelector('#crm-mute');
  const fitButton = root.querySelector('#crm-fit');

  const state = {
    mode: 'home',
    scope: 'global',
    query: '',
    after: null,
    loading: false,
    exhausted: false,
    seen: new Set(),
    subscriptions: null,
    subChunks: [],
    subCursors: [],
    muted: true,
    fill: false,
    current: null
  };

  let chromeTimer = 0;
  function showChrome(sticky = false) {
    root.classList.add('crm-chrome');
    clearTimeout(chromeTimer);
    if (!sticky && !root.classList.contains('crm-search-open')) {
      chromeTimer = setTimeout(() => root.classList.remove('crm-chrome'), 2600);
    }
  }
  function hideChrome() {
    if (root.classList.contains('crm-search-open')) return;
    root.classList.remove('crm-chrome');
  }

  root.querySelector('#crm-peek').addEventListener('click', e => {
    e.stopPropagation();
    showChrome();
  });
  root.querySelector('#crm-search').addEventListener('click', e => {
    e.stopPropagation();
    root.classList.toggle('crm-search-open');
    showChrome(true);
    if (root.classList.contains('crm-search-open')) setTimeout(() => queryInput.focus(), 30);
    else showChrome();
  });
  root.querySelector('#crm-home').addEventListener('click', e => {
    e.stopPropagation();
    root.classList.remove('crm-search-open');
    state.mode = 'home';
    state.query = '';
    queryInput.value = '';
    resetFeed();
    loadMore();
    showChrome();
  });
  root.querySelector('#crm-account').addEventListener('click', e => {
    e.stopPropagation();
    location.href = 'https://www.reddit.com/login/';
  });
  fitButton.addEventListener('click', e => {
    e.stopPropagation();
    state.fill = !state.fill;
    root.classList.toggle('crm-fill', state.fill);
    fitButton.textContent = state.fill ? 'Fill' : 'Fit';
    showChrome();
  });
  muteButton.addEventListener('click', e => {
    e.stopPropagation();
    state.muted = !state.muted;
    muteButton.textContent = state.muted ? 'Mute' : 'Sound';
    root.querySelectorAll('video').forEach(v => { v.muted = state.muted; });
    showChrome();
  });

  scopeButtons.forEach(button => button.addEventListener('click', e => {
    e.stopPropagation();
    state.scope = button.dataset.scope;
    scopeButtons.forEach(b => b.classList.toggle('active', b === button));
    showChrome(true);
  }));

  function runSearch() {
    const q = queryInput.value.trim();
    if (!q) return;
    state.query = q;
    state.mode = 'search';
    root.classList.remove('crm-search-open');
    resetFeed();
    loadMore();
    showChrome();
  }
  root.querySelector('#crm-go').addEventListener('click', e => { e.stopPropagation(); runSearch(); });
  queryInput.addEventListener('keydown', e => {
    if (e.key === 'Enter') {
      e.preventDefault();
      runSearch();
    }
  });

  root.addEventListener('click', e => {
    if (e.target.closest('button,input,.crm-gallery')) return;
    if (root.classList.contains('crm-chrome')) hideChrome(); else showChrome();
  });

  function resetFeed() {
    feed.replaceChildren();
    empty.classList.add('crm-hidden');
    state.after = null;
    state.exhausted = false;
    state.seen.clear();
    state.current = null;
    state.subChunks = [];
    state.subCursors = [];
    feed.scrollTop = 0;
  }

  async function fetchJson(url) {
    const response = await fetch(url, {
      credentials: 'include',
      cache: 'no-store',
      headers: { 'Accept': 'application/json' }
    });
    if (!response.ok) {
      const error = new Error(`Reddit request failed (${response.status})`);
      error.status = response.status;
      throw error;
    }
    const type = response.headers.get('content-type') || '';
    if (!type.includes('json')) throw new Error('Reddit returned a non-JSON response');
    return response.json();
  }

  function isAd(post) {
    return Boolean(
      post.promoted ||
      post.is_sponsored ||
      post.promoted_by ||
      post.adserver_click_url ||
      post.adserver_imp_pixel ||
      post.third_party_tracking ||
      post.third_party_tracking_2
    );
  }

  function galleryMedia(post) {
    const order = post.gallery_data?.items || [];
    const metadata = post.media_metadata || {};
    const out = [];
    for (const item of order) {
      const m = metadata[item.media_id];
      if (!m || m.status === 'failed') continue;
      const mime = m.m || '';
      const src = m.s || {};
      if (mime.startsWith('image/')) {
        const url = src.u || src.gif;
        if (url) out.push({ type: 'image', url });
      } else if (mime.startsWith('video/')) {
        const url = src.mp4 || src.gif || src.u;
        if (url) out.push({ type: 'video', url });
      }
    }
    return out;
  }

  function mediaFor(raw) {
    const post = raw.crosspost_parent_list?.[0] || raw;
    const gallery = galleryMedia(post);
    if (gallery.length) return gallery;

    const redditVideo = post.secure_media?.reddit_video || post.media?.reddit_video;
    if (redditVideo) {
      const url = redditVideo.hls_url || redditVideo.fallback_url;
      if (url) return [{ type: 'video', url, poster: post.preview?.images?.[0]?.source?.url || '' }];
    }

    const videoPreview = post.preview?.reddit_video_preview;
    if (videoPreview) {
      const url = videoPreview.hls_url || videoPreview.fallback_url;
      if (url) return [{ type: 'video', url, poster: post.preview?.images?.[0]?.source?.url || '' }];
    }

    const url = post.url_overridden_by_dest || post.url || '';
    if (/\.(jpe?g|png|webp|avif)(\?|$)/i.test(url)) return [{ type: 'image', url }];
    if (/\.(gif)(\?|$)/i.test(url)) return [{ type: 'image', url }];
    if (/\.(mp4|webm|m3u8)(\?|$)/i.test(url)) return [{ type: 'video', url }];

    const preview = post.preview?.images?.[0]?.source?.url;
    if (preview && !post.is_self) return [{ type: 'image', url: preview }];
    return [];
  }

  function normalizeChildren(listing) {
    const children = listing?.data?.children || [];
    return children
      .map(child => child?.data)
      .filter(Boolean)
      .filter(post => !isAd(post))
      .map(post => ({ post, media: mediaFor(post) }))
      .filter(item => item.media.length > 0);
  }

  function makeMediaElement(item) {
    if (item.type === 'video') {
      const video = document.createElement('video');
      video.className = 'crm-media';
      video.src = item.url;
      if (item.poster) video.poster = item.poster;
      video.loop = true;
      video.playsInline = true;
      video.preload = 'metadata';
      video.muted = state.muted;
      video.controls = false;
      video.setAttribute('webkit-playsinline', '');
      return video;
    }
    const image = document.createElement('img');
    image.className = 'crm-media';
    image.src = item.url;
    image.loading = 'lazy';
    image.decoding = 'async';
    image.alt = '';
    return image;
  }

  function renderPost(post, media) {
    const id = post.name || `t3_${post.id || ''}`;
    if (!id || state.seen.has(id)) return null;
    state.seen.add(id);

    const section = document.createElement('section');
    section.className = 'crm-post';
    section.dataset.id = id;

    const wrap = document.createElement('div');
    wrap.className = 'crm-media-wrap';
    if (media.length === 1) {
      wrap.appendChild(makeMediaElement(media[0]));
    } else {
      const gallery = document.createElement('div');
      gallery.className = 'crm-gallery';
      media.forEach((entry, index) => {
        const item = document.createElement('div');
        item.className = 'crm-gallery-item';
        item.appendChild(makeMediaElement(entry));
        const count = document.createElement('span');
        count.className = 'crm-gallery-count';
        count.textContent = `${index + 1}/${media.length}`;
        item.appendChild(count);
        gallery.appendChild(item);
      });
      wrap.appendChild(gallery);
    }
    section.appendChild(wrap);

    const meta = document.createElement('div');
    meta.className = 'crm-meta';
    const text = document.createElement('div');
    text.className = 'crm-text';
    const sub = document.createElement('div');
    sub.className = 'crm-sub';
    sub.textContent = `r/${post.subreddit || ''}`;
    const title = document.createElement('div');
    title.className = 'crm-title';
    title.textContent = post.title || '';
    text.append(sub, title);
    meta.appendChild(text);
    section.appendChild(meta);

    section.dataset.permalink = post.permalink || '';
    return section;
  }

  function appendListing(listing) {
    const normalized = normalizeChildren(listing);
    let added = 0;
    for (const { post, media } of normalized) {
      const node = renderPost(post, media);
      if (node) {
        feed.appendChild(node);
        postObserver.observe(node);
        added++;
      }
    }
    if (feed.children.length === 0 && !state.loading) showEmpty('No image or video posts found.');
    return added;
  }

  function showEmpty(message) {
    empty.textContent = message;
    empty.classList.remove('crm-hidden');
  }
  function hideEmpty() { empty.classList.add('crm-hidden'); }

  async function loadHome() {
    const params = new URLSearchParams({ raw_json: '1', limit: '100' });
    if (state.after) params.set('after', state.after);
    const listing = await fetchJson(`/hot.json?${params}`);
    state.after = listing?.data?.after || null;
    appendListing(listing);
    if (!state.after) state.exhausted = true;
  }

  async function loadGlobalSearch() {
    const params = new URLSearchParams({
      q: `${state.query} self:false`,
      sort: 'relevance',
      type: 'link',
      raw_json: '1',
      limit: '100'
    });
    if (state.after) params.set('after', state.after);
    const listing = await fetchJson(`/search.json?${params}`);
    state.after = listing?.data?.after || null;
    appendListing(listing);
    if (!state.after) state.exhausted = true;
  }

  async function ensureSubscriptions() {
    if (state.subscriptions) return state.subscriptions;
    const names = [];
    let after = null;
    let pages = 0;
    do {
      const params = new URLSearchParams({ raw_json: '1', limit: '100' });
      if (after) params.set('after', after);
      const data = await fetchJson(`/subreddits/mine/subscriber.json?${params}`);
      for (const child of data?.data?.children || []) {
        const name = child?.data?.display_name;
        if (name) names.push(name);
      }
      after = data?.data?.after || null;
      pages++;
    } while (after && pages < 30);
    state.subscriptions = [...new Set(names)];
    return state.subscriptions;
  }

  function chunkSubscriptions(names, size = 35) {
    const chunks = [];
    for (let i = 0; i < names.length; i += size) chunks.push(names.slice(i, i + size));
    return chunks;
  }

  async function loadSubscribedSearch() {
    const names = await ensureSubscriptions();
    if (!names.length) {
      state.exhausted = true;
      showEmpty('Subscribed search needs a logged-in Reddit account with subscribed communities.');
      return;
    }
    if (!state.subChunks.length) {
      state.subChunks = chunkSubscriptions(names);
      state.subCursors = state.subChunks.map(() => null);
    }

    const merged = [];
    let anyMore = false;
    for (let i = 0; i < state.subChunks.length; i++) {
      if (state.subCursors[i] === false) continue;
      const filters = state.subChunks[i].map(name => `subreddit:${name}`).join(' OR ');
      const q = `(${filters}) ${state.query} self:false`;
      const params = new URLSearchParams({ q, sort: 'relevance', type: 'link', raw_json: '1', limit: '100' });
      if (state.subCursors[i]) params.set('after', state.subCursors[i]);
      try {
        const listing = await fetchJson(`/search.json?${params}`);
        const next = listing?.data?.after || null;
        state.subCursors[i] = next || false;
        if (next) anyMore = true;
        merged.push(...normalizeChildren(listing));
      } catch (error) {
        if (error.status === 401 || error.status === 403) throw error;
        state.subCursors[i] = false;
      }
    }

    merged.sort((a, b) => (b.post.score || 0) - (a.post.score || 0));
    for (const { post, media } of merged) {
      const node = renderPost(post, media);
      if (node) {
        feed.appendChild(node);
        postObserver.observe(node);
      }
    }
    state.exhausted = !anyMore;
    if (!feed.children.length) showEmpty('No subscribed media matched that search.');
  }

  async function loadMore() {
    if (state.loading || state.exhausted) return;
    state.loading = true;
    root.classList.add('crm-loading');
    hideEmpty();
    try {
      if (state.mode === 'home') await loadHome();
      else if (state.scope === 'global') await loadGlobalSearch();
      else await loadSubscribedSearch();
    } catch (error) {
      if (error.status === 401 || error.status === 403) {
        showEmpty('Reddit login is required for subscribed search. Tap Account to sign in.');
      } else {
        showEmpty(error.message || 'Could not load Reddit media.');
      }
      state.exhausted = true;
    } finally {
      state.loading = false;
      root.classList.remove('crm-loading');
      updateCurrentFromScroll();
    }
  }

  function updateCurrentFromScroll() {
    const posts = [...feed.querySelectorAll('.crm-post')];
    if (!posts.length) return;
    const center = feed.scrollTop + feed.clientHeight / 2;
    let best = posts[0];
    let bestDistance = Infinity;
    for (const post of posts) {
      const middle = post.offsetTop + post.offsetHeight / 2;
      const d = Math.abs(center - middle);
      if (d < bestDistance) { best = post; bestDistance = d; }
    }
    setCurrent(best);
  }

  function setCurrent(post) {
    if (!post || state.current === post) return;
    state.current?.classList.remove('crm-current');
    state.current = post;
    post.classList.add('crm-current');
    root.querySelectorAll('video').forEach(video => {
      const active = post.contains(video);
      video.muted = state.muted;
      if (active) video.play().catch(() => {}); else video.pause();
    });
  }

  const postObserver = new IntersectionObserver(entries => {
    let candidate = null;
    for (const entry of entries) {
      if (entry.isIntersecting && entry.intersectionRatio >= 0.6) candidate = entry.target;
    }
    if (candidate) setCurrent(candidate);
  }, { root: feed, threshold: [0.6, 0.85] });

  let scrollTick = 0;
  feed.addEventListener('scroll', () => {
    if (!scrollTick) {
      scrollTick = requestAnimationFrame(() => {
        scrollTick = 0;
        updateCurrentFromScroll();
        const remaining = feed.scrollHeight - feed.scrollTop - feed.clientHeight;
        if (remaining < feed.clientHeight * 2.5) loadMore();
      });
    }
  }, { passive: true });

  document.addEventListener('visibilitychange', () => {
    if (document.hidden) root.querySelectorAll('video').forEach(v => v.pause());
    else if (state.current) state.current.querySelectorAll('video').forEach(v => v.play().catch(() => {}));
  });

  window.addEventListener('popstate', () => removeNativeAds());
  setTimeout(() => showChrome(), 150);
  loadMore();
})();
