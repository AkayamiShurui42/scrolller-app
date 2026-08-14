(function () {
    'use strict';

    if (window.__scrolllerProEarlyInstalled) return;
    window.__scrolllerProEarlyInstalled = true;

    var API_MARKER = 'api.scrolller.com/admin';
    var VISIBLE_LIMIT = 96;
    var BACKGROUND_LIMIT = 400;
    var MAX_PAGES = 100;
    var MAX_ITEMS = 20000;
    var activePreloads = new Map();
    var completedPreloads = new Set();
    var corpusCache = new Map();

    function isTargetUrl(url) {
        return String(url || '').indexOf(API_MARKER) >= 0;
    }

    function isGalleryOperation(entry) {
        if (!entry || typeof entry !== 'object') return false;
        var q = String(entry.query || '');
        var op = String(entry.operationName || '');
        return /getSubreddit|getUserCollectionContent|SubredditQuery|UserCollectionContent|Gallery/i.test(q + ' ' + op);
    }

    function parseJson(body) {
        if (typeof body !== 'string' || !body) return null;
        try { return JSON.parse(body); } catch (_) { return null; }
    }

    function galleryEntries(parsed) {
        if (!parsed) return [];
        if (Array.isArray(parsed)) return parsed.filter(isGalleryOperation);
        return isGalleryOperation(parsed) ? [parsed] : [];
    }

    function firstGalleryEntry(parsed) {
        var entries = galleryEntries(parsed);
        return entries.length ? entries[0] : null;
    }

    function raiseVisibleLimits(value, depth) {
        if (!value || typeof value !== 'object' || depth > 8) return;
        if (Array.isArray(value)) {
            for (var i = 0; i < value.length; i++) raiseVisibleLimits(value[i], depth + 1);
            return;
        }

        Object.keys(value).forEach(function (key) {
            var child = value[key];
            if ((key === 'limit' || key === 'first') && typeof child === 'number' && child > 0 && child < VISIBLE_LIMIT) {
                value[key] = VISIBLE_LIMIT;
                return;
            }
            if (child && typeof child === 'object') raiseVisibleLimits(child, depth + 1);
        });
    }

    function tuneVisibleBody(body) {
        var parsed = parseJson(body);
        if (!parsed) return body;
        var entries = galleryEntries(parsed);
        if (!entries.length) return body;
        entries.forEach(function (entry) {
            if (entry.variables && typeof entry.variables === 'object') raiseVisibleLimits(entry.variables, 0);
        });
        try { return JSON.stringify(parsed); } catch (_) { return body; }
    }

    function stableKey(entry) {
        if (!isGalleryOperation(entry)) return '';
        var variables = Object.assign({}, entry.variables || {});
        delete variables.iterator;
        delete variables.limit;
        delete variables.first;
        try {
            return String(entry.operationName || '') + '|' + String(entry.query || '') + '|' + JSON.stringify(variables);
        } catch (_) {
            return '';
        }
    }

    function extractPage(payload) {
        try {
            var data = payload && payload.data;
            if (!data) return null;
            var sub = data.getSubreddit;
            if (sub && sub.children) {
                return {
                    iterator: sub.children.iterator == null ? null : String(sub.children.iterator),
                    items: Array.isArray(sub.children.items) ? sub.children.items : []
                };
            }
            var collection = data.getUserCollectionContent;
            if (collection) {
                return {
                    iterator: collection.iterator == null ? null : String(collection.iterator),
                    items: Array.isArray(collection.items) ? collection.items : []
                };
            }
        } catch (_) {}
        return null;
    }

    function itemKey(item) {
        if (!item || typeof item !== 'object') return '';
        if (item.id != null) return 'id:' + String(item.id);
        if (item.url) return 'url:' + String(item.url);
        try {
            var sources = item.mediaSources;
            if (Array.isArray(sources) && sources[0] && sources[0].url) return 'media:' + String(sources[0].url);
        } catch (_) {}
        return '';
    }

    function corpusFor(key) {
        var state = corpusCache.get(key);
        if (!state) {
            state = { items: [], seen: new Set() };
            corpusCache.set(key, state);
        }
        return state;
    }

    function appendUnique(state, items) {
        var added = 0;
        (items || []).forEach(function (item) {
            var k = itemKey(item);
            if (k && state.seen.has(k)) return;
            if (k) state.seen.add(k);
            state.items.push(item);
            added += 1;
        });
        return added;
    }

    function copyFetchOptions(input, init) {
        var out = {};
        try {
            if (typeof Request !== 'undefined' && input instanceof Request) {
                out.method = input.method;
                out.headers = new Headers(input.headers);
                out.credentials = input.credentials;
                out.cache = input.cache;
                out.redirect = input.redirect;
                out.referrer = input.referrer;
                out.referrerPolicy = input.referrerPolicy;
                out.integrity = input.integrity;
                out.keepalive = input.keepalive;
            }
        } catch (_) {}
        if (init && typeof init === 'object') {
            Object.keys(init).forEach(function (key) {
                if (key !== 'body' && key !== 'signal') out[key] = init[key];
            });
        }
        out.method = 'POST';
        if (!out.credentials) out.credentials = 'include';
        return out;
    }

    function makeBackgroundBody(entry, iterator) {
        var copy;
        try { copy = JSON.parse(JSON.stringify(entry)); } catch (_) { return ''; }
        if (!copy.variables || typeof copy.variables !== 'object') copy.variables = {};
        copy.variables.iterator = iterator;
        if (Object.prototype.hasOwnProperty.call(copy.variables, 'limit')) copy.variables.limit = BACKGROUND_LIMIT;
        if (Object.prototype.hasOwnProperty.call(copy.variables, 'first')) copy.variables.first = BACKGROUND_LIMIT;
        return JSON.stringify(copy);
    }

    function publishStats(key, pages, state, iterator) {
        window.__scrolllerProPreloadStats = {
            key: key,
            pages: pages,
            items: state.items.length,
            complete: !iterator
        };
    }

    function startBackgroundPreload(nativeFetch, url, input, init, entry, firstPage) {
        var key = stableKey(entry);
        if (!key || !firstPage) return;

        var state = corpusFor(key);
        appendUnique(state, firstPage.items);
        publishStats(key, 0, state, firstPage.iterator);

        if (!firstPage.iterator || activePreloads.has(key) || completedPreloads.has(key)) return;

        var options = copyFetchOptions(input, init);
        var seenIterators = new Set();

        var task = (async function () {
            var iterator = firstPage.iterator;
            var pages = 0;

            while (iterator && !seenIterators.has(iterator) && pages < MAX_PAGES && state.items.length < MAX_ITEMS) {
                seenIterators.add(iterator);
                var body = makeBackgroundBody(entry, iterator);
                if (!body) break;

                var response = await nativeFetch.call(window, url, Object.assign({}, options, { body: body }));
                if (!response || !response.ok) break;

                var payload = await response.json();
                var page = extractPage(payload);
                if (!page) break;

                appendUnique(state, page.items);
                pages += 1;
                iterator = page.iterator;
                publishStats(key, pages, state, iterator);

                if (!page.items.length && iterator) break;
            }

            if (!iterator) completedPreloads.add(key);
            publishStats(key, pages, state, iterator);
        })().catch(function () {}).finally(function () {
            activePreloads.delete(key);
        });

        activePreloads.set(key, task);
    }

    var nativeFetch = window.fetch;

    function performFetch(input, init, rawBody) {
        var url = typeof input === 'string' ? input : (input && input.url) || '';
        var networkPromise = nativeFetch.call(window, input, init);

        networkPromise.then(function (response) {
            if (!response || !response.ok || !rawBody) return;
            response.clone().json().then(function (payload) {
                var entry = firstGalleryEntry(parseJson(rawBody));
                if (!entry || !payload) return;
                var page = extractPage(payload);
                if (!page) return;
                startBackgroundPreload(nativeFetch, url, input, init, entry, page);
            }).catch(function () {});
        }).catch(function () {});

        return networkPromise;
    }

    if (typeof nativeFetch === 'function') {
        window.fetch = function (input, init) {
            var url = typeof input === 'string' ? input : (input && input.url) || '';
            if (!isTargetUrl(url)) return nativeFetch.apply(this, arguments);

            if (init && typeof init.body === 'string') {
                var rawBody = init.body;
                var tunedBody = tuneVisibleBody(rawBody);
                var tunedInit = tunedBody === rawBody ? init : Object.assign({}, init, { body: tunedBody });
                return performFetch(input, tunedInit, rawBody);
            }

            try {
                if (typeof Request !== 'undefined' && input instanceof Request && String(input.method || '').toUpperCase() === 'POST') {
                    return input.clone().text().then(function (rawBody) {
                        var tunedBody = tuneVisibleBody(rawBody);
                        if (tunedBody === rawBody) return performFetch(input, init, rawBody);
                        var replacement = new Request(input, { body: tunedBody });
                        return performFetch(replacement, init, rawBody);
                    }).catch(function () {
                        return nativeFetch.call(window, input, init);
                    });
                }
            } catch (_) {}

            return nativeFetch.apply(this, arguments);
        };
    }

    if (typeof XMLHttpRequest !== 'undefined') {
        var nativeOpen = XMLHttpRequest.prototype.open;
        var nativeSend = XMLHttpRequest.prototype.send;

        XMLHttpRequest.prototype.open = function (method, url) {
            this.__scrolllerProMethod = String(method || '').toUpperCase();
            this.__scrolllerProUrl = String(url || '');
            return nativeOpen.apply(this, arguments);
        };

        XMLHttpRequest.prototype.send = function (body) {
            var originalBody = typeof body === 'string' ? body : '';
            try {
                if (this.__scrolllerProMethod === 'POST' && isTargetUrl(this.__scrolllerProUrl) && originalBody) {
                    var entry = firstGalleryEntry(parseJson(originalBody));
                    if (entry) body = tuneVisibleBody(originalBody);

                    var xhr = this;
                    this.addEventListener('load', function () {
                        try {
                            if (xhr.status < 200 || xhr.status >= 300) return;
                            var originalEntry = firstGalleryEntry(parseJson(originalBody));
                            if (!originalEntry) return;
                            var payload = JSON.parse(xhr.responseText || '{}');
                            var page = extractPage(payload);
                            if (!page) return;
                            startBackgroundPreload(
                                nativeFetch,
                                xhr.__scrolllerProUrl,
                                xhr.__scrolllerProUrl,
                                { headers: { 'Content-Type': 'application/json' }, credentials: 'include' },
                                originalEntry,
                                page
                            );
                        } catch (_) {}
                    }, { once: true });
                }
            } catch (_) {}
            return nativeSend.call(this, body);
        };
    }

    window.__scrolllerProPreloadLimit = BACKGROUND_LIMIT;
    window.__scrolllerProVisibleLimit = VISIBLE_LIMIT;
    window.__scrolllerProCorpusCache = corpusCache;
})();
