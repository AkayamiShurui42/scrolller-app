(function () {
    'use strict';

    if (window.__scrolllerProEarlyInstalled) return;
    window.__scrolllerProEarlyInstalled = true;

    var API_MARKER = 'api.scrolller.com/admin';
    var PRELOAD_LIMIT = 5000;

    function isTargetUrl(url) {
        return String(url || '').indexOf(API_MARKER) >= 0;
    }

    function parseJson(body) {
        if (typeof body !== 'string' || !body) return null;
        try { return JSON.parse(body); } catch (_) { return null; }
    }

    function isGalleryOperation(entry) {
        if (!entry || typeof entry !== 'object') return false;
        var q = String(entry.query || '');
        var op = String(entry.operationName || '');
        return /getSubreddit|getUserCollectionContent|SubredditQuery|UserCollectionContent|Gallery/i.test(q + ' ' + op);
    }

    function isFirstPage(entry) {
        return !!(entry && entry.variables && typeof entry.variables === 'object' && entry.variables.iterator == null);
    }

    function raiseFirstPageLimit(entry) {
        if (!isGalleryOperation(entry) || !isFirstPage(entry)) return;
        var vars = entry.variables;
        if (typeof vars.limit === 'number' && vars.limit > 0 && vars.limit < PRELOAD_LIMIT) {
            vars.limit = PRELOAD_LIMIT;
        }
        if (typeof vars.first === 'number' && vars.first > 0 && vars.first < PRELOAD_LIMIT) {
            vars.first = PRELOAD_LIMIT;
        }
    }

    function tuneRequestBody(body) {
        var parsed = parseJson(body);
        if (!parsed) return body;
        if (Array.isArray(parsed)) parsed.forEach(raiseFirstPageLimit);
        else raiseFirstPageLimit(parsed);
        try { return JSON.stringify(parsed); } catch (_) { return body; }
    }

    /*
     * Keep the old working preload behavior only:
     *   - first gallery request: iterator=null, limit=5000
     *   - every later iterator request: untouched
     *
     * Do NOT rewrite API responses or XHR native getters here. Scrolller/Apollo
     * receives its own response objects exactly as the site produced them.
     */
    var nativeFetch = window.fetch;
    if (typeof nativeFetch === 'function') {
        window.fetch = function (input, init) {
            var url = typeof input === 'string' ? input : (input && input.url) || '';
            if (!isTargetUrl(url)) return nativeFetch.apply(this, arguments);

            try {
                if (init && typeof init.body === 'string') {
                    var tuned = tuneRequestBody(init.body);
                    if (tuned !== init.body) {
                        return nativeFetch.call(this, input, Object.assign({}, init, { body: tuned }));
                    }
                }

                if (typeof Request !== 'undefined' && input instanceof Request &&
                    String(input.method || '').toUpperCase() === 'POST') {
                    return input.clone().text().then(function (raw) {
                        var tunedBody = tuneRequestBody(raw);
                        if (tunedBody === raw) return nativeFetch.call(window, input, init);
                        return nativeFetch.call(window, new Request(input, { body: tunedBody }), init);
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
            try {
                if (this.__scrolllerProMethod === 'POST' && isTargetUrl(this.__scrolllerProUrl) && typeof body === 'string') {
                    body = tuneRequestBody(body);
                }
            } catch (_) {}
            return nativeSend.call(this, body);
        };
    }

    window.__scrolllerProPreloadLimit = PRELOAD_LIMIT;
    window.__scrolllerProPreloadMode = 'legacy-one-shot-safe';
})();
