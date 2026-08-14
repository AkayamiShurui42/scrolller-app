(function () {
    'use strict';

    if (window.__scrolllerProEarlyInstalled) return;
    window.__scrolllerProEarlyInstalled = true;

    var API_MARKER = 'api.scrolller.com/admin';
    var BACKGROUND_LIMIT = 400;
    var MAX_PAGES = 100;
    var MAX_ITEMS = 20000;
    var pageCache = new Map();
    var activePreloads = new Map();

    function isTargetUrl(url) {
        return String(url || '').indexOf(API_MARKER) >= 0;
    }

    function isGalleryOperation(entry) {
        if (!entry || typeof entry !== 'object') return false;
        var q = String(entry.query || '');
        var op = String(entry.operationName || '');
        return /getSubreddit|getUserCollectionContent|SubredditQuery|UserCollectionContent|Gallery/i.test(q + ' ' + op);
    }

    function parseBody(body) {
        if (typeof body !== 'string' || !body) return null;
        try {
            var parsed = JSON.parse(body);
            return Array.isArray(parsed) ? null : parsed;
        } catch (_) {
            return null;
        }
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

    function requestedIterator(entry) {
        var variables = entry && entry.variables;
        if (!variables || typeof variables !== 'object') return null;
        return variables.iterator == null ? null : String(variables.iterator);
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
        } catch (_) {
        }
        return null;
    }

    function responseFrom(payload) {
        return new Response(JSON.stringify(payload), {
            status: 200,
            headers: { 'Content-Type': 'application/json' }
        });
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
        } catch (_) {
        }
        if (init && typeof init === 'object') {
            Object.keys(init).forEach(function (key) {
                if (key !== 'body' && key !== 'signal') out[key] = init[key];
            });
        }
        out.method = 'POST';
        if (!out.credentials) out.credentials = 'include';
        return out;
    }

    function bodyPromise(input, init) {
        if (init && typeof init.body === 'string') return Promise.resolve(init.body);
        try {
            if (typeof Request !== 'undefined' && input instanceof Request && String(input.method || '').toUpperCase() === 'POST') {
                return input.clone().text().catch(function () { return ''; });
            }
        } catch (_) {
        }
        return Promise.resolve('');
    }

    function makeBackgroundBody(entry, iterator) {
        var copy;
        try {
            copy = JSON.parse(JSON.stringify(entry));
        } catch (_) {
            return '';
        }
        if (!copy.variables || typeof copy.variables !== 'object') copy.variables = {};
        copy.variables.iterator = iterator;
        if (Object.prototype.hasOwnProperty.call(copy.variables, 'limit')) copy.variables.limit = BACKGROUND_LIMIT;
        if (Object.prototype.hasOwnProperty.call(copy.variables, 'first')) copy.variables.first = BACKGROUND_LIMIT;
        return JSON.stringify(copy);
    }

    function startBackgroundPreload(nativeFetch, url, input, init, entry, firstPage) {
        var key = stableKey(entry);
        if (!key || !firstPage || !firstPage.iterator || activePreloads.has(key)) return;

        var options = copyFetchOptions(input, init);
        var seen = new Set();
        var firstCount = Array.isArray(firstPage.items) ? firstPage.items.length : 0;

        var task = (async function () {
            var iterator = firstPage.iterator;
            var pages = 0;
            var items = firstCount;

            while (iterator && !seen.has(iterator) && pages < MAX_PAGES && items < MAX_ITEMS) {
                seen.add(iterator);
                var body = makeBackgroundBody(entry, iterator);
                if (!body) break;

                var requestOptions = Object.assign({}, options, { body: body });
                var response = await nativeFetch.call(window, url, requestOptions);
                if (!response || !response.ok) break;

                var payload = await response.json();
                var page = extractPage(payload);
                if (!page) break;

                pageCache.set(key + '|iterator=' + iterator, payload);
                pages += 1;
                items += page.items.length;
                iterator = page.iterator;

                window.__scrolllerProPreloadStats = {
                    key: key,
                    pages: pages,
                    items: items,
                    complete: !iterator
                };

                if (!page.items.length && iterator) break;
            }

            window.__scrolllerProPreloadStats = {
                key: key,
                pages: pages,
                items: items,
                complete: !iterator
            };
        })().catch(function () {
        }).finally(function () {
            activePreloads.delete(key);
        });

        activePreloads.set(key, task);
    }

    var nativeFetch = window.fetch;
    if (typeof nativeFetch === 'function') {
        window.fetch = function (input, init) {
            var url = typeof input === 'string' ? input : (input && input.url) || '';
            if (!isTargetUrl(url)) return nativeFetch.apply(this, arguments);

            var rawPromise = bodyPromise(input, init);

            if (init && typeof init.body === 'string') {
                var immediateEntry = parseBody(init.body);
                if (isGalleryOperation(immediateEntry)) {
                    var key = stableKey(immediateEntry);
                    var iterator = requestedIterator(immediateEntry);
                    if (key && iterator) {
                        var cached = pageCache.get(key + '|iterator=' + iterator);
                        if (cached) return Promise.resolve(responseFrom(cached));
                    }
                }
            }

            var networkPromise = nativeFetch.apply(this, arguments);
            networkPromise.then(function (response) {
                if (!response || !response.ok) return;
                Promise.all([
                    rawPromise,
                    response.clone().json().catch(function () { return null; })
                ]).then(function (parts) {
                    var entry = parseBody(parts[0]);
                    var payload = parts[1];
                    if (!isGalleryOperation(entry) || !payload) return;
                    var page = extractPage(payload);
                    if (!page) return;
                    startBackgroundPreload(nativeFetch, url, input, init, entry, page);
                }).catch(function () {
                });
            }).catch(function () {
            });

            return networkPromise;
        };
    }

    // Do not rewrite XHR limits anymore. A giant first XHR causes the same blank
    // startup problem. Let Scrolller paint its native first page immediately.
    window.__scrolllerProPreloadLimit = BACKGROUND_LIMIT;
    window.__scrolllerProPageCache = pageCache;
})();
