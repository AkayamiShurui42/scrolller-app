/* Scrolller Pro post-centric enhancement layer.
 *
 * Collections are sources/scopes only. Posts are merged first, then searched,
 * filtered, quality-ranked, deduplicated-by-post, and sorted globally.
 */
(function () {
  'use strict';

  if (window.scrolllerPostCorpusV2) return;
  window.scrolllerPostCorpusV2 = true;

  const corpus = {
    active: false,
    loading: false,
    rawPosts: [],
    query: '',
    sort: 'NEWEST',
    expandGroups: true,
    selectedTypes: new Set(['VIDEO', 'GIF', 'IMAGE', 'ALBUM']),
    selectedCollections: new Set(),
    collectionFingerprint: ''
  };

  function esc(value) {
    return String(value ?? '')
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;')
      .replaceAll("'", '&#039;');
  }

  function sourceUrl(source) {
    return source && typeof source.url === 'string' ? source.url : '';
  }

  function sourceHost(source) {
    try { return new URL(sourceUrl(source)).hostname.replace(/^www\./, ''); }
    catch (_) { return 'source'; }
  }

  function isVideoSource(source) {
    const url = sourceUrl(source).toLowerCase();
    return /\.(mp4|webm|m3u8)(?:[?#]|$)/i.test(url) ||
      url.includes('format=mp4') ||
      url.includes('format=webm') ||
      url.includes('/video/');
  }

  function isGifSource(source) {
    const url = sourceUrl(source).toLowerCase();
    return /\.(gif|gifv)(?:[?#]|$)/i.test(url) ||
      url.includes('format=gif') ||
      url.includes('/gif/');
  }

  function classifyPost(item) {
    const albumCount = Array.isArray(item?.albumContent) ? item.albumContent.length : 0;
    if (albumCount > 1) return 'ALBUM';

    const sources = Array.isArray(item?.mediaSources) ? item.mediaSources : [];
    const itemUrl = String(item?.url || '').toLowerCase();
    const explicitGif = item?.isGif === true || item?.is_gif === true ||
      /\.(gif|gifv)(?:[?#]|$)/i.test(itemUrl) || sources.some(isGifSource);

    if (explicitGif) return 'GIF';
    if (sources.some(isVideoSource)) return 'VIDEO';
    return 'IMAGE';
  }

  function pixels(source) {
    const width = Number(source?.width) || 0;
    const height = Number(source?.height) || 0;
    if (width && height) return width * height;
    return width ? width * width : 0;
  }

  function qualitySortedSources(sources) {
    const seen = new Set();
    const list = [];
    for (const source of Array.isArray(sources) ? sources : []) {
      if (!source?.url || seen.has(source.url)) continue;
      seen.add(source.url);
      list.push(source);
    }

    list.sort((a, b) => {
      const pixelDiff = pixels(b) - pixels(a);
      if (pixelDiff) return pixelDiff;

      const rawA = a.isOptimized === false ? 1 : 0;
      const rawB = b.isOptimized === false ? 1 : 0;
      if (rawB !== rawA) return rawB - rawA;

      const widthDiff = (Number(b.width) || 0) - (Number(a.width) || 0);
      if (widthDiff) return widthDiff;
      return (Number(b.height) || 0) - (Number(a.height) || 0);
    });
    return list;
  }

  // Replace the old width-only selector with pixel-area ranking.
  getHighestQuality = function (sources) {
    return qualitySortedSources(sources)[0] || null;
  };

  function canonicalPostKey(item) {
    if (item?.redditPath) return `reddit:${String(item.redditPath).toLowerCase()}`;
    if (item?.id != null) return `id:${item.id}`;
    if (item?.url) return `url:${String(item.url).toLowerCase()}`;
    const best = qualitySortedSources(item?.mediaSources)[0];
    return best ? `media:${best.url}` : `unknown:${Math.random()}`;
  }

  function mergeAlbumContent(target, incoming) {
    const a = Array.isArray(target.albumContent) ? target.albumContent : [];
    const b = Array.isArray(incoming.albumContent) ? incoming.albumContent : [];
    if (!b.length) return;
    if (!a.length || a.length !== b.length) {
      if (b.length > a.length) target.albumContent = b;
      return;
    }
    target.albumContent = a.map((slide, index) => ({
      ...slide,
      mediaSources: qualitySortedSources([
        ...(slide?.mediaSources || []),
        ...(b[index]?.mediaSources || [])
      ])
    }));
  }

  function mergeDuplicate(target, incoming, collection, rank) {
    target.mediaSources = qualitySortedSources([
      ...(target.mediaSources || []),
      ...(incoming.mediaSources || [])
    ]);
    mergeAlbumContent(target, incoming);
    target._sourceVariants = qualitySortedSources(target.mediaSources);
    target._foundInCollections = Array.from(new Set([
      ...(target._foundInCollections || []),
      collection.title
    ].filter(Boolean)));
    target._foundInCollectionIds = Array.from(new Set([
      ...(target._foundInCollectionIds || []),
      Number(collection.id)
    ]));
    target._topScore = (target._topScore || 0) + 1 / (rank + 1);
    return target;
  }

  async function fetchCollectionPosts(collection) {
    // TOP gives us a stable per-collection relevance ordering. Dates are still
    // present, so Newest/Oldest can be sorted globally after merging.
    const response = await queryGraphQL('GetCollection', {
      id: Number(collection.id),
      filter: null,
      sortBy: 'TOP',
      limit: 5000,
      iterator: null
    });
    return response?.getCollection?.children?.items || [];
  }

  function selectedCollections() {
    const collections = Array.isArray(state.userCollections) ? state.userCollections : [];
    if (!corpus.selectedCollections.size) return collections;
    return collections.filter(col => corpus.selectedCollections.has(Number(col.id)));
  }

  async function buildCorpus(force = false) {
    if (corpus.loading) return;
    if (!state.token) {
      showToast('Sign in to Scrolller to browse your complete post corpus.');
      document.getElementById('signin-trigger-btn')?.click();
      return;
    }

    if (!Array.isArray(state.userCollections) || !state.userCollections.length) {
      await fetchUserCollections();
    }

    const collections = selectedCollections();
    if (!collections.length) {
      showToast('Select at least one collection source.');
      return;
    }

    if (corpus.rawPosts.length && !force) {
      corpus.active = true;
      renderCorpus();
      return;
    }

    corpus.active = true;
    corpus.loading = true;
    state.hasMore = false;
    state.iterator = null;
    state.isCollection = false;

    updateCorpusStatus(`Indexing posts from ${collections.length} collection${collections.length === 1 ? '' : 's'}…`);
    document.getElementById('media-grid').innerHTML = '<div class="corpus-loading">Building post index…</div>';
    document.getElementById('sub-banner')?.classList.add('hidden');
    document.getElementById('star-sub-btn')?.classList.add('hidden');

    const grouped = new Map();

    for (let start = 0; start < collections.length; start += 4) {
      const batch = collections.slice(start, start + 4);
      const results = await Promise.all(batch.map(async collection => {
        try {
          return { collection, items: await fetchCollectionPosts(collection) };
        } catch (error) {
          console.error('Corpus source failed:', collection?.title, error);
          return { collection, items: [] };
        }
      }));

      for (const { collection, items } of results) {
        items.forEach((item, rank) => {
          const key = canonicalPostKey(item);
          if (!grouped.has(key)) {
            grouped.set(key, {
              ...item,
              _postKey: key,
              _sourceVariants: qualitySortedSources(item.mediaSources),
              _foundInCollections: [collection.title],
              _foundInCollectionIds: [Number(collection.id)],
              _topScore: 1 / (rank + 1)
            });
          } else {
            mergeDuplicate(grouped.get(key), item, collection, rank);
          }
        });
      }

      updateCorpusStatus(`Indexed ${Math.min(start + 4, collections.length)} / ${collections.length} sources • ${grouped.size} unique posts`);
    }

    corpus.rawPosts = Array.from(grouped.values());
    corpus.loading = false;
    renderCorpus();
  }

  function normalizedSearchText(item) {
    return [
      item?.title,
      item?.subredditTitle,
      item?.subredditUrl,
      item?.redditPath,
      ...(item?._foundInCollections || [])
    ].filter(Boolean).join(' ').toLowerCase();
  }

  function directSearchScore(item, terms) {
    if (!terms.length) return 0;
    const title = String(item?.title || '').toLowerCase();
    const subreddit = `${item?.subredditTitle || ''} ${item?.subredditUrl || ''}`.toLowerCase();
    const collections = (item?._foundInCollections || []).join(' ').toLowerCase();
    const path = String(item?.redditPath || '').toLowerCase();
    let score = 0;
    for (const term of terms) {
      if (title.includes(term)) score += 12;
      if (subreddit.includes(term)) score += 8;
      if (collections.includes(term)) score += 6;
      if (path.includes(term)) score += 3;
    }
    return score;
  }

  function filteredCorpus() {
    const terms = corpus.query.toLowerCase().trim().split(/\s+/).filter(Boolean);
    const relatedCollections = new Set();
    const relatedSubreddits = new Set();

    if (terms.length && corpus.expandGroups) {
      for (const item of corpus.rawPosts) {
        const collections = (item._foundInCollections || []).join(' ').toLowerCase();
        const subreddit = `${item.subredditTitle || ''} ${item.subredditUrl || ''}`.toLowerCase();
        if (terms.some(term => collections.includes(term))) {
          (item._foundInCollections || []).forEach(name => relatedCollections.add(name));
        }
        if (terms.some(term => subreddit.includes(term))) {
          relatedSubreddits.add(String(item.subredditTitle || item.subredditUrl || '').toLowerCase());
        }
      }
    }

    let items = corpus.rawPosts.filter(item => corpus.selectedTypes.has(classifyPost(item)));

    items = items.map(item => {
      if (!terms.length) return { ...item, _searchScore: 0 };
      const text = normalizedSearchText(item);
      const direct = directSearchScore(item, terms);
      const directMatch = terms.every(term => text.includes(term));
      const collectionExpansion = corpus.expandGroups && (item._foundInCollections || []).some(name => relatedCollections.has(name));
      const subredditExpansion = corpus.expandGroups && relatedSubreddits.has(String(item.subredditTitle || item.subredditUrl || '').toLowerCase());

      if (!directMatch && !collectionExpansion && !subredditExpansion) return null;
      return {
        ...item,
        _searchScore: direct + (collectionExpansion ? 4 : 0) + (subredditExpansion ? 5 : 0)
      };
    }).filter(Boolean);

    if (corpus.sort === 'OLDEST') {
      items.sort((a, b) => new Date(a.createdAt || 0) - new Date(b.createdAt || 0));
    } else if (corpus.sort === 'TOP') {
      items.sort((a, b) => (b._topScore || 0) - (a._topScore || 0));
    } else if (corpus.sort === 'RELEVANCE' && terms.length) {
      items.sort((a, b) => (b._searchScore || 0) - (a._searchScore || 0) || new Date(b.createdAt || 0) - new Date(a.createdAt || 0));
    } else if (corpus.sort === 'RANDOM') {
      items = items.map(item => ({ item, n: Math.random() })).sort((a, b) => a.n - b.n).map(x => x.item);
    } else {
      items.sort((a, b) => new Date(b.createdAt || 0) - new Date(a.createdAt || 0));
    }

    return items;
  }

  function renderCorpus() {
    if (!corpus.active) return;
    const items = filteredCorpus();
    const grid = document.getElementById('media-grid');
    grid.innerHTML = '';
    state.posts = [];
    state.hasMore = false;

    // Prevent the legacy process function from applying its own collection sort.
    const oldSort = state.sortBy;
    state.sortBy = 'HOT';
    processAndAppendPosts(items);
    state.sortBy = oldSort;

    decorateCards();
    updateCorpusStatus(`${items.length} post${items.length === 1 ? '' : 's'} • ${corpus.rawPosts.length} indexed`);

    if (!items.length) {
      grid.innerHTML = '<div class="corpus-empty">No posts match the current search and media filters.</div>';
    }
  }

  // The legacy renderer remains useful, but it must respect the four post-level
  // media switches even outside corpus mode.
  const legacyProcessAndAppendPosts = processAndAppendPosts;
  processAndAppendPosts = function (items) {
    const input = Array.isArray(items) ? items : [];
    const filtered = input.filter(item => corpus.selectedTypes.has(classifyPost(item)));
    return legacyProcessAndAppendPosts(filtered);
  };

  function bestResolutionText(post) {
    if (post.isAlbum && Array.isArray(post.albumContent)) {
      let best = null;
      for (const slide of post.albumContent) {
        const candidate = qualitySortedSources(slide.mediaSources)[0];
        if (candidate && (!best || pixels(candidate) > pixels(best))) best = candidate;
      }
      if (best?.width && best?.height) return `${best.width}×${best.height}`;
      return '';
    }
    const best = qualitySortedSources(post._sourceVariants || post.mediaSources)[0];
    return best?.width && best?.height ? `${best.width}×${best.height}` : '';
  }

  function decorateCards() {
    document.querySelectorAll('#media-grid .media-card').forEach(card => {
      const id = card.dataset.id;
      const post = state.posts.find(p => String(p.id) === String(id));
      if (!post || card.querySelector('.corpus-card-meta')) return;

      const info = card.querySelector('.card-info');
      if (!info) return;

      const meta = document.createElement('div');
      meta.className = 'corpus-card-meta';
      const type = classifyPost(post);
      const resolution = bestResolutionText(post);
      const collectionCount = post._foundInCollections?.length || 0;
      const variantCount = qualitySortedSources(post._sourceVariants || post.mediaSources).length;
      meta.innerHTML = `
        <span>${type}</span>
        ${resolution ? `<span>${resolution}</span>` : ''}
        ${collectionCount ? `<span>${collectionCount} source${collectionCount === 1 ? '' : 's'}</span>` : ''}
        ${variantCount > 1 ? `<span>${variantCount} qualities</span>` : ''}
      `;
      info.insertBefore(meta, info.firstChild);

      const subredditLink = card.querySelector('.card-sub');
      if (subredditLink) {
        subredditLink.removeAttribute('target');
        subredditLink.addEventListener('click', event => {
          event.preventDefault();
          event.stopPropagation();
          const sub = String(post.subredditUrl || post.subredditTitle || '').replace(/^\/?r\//i, '').replace(/^\//, '');
          if (!sub) return;
          corpus.active = false;
          updateModeButton();
          loadSubreddit(sub);
        }, true);
      }
    });
  }

  function applyVariant(source) {
    const stage = document.getElementById('viewer-stage');
    if (!stage || !source?.url) return;
    const video = stage.querySelector('video');
    const image = stage.querySelector('img.viewer-media');
    if (video) {
      video.pause();
      video.src = source.url;
      video.load();
      video.play().catch(() => {});
    } else if (image) {
      image.src = source.url;
    }
  }

  function addVariantBar() {
    const post = state.posts[state.currentViewerIndex];
    const stage = document.getElementById('viewer-stage');
    if (!post || !stage || post.isAlbum) return;

    stage.querySelector('.source-variants')?.remove();
    const variants = qualitySortedSources(post._sourceVariants || post.mediaSources);
    if (variants.length < 2) return;

    const bar = document.createElement('div');
    bar.className = 'source-variants';
    const label = document.createElement('span');
    label.className = 'source-variants-label';
    label.textContent = 'Quality';
    bar.appendChild(label);

    variants.forEach((source, index) => {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = `source-variant-btn${index === 0 ? ' active' : ''}`;
      const dimensions = source.width && source.height ? `${source.width}×${source.height}` : 'source';
      button.textContent = `${dimensions} • ${sourceHost(source)}${source.isOptimized === false ? ' • original' : ''}`;
      button.addEventListener('click', event => {
        event.stopPropagation();
        applyVariant(source);
        bar.querySelectorAll('button').forEach(x => x.classList.remove('active'));
        button.classList.add('active');
      });
      bar.appendChild(button);
    });
    stage.appendChild(bar);
  }

  const legacyRenderViewerPost = renderViewerPost;
  renderViewerPost = function () {
    const result = legacyRenderViewerPost.apply(this, arguments);
    setTimeout(addVariantBar, 0);
    return result;
  };

  function updateCorpusStatus(text) {
    const el = document.getElementById('corpus-status');
    if (el) el.textContent = text;
  }

  function updateModeButton() {
    const button = document.getElementById('corpus-mode-btn');
    if (!button) return;
    button.textContent = corpus.active ? 'All My Posts' : 'Current Feed';
    button.classList.toggle('active', corpus.active);
  }

  function updateSourceButton() {
    const button = document.getElementById('corpus-source-btn');
    if (!button) return;
    const all = Array.isArray(state.userCollections) ? state.userCollections.length : 0;
    const selected = corpus.selectedCollections.size || all;
    button.textContent = all ? `Sources ${selected}/${all}` : 'Sources';
  }

  function buildSourceModal() {
    let modal = document.getElementById('corpus-source-modal');
    if (!modal) {
      modal = document.createElement('div');
      modal.id = 'corpus-source-modal';
      modal.className = 'corpus-source-modal hidden';
      document.body.appendChild(modal);
    }

    const collections = Array.isArray(state.userCollections) ? state.userCollections : [];
    modal.innerHTML = `
      <div class="corpus-source-panel">
        <div class="corpus-source-head">
          <div><strong>Post Sources</strong><small>Collections only choose where posts come from.</small></div>
          <button type="button" id="corpus-source-close">×</button>
        </div>
        <div class="corpus-source-actions">
          <button type="button" id="corpus-source-all">Select all</button>
          <button type="button" id="corpus-source-none">Clear</button>
        </div>
        <div class="corpus-source-list">
          ${collections.map(col => {
            const selected = !corpus.selectedCollections.size || corpus.selectedCollections.has(Number(col.id));
            return `<label class="corpus-source-row"><input type="checkbox" data-id="${Number(col.id)}" ${selected ? 'checked' : ''}><span>${esc(col.title)}</span><small>${col.itemsCount || ''}</small></label>`;
          }).join('') || '<div class="corpus-empty-small">Sign in to load collections.</div>'}
        </div>
        <button type="button" id="corpus-source-apply" class="corpus-primary">Apply sources</button>
      </div>`;

    modal.querySelector('#corpus-source-close')?.addEventListener('click', () => modal.classList.add('hidden'));
    modal.querySelector('#corpus-source-all')?.addEventListener('click', () => modal.querySelectorAll('input[type=checkbox]').forEach(x => x.checked = true));
    modal.querySelector('#corpus-source-none')?.addEventListener('click', () => modal.querySelectorAll('input[type=checkbox]').forEach(x => x.checked = false));
    modal.querySelector('#corpus-source-apply')?.addEventListener('click', async () => {
      const checked = Array.from(modal.querySelectorAll('input[type=checkbox]:checked')).map(x => Number(x.dataset.id));
      if (!checked.length) {
        showToast('Select at least one source collection.');
        return;
      }
      corpus.selectedCollections = new Set(checked);
      corpus.rawPosts = [];
      updateSourceButton();
      modal.classList.add('hidden');
      if (corpus.active) await buildCorpus(true);
    });
  }

  function syncCollectionSidebar() {
    const collections = Array.isArray(state.userCollections) ? state.userCollections : [];
    const fingerprint = collections.map(x => `${x.id}:${x.title}`).join('|');
    if (!collections.length || fingerprint === corpus.collectionFingerprint) return;
    corpus.collectionFingerprint = fingerprint;

    if (!corpus.selectedCollections.size) {
      corpus.selectedCollections = new Set(collections.map(col => Number(col.id)));
    }

    const section = document.getElementById('user-collections-section');
    const list = document.getElementById('user-collections-list');
    if (section && list) {
      section.classList.remove('hidden');
      const heading = section.querySelector('h3');
      if (heading) heading.textContent = 'Post Sources';
      list.innerHTML = `
        <li class="sidebar-item corpus-all-posts"><span class="sub-link-text">All My Posts</span></li>
        ${collections.map(col => `<li class="sidebar-item corpus-source-item" data-id="${Number(col.id)}"><span class="sub-link-text">${esc(col.title)}</span><span class="corpus-source-check">✓</span></li>`).join('')}`;

      list.querySelector('.corpus-all-posts')?.addEventListener('click', async () => {
        corpus.selectedCollections = new Set(collections.map(col => Number(col.id)));
        corpus.rawPosts = [];
        updateSourceButton();
        document.getElementById('sidebar')?.classList.add('hidden');
        document.getElementById('sidebar-backdrop')?.classList.add('hidden');
        await buildCorpus(true);
        updateModeButton();
      });

      list.querySelectorAll('.corpus-source-item').forEach(row => {
        row.addEventListener('click', async () => {
          const id = Number(row.dataset.id);
          corpus.selectedCollections = new Set([id]);
          corpus.rawPosts = [];
          updateSourceButton();
          document.getElementById('sidebar')?.classList.add('hidden');
          document.getElementById('sidebar-backdrop')?.classList.add('hidden');
          await buildCorpus(true);
          updateModeButton();
        });
      });
    }

    updateSourceButton();
    buildSourceModal();
  }

  function installToolbar() {
    if (document.getElementById('corpus-toolbar')) return;

    const toolbar = document.createElement('section');
    toolbar.id = 'corpus-toolbar';
    toolbar.innerHTML = `
      <div class="corpus-row corpus-row-main">
        <button type="button" id="corpus-mode-btn" class="corpus-mode-btn">Current Feed</button>
        <button type="button" id="corpus-source-btn" class="corpus-source-btn">Sources</button>
        <div class="corpus-search-wrap">
          <input id="corpus-search" type="search" placeholder="Search post titles, subreddits, collections…" autocomplete="off">
          <button type="button" id="corpus-search-clear" aria-label="Clear search">×</button>
        </div>
      </div>
      <div class="corpus-row corpus-row-filters">
        <div class="corpus-filter-label">Show</div>
        <button type="button" class="corpus-chip active" data-type="VIDEO">Video</button>
        <button type="button" class="corpus-chip active" data-type="GIF">GIF</button>
        <button type="button" class="corpus-chip active" data-type="IMAGE">Image</button>
        <button type="button" class="corpus-chip active" data-type="ALBUM">Album</button>
        <select id="corpus-sort" aria-label="Sort posts">
          <option value="NEWEST">Newest</option>
          <option value="OLDEST">Oldest</option>
          <option value="TOP">Top</option>
          <option value="RELEVANCE">Relevance</option>
          <option value="RANDOM">Random</option>
        </select>
        <label class="corpus-expand"><input id="corpus-expand" type="checkbox" checked> Expand matching groups</label>
        <button type="button" id="corpus-refresh" title="Re-index selected sources">↻</button>
      </div>
      <div id="corpus-status" class="corpus-status">Post filters apply to posts, not collections.</div>
    `;

    const feed = document.getElementById('feed-container');
    feed.parentNode.insertBefore(toolbar, feed);

    document.getElementById('corpus-mode-btn').addEventListener('click', async () => {
      if (corpus.active) {
        corpus.active = false;
        updateModeButton();
        state.filter = 'ALL';
        reloadFeed();
      } else {
        await buildCorpus(false);
        updateModeButton();
      }
    });

    document.getElementById('corpus-source-btn').addEventListener('click', () => {
      syncCollectionSidebar();
      buildSourceModal();
      document.getElementById('corpus-source-modal')?.classList.remove('hidden');
    });

    let searchTimer;
    document.getElementById('corpus-search').addEventListener('input', event => {
      clearTimeout(searchTimer);
      searchTimer = setTimeout(() => {
        corpus.query = event.target.value.trim();
        if (corpus.query && corpus.sort === 'NEWEST') {
          corpus.sort = 'RELEVANCE';
          document.getElementById('corpus-sort').value = 'RELEVANCE';
        }
        if (corpus.active) renderCorpus();
      }, 160);
    });

    document.getElementById('corpus-search-clear').addEventListener('click', () => {
      const input = document.getElementById('corpus-search');
      input.value = '';
      corpus.query = '';
      if (corpus.active) renderCorpus();
    });

    document.querySelectorAll('.corpus-chip').forEach(button => {
      button.addEventListener('click', () => {
        const type = button.dataset.type;
        if (corpus.selectedTypes.has(type)) {
          if (corpus.selectedTypes.size === 1) {
            showToast('At least one media type must remain selected.');
            return;
          }
          corpus.selectedTypes.delete(type);
          button.classList.remove('active');
        } else {
          corpus.selectedTypes.add(type);
          button.classList.add('active');
        }

        state.filter = 'ALL';
        if (corpus.active) renderCorpus();
        else reloadFeed();
      });
    });

    document.getElementById('corpus-sort').addEventListener('change', event => {
      corpus.sort = event.target.value;
      if (corpus.active) renderCorpus();
      else {
        const map = { NEWEST: 'NEW', OLDEST: 'OLD', TOP: 'TOP', RANDOM: 'RANDOM', RELEVANCE: 'HOT' };
        state.sortBy = map[corpus.sort] || 'HOT';
        reloadFeed();
      }
    });

    document.getElementById('corpus-expand').addEventListener('change', event => {
      corpus.expandGroups = event.target.checked;
      if (corpus.active) renderCorpus();
    });

    document.getElementById('corpus-refresh').addEventListener('click', async () => {
      corpus.rawPosts = [];
      if (corpus.active) await buildCorpus(true);
      else reloadFeed();
    });
  }

  function installStyles() {
    if (document.getElementById('corpus-v2-style')) return;
    const style = document.createElement('style');
    style.id = 'corpus-v2-style';
    style.textContent = `
      .controls-toolbar{display:none!important}
      #corpus-toolbar{position:sticky;top:64px;z-index:90;margin:10px 12px 14px;padding:12px;border:1px solid rgba(255,255,255,.10);border-radius:16px;background:rgba(11,12,16,.94);backdrop-filter:blur(16px);box-shadow:0 8px 30px rgba(0,0,0,.28)}
      .corpus-row{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.corpus-row-main{margin-bottom:9px}.corpus-row-filters{gap:6px}
      .corpus-mode-btn,.corpus-source-btn,#corpus-refresh,.corpus-chip,#corpus-sort{height:38px;border:1px solid rgba(255,255,255,.14);background:#17191f;color:#f5f5f5;border-radius:11px;padding:0 12px;font-weight:600}
      .corpus-mode-btn.active,.corpus-chip.active{border-color:#ffb400;background:rgba(255,180,0,.14);color:#ffd66e}
      .corpus-search-wrap{display:flex;align-items:center;flex:1;min-width:210px;height:40px;border:1px solid rgba(255,255,255,.14);background:#111319;border-radius:12px;overflow:hidden}
      #corpus-search{flex:1;height:100%;min-width:0;border:0!important;outline:0;background:transparent;color:#fff;padding:0 12px;font-size:14px}
      #corpus-search-clear{width:40px;height:100%;border:0;background:transparent;color:#aaa;font-size:22px}
      .corpus-filter-label{font-size:12px;color:#8e949f;margin-right:2px;text-transform:uppercase;letter-spacing:.08em}
      .corpus-expand{display:flex;align-items:center;gap:6px;font-size:12px;color:#aab0ba;padding:0 4px}
      .corpus-status{margin-top:9px;font-size:12px;color:#8e949f}.corpus-loading,.corpus-empty{grid-column:1/-1;padding:42px 18px;text-align:center;color:#aab0ba}
      .corpus-card-meta{display:flex;gap:5px;flex-wrap:wrap;margin:0 0 7px}.corpus-card-meta span{font-size:10px;padding:3px 6px;border-radius:999px;background:rgba(255,255,255,.07);color:#aeb4be}
      .source-variants{position:absolute;left:12px;right:12px;bottom:16px;z-index:40;display:flex;align-items:center;gap:7px;overflow-x:auto;padding:8px;border:1px solid rgba(255,255,255,.12);border-radius:13px;background:rgba(8,9,12,.88);backdrop-filter:blur(12px)}
      .source-variants-label{font-size:11px;color:#9299a5;white-space:nowrap}.source-variant-btn{white-space:nowrap;border:1px solid rgba(255,255,255,.16);background:#17191f;color:#d8dbe0;border-radius:9px;padding:7px 9px;font-size:10px}.source-variant-btn.active{border-color:#ffb400;color:#ffd66e}
      .corpus-source-modal{position:fixed;inset:0;z-index:500;background:rgba(0,0,0,.72);display:flex;align-items:flex-end;justify-content:center}.corpus-source-modal.hidden{display:none}.corpus-source-panel{width:min(680px,100%);max-height:82vh;background:#111319;border:1px solid rgba(255,255,255,.12);border-radius:22px 22px 0 0;padding:16px;display:flex;flex-direction:column;gap:12px}.corpus-source-head{display:flex;justify-content:space-between;align-items:flex-start}.corpus-source-head strong{display:block;font-size:18px}.corpus-source-head small{display:block;color:#8e949f;margin-top:3px}.corpus-source-head button{background:none;border:0;color:#fff;font-size:28px}.corpus-source-actions{display:flex;gap:8px}.corpus-source-actions button,.corpus-primary{border:1px solid rgba(255,255,255,.14);background:#1b1e25;color:#fff;border-radius:10px;padding:9px 12px}.corpus-primary{background:#ffb400;color:#111;border-color:#ffb400;font-weight:700}.corpus-source-list{overflow:auto;display:flex;flex-direction:column;gap:4px}.corpus-source-row{display:grid;grid-template-columns:auto 1fr auto;align-items:center;gap:10px;padding:10px;border-radius:10px;background:rgba(255,255,255,.035)}.corpus-source-row small{color:#777}.corpus-source-check{color:#ffb400}
      @media(max-width:768px){#corpus-toolbar{top:58px;margin:6px 7px 10px;padding:9px;border-radius:13px}.corpus-row-main{display:grid;grid-template-columns:auto auto 1fr}.corpus-search-wrap{grid-column:1/-1;width:100%;min-width:0}.corpus-row-filters{display:grid;grid-template-columns:repeat(4,1fr);gap:5px}.corpus-filter-label{display:none}.corpus-chip{padding:0 6px;font-size:11px}.corpus-sort{grid-column:span 2}.corpus-expand{grid-column:1/-2}.corpus-status{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}}
    `;
    document.head.appendChild(style);
  }

  function disableExternalBrowserHandoffs() {
    document.addEventListener('click', event => {
      const anchor = event.target.closest?.('a[href]');
      if (!anchor || !anchor.target || anchor.target.toLowerCase() !== '_blank') return;
      event.preventDefault();
      event.stopImmediatePropagation();
      const href = anchor.getAttribute('href') || '';
      const subredditMatch = href.match(/reddit\.com\/r\/([^/?#]+)/i);
      if (subredditMatch) {
        corpus.active = false;
        updateModeButton();
        loadSubreddit(subredditMatch[1]);
      } else {
        showToast('That source is available through the in-app media variants.');
      }
    }, true);
  }

  function initialize() {
    installStyles();
    installToolbar();
    disableExternalBrowserHandoffs();
    state.filter = 'ALL';

    // Collections arrive asynchronously after account verification. Replace the
    // old "open collection" navigation as soon as they are available.
    setInterval(syncCollectionSidebar, 600);
    syncCollectionSidebar();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initialize);
  } else {
    initialize();
  }
})();
