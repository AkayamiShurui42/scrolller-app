(function () {
    'use strict';

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

    function marker(node) {
        try {
            return String(
                (node && node.id || '') + ' ' +
                (node && typeof node.className === 'string' ? node.className : '') + ' ' +
                (node && node.getAttribute && (node.getAttribute('aria-label') || ''))
            ).toLowerCase();
        } catch (_) {
            return '';
        }
    }

    function looksAuth(value) {
        return /(^|[^a-z])(login|log-in|signin|sign-in|oauth|authentication|auth)([^a-z]|$)/i.test(value || '');
    }

    function isAuthContext(node) {
        var current = node;
        var depth = 0;
        while (current && current !== document.documentElement && depth++ < 7) {
            if (containsLogin(current) || looksAuth(marker(current))) return true;
            current = current.parentElement;
        }
        return false;
    }

    function feedCard(node) {
        try {
            return node && node.closest(
                'article,' +
                'div[class*="slide"],div[class*="Slide"],' +
                'div[class*="card"],div[class*="Card"],' +
                'div[class*="item"],div[class*="Item"],' +
                'div[class*="post"],div[class*="Post"]'
            );
        } catch (_) {
            return null;
        }
    }

    function collapse(node) {
        if (!node || node === document.body || node === document.documentElement || isAuthContext(node)) return;
        try {
            node.style.setProperty('display', 'none', 'important');
            node.style.setProperty('visibility', 'hidden', 'important');
            node.style.setProperty('pointer-events', 'none', 'important');
            node.style.setProperty('height', '0', 'important');
            node.style.setProperty('min-height', '0', 'important');
            node.style.setProperty('max-height', '0', 'important');
            node.style.setProperty('margin', '0', 'important');
            node.style.setProperty('padding', '0', 'important');
            node.style.setProperty('overflow', 'hidden', 'important');
            node.setAttribute('aria-hidden', 'true');
            node.setAttribute('data-scrolller-pro-ad-hidden', '1');
        } catch (_) {}
    }

    function collapseCardFor(node) {
        if (!node || isAuthContext(node)) return;
        collapse(feedCard(node) || node);
    }

    function collapseFeedCardOnly(node) {
        if (!node || isAuthContext(node)) return;
        var card = feedCard(node);
        if (card) collapse(card);
    }

    function removeKnownAds() {
        var selectors = [
            'iframe',
            'ins',
            '[class*="sponsored"]',
            '[class*="Sponsored"]',
            '[class*="sponsor"]',
            '[class*="Sponsor"]',
            '[class*="promoted"]',
            '[class*="Promoted"]',
            '[class*="promotion"]',
            '[class*="Promotion"]',
            '[class*="advert"]',
            '[class*="Advert"]',
            '[class*="adContainer"]',
            '[class*="AdContainer"]',
            '[class*="adsbygoogle"]',
            '[class*="exoclick"]',
            '[class*="juicyads"]',
            '[data-ad]',
            '[data-ad-id]',
            '[data-ad-slot]',
            '[data-ad-client]',
            '[data-ad-unit]',
            '[data-google-query-id]',
            '[aria-label*="advertisement" i]',
            '[aria-label*="sponsored" i]',
            '[aria-label*="promoted" i]',
            'a[href*="chaturbate"]',
            'a[href*="stripchat"]',
            'a[href*="cant3am"]',
            'a[href*="exoclick"]',
            'a[href*="juicyads"]'
        ];

        document.querySelectorAll(selectors.join(',')).forEach(function (node) {
            collapseCardFor(node);
        });
    }

    /*
     * Restored from the July website-wrapper ad blocker. The old build also
     * matched Scrolller's own premium/upgrade/paywall feed cards. Restrict the
     * rule to a real feed card so header/account controls are never removed.
     */
    function removeLegacyScrolllerPromoCards() {
        var selectors = [
            '[class*="Premium"]', '[class*="premium"]',
            '[class*="Upgrade"]', '[class*="upgrade"]',
            '[class*="paywall"]', '[class*="Paywall"]',
            '[class*="Billing"]', '[class*="billing"]',
            '[class*="fallbackContainer"]',
            '[class*="paidFallbackContainer"]',
            '[class*="exclusiveBadge"]'
        ];
        document.querySelectorAll(selectors.join(',')).forEach(collapseFeedCardOnly);
    }

    function removeLabeledPostAds() {
        var nodes = document.querySelectorAll('span,p,div,section,aside,article');
        for (var i = 0; i < nodes.length; i++) {
            var node = nodes[i];
            if (node.getAttribute('data-scrolller-pro-ad-hidden') === '1' || isAuthContext(node)) continue;

            var value = text(node);
            if (!value || value.length > 260) continue;

            var exact = value === 'sponsored' || value === 'promoted' || value === 'advertisement' ||
                        value === 'sponsored post' || value === 'promoted post' || value === 'ad';

            var promo = value.indexOf('sponsored by') === 0 ||
                        value.indexOf('promoted by') === 0 ||
                        value.indexOf('advertisement') === 0 ||
                        value.indexOf('enjoying scrolller') >= 0 ||
                        value.indexOf('remove ads') >= 0 ||
                        value.indexOf('ad-free') >= 0 ||
                        value.indexOf('ad free') >= 0 ||
                        value.indexOf('get premium') >= 0 ||
                        value.indexOf('go premium') >= 0 ||
                        value.indexOf('upgrade to premium') >= 0 ||
                        value.indexOf('premium removes ads') >= 0 ||
                        value.indexOf('support us') >= 0;

            if (!exact && !promo) continue;

            var card = feedCard(node);
            if (card) collapse(card);
        }
    }

    function removeAdBlockWarnings() {
        var nodes = document.querySelectorAll('[role="dialog"],[aria-modal="true"],[class*="modal"],[class*="Modal"],[class*="popup"],[class*="Popup"],[class*="overlay"],[class*="Overlay"]');
        for (var i = 0; i < nodes.length; i++) {
            var node = nodes[i];
            if (isAuthContext(node)) continue;
            var value = text(node);
            if (value.indexOf('adblock') >= 0 || value.indexOf('ad blocker') >= 0 ||
                value.indexOf('ad-block') >= 0 || value.indexOf('disable your ad') >= 0 ||
                value.indexOf('turn off ad') >= 0 || value.indexOf('whitelist') >= 0) {
                collapse(node);
            }
        }
    }

    function restoreTouchScrolling() {
        if (document.documentElement) {
            document.documentElement.style.setProperty('touch-action', 'pan-y pinch-zoom', 'important');
        }
        if (document.body) {
            document.body.style.setProperty('touch-action', 'pan-y pinch-zoom', 'important');
        }
    }

    function apply() {
        document.documentElement.setAttribute('data-scrolller-pro', '1');
        removeKnownAds();
        removeLegacyScrolllerPromoCards();
        removeLabeledPostAds();
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
        attributeFilter: ['class','id','href','aria-label','data-ad','data-ad-id','data-ad-slot']
    });

    addEventListener('popstate', function () { setTimeout(queueApply, 0); });

    window.ScrolllerNativeBack = function () { return false; };
    window.ScrolllerProCleanAds = apply;

    apply();
    setInterval(apply, 350);
})();
