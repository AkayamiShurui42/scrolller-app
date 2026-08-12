const $ = id => document.getElementById(id);

const state = {
  scope: 'POSTS',
  searchContext: 'HERE',
  sort: localStorage.getItem('sort') || 'HOT',
  media: localStorage.getItem('media') || 'ALL',
  category: localStorage.getItem('category') || 'ALL',
  nsfw: localStorage.getItem('nsfw') || 'ALL',
  query: localStorage.getItem('query') || '',
  posts: [],
  rawPosts: [],
  history: [],
  activeIndex: 0,
  currentLabel: 'Feed',
  currentCollection: null,
  account: null,
  categoryTitles: []
};

const pending = new Map();
let requestSeq = 0;
let videoObserver = null;
let collectionLoadBusy = false;

window.__nativeMediaResult = (id, ok, body, status) => {
  const item = pending.get(id);
  if (!item) return;
  pending.delete(id);
  if (!ok) item.reject(new Error(`HTTP ${status || 0}: ${body || 'request failed'}`));
  else item.resolve({ body, status });
};

function nativePost(url, body, headers = {}) {
  if (!window.NativeMedia) {
    return fetch(url, { method: 'POST', headers, body, credentials: 'include' })
      .then(async r => ({ body: await r.text(), status: r.status }));
  }
  return new Promise((resolve, reject) => {
    const id = `p${Date.now()}_${++requestSeq}`;
    pending.set(id, { resolve, reject });
    NativeMedia.postJson(id, url, body, JSON.stringify(headers));
  });
}

function toast(message) {
  const el = $('toast');
  el.textContent = message;
  el.classList.remove('hidden');
  clearTimeout(toast._t);
  toast._t = setTimeout(() => el.classList.add('hidden'), 2800);
}

function setBusy(on, text = 'Loading…') {
  $('busy').textContent = text;
  $('busy').classList.toggle('hidden', !on);
}

function openSheet(id) {
  closeSheets();
  $(id).classList.remove('hidden');
}

function closeSheets() {
  document.querySelectorAll('.sheet').forEach(s => s.classList.add('hidden'));
}

function currentVisibleIndex() {
  const f = $('feed');
  if (!f || !state.posts.length) return 0;
  return Math.max(0, Math.min(state.posts.length - 1, Math.round(f.scrollTop / Math.max(1, f.clientHeight))));
}

function snapshot() {
  return {
    scope: state.scope,
    searchContext: state.searchContext,
    sort: state.sort,
    media: state.media,
    category: state.category,
    nsfw: state.nsfw,
    query: state.query,
    posts: state.posts,
    rawPosts: state.rawPosts,
    activeIndex: currentVisibleIndex(),
    currentLabel: state.currentLabel,
    currentCollection: state.currentCollection ? { ...state.currentCollection } : null
  };
}

function pushHistory() {
  if (state.posts.length) state.history.push(snapshot());
  if (state.history.length > 20) state.history.shift();
}

function restore(s) {
  Object.assign(state, s);
  syncControls();
  syncSearchContext();
  $('titleBtn').textContent = state.currentLabel || 'Scrolller';
  renderFeed();
  setTimeout(() => scrollToIndex(s.activeIndex || 0, false), 40);
}

window.ScrolllerNativeBack = function () {
  const open = [...document.querySelectorAll('.sheet')].find(s => !s.classList.contains('hidden'));
  if (open) {
    closeSheets();
    return true;
  }
  if (state.history.length) {
    restore(state.history.pop());
    return true;
  }
  return false;
};

