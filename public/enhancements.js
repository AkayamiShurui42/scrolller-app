/* Scrolller Pro app enhancements
 * - Four combinable media filters: Video, GIF, Image, Album
 * - Highest-quality source becomes primary while alternatives stay selectable
 * - Search every signed-in Scrolller collection at once
 * - Keep source navigation inside the APK/WebView
 */
(function () {
  if (window.scrolllerProEnhancements) return;
  window.scrolllerProEnhancements = true;

  const mediaSelection = new Set(['VIDEO', 'GIF', 'IMAGE', 'ALBUM']);

  function urlOf(source) {
    return source && typeof source.url === 'string' ? source.url : '';
  }

  function hostOf(url) {
    try { return new URL(url).hostname.replace(/^www\./, ''); } catch (_) { return 'source'; }
  }

  function isVideoSource(source) {
    const u = urlOf(source).toLowerCase();
    return /\.(mp4|webm|m3u8)(?:[?#]|$)/i.test(u) || u.includes('format=mp4') || u.includes('format=webm') || u.includes('/video/');
  }

  function isGifSource(source) {
    const u = urlOf(source).toLowerCase();
    return /\.(gif|gifv)(?:[?#]|$)/i.test(u) || u.includes('format=gif') || u.includes('/gif/');
  }

  function classifyItem(item) {
    const albumCount = Array.isArray(item?.albumContent) ? item.albumContent.length : 0;
    if (albumCount > 1) return 'ALBUM';

    const sources = Array.isArray(item?.mediaSources) ? item.mediaSources : [];
    if (sources.some(isGifSource) || item?.isGif === true || item?.is_gif === true) return 'GIF';
    if (sources.some(isVideoSource)) return 'VIDEO';
    return 'IMAGE';
  }

  function selectedMediaTypes() {
    return new Set(mediaSelection);
  }

  function matchesSelectedMedia(item) {
    const selected = selectedMediaTypes();
    if (selected.size === 0 || selected.size === 4) return true;
    return selected.has(classifyItem(item));
  }

  function pixelCount(source) {
    const w = Number(source?.width) || 0;
    const h = Number(source?.height) || 0;
    if (w && h) return w * h;
    return w ? w * w : 0;
  }

  function qualitySortedSources(sources) {
    if (!Array.isArray(sources)) return [];
    const unique = [];
    const seen = new Set();
    for (const source of sources) {
      if (!source || !source.url || seen.has(source.url)) continue;
      seen.add(source.url);
      unique.push(source);
    }

    unique.sort((a, b) => {
      const px = pixelCount(b) - pixelCount(a);
      if (px !== 0) return px;

      // At equal resolution, prefer original/unoptimized assets.
      const rawA = a.isOptimized === false ? 1 : 0;
      const rawB = b.isOptimized === false ? 1 : 0;
      if (rawB !== rawA) return rawB - rawA;

      const width = (Number(b.width) || 0) - (Number(a.width) || 0);
      if (width !== 0) return width;
      return (Number(b.height) || 0) - (Number(a.height) || 0);
    });
    return unique;
  }

  // Override the app's source chooser. The highest pixel-count source wins;
  // alternatives remain in the post's original mediaSources list.
  window.getHighestQuality = function (sources) {
    return qualitySortedSources(sources)[0] || null;
  };

  function installMediaFilterUi() {
    const oldSelect = document.getElementById('filter-select');
    if (!oldSelect || document.getElementById('media-multi-filter')) return;

    const group = oldSelect.closest('.control-group');
    if (!group) return;
    oldSelect.style.display = 'none';
    const label = group.querySelector('label');
    if (label) label.textContent = 'Media';

    const holder = document.createElement('div');
    holder.id = 'media-multi-filter';
    holder.className = 'media-multi-filter';
    const definitions = [
      ['VIDEO', 'Video'],
      ['GIF', 'GIF'],
      ['IMAGE', 'Image'],
      ['ALBUM', 'Album']
    ];

    for (const [value, text] of definitions) {
      const wrap = document.createElement('label');
      wrap.className = 'media-filter-chip active';
      const input = document.createElement('input');
      input.type = 'checkbox';
      input.checked = true;
      input.value = value;
      input.addEventListener('change', () => {
        if (input.checked) mediaSelection.add(value); else mediaSelection.delete(value);
        wrap.classList.toggle('active', input.checked);
        if (mediaSelection.size === 0) {
          input.checked = true;
          mediaSelection.add(value);
          wrap.classList.add('active');
          if (typeof showToast === 'function') showToast('Keep at least one media type enabled.');
          return;
        }
        // Server-side GalleryFilter cannot represent arbitrary combinations, so
        // fetch broadly and refine locally.
        try { state.filter = 'ALL'; } catch (_) {}
        if (typeof reloadFeed === 'function') reloadFeed();
      });
      const span = document.createElement('span');
      span.textContent = text;
      wrap.append(input, span);
      holder.appendChild(wrap);
    }
    group.appendChild(holder);
  }

  function installStyles() {
    if (document.getElementById('scrolller-enhancement-style')) return;
    const style = document.createElement('style');
    style.id = 'scrolller-enhancement-style';
    style.textContent = `
      .media-multi-filter{display:flex;gap:6px;flex-wrap:wrap;align-items:center}
      .media-filter-chip{display:inline-flex;align-items:center;gap:5px;padding:7px 10px;border:1px solid rgba(255,255,255,.18);border-radius:999px;cursor:pointer;user-select:none;opacity:.58;font-size:12px}
      .media-filter-chip.active{opacity:1;border-color:#ffb400;background:rgba(255,180,0,.13)}
      .media-filter-chip input{display:none}
      .source-variants{position:absolute;left:12px;right:12px;bottom:14px;z-index:30;display:flex;gap:7px;align-items:center;overflow-x:auto;padding:8px;border-radius:12px;background:rgba(0,0,0,.72);backdrop-filter:blur(8px)}
      .source-variants-label{white-space:nowrap;font-size:12px;opacity:.75}
      .source-variant-btn{white-space:nowrap;border:1px solid rgba(255,255,255,.25);background:rgba(20,20,20,.78);color:#fff;border-radius:9px;padding:6px 9px;font-size:11px}
      .source-variant-btn.primary{border-color:#ffb400;color:#ffcb55}
      .collection-search-btn{margin-left:6px;white-space:nowrap}
      .search-progress-note{grid-column:1/-1;text-align:center;padding:18px;opacity:.75}
      img.card-media,img.viewer-media,video.card-media,video.viewer-media{image-rendering:auto!important;backface-visibility:hidden}
    `;
    (document.head || document.documentElement).appendChild(style);
  }

  // Wrap processing so the four media chips can be combined freely.
  if (typeof window.processAndAppendPosts === 'function') {
    const baseProcess = window.processAndAppendPosts;
    window.processAndAppendPosts = function (items) {
      const list = Array.isArray(items) ? items.filter(matchesSelectedMedia) : [];
      return baseProcess(list);
    };
  }

  function mergeSourceLists(a, b) {
    return qualitySortedSources([...(a || []), ...(b || [])]);
  }

  function canonicalKey(item) {
    if (item?.redditPath) return 'reddit:' + String(item.redditPath).toLowerCase();
    if (item?.url) return 'url:' + String(item.url).toLowerCase();
    if (item?.id != null) return 'id:' + item.id;
    const best = qualitySortedSources(item?.mediaSources || [])[0];
    return best ? 'media:' + best.url : Math.random().toString(36);
  }

  function mergeDuplicateItem(target, incoming, collectionTitle) {
    target.mediaSources = mergeSourceLists(target.mediaSources, incoming.mediaSources);
    if (Array.isArray(incoming.albumContent) && incoming.albumContent.length) {
      if (!Array.isArray(target.albumContent) || incoming.albumContent.length > target.albumContent.length) {
        target.albumContent = incoming.albumContent;
      }
    }
    target._foundInCollections = Array.from(new Set([
      ...(target._foundInCollections || []),
      collectionTitle
    ].filter(Boolean)));
    target._sourceVariants = qualitySortedSources(target.mediaSources);
    return target;
  }

  function searchScore(item, collectionTitle, terms) {
    const title = String(item?.title || '').toLowerCase();
    const subreddit = String(item?.subredditTitle || item?.subredditUrl || '').toLowerCase();
    const collection = String(collectionTitle || '').toLowerCase();
    const redditPath = String(item?.redditPath || '').toLowerCase();
    let score = 0;
    for (const term of terms) {
      if (title.includes(term)) score += 10;
      if (subreddit.includes(term)) score += 6;
      if (collection.includes(term)) score += 5;
      if (redditPath.includes(term)) score += 3;
    }
    return score;
  }

  async function fetchWholeCollection(col) {
    const response = await queryGraphQL('GetCollection', {
      id: Number(col.id),
      filter: null,
      sortBy: 'NEW',
      limit: 5000,
      iterator: null
    });
    return response?.getCollection?.children?.items || [];
  }

  window.searchAllCollections = async function (queryText) {
    const query = String(queryText || '').trim();
    if (!query) return;
    if (!state?.token) {
      if (typeof showToast === 'function') showToast('Sign in to Scrolller first to search all collections.');
      return;
    }
    if (!Array.isArray(state.userCollections) || state.userCollections.length === 0) {
      if (typeof fetchUserCollections === 'function') await fetchUserCollections();
    }
    const collections = Array.isArray(state.userCollections) ? state.userCollections : [];
    if (!collections.length) {
      if (typeof showToast === 'function') showToast('No synced collections found.');
      return;
    }

    state.loading = true;
    state.posts = [];
    state.iterator = null;
    state.hasMore = false;
    state.isCollection = false;
    const grid = document.getElementById('media-grid');
    grid.innerHTML = '<div class="search-progress-note">Searching every synced collection…</div>';
    document.getElementById('feed-end')?.classList.add('hidden');
    document.getElementById('star-sub-btn')?.classList.add('hidden');

    const terms = query.toLowerCase().split(/\s+/).filter(Boolean);
    const grouped = new Map();

    // Small batches avoid hammering the GraphQL endpoint while still being quick.
    for (let start = 0; start < collections.length; start += 4) {
      const batch = collections.slice(start, start + 4);
      const results = await Promise.all(batch.map(async col => {
        try { return { col, items: await fetchWholeCollection(col) }; }
        catch (error) { console.error('Collection search failed:', col?.title, error); return { col, items: [] }; }
      }));

      for (const { col, items } of results) {
        for (const item of items) {
          const score = searchScore(item, col.title, terms);
          if (score <= 0 || !matchesSelectedMedia(item)) continue;
          const key = canonicalKey(item);
          if (!grouped.has(key)) {
            const copy = { ...item, _searchScore: score, _foundInCollections: [col.title], _sourceVariants: qualitySortedSources(item.mediaSources) };
            grouped.set(key, copy);
          } else {
            const existing = grouped.get(key);
            existing._searchScore = Math.max(existing._searchScore || 0, score);
            mergeDuplicateItem(existing, item, col.title);
          }
        }
      }
    }

    const matches = Array.from(grouped.values()).sort((a, b) => {
      if ((b._searchScore || 0) !== (a._searchScore || 0)) return (b._searchScore || 0) - (a._searchScore || 0);
      return new Date(b.createdAt || 0) - new Date(a.createdAt || 0);
    });

    grid.innerHTML = '';
    state.posts = [];
    if (matches.length) {
      processAndAppendPosts(matches);
      if (typeof renderSubBanner === 'function') {
        renderSubBanner({
          title: `Search: ${query}`,
          description: `Across ${collections.length} synced collection${collections.length === 1 ? '' : 's'}`,
          subscribers: 0,
          itemCount: matches.length,
          banner: null
        });
      }
    } else {
      grid.innerHTML = `<div class="empty-list-msg">No collection posts matched “${query}”.</div>`;
    }
    state.loading = false;
  };

  function installCollectionSearchButton() {
    if (document.getElementById('collection-global-search-btn')) return;
    const form = document.getElementById('search-form');
    const input = document.getElementById('search-input');
    if (!form || !input) return;

    const button = document.createElement('button');
    button.type = 'button';
    button.id = 'collection-global-search-btn';
    button.className = 'search-btn collection-search-btn';
    button.textContent = 'Collections';
    button.title = 'Search every synced collection';
    button.addEventListener('click', async () => {
      const q = input.value.trim();
      if (!q) {
        if (typeof showToast === 'function') showToast('Enter search terms first.');
        return;
      }
      await window.searchAllCollections(q);
    });
    form.appendChild(button);
  }

  function applyVariantToViewer(source) {
    const stage = document.getElementById('viewer-stage');
    if (!stage || !source?.url) return;
    const video = stage.querySelector('video');
    const image = stage.querySelector('img.viewer-media');
    if (video) {
      video.pause();
      const childSource = video.querySelector('source');
      if (childSource) childSource.src = source.url;
      else video.src = source.url;
      video.load();
      video.play().catch(() => {});
    } else if (image) {
      image.src = source.url;
    }
  }

  function renderSourceVariants() {
    const post = state?.posts?.[state.currentViewerIndex];
    const stage = document.getElementById('viewer-stage');
    if (!post || !stage || post.isAlbum) return;
    stage.querySelector('.source-variants')?.remove();

    const variants = qualitySortedSources(post._sourceVariants || post.mediaSources || []);
    if (variants.length <= 1 && !(post._foundInCollections?.length > 1)) return;

    const bar = document.createElement('div');
    bar.className = 'source-variants';
    const label = document.createElement('span');
    label.className = 'source-variants-label';
    label.textContent = post._foundInCollections?.length
      ? `Best source • ${post._foundInCollections.length} collection${post._foundInCollections.length === 1 ? '' : 's'}`
      : 'Sources';
    bar.appendChild(label);

    variants.forEach((source, index) => {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'source-variant-btn' + (index === 0 ? ' primary' : '');
      const w = Number(source.width) || 0;
      const h = Number(source.height) || 0;
      const dims = w && h ? `${w}×${h}` : 'original';
      btn.textContent = `${hostOf(source.url)} • ${dims}${source.isOptimized === false ? ' • raw' : ''}`;
      btn.addEventListener('click', e => {
        e.stopPropagation();
        applyVariantToViewer(source);
        bar.querySelectorAll('.source-variant-btn').forEach(x => x.classList.remove('primary'));
        btn.classList.add('primary');
      });
      bar.appendChild(btn);
    });
    stage.appendChild(bar);
  }

  if (typeof window.renderViewerPost === 'function') {
    const baseRenderViewer = window.renderViewerPost;
    window.renderViewerPost = function () {
      const result = baseRenderViewer.apply(this, arguments);
      setTimeout(renderSourceVariants, 0);
      return result;
    };
  }

  function keepNavigationInternal() {
    document.addEventListener('click', event => {
      const anchor = event.target.closest?.('a[href]');
      if (!anchor) return;
      const href = anchor.href;
      if (!href || href.startsWith('javascript:')) return;
      event.preventDefault();
      // Same WebView, never hand off to another browser app.
      window.location.href = href;
    }, true);
  }

  function initialize() {
    installStyles();
    installMediaFilterUi();
    installCollectionSearchButton();
    keepNavigationInternal();
    try { state.filter = 'ALL'; } catch (_) {}
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initialize);
  else initialize();
})();
