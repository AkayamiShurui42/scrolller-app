const $ = id => document.getElementById(id);

const PRELOAD_LIMIT = 5000;
const SEARCH_COLLECTION_LIMIT = 8;
const INITIAL_RENDER_COUNT = 80;
const RENDER_CHUNK = 60;
const DEFAULT_COLLECTIONS = ['funny', 'pics', 'videos', 'aww', 'art'];

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
  renderedCount: 0,
  currentLabel: 'Feed',
  currentCollection: null,
  account: null,
  categoryTitles: [],
  savePost: null
};

const pending = new Map();
const preloadCache = new Map();
const userCollectionCache = new Map();
let requestSeq = 0;
let videoObserver = null;

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
  toast._t = setTimeout(() => el.classList.add('hidden'), 3000);
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
    renderedCount: state.renderedCount,
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
  renderFeed(Math.max(INITIAL_RENDER_COUNT, s.renderedCount || 0));
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

const POST_FIELDS = `
  id url title subredditId subredditTitle subredditUrl redditPath isNsfw hasAudio createdAt isPaid
  favoriteCount isFavorite
  albumContent{mediaSources{url width height isOptimized}}
  mediaSources{url width height isOptimized}
`;

const QUERIES = {
  SubredditQuery: `query SubredditQuery($url:String!,$iterator:String,$sortBy:GallerySortBy,$filter:GalleryFilter,$limit:Int!){getSubreddit(data:{url:$url,iterator:$iterator,filter:$filter,limit:$limit,sortBy:$sortBy}){id url title description isNsfw children{iterator items{${POST_FIELDS}}}}}`,
  SearchSubreddits: `query SearchSubredditsQuery($query:String!,$limit:Int!,$isNsfw:Boolean!,$pageIndex:Int!){searchSubreddits(data:{query:$query,isNsfw:$isNsfw,limit:$limit,pageIndex:$pageIndex}){id title url secondary_title description is_nsfw item_count preview_images{url width height}}}`,
  SearchCollections: `query SearchCollectionsQuery($query:String!,$limit:Int!,$isNsfw:Boolean!,$pageIndex:Int!){searchCollections(data:{query:$query,isNsfw:$isNsfw,limit:$limit,pageIndex:$pageIndex}){id title description is_nsfw url preview_images{url width height}}}`,
  GetCategories: `query GetCategories($is_nsfw:Boolean!){categories(data:{is_nsfw:$is_nsfw}){title}}`,
  GetCategory: `query GetCategory($url:String!){getCategory(data:{url:$url}){id url title isNsfw}}`,
  GetCategorySubreddits: `query GetCategorySubreddits($categoryId:Int!){getCategorySubreddits(data:{categoryId:$categoryId}){subreddits{subredditUrl}}}`,
  GetUserCollections: `query GetUserCollectionsQuery{getUserCollections{id title url is_nsfw is_private description posts{id}}}`,
  UserCollectionContent: `query getUserCollectionContentQuery($iterator:String,$nsfw:NsfwFilter,$filter:GalleryFilter,$sortBy:GallerySortBy,$limit:Int!,$collectionId:Int!){getUserCollectionContent(data:{nsfw:$nsfw,limit:$limit,sortBy:$sortBy,filter:$filter,iterator:$iterator,collectionId:$collectionId}){iterator items{${POST_FIELDS}}}}`,
  GetLoggedInUser: `query GetLoggedInUser{getLoggedInUser{id username email}}`,
  AddFavorite: `mutation AddFavorite($url:String!){addFavorite(url:$url)}`,
  RemoveFavorite: `mutation RemoveFavorite($url:String!){removeFavorite(url:$url)}`,
  AddPostToCollection: `mutation AddPostToCollection($postId:Int!,$collectionId:Int!){addPostToCollection(data:{postId:$postId,collectionId:$collectionId})}`
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
  if (json.errors?.length) throw new Error(json.errors.map(x => x.message || 'Scrolller API error').join(' · '));
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
  const mediaUrl = album[0] || video?.url || image?.url;
  if (!mediaUrl) return null;

  const mediaType = album.length > 1 ? 'ALBUM' : video ? 'VIDEO' : /\.gif(\?|$)/i.test(mediaUrl) ? 'GIF' : 'IMAGE';
  return {
    id: `scrolller:${item.id}`,
    postId: Number(item.id),
    postUrl: item.url || '',
    title: item.title || 'Untitled',
    collection: item.subredditTitle || item.subredditUrl || 'Scrolller',
    collectionUrl: item.subredditUrl || '',
    url: mediaUrl,
    poster: image?.url || album[0] || '',
    album,
    mediaType,
    nsfw: !!item.isNsfw,
    created: item.createdAt ? Date.parse(item.createdAt) || 0 : 0,
    sourceUrl: item.redditPath ? `https://reddit.com${item.redditPath}` : '',
    isFavorite: !!item.isFavorite,
    favoriteCount: Number(item.favoriteCount || 0)
  };
}

