(function () {
    if (window.__scrolllerProInstalled) return;
    window.__scrolllerProInstalled = true;

    document.documentElement.setAttribute('data-scrolller-pro', '1');

    function text(node) {
        return String((node && node.innerText) || '').toLowerCase().replace(/\s+/g, ' ').trim();
    }

    function containsLogin(node) {
        try {
            return !!node.querySelector('input[type="password"],input[name="password"],input[autocomplete="current-password"]');
        } catch (_) {
            return false;
        }
    }

    function isAuthContext(node) {
        var current = node;
        var depth = 0;
        while (current && current !== document.documentElement && depth++ < 7) {
            if (containsLogin(current)) return true;
            var marker = String((current.id || '') + ' ' + (current.className || '') + ' ' +
                (current.getAttribute && (current.getAttribute('aria-label') || ''))).toLowerCase();
            if (marker.indexOf('login') >= 0 || marker.indexOf('sign-in') >= 0 ||
                marker.indexOf('signin') >= 0 || marker.indexOf('auth') >= 0) return true;
            current = current.parentElement;
        }
        return false;
    }

    function isAdBlockWarning(node) {
        var t = text(node);
        return t.indexOf('adblock') >= 0 || t.indexOf('ad blocker') >= 0 ||
               t.indexOf('ad-block') >= 0 || t.indexOf('disable your ad') >= 0 ||
               t.indexOf('disable adblock') >= 0 || t.indexOf('turn off ad') >= 0 ||
               t.indexOf('whitelist') >= 0;
    }

    function hide(node) {
        if (!node || node === document.body || node === document.documentElement || isAuthContext(node)) return;
        node.style.setProperty('display', 'none', 'important');
        node.style.setProperty('visibility', 'hidden', 'important');
        node.style.setProperty('pointer-events', 'none', 'important');
        node.style.setProperty('height', '0', 'important');
        node.style.setProperty('min-height', '0', 'important');
        node.style.setProperty('margin', '0', 'important');
        node.style.setProperty('padding', '0', 'important');
        node.setAttribute('aria-hidden', 'true');
        node.setAttribute('data-scrolller-pro-ad-hidden', '1');
    }

    function closestFeedCard(node) {
        try {
            return node.closest('article,[class*="slide"],[class*="Slide"],[class*="card"],[class*="Card"],[class*="item"],[class*="Item"],[class*="post"],[class*="Post"]');
        } catch (_) {
            return null;
        }
    }

    function removeAdBlockWarnings() {
        var nodes = document.querySelectorAll('[role="dialog"],[aria-modal="true"],[class*="modal"],[class*="Modal"],[class*="popup"],[class*="Popup"],[class*="overlay"],[class*="Overlay"],[class*="backdrop"],[class*="Backdrop"]');
        for (var i = 0; i < nodes.length; i++) {
            var node = nodes[i];
            if (node.getAttribute('data-scrolller-pro-ad-hidden') === '1') continue;
            if (!isAdBlockWarning(node) || isAuthContext(node)) continue;
            hide(node);
        }
    }

    function removeLegacyFrameAds() {
        // The older working build hid every feed iframe. Some Scrolller ads are
        // created in blank/first-party frames, so host-based blocking alone never sees them.
        document.querySelectorAll('iframe,ins').forEach(function (node) {
            if (isAuthContext(node)) return;
            var card = closestFeedCard(node);
            hide(card || node);
        });
    }

    function removeKnownAdNodes() {
        var selectors = [
            'a[href*="chaturbate"]',
            'a[href*="stripchat"]',
            '[id^="google_ads_"]',
            '[id*="div-gpt-ad"]',
            '[class*="adsbygoogle"]',
            '[class*="advertisement"]',
            '[class*="adContainer"]',
            '[class*="AdContainer"]',
            '[class*="sponsored"]',
            '[class*="Sponsored"]',
            '[class*="sponsor"]',
            '[class*="Sponsor"]',
            '[class*="promoted"]',
            '[class*="Promoted"]',
            '[class*="promotion"]',
            '[class*="Promotion"]',
            '[class*="Cam"]',
            '[class*="cam"]',
            '[class*="exoclick"]',
            '[class*="juicyads"]',
            '[data-ad-slot]',
            '[data-ad-client]',
            '[data-ad-unit]',
            '[data-google-query-id]'
        ];

        document.querySelectorAll(selectors.join(',')).forEach(function (node) {
            if (isAuthContext(node)) return;
            var card = closestFeedCard(node);
            hide(card || node);
        });
    }

    function removeLabeledAds() {
        document.querySelectorAll('span,p,div,section,aside,article').forEach(function (node) {
            if (node.getAttribute('data-scrolller-pro-ad-hidden') === '1' || isAuthContext(node)) return;
            var label = text(node);
            if (label !== 'sponsored' && label !== 'promoted' && label !== 'advertisement' &&
                label !== 'sponsored post' && label !== 'promoted post' && label !== 'ad') return;
            var card = closestFeedCard(node);
            hide(card || node);
        });
    }

    function restoreTouchScrolling() {
        var html = document.documentElement;
        if (html) html.style.setProperty('touch-action', 'pan-y pinch-zoom', 'important');
        if (document.body) document.body.style.setProperty('touch-action', 'pan-y pinch-zoom', 'important');
    }

    function apply() {
        document.documentElement.setAttribute('data-scrolller-pro', '1');
        removeLegacyFrameAds();
        removeKnownAdNodes();
        removeLabeledAds();
        removeAdBlockWarnings();
        restoreTouchScrolling();
    }

    var queued = false;
    function queueApply() {
        if (queued) return;
        queued = true;
        requestAnimationFrame(function () {
            queued = false;
            apply();
        });
    }

    var observer = new MutationObserver(queueApply);
    observer.observe(document.documentElement, {
        childList: true,
        subtree: true,
        characterData: true,
        attributes: true,
        attributeFilter: ['class','id','src','data-ad-slot','data-ad-client','data-ad-unit']
    });

    var pushState = history.pushState;
    var replaceState = history.replaceState;
    history.pushState = function () {
        var result = pushState.apply(this, arguments);
        setTimeout(queueApply, 0);
        return result;
    };
    history.replaceState = function () {
        var result = replaceState.apply(this, arguments);
        setTimeout(queueApply, 0);
        return result;
    };
    addEventListener('popstate', function () { setTimeout(queueApply, 0); });

    window.ScrolllerNativeBack = function () { return false; };
    window.ScrolllerProCleanAds = apply;

    apply();
    setInterval(apply, 500);
})();
