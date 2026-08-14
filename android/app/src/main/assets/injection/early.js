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

    function firstPage(entry) {
        if (!entry || !entry.variables || typeof entry.variables !== 'object') return false;
        return entry.variables.iterator == null;
    }

    function raiseFirstPageLimit(entry) {
        if (!isGalleryOperation(entry) || !firstPage(entry)) return;
        var vars = entry.variables;
        if (typeof vars.limit === 'number' && vars.limit > 0 && vars.limit < PRELOAD_LIMIT) vars.limit = PRELOAD_LIMIT;
        if (typeof vars.first === 'number' && vars.first > 0 && vars.first < PRELOAD_LIMIT) vars.first = PRELOAD_LIMIT;
    }

    function tuneRequestBody(body) {
        var parsed = parseJson(body);
        if (!parsed) return body;
        if (Array.isArray(parsed)) parsed.forEach(raiseFirstPageLimit);
        else raiseFirstPageLimit(parsed);
        try { return JSON.stringify(parsed); } catch (_) { return body; }
    }

    function lower(value) {
        return String(value || '').toLowerCase();
    }

    function isAdItem(item) {
        if (!item || typeof item !== 'object') return false;

        if (item.isAd === true || item.is_ad === true ||
            item.isSponsor === true || item.is_sponsor === true || item.sponsored === true ||
            item.isPromoted === true || item.is_promoted === true || item.promoted === true ||
            item.promotion === true || item.isPromotion === true || item.is_promotion === true) {
            return true;
        }

        var url = lower(item.url || item.href || item.link || item.targetUrl || item.clickUrl);
        if (url.indexOf('cant3am.com') >= 0 || url.indexOf('chaturbate') >= 0 ||
            url.indexOf('stripchat') >= 0 || url.indexOf('exoclick') >= 0 ||
            url.indexOf('juicyads') >= 0 || url.indexOf('realsrv') >= 0) {
            return true;
        }

        var author = lower(item.reddit_posted_by || item.username || item.author || item.postedBy);
        if (author === 'admin' || author === 'official' || author === 'sponsor' ||
            author === 'scrolller' || author.indexOf('scrolllerofficial') >= 0) {
            return true;
        }

        var text = lower((item.title || '') + ' ' + (item.description || '') + ' ' + (item.label || ''));
        if (text.indexOf('sponsored') >= 0 || text.indexOf('promoted') >= 0 ||
            text.indexOf('advertisement') >= 0 || text.indexOf('stripchat') >= 0 ||
            text.indexOf('chaturbate') >= 0 || text.indexOf('onlyfans') >= 0 ||
            text.indexOf('link in bio') >= 0 || text.indexOf('bio link') >= 0) {
            return true;
        }

        return false;
    }

    function sourceArea(source) {
        if (!source || typeof source !== 'object') return 0;
        var w = Number(source.width || 0);
        var h = Number(source.height || 0);
        return w * h;
    }

    function bestSource(sources) {
        if (!Array.isArray(sources) || !sources.length) return null;
        return sources.filter(function (s) { return s && s.url; }).sort(function (a, b) {
            var area = sourceArea(b) - sourceArea(a);
            if (area) return area;
            var width = Number(b.width || 0) - Number(a.width || 0);
            if (width) return width;
            return Number(!!a.isOptimized) - Number(!!b.isOptimized);
        })[0] || null;
    }

    function forceBestMedia(obj) {
        if (!obj || typeof obj !== 'object') return;

        if (Array.isArray(obj.mediaSources) && obj.mediaSources.length > 1) {
            var best = bestSource(obj.mediaSources);
            if (best) obj.mediaSources = [best];
        }

        if (Array.isArray(obj.albumContent)) {
            obj.albumContent.forEach(function (slide) {
                if (!slide || typeof slide !== 'object') return;
                if (Array.isArray(slide.mediaSources) && slide.mediaSources.length > 1) {
                    var best = bestSource(slide.mediaSources);
                    if (best) slide.mediaSources = [best];
                }
            });
        }
    }

    function cleanApiObject(obj) {
        if (!obj || typeof obj !== 'object') return obj;

        if (Array.isArray(obj)) {
            var filtered = [];
            for (var i = 0; i < obj.length; i++) {
                var item = obj[i];
                if (isAdItem(item)) continue;
                var cleaned = cleanApiObject(item);
                if (cleaned !== undefined) filtered.push(cleaned);
            }
            return filtered;
        }

        forceBestMedia(obj);
        Object.keys(obj).forEach(function (key) {
            obj[key] = cleanApiObject(obj[key]);
        });
        return obj;
    }

    function cleanPayload(payload) {
        try { return cleanApiObject(payload); } catch (_) { return payload; }
    }

    function cleanedResponse(response, payload) {
        var headers = new Headers(response.headers);
        headers.delete('content-length');
        return new Response(JSON.stringify(payload), {
            status: response.status,
            statusText: response.statusText,
            headers: headers
        });
    }

    var nativeFetch = window.fetch;
    if (typeof nativeFetch === 'function') {
        window.fetch = async function (input, init) {
            var url = typeof input === 'string' ? input : (input && input.url) || '';
            if (!isTargetUrl(url)) return nativeFetch.apply(this, arguments);

            var nextInput = input;
            var nextInit = init;

            try {
                if (init && typeof init.body === 'string') {
                    var tuned = tuneRequestBody(init.body);
                    if (tuned !== init.body) nextInit = Object.assign({}, init, { body: tuned });
                } else if (typeof Request !== 'undefined' && input instanceof Request &&
                           String(input.method || '').toUpperCase() === 'POST') {
                    var raw = await input.clone().text();
                    var tunedBody = tuneRequestBody(raw);
                    if (tunedBody !== raw) nextInput = new Request(input, { body: tunedBody });
                }
            } catch (_) {}

            var response = await nativeFetch.call(this, nextInput, nextInit);
            if (!response || !response.ok) return response;

            try {
                var payload = await response.clone().json();
                payload = cleanPayload(payload);
                return cleanedResponse(response, payload);
            } catch (_) {
                return response;
            }
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
    window.__scrolllerProPreloadMode = 'legacy-one-shot';
})();