function categorySlug(title) {
  return String(title || '').trim().toLowerCase().replace(/&/g, 'and').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
}

function normalizeCollectionUrl(url) {
  return String(url || '').trim().replace(/^https?:\/\/(?:www\.)?scrolller\.com\//i, '').replace(/^\/+/, '').replace(/^r\//i, '').replace(/\/+$/, '');
}

function nsfwBooleanForSearch() {
  return state.nsfw !== 'SFW';
}

function nsfwEnum() {
  if (state.nsfw === 'SFW') return 'SFW';
  if (state.nsfw === 'NSFW') return 'NSFW';
  return 'ALL';
}

function preloadKey(url) {
  return `${normalizeCollectionUrl(url).toLowerCase()}|${state.sort}|${state.nsfw}`;
}

async function searchSubreddits(query) {
  const d = await gql('SearchSubreddits', {
    query: (query || '').trim(),
    limit: 50,
    isNsfw: nsfwBooleanForSearch(),
    pageIndex: 0
  });
  return d.searchSubreddits || [];
}

async function searchPublicCollections(query) {
  try {
    const d = await gql('SearchCollections', {
      query: (query || '').trim(),
      limit: 50,
      isNsfw: nsfwBooleanForSearch(),
      pageIndex: 0
    });
    return d.searchCollections || [];
  } catch (e) {
    return [];
  }
}

async function preloadSubreddit(url, limit = PRELOAD_LIMIT) {
  const clean = normalizeCollectionUrl(url);
  if (!clean) throw new Error('Invalid collection URL');
  const key = preloadKey(clean);
  if (preloadCache.has(key)) return preloadCache.get(key);

  const promise = (async () => {
    const d = await gql('SubredditQuery', {
      url: clean,
      iterator: null,
      sortBy: state.sort === 'RANDOM' ? 'RANDOM' : state.sort,
      filter: null,
      limit
    });
    const sub = d.getSubreddit || {};
    const children = sub.children || {};
    return {
      title: sub.title || clean,
      description: sub.description || '',
      url: sub.url || clean,
      posts: (children.items || []).map(normalizeScrolller).filter(Boolean)
    };
  })();

  preloadCache.set(key, promise);
  try {
    const result = await promise;
    preloadCache.set(key, result);
    return result;
  } catch (e) {
    preloadCache.delete(key);
    throw e;
  }
}

async function preloadUserCollection(collection) {
  const id = Number(collection?.id);
  if (!Number.isFinite(id)) throw new Error('Invalid account collection');
  const key = `${id}|${state.sort}|${state.nsfw}`;
  if (userCollectionCache.has(key)) return userCollectionCache.get(key);
  const promise = (async () => {
    const d = await gql('UserCollectionContent', {
      iterator: null,
      nsfw: nsfwEnum(),
      filter: null,
      sortBy: state.sort === 'RANDOM' ? 'RANDOM' : state.sort,
      limit: PRELOAD_LIMIT,
      collectionId: id
    });
    const root = d.getUserCollectionContent || {};
    return {
      title: collection.title || collection.url || `Collection ${id}`,
      url: collection.url || '',
      id,
      posts: (root.items || []).map(normalizeScrolller).filter(Boolean)
    };
  })();
  userCollectionCache.set(key, promise);
  try {
    const result = await promise;
    userCollectionCache.set(key, result);
    return result;
  } catch (e) {
    userCollectionCache.delete(key);
    throw e;
  }
}

async function categoryCollections(title) {
  const c = await gql('GetCategory', { url: categorySlug(title) });
  if (!c.getCategory?.id) throw new Error('Category lookup failed');
  const s = await gql('GetCategorySubreddits', { categoryId: c.getCategory.id });
  return (s.getCategorySubreddits?.subreddits || []).map(x => x.subredditUrl).filter(Boolean);
}

async function preloadManyCollections(urls, maxCollections = SEARCH_COLLECTION_LIMIT) {
  const unique = [...new Set((urls || []).map(normalizeCollectionUrl).filter(Boolean))].slice(0, maxCollections);
  const settled = await Promise.allSettled(unique.map(u => preloadSubreddit(u)));
  return settled.flatMap(r => r.status === 'fulfilled' ? r.value.posts : []);
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
  } else if (q) {
    const groups = (await searchSubreddits(q)).filter(g => g.item_count !== 0 && g.url);
    urls = groups.map(g => g.url);
    if (!urls.length) urls = [q];
  } else {
    urls = DEFAULT_COLLECTIONS;
  }

  const posts = await preloadManyCollections(urls, q ? SEARCH_COLLECTION_LIMIT : DEFAULT_COLLECTIONS.length);
  if (!q) return posts;

  return posts
    .map(p => ({ p, score: scorePost(p, q) }))
    .sort((a, b) => b.score - a.score || b.p.created - a.p.created)
    .map(x => x.p);
}

