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

    /*
     * This intentionally mirrors the older working response filter. These are
     * feed objects that Scrolller returned through its own API and rendered as
     * ordinary posts, so host/iframe blocking cannot remove them reliably.
     */
    function isAdItem(item) {
        if (!item || typeof item !== 'object') return false;

        if (item.isAd === true || item.is_ad === true ||
            item.isSponsor === true || item.is_sponsor === true || item.sponsored === true ||
            item.isPromoted === true || item.is_promoted === true || item.promoted === true ||
            item.promotion === true || item.isPromotion === true || item.is_promotion === true ||
            item.isPaid === true || item.is_paid === true) {
            return true;
        }

        var url = lower(item.url || item.href || item.link || item.targetUrl || item.clickUrl);
        if (url.indexOf('cant3am.com') >= 0 || url.indexOf('chaturbate') >= 0 ||
            url.indexOf('stripchat') >= 0 || url.indexOf('exoclick') >= 0 ||
            url.indexOf('juicyads') >= 0 || url.indexOf('realsrv') >= 0) {
            return true;
        }

        var redditAuthor = lower(item.reddit_posted_by);
        if (redditAuthor && (redditAuthor.indexOf('scrolller') >= 0 || redditAuthor === 'admin' ||
            redditAuthor === 'official' || redditAuthor === 'sponsor')) {
            return true;
        }

        var username = lower(item.username);
        if (username && (username.indexOf('scrolller') >= 0 || username === 'admin' ||
            username === 'official' || username === 'sponsor')) {
            return true;
        }

        var genericAuthor = lower(item.author || item.postedBy);
        if (genericAuthor && (genericAuthor === 'scrolller' || genericAuthor === 'admin' ||
            genericAuthor === 'official' || genericAuthor === 'sponsor')) {
            return true;
        }

        var title = lower(item.title);
        var description = lower(item.description);
        var promoPattern = /(^|\W)pro(\W|$)|cam|sponsor|promot|premium|unlock|wank|wish me luck|link in bio|onlyfans|snapchat|bio link/;
        if ((title && promoPattern.test(title)) || (description && promoPattern.test(description))) {
            return true;
        }

        var label = lower(item.label || item.type || item.kind);
        if (label === 'sponsored' || label === 'promoted' || label === 'advertisement' || label === 'ad') {
            return true;
        }

        return false;
    }

    function sourceArea(source) {
        if (!source || typeof source !== 'object') return 0;
        return Number(source.width || 0) * Number(source.height || 0);
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

        if (Array.isArray(obj.mediaSources) && obj.mediaSources.length > 0) {
            var best = bestSource(obj.mediaSources);
            if (best) obj.mediaSources = [best];
        }

        if (Array.isArray(obj.albumContent)) {
            obj.albumContent.forEach(function (slide) {
                if (!slide || typeof slide !== 'object') return;
                if (Array.isArray(slide.mediaSources) && slide.mediaSources.length > 0) {
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
                filtered.push(cleanApiObject(item));
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
                var payload = cleanPayload(await response.clone().json());
                return cleanedResponse(response, payload);
            } catch (_) {
                return response;
            }
        };
    }

    if (typeof XMLHttpRequest !== 'undefined') {
        var nativeOpen = XMLHttpRequest.prototype.open;
        var nativeSend = XMLHttpRequest.prototype.send;
        var responseTextDescriptor = Object.getOwnPropertyDescriptor(XMLHttpRequest.prototype, 'responseText');
        var responseDescriptor = Object.getOwnPropertyDescriptor(XMLHttpRequest.prototype, 'response');

        function cleanedXhrText(xhr) {
            if (!xhr.__scrolllerProTarget || !responseTextDescriptor || !responseTextDescriptor.get) return null;
            try {
                var raw = responseTextDescriptor.get.call(xhr);
                if (!raw) return raw;
                if (xhr.__scrolllerProCleanRaw === raw && typeof xhr.__scrolllerProCleanText === 'string') {
                    return xhr.__scrolllerProCleanText;
                }
                var parsed = JSON.parse(raw);
                var cleaned = JSON.stringify(cleanPayload(parsed));
                xhr.__scrolllerProCleanRaw = raw;
                xhr.__scrolllerProCleanText = cleaned;
                try { xhr.__scrolllerProCleanJson = JSON.parse(cleaned); } catch (_) {}
                return cleaned;
            } catch (_) {
                return null;
            }
        }

        XMLHttpRequest.prototype.open = function (method, url) {
            this.__scrolllerProMethod = String(method || '').toUpperCase();
            this.__scrolllerProUrl = String(url || '');
            this.__scrolllerProTarget = isTargetUrl(this.__scrolllerProUrl);
            this.__scrolllerProCleanRaw = null;
            this.__scrolllerProCleanText = null;
            this.__scrolllerProCleanJson = null;
            return nativeOpen.apply(this, arguments);
        };

        XMLHttpRequest.prototype.send = function (body) {
            try {
                if (this.__scrolllerProMethod === 'POST' && this.__scrolllerProTarget && typeof body === 'string') {
                    body = tuneRequestBody(body);
                }
            } catch (_) {}
            return nativeSend.call(this, body);
        };

        /*
         * XHR properties are native read-only getters. Overriding the prototype
         * getters lets Apollo/Scrolller receive the cleaned API JSON without
         * changing XHR timing, events, status codes, or cursor semantics.
         */
        if (responseTextDescriptor && responseTextDescriptor.get && responseTextDescriptor.configurable) {
            try {
                Object.defineProperty(XMLHttpRequest.prototype, 'responseText', {
                    configurable: true,
                    enumerable: responseTextDescriptor.enumerable,
                    get: function () {
                        if (this.__scrolllerProTarget && (!this.responseType || this.responseType === 'text')) {
                            var cleaned = cleanedXhrText(this);
                            if (cleaned !== null) return cleaned;
                        }
                        return responseTextDescriptor.get.call(this);
                    }
                });
            } catch (_) {}
        }

        if (responseDescriptor && responseDescriptor.get && responseDescriptor.configurable) {
            try {
                Object.defineProperty(XMLHttpRequest.prototype, 'response', {
                    configurable: true,
                    enumerable: responseDescriptor.enumerable,
                    get: function () {
                        if (this.__scrolllerProTarget) {
                            try {
                                if (this.responseType === 'json') {
                                    if (this.__scrolllerProCleanJson) return this.__scrolllerProCleanJson;
                                    var originalJson = responseDescriptor.get.call(this);
                                    if (originalJson && typeof originalJson === 'object') {
                                        var cloned = JSON.parse(JSON.stringify(originalJson));
                                        this.__scrolllerProCleanJson = cleanPayload(cloned);
                                        return this.__scrolllerProCleanJson;
                                    }
                                }
                                if (!this.responseType || this.responseType === 'text') {
                                    var cleaned = cleanedXhrText(this);
                                    if (cleaned !== null) return cleaned;
                                }
                            } catch (_) {}
                        }
                        return responseDescriptor.get.call(this);
                    }
                });
            } catch (_) {}
        }
    }

    window.__scrolllerProPreloadLimit = PRELOAD_LIMIT;
    window.__scrolllerProPreloadMode = 'legacy-one-shot';
    window.__scrolllerProApiAdFilter = 'legacy-fetch-xhr';
})();
