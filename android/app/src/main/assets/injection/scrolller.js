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

    function isAdBlockWarning(node) {
        const t = text(node);
        return t.includes('adblock') || t.includes('ad blocker') || t.includes('ad-block') ||
               t.includes('disable your ad') || t.includes('disable adblock') ||
               t.includes('turn off ad') || t.includes('whitelist');
    }

    function hide(node) {
        if (!node || node === document.body || node === document.documentElement) return;
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
            return node.closest('article,[class*="slide"],[class*="Slide"],[class*="card"],[class*="Card"],[class*="item"],[class*="Item"]');
        } catch (_) {
            return null;
        }
    }

    function removeAdBlockWarnings() {
        const nodes = document.querySelectorAll('[role="dialog"],[aria-modal="true"],[class*="modal"],[class*="Modal"],[class*="popup"],[class*="Popup"],[class*="overlay"],[class*="Overlay"],[class*="backdrop"],[class*="Backdrop"]');
        for (const node of nodes) {
            if (node.getAttribute('data-scrolller-pro-ad-hidden') === '1') continue;
            if (!isAdBlockWarning(node) || containsLogin(node)) continue;
            hide(node);
        }
    }

    function removeKnownAdNodes() {
        const selectors = [
            'ins.adsbygoogle',
            'iframe[src*="doubleclick.net"]',
            'iframe[src*="googlesyndication.com"]',
            'iframe[src*="adnxs.com"]',
            'iframe[src*="exoclick"]',
            'iframe[src*="juicyads"]',
            'iframe[src*="realsrv"]',
            'a[href*="chaturbate"]',
            'a[href*="stripchat"]',
            '[id^="google_ads_"]',
            '[id*="div-gpt-ad"]',
            '[class*="adsbygoogle"]',
            '[class*="adContainer"]',
            '[class*="AdContainer"]',
            '[class*="sponsored"]',
            '[class*="Sponsored"]',
            '[class*="promoted"]',
            '[class*="Promoted"]',
            '[class*="exoclick"]',
            '[class*="juicyads"]',
            '[data-ad-slot]',
            '[data-ad-client]',
            '[data-ad-unit]',
            '[data-google-query-id]'
        ];

        document.querySelectorAll(selectors.join(',')).forEach(function (node) {
            const card = closestFeedCard(node);
            hide(card || node);
        });
    }

    function removeLabeledAds() {
        document.querySelectorAll('span,p,div,section,aside,article').forEach(function (node) {
            if (node.getAttribute('data-scrolller-pro-ad-hidden') === '1') return;
            const label = text(node);
            if (label !== 'sponsored' && label !== 'promoted' && label !== 'advertisement' &&
                label !== 'sponsored post' && label !== 'promoted post') return;
            const card = closestFeedCard(node);
            hide(card || node);
        });
    }

    function restoreTouchScrolling() {
        // Do not restore the older build's global overflow:auto rule; it caused
        // the frozen-scroll regression in the hybrid WebView. Only preserve touch input.
        const html = document.documentElement;
        if (html) html.style.setProperty('touch-action', 'pan-y pinch-zoom', 'important');
        if (document.body) document.body.style.setProperty('touch-action', 'pan-y pinch-zoom', 'important');
    }

    function apply() {
        document.documentElement.setAttribute('data-scrolller-pro', '1');
        removeKnownAdNodes();
        removeLabeledAds();
        removeAdBlockWarnings();
        restoreTouchScrolling();
    }

    let queued = false;
    function queueApply() {
        if (queued) return;
        queued = true;
        requestAnimationFrame(function () {
            queued = false;
            apply();
        });
    }

    const observer = new MutationObserver(queueApply);
    observer.observe(document.documentElement, { childList: true, subtree: true, characterData: true });

    const pushState = history.pushState;
    const replaceState = history.replaceState;
    history.pushState = function () {
        const result = pushState.apply(this, arguments);
        setTimeout(queueApply, 0);
        return result;
    };
    history.replaceState = function () {
        const result = replaceState.apply(this, arguments);
        setTimeout(queueApply, 0);
        return result;
    };
    addEventListener('popstate', function () { setTimeout(queueApply, 0); });

    window.ScrolllerNativeBack = function () { return false; };
    window.ScrolllerProCleanAds = apply;

    apply();
    setInterval(apply, 750);
})();