const QUERIES = {
  SubredditQuery: `query SubredditQuery($url:String!,$iterator:String,$sortBy:GallerySortBy,$filter:GalleryFilter,$limit:Int!){getSubreddit(data:{url:$url,iterator:$iterator,filter:$filter,limit:$limit,sortBy:$sortBy}){id url title description isNsfw children{iterator items{id url title subredditId subredditTitle subredditUrl redditPath isNsfw hasAudio createdAt isPaid albumContent{mediaSources{url width height isOptimized}} mediaSources{url width height isOptimized}}}}}`,
  SearchSubreddits: `query SearchSubredditsQuery($query:String!,$limit:Int!,$isNsfw:Boolean!,$pageIndex:Int!){searchSubreddits(data:{query:$query,isNsfw:$isNsfw,limit:$limit,pageIndex:$pageIndex}){id title url secondary_title description is_nsfw item_count preview_images{url width height}}}`,
  SearchCollections: `query SearchCollectionsQuery($query:String!,$limit:Int!,$isNsfw:Boolean!,$pageIndex:Int!){searchCollections(data:{query:$query,isNsfw:$isNsfw,limit:$limit,pageIndex:$pageIndex}){id title description is_nsfw url preview_images{url width height}}}`,
  GetCategories: `query GetCategories($is_nsfw:Boolean!){categories(data:{is_nsfw:$is_nsfw}){title}}`,
  GetCategory: `query GetCategory($url:String!){getCategory(data:{url:$url}){id url title isNsfw}}`,
  GetCategorySubreddits: `query GetCategorySubreddits($categoryId:Int!){getCategorySubreddits(data:{categoryId:$categoryId}){subreddits{subredditUrl}}}`,
  GetUserCollections: `query GetUserCollections{getUserCollections{id url title isNsfw}}`,
  GetLoggedInUser: `query GetLoggedInUser{getLoggedInUser{id username email}}`
};

async function gql(operation, variables = {}) {
  const query = QUERIES[operation];
  if (!query) throw new Error(`Unknown query: ${operation}`);
  const res = await nativePost(
    'https://api.scrolller.com/admin',
    JSON.stringify({ query, variables }),
    { 'Content-Type': 'application/json' }
  );
  const json = JSON.parse(res.body || '{}');
  if (json.errors?.length) throw new Error(json.errors[0].message || 'Scrolller API error');
  return json.data || {};
}

function bestSource(sources = []) {
  return sources
    .filter(s => s?.url)
    .sort((a, b) => ((b.width || 0) * (b.height || 0)) - ((a.width || 0) * (a.height || 0)))[0] || null;
}

function normalizeScrolller(item) {
  if (!item || item.isPaid) return null;
  let album = [];
  if (Array.isArray(item.albumContent)) {
    album = item.albumContent.map(x => bestSource(x.mediaSources || [])?.url).filter(Boolean);
  } else if (item.albumContent && Array.isArray(item.albumContent.mediaSources)) {
    const u = bestSource(item.albumContent.mediaSources)?.url;
    if (u) album = [u];
  }

  const sources = item.mediaSources || [];
  const video = bestSource(sources.filter(s => /\.(mp4|webm)(\?|$)/i.test(s.url || '')));
  const image = bestSource(sources.filter(s => !/\.(mp4|webm)(\?|$)/i.test(s.url || '')));
  const url = album[0] || video?.url || image?.url;
  if (!url) return null;

  const mediaType = album.length > 1 ? 'ALBUM' : video ? 'VIDEO' : /\.gif(\?|$)/i.test(url) ? 'GIF' : 'IMAGE';
  return {
    id: `scrolller:${item.id}`,
    title: item.title || 'Untitled',
    collection: item.subredditTitle || item.subredditUrl || 'Scrolller',
    collectionUrl: item.subredditUrl || '',
    url,
    poster: image?.url || album[0] || '',
    album,
    mediaType,
    nsfw: !!item.isNsfw,
    created: item.createdAt ? Date.parse(item.createdAt) || 0 : 0,
    sourceUrl: item.redditPath ? `https://reddit.com${item.redditPath}` : ''
  };
}

function categorySlug(title) {
  return String(title || '').trim().toLowerCase().replace(/&/g, 'and').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
}

function nsfwBooleanForSearch() {
  return state.nsfw !== 'SFW';
}

async function searchSubreddits(query, maxPages = 4) {
  const all = [];
  const seen = new Set();
  for (let pageIndex = 0; pageIndex < maxPages; pageIndex++) {
    const d = await gql('SearchSubreddits', {
      query: query || '',
      limit: 50,
      isNsfw: nsfwBooleanForSearch(),
      pageIndex
    });
    const items = d.searchSubreddits || [];
    for (const c of items) {
      const key = String(c.id || c.url || '').toLowerCase();
      if (key && !seen.has(key)) {
        seen.add(key);
        all.push(c);
      }
    }
    if (items.length < 50) break;
  }
  return all;
}

