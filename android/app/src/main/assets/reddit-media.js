(() => {
  'use strict';

  const AUTH = /^\/(login|register)(\/|$)/i;
  const COMMENTS = /\/comments\//i;
  if (AUTH.test(location.pathname)) {
    if (window.__rv2AuthWatch) return;
    window.__rv2AuthWatch = true;
    const timer = setInterval(() => {
      if (!AUTH.test(location.pathname)) { clearInterval(timer); location.reload(); }
    }, 350);
    addEventListener('pagehide', () => clearInterval(timer), { once: true });
    return;
  }
  if (COMMENTS.test(location.pathname)) return;
  if (window.__rv2Loaded) return;
  window.__rv2Loaded = true;

  const HOST_ID = 'rv2-host';
  const state = {
    route: 'home',
    context: 'home',
    subreddit: '',
    sort: localStorage.getItem('rv2.sort') || 'best',
    topTime: localStorage.getItem('rv2.topTime') || 'day',
    media: localStorage.getItem('rv2.media') || 'all',
    layout: localStorage.getItem('rv2.layout') || 'single',
    muted: localStorage.getItem('rv2.muted') !== 'false',
    searchScope: localStorage.getItem('rv2.searchScope') || 'global',
    query: '',
    user: null,
    subscriptions: [],
    subscriptionNames: new Set(),
    posts: [],
    after: null,
    loading: false,
    error: '',
    modal: null
  };

  const decode = (s = '') => String(s).replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;/g, "'");
  const esc = (s = '') => String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const vibrate = () => { try { navigator.vibrate?.(8); } catch (_) {} };

  async function api(path, options = {}) {
    const res = await fetch(path, {
      credentials: 'include',
      redirect: 'follow',
      ...options,
      headers: { accept: 'application/json', ...(options.headers || {}) }
    });
    if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
    const type = res.headers.get('content-type') || '';
    return type.includes('json') ? res.json() : res.text();
  }

  async function loadIdentity() {
    try {
      const j = await api('/api/me.json?raw_json=1');
      if (j?.data?.name) state.user = j.data;
      else state.user = null;
    } catch (_) { state.user = null; }
  }

  async function loadSubscriptions() {
    if (!state.user) { state.subscriptions = []; state.subscriptionNames = new Set(); return; }
    const out = [];
    let after = '';
    try {
      for (let page = 0; page < 20; page++) {
        const p = new URLSearchParams({ limit: '100', raw_json: '1' });
        if (after) p.set('after', after);
        const j = await api('/subreddits/mine/subscriber.json?' + p);
        for (const c of j?.data?.children || []) {
          const d = c?.data;
          if (d?.display_name) out.push({ name: d.display_name, title: d.title || '', icon: decode(d.community_icon || d.icon_img || '') });
        }
        after = j?.data?.after || '';
        if (!after) break;
      }
    } catch (_) {}
    out.sort((a,b) => a.name.localeCompare(b.name));
    state.subscriptions = out;
    state.subscriptionNames = new Set(out.map(s => s.name.toLowerCase()));
  }

  function mediaFromPost(d) {
    const gallery = [];
    if (d?.is_gallery && Array.isArray(d?.gallery_data?.items)) {
      for (const item of d.gallery_data.items) {
        const m = d.media_metadata?.[item.media_id];
        if (!m) continue;
        const u = decode(m?.s?.gif || m?.s?.u || m?.s?.mp4 || '');
        if (u) gallery.push({ kind: 'image', url: u });
      }
      if (gallery.length) return { type: 'image', gallery };
    }

    const rv = d?.secure_media?.reddit_video || d?.media?.reddit_video || d?.preview?.reddit_video_preview;
    if (rv?.fallback_url) return { type: 'video', url: decode(rv.fallback_url), poster: decode(d?.preview?.images?.[0]?.source?.url || d?.thumbnail || '') };

    const direct = decode(d?.url_overridden_by_dest || d?.url || '');
    if (d?.post_hint === 'image' || /\.(jpe?g|png|webp|gif)(\?|$)/i.test(direct) || /(^|\.)i\.redd\.it$/i.test(d?.domain || '')) {
      const u = direct || decode(d?.preview?.images?.[0]?.source?.url || '');
      if (u) return { type: 'image', url: u };
    }

    const preview = decode(d?.preview?.images?.[0]?.source?.url || '');
    if (preview && (d?.post_hint === 'rich:video' || /redgifs|imgur|gfycat|streamable/i.test(d?.domain || ''))) {
      return { type: 'external', url: direct, poster: preview };
    }
    return null;
  }

  function normalizeChild(c) {
    const d = c?.data;
    if (!d || d.promoted || d.is_sponsored) return null;
    const media = mediaFromPost(d);
    if (!media) return null;
    return {
      id: d.name,
      title: d.title || '',
      author: d.author || '',
      subreddit: d.subreddit || '',
      permalink: d.permalink || '',
      url: decode(d.url_overridden_by_dest || d.url || ''),
      score: d.score || 0,
      comments: d.num_comments || 0,
      saved: !!d.saved,
      nsfw: !!d.over_18,
      media
    };
  }

  function filterMedia(posts) {
    if (state.media === 'all') return posts;
    return posts.filter(p => state.media === 'video' ? (p.media.type === 'video' || p.media.type === 'external') : p.media.type === 'image');
  }

  function sortPath(context = state.context) {
    if (context === 'home') {
      if (state.sort === 'best') return '/.json';
      return `/${state.sort}.json`;
    }
    const s = state.sort === 'best' ? 'hot' : state.sort;
    if (context === 'popular') return `/r/popular/${s}.json`;
    if (context === 'subreddit') return `/r/${encodeURIComponent(state.subreddit)}/${s}.json`;
    return '/.json';
  }

  function listingUrl(reset = true) {
    const p = new URLSearchParams({ limit: '100', raw_json: '1' });
    if (state.sort === 'top') p.set('t', state.topTime);
    if (!reset && state.after) p.set('after', state.after);
    return `${sortPath()}?${p}`;
  }

  async function loadFeed(reset = true) {
    if (state.loading) return;
    state.loading = true; state.error = ''; renderStatus();
    try {
      const j = await api(listingUrl(reset));
      const incoming = filterMedia((j?.data?.children || []).map(normalizeChild).filter(Boolean));
      state.posts = reset ? incoming : state.posts.concat(incoming);
      state.after = j?.data?.after || null;
      renderFeed();
    } catch (e) { state.error = `Could not load Reddit feed: ${e.message}`; renderFeed(); }
    finally { state.loading = false; renderStatus(); }
  }

  async function loadSaved() {
    state.route = 'favorites'; state.posts = []; state.after = null; state.error = ''; render();
    if (!state.user) return;
    state.loading = true; renderStatus();
    try {
      const p = new URLSearchParams({ limit: '100', raw_json: '1' });
      const j = await api(`/user/${encodeURIComponent(state.user.name)}/saved.json?${p}`);
      state.posts = filterMedia((j?.data?.children || []).map(normalizeChild).filter(Boolean));
      state.after = j?.data?.after || null;
    } catch (e) { state.error = `Could not load saved posts: ${e.message}`; }
    finally { state.loading = false; render(); }
  }

  async function loadSearch() {
    state.route = 'search'; state.posts = []; state.after = null; state.error = ''; render();
    if (!state.query.trim()) return;
    state.loading = true; renderStatus();
    try {
      const collected = [];
      let after = '';
      for (let page = 0; page < (state.searchScope === 'subscribed' ? 6 : 1); page++) {
        const sort = state.sort === 'best' ? 'relevance' : (state.sort === 'rising' ? 'new' : state.sort);
        const p = new URLSearchParams({ q: state.query.trim(), type: 'link', sort, limit: '100', raw_json: '1' });
        if (state.sort === 'top') p.set('t', state.topTime);
        if (after) p.set('after', after);
        const j = await api('/search.json?' + p);
        let batch = (j?.data?.children || []).map(normalizeChild).filter(Boolean);
        if (state.searchScope === 'subscribed') batch = batch.filter(x => state.subscriptionNames.has(x.subreddit.toLowerCase()));
        collected.push(...batch);
        after = j?.data?.after || '';
        if (!after || collected.length >= 50) break;
      }
      state.posts = filterMedia(collected);
      state.after = after || null;
    } catch (e) { state.error = `Search failed: ${e.message}`; }
    finally { state.loading = false; render(); }
  }

  async function toggleSave(post) {
    if (!state.user) { openLogin(); return; }
    try {
      const body = new URLSearchParams({ id: post.id, uh: state.user.modhash || '' });
      await api(post.saved ? '/api/unsave' : '/api/save', { method: 'POST', headers: {'content-type':'application/x-www-form-urlencoded; charset=UTF-8'}, body });
      post.saved = !post.saved; vibrate(); renderFeed();
    } catch (e) { toast(`Save failed: ${e.message}`); }
  }

  function openLogin() {
    location.href = '/login/?dest=' + encodeURIComponent('https://www.reddit.com/');
  }

  function openSubreddit(name) {
    state.route = 'home'; state.context = 'subreddit'; state.subreddit = name; state.sort = state.sort === 'best' ? 'hot' : state.sort;
    state.posts = []; state.after = null; render(); loadFeed(true);
  }

  function openHome(context = 'home') {
    state.route = 'home'; state.context = context; state.subreddit = ''; state.posts = []; state.after = null;
    if (context !== 'home' && state.sort === 'best') state.sort = 'hot';
    render(); loadFeed(true);
  }

  function setMedia(v) { state.media = v; localStorage.setItem('rv2.media', v); closeModal(); if (state.route === 'search') loadSearch(); else if (state.route === 'favorites') loadSaved(); else loadFeed(true); }
  function setSort(v) { state.sort = v; localStorage.setItem('rv2.sort', v); closeModal(); if (state.route === 'search') loadSearch(); else if (state.route === 'favorites') render(); else loadFeed(true); }
  function setTopTime(v) { state.topTime = v; localStorage.setItem('rv2.topTime', v); closeModal(); if (state.route === 'search') loadSearch(); else loadFeed(true); }
  function setLayout(v) { state.layout = v; localStorage.setItem('rv2.layout', v); renderFeed(); closeModal(); }
  function setScope(v) { state.searchScope = v; localStorage.setItem('rv2.searchScope', v); closeModal(); if (state.route === 'search' && state.query) loadSearch(); }

  const host = document.createElement('div');
  host.id = HOST_ID;
  document.body.appendChild(host);
  const shadow = host.attachShadow({ mode: 'open' });

  const style = document.createElement('style');
  style.textContent = `
    :host{all:initial}*{box-sizing:border-box}button,input,select{font:inherit}button{cursor:pointer}
    .app{position:fixed;inset:0;background:#090909;color:#f5f5f5;font-family:system-ui,-apple-system,Roboto,sans-serif;z-index:2147483647;display:flex;flex-direction:column;overflow:hidden}
    .top{height:56px;display:flex;align-items:center;gap:8px;padding:7px 10px;border-bottom:1px solid #232323;background:#0b0b0bf5;backdrop-filter:blur(18px);flex:none}
    .back,.iconbtn{width:40px;height:40px;border:0;border-radius:12px;background:#171717;color:#fff;display:grid;place-items:center;font-size:18px}.brand{font-size:18px;font-weight:800;letter-spacing:-.02em;min-width:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
    .grow{flex:1}.pill{height:38px;border:1px solid #2b2b2b;background:#151515;color:#eee;border-radius:13px;padding:0 11px;font-size:12px;font-weight:750;white-space:nowrap}.pill.active{background:#efefef;color:#090909}
    .content{position:relative;flex:1;min-height:0;background:#000}.feed{position:absolute;inset:0;overflow-y:auto;overscroll-behavior-y:contain;scroll-snap-type:y mandatory;background:#000}.feed.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));align-content:start;gap:1px;scroll-snap-type:none;background:#181818}
    .card{height:100%;min-height:100%;scroll-snap-align:start;scroll-snap-stop:always;background:#050505;display:flex;flex-direction:column;overflow:hidden;border-bottom:1px solid #1d1d1d}.grid .card{height:auto;min-height:0;scroll-snap-align:none}.meta{height:48px;display:flex;align-items:center;gap:8px;padding:0 12px;flex:none;background:#0a0a0a}.sub{border:0;background:transparent;color:#fff;font-weight:800;padding:6px 0;font-size:13px}.author{font-size:11px;color:#8d8d8d;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.nsfw{font-size:9px;font-weight:800;padding:3px 5px;border:1px solid #663;border-radius:5px;color:#f0c46b}
    .media{position:relative;flex:1;min-height:0;display:flex;align-items:center;justify-content:center;overflow:hidden;background:#000}.media img,.media video{display:block;max-width:100%;max-height:100%;width:auto;height:auto;object-fit:contain}.grid .media{aspect-ratio:1/1;flex:none}.grid .media img,.grid .media video{width:100%;height:100%;object-fit:cover}
    .gallery{position:absolute;inset:0;display:flex;overflow-x:auto;scroll-snap-type:x mandatory}.gallery img{min-width:100%;width:100%;height:100%;object-fit:contain;scroll-snap-align:start}.badge{position:absolute;right:9px;top:9px;background:#000b;border:1px solid #ffffff20;color:#fff;border-radius:9px;padding:5px 7px;font-size:11px;font-weight:700}.external{position:absolute;inset:0;display:grid;place-items:center;background:#0008}.external button{border:0;border-radius:999px;background:#fff;color:#000;padding:10px 15px;font-weight:800}
    .info{flex:none;background:#0a0a0a;border-top:1px solid #191919;padding:9px 11px 8px}.title{font-size:14px;line-height:1.25;font-weight:650;display:-webkit-box;-webkit-box-orient:vertical;-webkit-line-clamp:2;overflow:hidden}.actions{height:40px;display:flex;align-items:center;gap:6px;margin-top:5px}.act{height:34px;border:0;border-radius:10px;background:#151515;color:#ddd;padding:0 10px;font-size:12px;font-weight:700}.act.saved{background:#f0f0f0;color:#111}.act.more{margin-left:auto}
    .grid .meta{display:none}.grid .info{padding:7px}.grid .title{font-size:11px;-webkit-line-clamp:1}.grid .actions{display:none}
    .bottom{height:64px;display:grid;grid-template-columns:repeat(4,1fr);border-top:1px solid #242424;background:#0b0b0bf6;flex:none}.nav{border:0;background:transparent;color:#868686;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:2px;font-size:10px;font-weight:700}.nav .ico{font-size:19px;line-height:1}.nav.active{color:#fff}.nav.active .ico{transform:scale(1.08)}
    .searchPage,.accountPage,.emptyPage{position:absolute;inset:0;overflow-y:auto;background:#0a0a0a;padding:12px}.searchBox{display:flex;gap:7px;position:sticky;top:0;background:#0a0a0af5;padding:0 0 10px;z-index:2}.searchBox input{flex:1;min-width:0;height:44px;border:1px solid #2b2b2b;border-radius:14px;background:#151515;color:#fff;padding:0 13px;outline:none}.primary{height:44px;border:0;border-radius:14px;background:#f2f2f2;color:#111;padding:0 15px;font-weight:800}.searchResults{height:calc(100% - 54px);position:relative}.searchResults .feed{position:absolute}
    .panel{background:#121212;border:1px solid #252525;border-radius:16px;padding:14px;margin-bottom:10px}.profile{display:flex;align-items:center;gap:11px}.avatar{width:48px;height:48px;border-radius:50%;object-fit:cover;background:#222}.headline{font-weight:800;font-size:17px}.muted{color:#8f8f8f;font-size:12px}.row{display:flex;gap:8px;align-items:center}.row.wrap{flex-wrap:wrap}.sectionTitle{font-size:12px;text-transform:uppercase;letter-spacing:.08em;color:#777;font-weight:800;margin:18px 3px 8px}.subRow{height:48px;display:flex;align-items:center;gap:9px;border-bottom:1px solid #1d1d1d;color:#eee;text-decoration:none}.subIcon{width:30px;height:30px;border-radius:50%;background:#222;object-fit:cover}.subText{min-width:0;flex:1}.subName{font-weight:750;font-size:13px}.subTitle{font-size:10px;color:#777;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
    .modalWrap{position:fixed;inset:0;background:#0009;display:flex;align-items:flex-end;z-index:5}.sheet{width:100%;max-height:82vh;overflow-y:auto;background:#121212;border-radius:22px 22px 0 0;border-top:1px solid #303030;padding:13px 14px 22px}.handle{width:44px;height:4px;border-radius:99px;background:#444;margin:0 auto 12px}.sheetTitle{font-size:17px;font-weight:850;margin-bottom:10px}.options{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}.option{height:46px;border:1px solid #292929;border-radius:13px;background:#191919;color:#eee;font-weight:750}.option.on{background:#eee;color:#111}.closeSheet{width:100%;height:44px;border:0;border-radius:13px;background:#242424;color:#fff;font-weight:800;margin-top:10px}
    .status{position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);background:#151515d9;border:1px solid #2c2c2c;border-radius:13px;padding:10px 13px;font-size:12px;z-index:3}.toast{position:absolute;left:50%;bottom:78px;transform:translateX(-50%);background:#eeeeee;color:#111;border-radius:999px;padding:9px 14px;font-size:12px;font-weight:800;z-index:8;max-width:86%;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
    @media(min-width:720px){.app{left:50%;width:min(520px,100vw);transform:translateX(-50%);border-left:1px solid #222;border-right:1px solid #222}.feed.grid{grid-template-columns:repeat(3,minmax(0,1fr))}}
  `;
  shadow.appendChild(style);
  const app = document.createElement('div'); app.className = 'app'; shadow.appendChild(app);

  function icon(name) {
    const m = { home:'⌂', search:'⌕', fav:'★', account:'●', filter:'≡', sort:'↕', grid:'▦', back:'‹', share:'↗', comment:'◌', save:'☆', saved:'★' };
    return m[name] || '•';
  }

  function topTitle() {
    if (state.route === 'search') return 'Search';
    if (state.route === 'favorites') return 'Favorites';
    if (state.route === 'account') return 'Account';
    if (state.context === 'subreddit') return `r/${state.subreddit}`;
    if (state.context === 'popular') return 'Popular';
    return 'Home';
  }

  function topBar() {
    const back = state.context === 'subreddit' && state.route === 'home';
    return `<div class="top">
      ${back ? `<button class="back" data-action="home">${icon('back')}</button>` : ''}
      <div class="brand">${esc(topTitle())}</div><div class="grow"></div>
      ${state.route === 'home' || state.route === 'search' || state.route === 'favorites' ? `<button class="pill" data-modal="media">${state.media === 'all' ? 'All media' : state.media === 'image' ? 'Images' : 'Video'}</button>` : ''}
      ${state.route === 'search' ? `<button class="pill" data-modal="scope">${state.searchScope==='global'?'Global':'Subs'}</button>` : ''}
      ${state.route === 'home' || state.route === 'search' ? `<button class="pill" data-modal="sort">${state.sort[0].toUpperCase()+state.sort.slice(1)}</button>` : ''}
      ${state.route === 'home' ? `<button class="iconbtn" data-modal="feed">${icon('filter')}</button>` : ''}
    </div>`;
  }

  function bottomBar() {
    return `<div class="bottom">
      <button class="nav ${state.route==='home'?'active':''}" data-route="home"><span class="ico">${icon('home')}</span>Home</button>
      <button class="nav ${state.route==='search'?'active':''}" data-route="search"><span class="ico">${icon('search')}</span>Search</button>
      <button class="nav ${state.route==='favorites'?'active':''}" data-route="favorites"><span class="ico">${icon('fav')}</span>Favorites</button>
      <button class="nav ${state.route==='account'?'active':''}" data-route="account"><span class="ico">${icon('account')}</span>Account</button>
    </div>`;
  }

  function mediaHtml(p) {
    if (p.media.gallery?.length) return `<div class="media"><div class="gallery">${p.media.gallery.map(x=>`<img loading="lazy" src="${esc(x.url)}">`).join('')}</div><span class="badge">${p.media.gallery.length} images</span></div>`;
    if (p.media.type === 'video') return `<div class="media"><video playsinline loop ${state.muted?'muted':''} preload="metadata" poster="${esc(p.media.poster||'')}" src="${esc(p.media.url)}"></video><button class="badge" data-mute="1">${state.muted?'Muted':'Sound'}</button></div>`;
    if (p.media.type === 'external') return `<div class="media"><img loading="lazy" src="${esc(p.media.poster||'')}"><div class="external"><button data-open="${esc(p.url)}">Open media</button></div></div>`;
    return `<div class="media"><img loading="lazy" src="${esc(p.media.url)}"></div>`;
  }

  function cardHtml(p) {
    return `<article class="card" data-id="${esc(p.id)}">
      <div class="meta"><button class="sub" data-sub="${esc(p.subreddit)}">r/${esc(p.subreddit)}</button><span class="author">u/${esc(p.author)}</span><span class="grow"></span>${p.nsfw?'<span class="nsfw">NSFW</span>':''}</div>
      ${mediaHtml(p)}
      <div class="info"><div class="title">${esc(p.title)}</div><div class="actions">
        <button class="act ${p.saved?'saved':''}" data-save="${esc(p.id)}">${p.saved?icon('saved'):icon('save')} ${p.saved?'Saved':'Save'}</button>
        <button class="act" data-comments="${esc(p.permalink)}">${icon('comment')} ${p.comments}</button>
        <button class="act" data-share="${esc(p.permalink)}">${icon('share')} Share</button>
        <span class="act more">▲ ${p.score}</span>
      </div></div>
    </article>`;
  }

  function feedHtml() {
    if (!state.user && state.route === 'favorites') return `<div class="emptyPage"><div class="panel"><div class="headline">Sign in to view Favorites</div><div class="muted">Your Reddit saved posts will appear here.</div><button class="primary" data-login="1" style="margin-top:12px">Sign in to Reddit</button></div></div>`;
    if (!state.posts.length && !state.loading) return `<div class="emptyPage"><div class="panel"><div class="headline">${state.error?'Could not load':'Nothing here yet'}</div><div class="muted">${esc(state.error || (state.media==='all'?'No media posts were found.':'No posts match this media filter.'))}</div></div></div>`;
    return `<div class="feed ${state.layout==='grid'?'grid':''}" id="rv-feed">${state.posts.map(cardHtml).join('')}</div>`;
  }

  function searchPageHtml() {
    return `<div class="searchPage"><form class="searchBox" id="rv-search-form"><input id="rv-search" type="search" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="Search Reddit" value="${esc(state.query)}"><button class="primary">Search</button></form><div class="searchResults">${feedHtml()}</div></div>`;
  }

  function accountPageHtml() {
    if (!state.user) return `<div class="accountPage"><div class="panel"><div class="headline">Reddit account</div><div class="muted">Sign in to access saved posts and your subscriptions.</div><button class="primary" data-login="1" style="margin-top:12px">Sign in to Reddit</button></div><div class="panel"><div class="headline">Browsing</div><div class="row wrap" style="margin-top:10px"><button class="pill" data-modal="media">Media filter</button><button class="pill" data-modal="layout">Layout</button></div></div></div>`;
    const avatar = decode(state.user.icon_img || '');
    return `<div class="accountPage"><div class="panel"><div class="profile">${avatar?`<img class="avatar" src="${esc(avatar)}">`:'<div class="avatar"></div>'}<div><div class="headline">u/${esc(state.user.name)}</div><div class="muted">${Number(state.user.total_karma||0).toLocaleString()} karma</div></div></div></div>
      <div class="panel"><div class="headline">Preferences</div><div class="row wrap" style="margin-top:10px"><button class="pill" data-modal="media">${state.media==='all'?'All media':state.media==='image'?'Images':'Video'}</button><button class="pill" data-modal="layout">${state.layout==='single'?'Single post':'Grid'}</button><button class="pill" data-toggle-mute="1">${state.muted?'Videos muted':'Video sound on'}</button></div></div>
      <div class="sectionTitle">Subscriptions · ${state.subscriptions.length}</div><div class="panel">${state.subscriptions.length?state.subscriptions.map(s=>`<button class="subRow" data-sub="${esc(s.name)}" style="width:100%;border-left:0;border-right:0;border-top:0;background:transparent;text-align:left">${s.icon?`<img class="subIcon" src="${esc(s.icon)}">`:'<div class="subIcon"></div>'}<span class="subText"><span class="subName">r/${esc(s.name)}</span><span class="subTitle">${esc(s.title)}</span></span></button>`).join(''):'<div class="muted">No subscribed communities returned by Reddit.</div>'}</div>
      <div class="panel"><button class="primary" data-account-settings="1" style="width:100%">Open Reddit account settings</button></div></div>`;
  }

  function contentHtml() {
    if (state.route === 'search') return searchPageHtml();
    if (state.route === 'account') return accountPageHtml();
    return feedHtml();
  }

  function modalHtml() {
    if (!state.modal) return '';
    let title = '', body = '';
    if (state.modal === 'sort') {
      title = 'Choose sorting option';
      const opts = state.route === 'search' ? ['best','hot','new','top'] : ['best','hot','new','top','rising'];
      body = `<div class="options">${opts.map(x=>`<button class="option ${state.sort===x?'on':''}" data-sort="${x}">${x[0].toUpperCase()+x.slice(1)}</button>`).join('')}</div>${state.sort==='top'?`<div class="sectionTitle">Top timeframe</div><div class="options">${['hour','day','week','month','year','all'].map(x=>`<button class="option ${state.topTime===x?'on':''}" data-time="${x}">${x==='all'?'All time':x[0].toUpperCase()+x.slice(1)}</button>`).join('')}</div>`:''}`;
    } else if (state.modal === 'media') {
      title = 'Media type'; body = `<div class="options">${[['all','All media'],['image','Images'],['video','Video']].map(([v,l])=>`<button class="option ${state.media===v?'on':''}" data-media="${v}">${l}</button>`).join('')}</div>`;
    } else if (state.modal === 'layout') {
      title = 'Layout'; body = `<div class="options"><button class="option ${state.layout==='single'?'on':''}" data-layout="single">Single post</button><button class="option ${state.layout==='grid'?'on':''}" data-layout="grid">Grid</button></div>`;
    } else if (state.modal === 'feed') {
      title = 'Choose feed'; body = `<div class="options"><button class="option ${state.context==='home'?'on':''}" data-feed="home">Home</button><button class="option ${state.context==='popular'?'on':''}" data-feed="popular">Popular</button><button class="option" data-route="favorites">Favorites</button><button class="option" data-modal-next="layout">Layout</button></div>`;
    } else if (state.modal === 'scope') {
      title = 'Search scope'; body = `<div class="options"><button class="option ${state.searchScope==='global'?'on':''}" data-scope="global">Global</button><button class="option ${state.searchScope==='subscribed'?'on':''}" data-scope="subscribed">Subscribed only</button></div>`;
    }
    return `<div class="modalWrap" data-close="1"><div class="sheet" data-sheet="1"><div class="handle"></div><div class="sheetTitle">${title}</div>${body}${state.route==='search'&&state.modal!=='scope'?`<button class="closeSheet" data-modal-next="scope">Search scope: ${state.searchScope==='global'?'Global':'Subscribed only'}</button>`:''}<button class="closeSheet" data-close="1">Done</button></div></div>`;
  }

  function render() {
    app.innerHTML = `${topBar()}<div class="content">${contentHtml()}</div>${bottomBar()}${modalHtml()}<div id="rv-status"></div><div id="rv-toast"></div>`;
    bind(); activateVideoObserver();
  }

  function renderFeed() {
    const content = app.querySelector('.content');
    if (!content) return render();
    if (state.route === 'search') return render();
    if (state.route === 'account') return render();
    content.innerHTML = feedHtml(); bind(); activateVideoObserver(); renderStatus();
  }

  function renderStatus() {
    const n = app.querySelector('#rv-status');
    if (!n) return;
    n.innerHTML = state.loading ? '<div class="status">Loading…</div>' : '';
  }

  let toastTimer;
  function toast(msg) {
    const n = app.querySelector('#rv-toast'); if (!n) return;
    n.innerHTML = `<div class="toast">${esc(msg)}</div>`;
    clearTimeout(toastTimer); toastTimer = setTimeout(()=>{ if (n) n.innerHTML=''; }, 2200);
  }

  function closeModal() { state.modal = null; render(); }
  function showModal(m) { state.modal = m; vibrate(); render(); }

  function bind() {
    app.querySelectorAll('[data-route]').forEach(b => b.onclick = () => {
      const r = b.dataset.route; vibrate();
      if (r === 'home') openHome('home');
      else if (r === 'favorites') loadSaved();
      else if (r === 'search') { state.route='search'; render(); setTimeout(()=>app.querySelector('#rv-search')?.focus(),50); }
      else if (r === 'account') { state.route='account'; render(); }
    });
    app.querySelectorAll('[data-modal]').forEach(b => b.onclick = () => showModal(b.dataset.modal));
    app.querySelectorAll('[data-modal-next]').forEach(b => b.onclick = e => { e.stopPropagation(); state.modal=b.dataset.modalNext; render(); });
    app.querySelectorAll('[data-close]').forEach(b => b.onclick = e => { if (e.target.closest('[data-sheet]') && !e.target.hasAttribute('data-close')) return; closeModal(); });
    app.querySelectorAll('[data-sheet]').forEach(x => x.onclick = e => e.stopPropagation());
    app.querySelectorAll('[data-media]').forEach(b => b.onclick = () => setMedia(b.dataset.media));
    app.querySelectorAll('[data-sort]').forEach(b => b.onclick = () => setSort(b.dataset.sort));
    app.querySelectorAll('[data-time]').forEach(b => b.onclick = () => setTopTime(b.dataset.time));
    app.querySelectorAll('[data-layout]').forEach(b => b.onclick = () => setLayout(b.dataset.layout));
    app.querySelectorAll('[data-scope]').forEach(b => b.onclick = () => setScope(b.dataset.scope));
    app.querySelectorAll('[data-feed]').forEach(b => b.onclick = () => { closeModal(); openHome(b.dataset.feed); });
    app.querySelectorAll('[data-sub]').forEach(b => b.onclick = () => openSubreddit(b.dataset.sub));
    app.querySelectorAll('[data-login]').forEach(b => b.onclick = openLogin);
    app.querySelectorAll('[data-account-settings]').forEach(b => b.onclick = () => location.href='/settings/account/');
    app.querySelectorAll('[data-toggle-mute]').forEach(b => b.onclick = () => { state.muted=!state.muted; localStorage.setItem('rv2.muted', String(state.muted)); render(); });
    app.querySelectorAll('[data-mute]').forEach(b => b.onclick = e => { const v=e.currentTarget.closest('.media')?.querySelector('video'); if (v) { v.muted=!v.muted; e.currentTarget.textContent=v.muted?'Muted':'Sound'; } });
    app.querySelectorAll('[data-save]').forEach(b => b.onclick = () => { const p=state.posts.find(x=>x.id===b.dataset.save); if(p) toggleSave(p); });
    app.querySelectorAll('[data-comments]').forEach(b => b.onclick = () => { location.href=b.dataset.comments; });
    app.querySelectorAll('[data-open]').forEach(b => b.onclick = () => { location.href=b.dataset.open; });
    app.querySelectorAll('[data-share]').forEach(b => b.onclick = async () => { const url='https://www.reddit.com'+b.dataset.share; try { if(navigator.share) await navigator.share({url}); else { await navigator.clipboard.writeText(url); toast('Link copied'); } } catch(_){} });
    const form = app.querySelector('#rv-search-form');
    if (form) form.onsubmit = e => { e.preventDefault(); const q=app.querySelector('#rv-search')?.value?.trim()||''; state.query=q; if(q) loadSearch(); };
    const feed = app.querySelector('#rv-feed');
    if (feed) feed.onscroll = () => { if (!state.loading && state.after && feed.scrollTop + feed.clientHeight > feed.scrollHeight - feed.clientHeight*1.5 && state.route==='home') loadFeed(false); };
  }

  function activateVideoObserver() {
    const feed = app.querySelector('#rv-feed');
    if (!feed || state.layout === 'grid') return;
    const vids = [...feed.querySelectorAll('video')];
    if (!vids.length) return;
    const io = new IntersectionObserver(entries => {
      for (const e of entries) {
        const v = e.target;
        if (e.isIntersecting && e.intersectionRatio > .72) { v.muted = state.muted; v.play().catch(()=>{}); }
        else v.pause();
      }
    }, { root: feed, threshold: [.2,.72,.95] });
    vids.forEach(v=>io.observe(v));
  }

  const blocker = document.createElement('style');
  blocker.id = 'rv2-blocker';
  blocker.textContent = `body > :not(#${HOST_ID}){display:none!important}html,body{margin:0!important;padding:0!important;overflow:hidden!important;background:#000!important}`;
  document.documentElement.appendChild(blocker);

  (async () => {
    await loadIdentity();
    await loadSubscriptions();
    render();
    await loadFeed(true);
  })();
})();
