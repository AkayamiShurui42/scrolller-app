(function () {
    'use strict';

    if (window.__scrolllerProEarlyInstalled) return;
    window.__scrolllerProEarlyInstalled = true;

    var TARGET_LIMIT = 5000;
    var API_MARKER = 'api.scrolller.com/admin';

    function isTargetUrl(url) {
        return String(url || '').indexOf(API_MARKER) >= 0;
    }

    function isGalleryOperation(entry) {
        if (!entry || typeof entry !== 'object') return false;
        var q = String(entry.query || '');
        var op = String(entry.operationName || '');
        return /getSubreddit|getUserCollectionContent|SubredditQuery|UserCollectionContent|Gallery/i.test(q + ' ' + op);
    }

    function raiseLimits(value, depth) {
        if (!value || typeof value !== 'object' || depth > 8) return;
        if (Array.isArray(value)) {
            for (var i = 0; i < value.length; i++) raiseLimits(value[i], depth + 1);
            return;
        }

        Object.keys(value).forEach(function (key) {
            var child = value[key];
            if ((key === 'limit' || key === 'first') && typeof child === 'number' && child > 0 && child < TARGET_LIMIT) {
                value[key] = TARGET_LIMIT;
                return;
            }
            if (child && typeof child === 'object') raiseLimits(child, depth + 1);
        });
    }

    function tuneEntry(entry) {
        if (!isGalleryOperation(entry)) return entry;
        if (entry.variables && typeof entry.variables === 'object') {
            raiseLimits(entry.variables, 0);
        }
        return entry;
    }

    function tuneBody(body) {
        if (typeof body !== 'string' || !body) return body;
        try {
            var parsed = JSON.parse(body);
            if (Array.isArray(parsed)) parsed.forEach(tuneEntry);
            else tuneEntry(parsed);
            return JSON.stringify(parsed);
        } catch (_) {
            return body;
        }
    }

    var nativeFetch = window.fetch;
    if (typeof nativeFetch === 'function') {
        window.fetch = function (input, init) {
            try {
                var url = typeof input === 'string' ? input : (input && input.url) || '';
                if (!isTargetUrl(url)) return nativeFetch.apply(this, arguments);

                if (init && typeof init.body === 'string') {
                    var next = Object.assign({}, init, { body: tuneBody(init.body) });
                    return nativeFetch.call(this, input, next);
                }

                if (typeof Request !== 'undefined' && input instanceof Request && String(input.method || '').toUpperCase() === 'POST') {
                    return input.clone().text().then(function (raw) {
                        var tuned = tuneBody(raw);
                        if (tuned === raw) return nativeFetch.call(window, input, init);
                        var replacement = new Request(input, { body: tuned });
                        return nativeFetch.call(window, replacement, init);
                    }).catch(function () {
                        return nativeFetch.call(window, input, init);
                    });
                }
            } catch (_) {
            }
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
                    body = tuneBody(body);
                }
            } catch (_) {
            }
            return nativeSend.call(this, body);
        };
    }

    window.__scrolllerProPreloadLimit = TARGET_LIMIT;
})();