function searchCorpus(corpus, query) {
  const q = (query || '').trim().toLowerCase();
  if (!q) return [...corpus];
  const words = q.split(/\s+/).filter(Boolean);
  return corpus
    .map(p => {
      const title = (p.title || '').toLowerCase();
      const collection = (p.collection || '').toLowerCase();
      let score = title.includes(q) ? 100 : 0;
      if (collection.includes(q)) score += 40;
      for (const w of words) {
        if (title.includes(w)) score += 10;
        if (collection.includes(w)) score += 4;
      }
      return { p, score };
    })
    .filter(x => x.score > 0)
    .sort((a, b) => b.score - a.score)
    .map(x => x.p);
}

async function searchWithinCurrentCollection(query) {
  const corpus = state.currentCollection?.corpus || state.rawPosts || [];
  return searchCorpus(corpus, query);
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
  if (state.sort === 'RANDOM') out = [...out].sort(() => Math.random() - 0.5);
  return out;
}

async function runSearch(query = state.query, push = true, forceGlobal = false) {
  if (push) pushHistory();
  closeSheets();
  state.query = (query || '').trim();
  localStorage.setItem('query', state.query);
  setBusy(true, state.currentCollection && !forceGlobal ? 'Searching preloaded posts…' : 'Preloading posts…');
  $('empty').classList.add('hidden');

  try {
    const useCurrent = !forceGlobal && state.searchContext === 'HERE' && state.currentCollection?.corpus;
    state.rawPosts = useCurrent
      ? await searchWithinCurrentCollection(state.query)
      : await searchScrolllerPosts(state.query);
    state.posts = filterPosts(state.rawPosts);
    state.activeIndex = 0;
    state.renderedCount = 0;
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
  return `<div class="media-frame"><img class="post-media" src="${escapeHtml(p.url)}" alt="" loading="lazy" decoding="async"></div>`;
}

function favoriteButtonMarkup(p) {
  return `<button class="action favorite${p.isFavorite ? ' active' : ''}" type="button"><b>${p.isFavorite ? '♥' : '♡'}</b>${p.isFavorite ? 'Saved' : 'Favorite'}</button>`;
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
      ${favoriteButtonMarkup(p)}
      <button class="action save-collection" type="button"><b>＋</b>Collection</button>
      <button class="action similar" type="button"><b>≈</b>Similar</button>
      ${p.album?.length > 1 ? '<button class="action next-album" type="button"><b>›</b>Album</button>' : ''}
      ${p.mediaType === 'VIDEO' || p.mediaType === 'GIF' ? '<button class="action mute" type="button"><b>◖</b>Sound</button>' : ''}
    </div>`;

  el.querySelector('.collection-link').onclick = e => {
    e.stopPropagation();
    if (p.collectionUrl) openCollectionByUrl(p.collectionUrl, p.collection);
  };
  el.querySelector('.similar').onclick = () => similarFrom(p);
  el.querySelector('.favorite').onclick = e => toggleFavorite(p, e.currentTarget);
  el.querySelector('.save-collection').onclick = () => openSaveToCollection(p);

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

function appendBufferedPosts(targetCount) {
  const feed = $('feed');
  const from = state.renderedCount;
  const to = Math.min(state.posts.length, Math.max(targetCount, from));
  if (to <= from) return;
  ensureVideoObserver(false);
  const frag = document.createDocumentFragment();
  for (let i = from; i < to; i++) frag.appendChild(createPostElement(state.posts[i], i));
  feed.appendChild(frag);
  [...feed.children].slice(from).forEach(el => {
    const v = el.querySelector('video.post-media');
    if (v) videoObserver.observe(v);
  });
  state.renderedCount = to;
}

function renderFeed(requestedCount = INITIAL_RENDER_COUNT) {
  const feed = $('feed');
  ensureVideoObserver(true);
  feed.innerHTML = '';
  state.renderedCount = 0;
  $('empty').classList.toggle('hidden', !!state.posts.length);
  appendBufferedPosts(Math.max(requestedCount, Math.min(INITIAL_RENDER_COUNT, state.posts.length)));
  setTimeout(() => scrollToIndex(Math.min(state.activeIndex, Math.max(0, state.renderedCount - 1)), false), 30);
}

function scrollToIndex(i, smooth = true) {
  if (i >= state.renderedCount) appendBufferedPosts(i + RENDER_CHUNK);
  const c = $('feed').children[i];
  if (c) c.scrollIntoView({ behavior: smooth ? 'smooth' : 'auto', block: 'start' });
}

$('feed').addEventListener('scroll', () => {
  state.activeIndex = currentVisibleIndex();
  if (state.renderedCount < state.posts.length && state.activeIndex >= Math.max(0, state.renderedCount - 8)) {
    appendBufferedPosts(state.renderedCount + RENDER_CHUNK);
  }
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
  setBusy(true, 'Preloading entire collection…');
  try {
    const loaded = await preloadSubreddit(url);
    const collection = {
      kind: 'subreddit',
      url: normalizeCollectionUrl(url),
      title: title || loaded.title || url,
      corpus: loaded.posts
    };
    state.currentCollection = collection;
    state.searchContext = 'HERE';
    state.query = '';
    state.category = 'ALL';
    state.currentLabel = collection.title;
    $('titleBtn').textContent = state.currentLabel;
    state.rawPosts = collection.corpus;
    state.posts = filterPosts(collection.corpus);
    state.activeIndex = 0;
    state.renderedCount = 0;
    syncControls();
    syncSearchContext();
    renderFeed();
  } catch (e) {
    toast(e.message);
  } finally {
    setBusy(false);
  }
}

async function openUserCollection(collection) {
  pushHistory();
  closeSheets();
  setBusy(true, 'Preloading your collection…');
  try {
    const loaded = await preloadUserCollection(collection);
    state.currentCollection = {
      kind: 'user',
      id: Number(collection.id),
      url: collection.url || '',
      title: loaded.title,
      corpus: loaded.posts
    };
    state.searchContext = 'HERE';
    state.query = '';
    state.category = 'ALL';
    state.currentLabel = loaded.title;
    $('titleBtn').textContent = state.currentLabel;
    state.rawPosts = loaded.posts;
    state.posts = filterPosts(loaded.posts);
    state.activeIndex = 0;
    state.renderedCount = 0;
    syncControls();
    syncSearchContext();
    renderFeed();
  } catch (e) {
    toast(e.message);
  } finally {
    setBusy(false);
  }
}

function hasNativeToken() {
  try {
    return !!(window.NativeAuth && NativeAuth.getToken && NativeAuth.getToken());
  } catch (e) {
    return false;
  }
}

async function ensureSignedIn() {
  if (state.account || hasNativeToken()) return true;
  await checkAccount();
  if (state.account || hasNativeToken()) return true;
  openSheet('accountSheet');
  toast('Sign in to save posts.');
  return false;
}

async function toggleFavorite(post, button) {
  if (!post.postUrl || !(await ensureSignedIn())) return;
  const previous = post.isFavorite;
  post.isFavorite = !previous;
  if (button) {
    button.classList.toggle('active', post.isFavorite);
    button.innerHTML = `<b>${post.isFavorite ? '♥' : '♡'}</b>${post.isFavorite ? 'Saved' : 'Favorite'}`;
  }
  try {
    await gql(post.isFavorite ? 'AddFavorite' : 'RemoveFavorite', { url: post.postUrl });
    post.favoriteCount = Math.max(0, post.favoriteCount + (post.isFavorite ? 1 : -1));
  } catch (e) {
    post.isFavorite = previous;
    if (button) {
      button.classList.toggle('active', post.isFavorite);
      button.innerHTML = `<b>${post.isFavorite ? '♥' : '♡'}</b>${post.isFavorite ? 'Saved' : 'Favorite'}`;
    }
    toast(e.message);
  }
}

async function getMyCollections() {
  const d = await gql('GetUserCollections', {});
  return d.getUserCollections || [];
}

async function openSaveToCollection(post) {
  if (!Number.isFinite(post.postId) || !(await ensureSignedIn())) return;
  state.savePost = post;
  $('savePostTitle').textContent = post.title || 'Post';
  const box = $('saveCollectionResults');
  box.innerHTML = '<div class="result">Loading your collections…</div>';
  openSheet('saveSheet');
  try {
    const collections = await getMyCollections();
    box.innerHTML = '';
    for (const c of collections) {
      const added = (c.posts || []).some(x => Number(x.id) === post.postId);
      const el = document.createElement('button');
      el.className = `result save-target${added ? ' already-added' : ''}`;
      el.type = 'button';
      el.innerHTML = `<div class="result-title">${escapeHtml(c.title || c.url || `Collection ${c.id}`)}</div>
        <div class="result-sub">${added ? 'Already in collection' : 'Tap to add'}</div>`;
      el.disabled = added;
      if (!added) {
        el.onclick = async () => {
          el.disabled = true;
          el.querySelector('.result-sub').textContent = 'Adding…';
          try {
            await gql('AddPostToCollection', { postId: post.postId, collectionId: Number(c.id) });
            c.posts = [...(c.posts || []), { id: post.postId }];
            el.classList.add('already-added');
            el.querySelector('.result-sub').textContent = 'Added';
            userCollectionCache.clear();
            toast(`Added to ${c.title || 'collection'}`);
          } catch (e) {
            el.disabled = false;
            el.querySelector('.result-sub').textContent = 'Tap to add';
            toast(e.message);
          }
        };
      }
      box.appendChild(el);
    }
    if (!collections.length) box.innerHTML = '<div class="result">No account collections were returned.</div>';
  } catch (e) {
    box.innerHTML = `<div class="result">${escapeHtml(e.message)}</div>`;
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
      const el = document.createElement('button');
      el.type = 'button';
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
  box.innerHTML = '<div class="result">Searching the website collection indexes…</div>';
  try {
    const q = (query || '').trim();
    const [subreddits, publicCollections] = await Promise.all([searchSubreddits(q), searchPublicCollections(q)]);
    box.innerHTML = '';

    subreddits.forEach(c => {
      const el = document.createElement('button');
      el.type = 'button';
      el.className = 'result';
      el.innerHTML = `<div class="result-title">${escapeHtml(c.title || c.url)}</div><div class="result-sub">Website collection${Number.isFinite(c.item_count) ? ` · ${c.item_count} items` : ''}${c.description ? ` · ${escapeHtml(c.description)}` : ''}</div>`;
      el.onclick = () => openCollectionByUrl(c.url, c.title || c.url);
      box.appendChild(el);
    });

    publicCollections.forEach(c => {
      const el = document.createElement('button');
      el.type = 'button';
      el.className = 'result';
      el.innerHTML = `<div class="result-title">${escapeHtml(c.title || c.url)}</div><div class="result-sub">Scrolller user collection${c.description ? ` · ${escapeHtml(c.description)}` : ''}</div>`;
      el.onclick = () => openUserCollection(c);
      box.appendChild(el);
    });

    if (!subreddits.length && !publicCollections.length) box.innerHTML = '<div class="result">No collections found.</div>';
  } catch (e) {
    box.innerHTML = `<div class="result">${escapeHtml(e.message)}</div>`;
  }
}

async function showMyCollections() {
  const box = $('collectionResults');
  box.innerHTML = '<div class="result">Loading account collections…</div>';
  try {
    const items = await getMyCollections();
    box.innerHTML = '';
    items.forEach(c => {
      const el = document.createElement('button');
      el.type = 'button';
      el.className = 'result';
      el.innerHTML = `<div class="result-title">${escapeHtml(c.title || c.url)}</div><div class="result-sub">Your Scrolller collection · ${(c.posts || []).length} saved posts</div>`;
      el.onclick = () => openUserCollection(c);
      box.appendChild(el);
    });
    if (!items.length) box.innerHTML = '<div class="result">No account collections were returned.</div>';
  } catch (e) {
    box.innerHTML = '<div class="result">Sign in first, then try again.</div>';
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
      return true;
    }
  } catch (e) {}
  state.account = null;
  status.textContent = hasNativeToken() ? 'Session saved — verifying account…' : 'Not signed in';
  $('loginBtn').textContent = 'Sign in to Scrolller';
  $('accountBtn').textContent = hasNativeToken() ? '◐' : '●';
  return false;
}

function syncSearchContext() {
  const visible = state.scope === 'POSTS' && !!state.currentCollection?.corpus;
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
  if (state.currentCollection?.corpus) {
    state.rawPosts = [...state.currentCollection.corpus];
    state.posts = filterPosts(state.rawPosts);
    state.activeIndex = 0;
    renderFeed();
  } else {
    await runSearch(state.query, true, true);
  }
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
  const oldSort = state.sort;
  const oldNsfw = state.nsfw;
  state.sort = $('sortSelect').value;
  state.media = $('mediaSelect').value;
  state.category = $('categorySelect').value;
  state.nsfw = $('nsfwSelect').value;
  ['sort', 'media', 'category', 'nsfw'].forEach(k => localStorage.setItem(k, state[k]));
  if (oldSort !== state.sort || oldNsfw !== state.nsfw) {
    preloadCache.clear();
    userCollectionCache.clear();
  }
  closeSheets();
  await loadCategories();

  if (state.currentCollection && state.category === 'ALL') {
    if (state.currentCollection.kind === 'user') {
      const c = { id: state.currentCollection.id, url: state.currentCollection.url, title: state.currentCollection.title };
      await openUserCollection(c);
    } else {
      await openCollectionByUrl(state.currentCollection.url, state.currentCollection.title);
    }
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
