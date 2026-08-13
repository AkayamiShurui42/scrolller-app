(function () {
    'use strict';
    if (window.__scrolllerProMediaQualityInstalled) return;
    window.__scrolllerProMediaQualityInstalled = true;

    function cleanUrl(url) {
        url = String(url || '').trim();
        if (!url || url.indexOf('data:') === 0 || url.indexOf('blob:') === 0) return '';
        try { return new URL(url, location.href).href; } catch (_) { return url; }
    }

    function scoreUrl(url) {
        var u = String(url || '');
        var score = 1;
        var p = u.match(/(?:^|[^0-9])(2160|1440|1080|720|480|360)(?:p|[^0-9]|$)/i);
        if (p) score = Math.max(score, Number(p[1]) * 100000);
        var wh = u.match(/(?:^|[^0-9])(\d{3,5})[xX](\d{3,5})(?:[^0-9]|$)/);
        if (wh) score = Math.max(score, Number(wh[1]) * Number(wh[2]));
        try {
            var parsed = new URL(u, location.href);
            var w = Number(parsed.searchParams.get('w') || parsed.searchParams.get('width') || 0);
            var h = Number(parsed.searchParams.get('h') || parsed.searchParams.get('height') || 0);
            if (w && h) score = Math.max(score, w * h);
            else if (w) score = Math.max(score, w * 10000);
        } catch (_) {}
        return score;
    }

    function parseSrcset(value) {
        var out = [];
        String(value || '').split(',').forEach(function (part) {
            var bits = part.trim().split(/\s+/);
            var url = cleanUrl(bits[0]);
            if (!url) return;
            var score = scoreUrl(url);
            var descriptor = bits[1] || '';
            var w = descriptor.match(/^(\d+(?:\.\d+)?)w$/);
            var x = descriptor.match(/^(\d+(?:\.\d+)?)x$/);
            if (w) score = Math.max(score, Number(w[1]) * 10000);
            if (x) score = Math.max(score, Number(x[1]) * 10000000);
            out.push({ url: url, score: score });
        });
        return out;
    }

    function add(list, url, score) {
        url = cleanUrl(url);
        if (url) list.push({ url: url, score: Math.max(score || 0, scoreUrl(url)) });
    }

    function bestImage(img) {
        var list = [];
        add(list, img.currentSrc, (img.naturalWidth || 0) * (img.naturalHeight || 0));
        add(list, img.src, (img.naturalWidth || 0) * (img.naturalHeight || 0));
        ['data-src','data-original','data-full','data-full-src','data-fullsize','data-image','data-lazy-src'].forEach(function (name) {
            add(list, img.getAttribute(name), 0);
        });
        list = list.concat(parseSrcset(img.getAttribute('srcset')));
        list = list.concat(parseSrcset(img.getAttribute('data-srcset')));
        var picture = img.closest && img.closest('picture');
        if (picture) {
            picture.querySelectorAll('source').forEach(function (source) {
                list = list.concat(parseSrcset(source.getAttribute('srcset')));
                list = list.concat(parseSrcset(source.getAttribute('data-srcset')));
            });
        }
        list.sort(function (a, b) { return b.score - a.score; });
        return list[0] || null;
    }

    function largeEnough(img) {
        try {
            var r = img.getBoundingClientRect();
            return (r.width >= 180 && r.height >= 140) || (img.naturalWidth || 0) >= 500 || (img.naturalHeight || 0) >= 500;
        } catch (_) { return false; }
    }

    function upgradeImage(img) {
        if (!img || !largeEnough(img)) return;
        var best = bestImage(img);
        if (!best || !best.url) return;
        var current = cleanUrl(img.currentSrc || img.src);
        var currentScore = Math.max(scoreUrl(current), (img.naturalWidth || 0) * (img.naturalHeight || 0));
        if (best.url === current || best.score <= currentScore) return;
        try {
            img.setAttribute('data-scrolller-pro-hq', '1');
            img.removeAttribute('srcset');
            img.removeAttribute('sizes');
            var picture = img.closest && img.closest('picture');
            if (picture) picture.querySelectorAll('source').forEach(function (s) { s.removeAttribute('srcset'); s.removeAttribute('sizes'); });
            img.src = best.url;
            img.decoding = 'async';
        } catch (_) {}
    }

    function qualityOfSource(source) {
        var score = scoreUrl(source.src || source.getAttribute('src'));
        ['data-height','height','data-quality','data-resolution','res','label'].forEach(function (name) {
            var raw = String(source.getAttribute && source.getAttribute(name) || '');
            var m = raw.match(/(2160|1440|1080|720|480|360)/);
            if (m) score = Math.max(score, Number(m[1]) * 100000);
        });
        return score;
    }

    function upgradeVideo(video) {
        if (!video || video.getAttribute('data-scrolller-pro-video-checked') === '1') return;
        var sources = Array.prototype.slice.call(video.querySelectorAll('source[src]')).filter(function (s) {
            return /\.(mp4|webm)(?:$|\?)/i.test(s.src || '');
        });
        video.setAttribute('data-scrolller-pro-video-checked', '1');
        if (sources.length < 2) return;
        sources.sort(function (a, b) { return qualityOfSource(b) - qualityOfSource(a); });
        var best = sources[0];
        var bestScore = qualityOfSource(best);
        var currentScore = scoreUrl(video.currentSrc || video.src);
        if (!best.src || bestScore <= currentScore || bestScore <= 1) return;
        try {
            var paused = video.paused;
            var time = Number(video.currentTime || 0);
            video.src = best.src;
            video.load();
            video.addEventListener('loadedmetadata', function restore() {
                video.removeEventListener('loadedmetadata', restore);
                try { if (time > 0 && isFinite(time)) video.currentTime = Math.min(time, video.duration || time); } catch (_) {}
                if (!paused) {
                    var p = video.play();
                    if (p && p.catch) p.catch(function () {});
                }
            });
        } catch (_) {}
    }

    function upgradeAll() {
        document.querySelectorAll('img').forEach(upgradeImage);
        document.querySelectorAll('video').forEach(upgradeVideo);
    }

    var queued = false;
    function queue() {
        if (queued) return;
        queued = true;
        requestAnimationFrame(function () { queued = false; upgradeAll(); });
    }

    new MutationObserver(queue).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['src','srcset','data-src','data-srcset'] });
    addEventListener('load', queue, true);
    addEventListener('scroll', queue, { passive: true, capture: true });
    window.ScrolllerProUpgradeMedia = upgradeAll;
    upgradeAll();
})();