async function searchUserCollections(query, maxPages = 3) {
  const all = [];
  const seen = new Set();
  for (let pageIndex = 0; pageIndex < maxPages; pageIndex++) {
    try {
      const d = await gql('SearchCollections', {
        query: query || '',
        limit: 50,
        isNsfw: nsfwBooleanForSearch(),
        pageIndex
      });
      const items = d.searchCollections || [];
      for (const c of items) {
        const key = String(c.id || c.url || '').toLowerCase();
        if (key && !seen.has(key)) {
          seen.add(key);
          all.push(c);
        }
      }
      if (items.length < 50) break;
    } catch (e) {
      break;
    }
  }
  return all;
}

async function scrolllerSubredditPage(url, iterator = null, limit = 50) {
  const d = await gql('SubredditQuery', {
    url,
    iterator,
    sortBy: state.sort === 'RANDOM' ? 'RANDOM' : state.sort,
    filter: null,
    limit
  });
  const sub = d.getSubreddit || {};
  const children = sub.children || {};
  return {
    title: sub.title || url,
    description: sub.description || '',
    url: sub.url || url,
    posts: (children.items || []).map(normalizeScrolller).filter(Boolean),
    iterator: children.iterator || null
  };
}

async function collectSubredditPosts(url, maxItems = 200) {
  const out = [];
  let iterator = null;
  const seenCursors = new Set();
  do {
    const page = await scrolllerSubredditPage(url, iterator, Math.min(50, maxItems - out.length));
    out.push(...page.posts);
    const next = page.iterator;
    if (!next || seenCursors.has(next) || out.length >= maxItems || page.posts.length === 0) break;
    seenCursors.add(next);
    iterator = next;
  } while (out.length < maxItems);
  return out;
}

async function categoryCollections(title) {
  const c = await gql('GetCategory', { url: categorySlug(title) });
  if (!c.getCategory?.id) throw new Error('Category lookup failed');
  const s = await gql('GetCategorySubreddits', { categoryId: c.getCategory.id });
  return (s.getCategorySubreddits?.subreddits || []).map(x => x.subredditUrl).filter(Boolean);
}

async function fetchManyCollections(urls, perCollection = 80, cap = 600) {
  const out = [];
  const uniqueUrls = [...new Set(urls.filter(Boolean))];
  for (let i = 0; i < uniqueUrls.length && out.length < cap; i += 4) {
    const batchUrls = uniqueUrls.slice(i, i + 4);
    const batch = await Promise.allSettled(batchUrls.map(u => collectSubredditPosts(u, perCollection)));
    for (const r of batch) {
      if (r.status === 'fulfilled') out.push(...r.value);
      if (out.length >= cap) break;
    }
  }
  return out.slice(0, cap);
}

function scorePost(post, query) {
  if (!query) return 0;
  const q = query.toLowerCase().trim();
  const title = (post.title || '').toLowerCase();
  const collection = (post.collection || '').toLowerCase();
  const words = q.split(/\s+/).filter(Boolean);
  let score = 0;
  if (title === q) score += 180;
  if (title.includes(q)) score += 120;
  if (collection === q || collection === `r/${q}`) score += 100;
  if (collection.includes(q)) score += 65;
  for (const w of words) {
    if (title.includes(w)) score += 18;
    if (collection.includes(w)) score += 8;
  }
  return score;
}

async function searchScrolllerPosts(query) {
  const q = (query || '').trim();
  let urls = [];

  if (state.category !== 'ALL') {
    urls = await categoryCollections(state.category);
  } else {
    let groups = await searchSubreddits(q, q ? 3 : 1);
    if (q) groups = groups.filter(g => g.item_count !== 0);
    urls = groups.map(g => g.url).filter(Boolean);
    if (!urls.length) urls = q ? [q] : ['funny', 'pics', 'videos', 'aww', 'art'];
  }

  const posts = await fetchManyCollections(urls.slice(0, q ? 16 : 12), q ? 90 : 70, q ? 900 : 500);
  if (!q) return posts;

  const ranked = posts.map(p => ({ p, score: scorePost(p, q) }));
  ranked.sort((a, b) => b.score - a.score || b.p.created - a.p.created);

  const direct = ranked.filter(x => x.score > 0).map(x => x.p);
  const related = ranked.filter(x => x.score === 0).map(x => x.p);
  return [...direct, ...related];
}

