/* Scrolller website-parity behavior.
 *
 * The captured current Scrolller client keeps sort configuration in the gallery
 * filter state and passes that sort into the gallery fetch. Do the same here:
 * the API response order is authoritative. Do not re-sort or re-randomize a
 * one-shot preloaded collection on the client after Scrolller has already done
 * it on the server.
 *
 * Scrolller also treats subreddit/gallery discovery separately from a signed-in
 * user's saved collections. Public "Collections" browsing therefore uses the
 * same subreddit/gallery index used by the website, while "My collections"
 * remains the account-specific saved-collection surface.
 */
(function () {
  'use strict';

  // Reset the legacy default once when upgrading from the custom sorting model.
  // Scrolller's current website filter store falls back to RANDOM when a route
  // has not supplied its own filter config.
  if (localStorage.getItem('scrolllerWebsiteParitySort') !== '1') {
    state.sort = 'RANDOM';
    localStorage.setItem('sort', state.sort);
    localStorage.setItem('scrolllerWebsiteParitySort', '1');
    if (typeof syncControls === 'function') syncControls();
  }

  // Keep server ordering intact. Media/content filtering and de-duplication are
  // local, but ordering belongs to Scrolller's GallerySortBy response.
  filterPosts = function (posts) {
    let out = (posts || []).filter(Boolean);
    if (state.media !== 'ALL') out = out.filter(p => p.mediaType === state.media);
    if (state.nsfw === 'SFW') out = out.filter(p => !p.nsfw);
    if (state.nsfw === 'NSFW') out = out.filter(p => p.nsfw);

    const seen = new Set();
    out = out.filter(p => {
      const key = p.postId || p.postUrl || p.url || p.id;
      if (!key || seen.has(key)) return false;
      seen.add(key);
      return true;
    });

    return out;
  };

  // Public website gallery/subreddit discovery only. Signed-in saved collections
  // stay behind the explicit "My collections" button, matching the website's
  // separation between gallery navigation and account collection management.
  showCollectionResults = async function (query, target = 'collectionResults') {
    const box = $(target);
    box.innerHTML = '<div class="result">Searching Scrolller galleries…</div>';
    try {
      const q = (query || '').trim();
      const galleries = await searchSubreddits(q);
      box.innerHTML = '';

      galleries.forEach(c => {
        const el = document.createElement('button');
        el.type = 'button';
        el.className = 'result';
        const title = c.title || c.url || 'Untitled gallery';
        const count = Number.isFinite(c.item_count) ? ` · ${c.item_count} items` : '';
        const description = c.description ? ` · ${escapeHtml(c.description)}` : '';
        el.innerHTML = `<div class="result-title">${escapeHtml(title)}</div>` +
          `<div class="result-sub">Scrolller gallery${count}${description}</div>`;
        el.onclick = () => openCollectionByUrl(c.url, title);
        box.appendChild(el);
      });

      if (!galleries.length) {
        box.innerHTML = '<div class="result">No Scrolller galleries found.</div>';
      }
    } catch (e) {
      box.innerHTML = `<div class="result">${escapeHtml(e.message)}</div>`;
    }
  };

  // Make the public collection label explicit so account collections are not
  // presented as if they were part of the website's gallery index.
  const collectionQuery = $('collectionQuery');
  if (collectionQuery) collectionQuery.placeholder = 'Search Scrolller galleries';
  const myCollections = $('myCollectionsBtn');
  if (myCollections) myCollections.textContent = 'My saved collections';
})();