async function searchWithinCurrentCollection(query) {
  if (!state.currentCollection?.url) return [];
  const q = (query || '').trim().toLowerCase();
  const corpus = await collectSubredditPosts(state.currentCollection.url, 700);
  if (!q) return corpus;
  const words = q.split(/\s+/).filter(Boolean);
  return corpus
    .map(p => {
      const title = (p.title || '').toLowerCase();
      let score = title.includes(q) ? 100 : 0;
      for (const w of words) if (title.includes(w)) score += 10;
      return { p, score };
    })
    .filter(x => x.score > 0)
    .sort((a, b) => b.score - a.score)
    .map(x => x.p);
}

function filterPosts(posts) {
  const seen = new Set();
  let out = posts.filter(p => {
    if (!p?.url) return false;
    if (state.media !== 'ALL' && p.mediaType !== state.media) return false;
    if (state.nsfw === 'SFW' && p.nsfw) return false;
    if (state.nsfw === 'NSFW' && !p.nsfw) return false;
    const key = p.url.replace(/\?.*$/, '').toLowerCase();
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
  if (state.sort === 'NEW') out.sort((a, b) => b.created - a.created);
  if (state.sort === 'RANDOM') out.sort(() => Math.random() - 0.5);
  return out;
}

async function runSearch(query = state.query, push = true, forceGlobal = false) {
  if (push) pushHistory();
  closeSheets();
  state.query = (query || '').trim();
  localStorage.setItem('query', state.query);
  setBusy(true, 'Searching posts…');
  $('empty').classList.add('hidden');
  try {
    const useCurrent = !forceGlobal && state.searchContext === 'HERE' && state.currentCollection?.url;
    state.rawPosts = useCurrent
      ? await searchWithinCurrentCollection(state.query)
      : await searchScrolllerPosts(state.query);
    state.posts = filterPosts(state.rawPosts);
    state.activeIndex = 0;
    state.currentLabel = useCurrent
      ? `${state.currentCollection.title}: ${state.query || 'All posts'}`
      : state.query ? `Search: ${state.query}` : (state.category !== 'ALL' ? state.category : 'Feed');
    $('titleBtn').textContent = state.currentLabel;
    renderFeed();
  } catch (e) {
    state.posts = [];
    renderFeed();
    toast(e.message);
  } finally {
    setBusy(false);
  }
}

function escapeHtml(s = '') {
  return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function mediaMarkup(p) {
  if (p.mediaType === 'VIDEO' || p.mediaType === 'GIF') {
    return `<div class="media-frame"><video class="post-media" src="${escapeHtml(p.url)}" ${p.poster ? `poster="${escapeHtml(p.poster)}"` : ''} loop playsinline muted preload="metadata"></video></div>`;
  }
  return `<div class="media-frame"><img class="post-media" src="${escapeHtml(p.url)}" alt=""></div>`;
}

function createPostElement(p, i) {
  const el = document.createElement('section');
  el.className = 'post';
  el.dataset.index = String(i);
  el.innerHTML = `${mediaMarkup(p)}
    <div class="post-gradient"></div>
    ${p.album?.length > 1 ? `<div class="album-count">1/${p.album.length}</div>` : ''}
    <div class="post-info">
      <div class="post-title">${escapeHtml(p.title)}</div>
      <div class="post-meta">
        <button class="collection-link" type="button">${escapeHtml(p.collection || 'Scrolller')}</button>
        <span class="media-label">${escapeHtml(p.mediaType)}</span>
      </div>
    </div>
    <div class="actions">
      <button class="action similar" type="button"><b>≈</b>Similar</button>
      ${p.album?.length > 1 ? '<button class="action next-album" type="button"><b>›</b>Album</button>' : ''}
      ${p.mediaType === 'VIDEO' || p.mediaType === 'GIF' ? '<button class="action mute" type="button"><b>◖</b>Sound</button>' : ''}
    </div>`;

  el.querySelector('.collection-link').onclick = e => {
    e.stopPropagation();
    if (p.collectionUrl) openCollectionByUrl(p.collectionUrl, p.collection);
  };
  el.querySelector('.similar').onclick = () => similarFrom(p);

  const mute = el.querySelector('.mute');
  if (mute) {
    mute.onclick = () => {
      const v = el.querySelector('video');
      if (!v) return;
      v.muted = !v.muted;
      mute.innerHTML = `<b>${v.muted ? '◖' : '◗'}</b>${v.muted ? 'Sound' : 'Mute'}`;
    };
  }

  const albumButton = el.querySelector('.next-album');
  if (albumButton) {
    let ai = 0;
    albumButton.onclick = () => {
      ai = (ai + 1) % p.album.length;
      el.querySelector('.post-media').src = p.album[ai];
      el.querySelector('.album-count').textContent = `${ai + 1}/${p.album.length}`;
    };
  }
  return el;
}

function ensureVideoObserver(reset = false) {
  if (reset && videoObserver) {
    videoObserver.disconnect();
    videoObserver = null;
  }
  if (!videoObserver) {
    videoObserver = new IntersectionObserver(entries => entries.forEach(e => {
      const v = e.target;
      if (e.isIntersecting && e.intersectionRatio > 0.7) v.play().catch(() => {});
      else v.pause();
    }), { threshold: [0.2, 0.7, 0.95] });
  }
}

function renderFeed() {
  const feed = $('feed');
  ensureVideoObserver(true);
  feed.innerHTML = '';
  $('empty').classList.toggle('hidden', !!state.posts.length);
  const frag = document.createDocumentFragment();
  state.posts.forEach((p, i) => frag.appendChild(createPostElement(p, i)));
  feed.appendChild(frag);
  feed.querySelectorAll('video.post-media').forEach(v => videoObserver.observe(v));
  setTimeout(() => scrollToIndex(Math.min(state.activeIndex, Math.max(0, state.posts.length - 1)), false), 30);
}

function appendFeedPosts(newPosts) {
  if (!newPosts.length) return;
  ensureVideoObserver(false);
  const feed = $('feed');
  const start = state.posts.length - newPosts.length;
  const frag = document.createDocumentFragment();
  newPosts.forEach((p, j) => frag.appendChild(createPostElement(p, start + j)));
  feed.appendChild(frag);
  [...feed.children].slice(start).forEach(el => {
    const v = el.querySelector('video.post-media');
    if (v) videoObserver.observe(v);
  });
}

function scrollToIndex(i, smooth = true) {
  const c = $('feed').children[i];
  if (c) c.scrollIntoView({ behavior: smooth ? 'smooth' : 'auto', block: 'start' });
}

async function loadMoreCurrentCollection() {
  const c = state.currentCollection;
  if (!c?.url || c.exhausted || collectionLoadBusy || !c.nextIterator) return;
  collectionLoadBusy = true;
  try {
    const page = await scrolllerSubredditPage(c.url, c.nextIterator, 50);
    c.nextIterator = page.iterator;
    if (!page.iterator || !page.posts.length) c.exhausted = true;
    const existing = new Set(state.posts.map(p => p.url.replace(/\?.*$/, '').toLowerCase()));
    const add = filterPosts(page.posts).filter(p => {
      const k = p.url.replace(/\?.*$/, '').toLowerCase();
      if (existing.has(k)) return false;
      existing.add(k);
      return true;
    });
    state.rawPosts.push(...page.posts);
    state.posts.push(...add);
    appendFeedPosts(add);
  } catch (e) {
    c.exhausted = true;
  } finally {
    collectionLoadBusy = false;
  }
}

$('feed').addEventListener('scroll', () => {
  state.activeIndex = currentVisibleIndex();
  if (state.currentCollection && state.activeIndex >= Math.max(0, state.posts.length - 6)) loadMoreCurrentCollection();
}, { passive: true });

async function similarFrom(post) {
  const words = (post.title || '')
    .toLowerCase().replace(/[^a-z0-9 ]/g, ' ').split(/\s+/)
    .filter(w => w.length > 3 && !['this', 'that', 'with', 'from', 'have', 'your', 'just', 'when', 'what'].includes(w))
    .slice(0, 6);
  state.searchContext = 'ALL';
  await runSearch(words.join(' ') || post.collection, true, true);
}

async function openCollectionByUrl(url, title = '') {
  if (!url) return;
  pushHistory();
  closeSheets();
  setBusy(true, 'Opening collection…');
  try {
    const page = await scrolllerSubredditPage(url, null, 50);
    state.currentCollection = {
      url,
      title: title || page.title || url,
      nextIterator: page.iterator,
      exhausted: !page.iterator
    };
    state.searchContext = 'HERE';
    state.query = '';
    state.category = 'ALL';
    state.currentLabel = state.currentCollection.title;
    $('titleBtn').textContent = state.currentLabel;
    state.rawPosts = page.posts;
    state.posts = filterPosts(page.posts);
    state.activeIndex = 0;
    syncControls();
    syncSearchContext();
    renderFeed();
  } catch (e) {
    toast(e.message);
  } finally {
    setBusy(false);
  }
}

async function loadCategories() {
  try {
    const requests = state.nsfw === 'SFW'
      ? [gql('GetCategories', { is_nsfw: false })]
      : state.nsfw === 'NSFW'
        ? [gql('GetCategories', { is_nsfw: true })]
        : [gql('GetCategories', { is_nsfw: false }), gql('GetCategories', { is_nsfw: true })];
    const groups = await Promise.all(requests);
    state.categoryTitles = [...new Set(groups.flatMap(d => (d.categories || []).map(x => x.title).filter(Boolean)))].sort((a, b) => a.localeCompare(b));

    const select = $('categorySelect');
    select.innerHTML = '<option value="ALL">All categories</option>';
    state.categoryTitles.forEach(t => {
      const o = document.createElement('option');
      o.value = t;
      o.textContent = t;
      select.appendChild(o);
    });
    if ([...select.options].some(o => o.value === state.category)) select.value = state.category;

    const box = $('categoryResults');
    box.innerHTML = '';
    state.categoryTitles.forEach(t => {
      const b = document.createElement('button');
      b.className = 'category-chip';
      b.textContent = t;
      b.onclick = () => showCategoryCollections(t);
      box.appendChild(b);
    });
  } catch (e) {
    $('categoryResults').innerHTML = '<span class="muted">Could not load website categories.</span>';
  }
}

async function showCategoryCollections(title) {
  const box = $('collectionResults');
  box.innerHTML = `<div class="result">Loading ${escapeHtml(title)}…</div>`;
  try {
    const urls = await categoryCollections(title);
    box.innerHTML = '';
    urls.forEach(url => {
      const el = document.createElement('div');
      el.className = 'result';
      el.innerHTML = `<div class="result-title">${escapeHtml(url)}</div><div class="result-sub">${escapeHtml(title)} collection</div>`;
      el.onclick = () => openCollectionByUrl(url, url);
      box.appendChild(el);
    });
    if (!urls.length) box.innerHTML = '<div class="result">No collections in this category.</div>';
  } catch (e) {
    box.innerHTML = `<div class="result">${escapeHtml(e.message)}</div>`;
  }
}

async function showCollectionResults(query, target = 'collectionResults') {
  const box = $(target);
  box.innerHTML = '<div class="result">Searching all collections…</div>';
  try {
    const q = (query || '').trim();
    const items = await searchSubreddits(q, 6);
    box.innerHTML = '';
    items.forEach(c => {
      const el = document.createElement('div');
      el.className = 'result';
      el.innerHTML = `<div class="result-title">${escapeHtml(c.title || c.url)}</div><div class="result-sub">${escapeHtml(c.description || '')}${Number.isFinite(c.item_count) ? ` · ${c.item_count} items` : ''}</div>`;
      el.onclick = () => openCollectionByUrl(c.url, c.title || c.url);
      box.appendChild(el);
    });
    if (!items.length) box.innerHTML = '<div class="result">No collections found.</div>';
  } catch (e) {
    box.innerHTML = `<div class="result">${escapeHtml(e.message)}</div>`;
  }
}

async function showMyCollections() {
  const box = $('collectionResults');
  box.innerHTML = '<div class="result">Loading account collections…</div>';
  try {
    const d = await gql('GetUserCollections', {});
    const items = d.getUserCollections || [];
    box.innerHTML = '';
    items.forEach(c => {
      const el = document.createElement('div');
      el.className = 'result';
      el.innerHTML = `<div class="result-title">${escapeHtml(c.title || c.url)}</div><div class="result-sub">Your Scrolller collection</div>`;
      el.onclick = () => openCollectionByUrl(c.url, c.title || c.url);
      box.appendChild(el);
    });
    if (!items.length) box.innerHTML = '<div class="result">No account collections were returned.</div>';
  } catch (e) {
    box.innerHTML = '<div class="result">Sign in first, then return with Android Back.</div>';
  }
}

async function checkAccount() {
  const status = $('accountStatus');
  try {
    const d = await gql('GetLoggedInUser', {});
    const u = d.getLoggedInUser;
    if (u?.username) {
      state.account = u;
      status.textContent = `Signed in as ${u.username}`;
      $('loginBtn').textContent = 'Refresh sign-in';
      $('accountBtn').textContent = '✓';
      return;
    }
  } catch (e) {}
  state.account = null;
  status.textContent = 'Not signed in';
  $('loginBtn').textContent = 'Sign in to Scrolller';
  $('accountBtn').textContent = '●';
}

function syncSearchContext() {
  const visible = state.scope === 'POSTS' && !!state.currentCollection?.url;
  $('searchContext').classList.toggle('hidden', !visible);
  if (visible) $('searchContextLabel').textContent = `Search inside ${state.currentCollection.title}`;
  $('searchHereBtn').classList.toggle('active', state.searchContext === 'HERE');
  $('searchEverywhereBtn').classList.toggle('active', state.searchContext === 'ALL');
}

function syncControls() {
  document.querySelectorAll('#scopeSwitch button').forEach(b => b.classList.toggle('active', b.dataset.scope === state.scope));
  $('sortSelect').value = state.sort;
  $('mediaSelect').value = state.media;
  $('nsfwSelect').value = state.nsfw;
  if ([...$('categorySelect').options].some(o => o.value === state.category)) $('categorySelect').value = state.category;
  syncSearchContext();
}

document.querySelectorAll('[data-close]').forEach(b => b.onclick = closeSheets);
document.querySelectorAll('#scopeSwitch button').forEach(b => b.onclick = () => {
  state.scope = b.dataset.scope;
  syncControls();
});

$('searchHereBtn').onclick = () => { state.searchContext = 'HERE'; syncSearchContext(); };
$('searchEverywhereBtn').onclick = () => { state.searchContext = 'ALL'; syncSearchContext(); };

$('backBtn').onclick = () => window.ScrolllerNativeBack();
$('searchBtn').onclick = () => {
  syncSearchContext();
  openSheet('searchSheet');
  setTimeout(() => $('searchInput').focus(), 70);
};
$('filterBtn').onclick = () => openSheet('filterSheet');
$('collectionsBtn').onclick = () => openSheet('collectionsSheet');
$('accountBtn').onclick = async () => { openSheet('accountSheet'); await checkAccount(); };
$('feedBtn').onclick = async () => {
  state.currentCollection = null;
  state.searchContext = 'ALL';
  state.category = 'ALL';
  state.query = '';
  await runSearch('', true, true);
};
$('randomBtn').onclick = async () => {
  state.sort = 'RANDOM';
  localStorage.setItem('sort', state.sort);
  await runSearch(state.query, true, state.searchContext === 'ALL');
};

$('searchForm').onsubmit = async e => {
  e.preventDefault();
  const q = $('searchInput').value.trim();
  if (state.scope === 'COLLECTIONS') await showCollectionResults(q, 'searchResults');
  else await runSearch(q, true, state.searchContext === 'ALL');
};

$('collectionSearchBtn').onclick = () => showCollectionResults($('collectionQuery').value.trim());
$('myCollectionsBtn').onclick = showMyCollections;

$('applyFilters').onclick = async () => {
  state.sort = $('sortSelect').value;
  state.media = $('mediaSelect').value;
  state.category = $('categorySelect').value;
  state.nsfw = $('nsfwSelect').value;
  ['sort', 'media', 'category', 'nsfw'].forEach(k => localStorage.setItem(k, state[k]));
  closeSheets();
  await loadCategories();
  if (state.currentCollection?.url && state.category === 'ALL') {
    await openCollectionByUrl(state.currentCollection.url, state.currentCollection.title);
  } else {
    state.currentCollection = null;
    state.searchContext = 'ALL';
    await runSearch(state.query, true, true);
  }
};

$('loginBtn').onclick = () => {
  try {
    if (window.NativeAuth) NativeAuth.openLogin();
    else toast('Sign-in is available in the Android app.');
  } catch (e) {
    toast(e.message);
  }
};

(async function init() {
  syncControls();
  await loadCategories();
  await checkAccount();
  state.searchContext = 'ALL';
  await runSearch(state.query, false, true);
})();
