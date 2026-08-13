(function () {
    if (window.__scrolllerProInstalled) return;
    window.__scrolllerProInstalled = true;

    document.documentElement.setAttribute('data-scrolller-pro', '1');

    function text(node) {
        return String((node && node.innerText) || '').toLowerCase().replace(/\s+/g, ' ').trim();
    }

    function isAdBlockWarning(node) {
        const t = text(node);
        return t.includes('adblock') || t.includes('ad blocker') || t.includes('ad-block') ||
               t.includes('disable your ad') || t.includes('disable adblock') || t.includes('whitelist');
    }

    function containsLogin(node) {
        try {
            return !!node.querySelector('input[type="password"],input[name="password"],input[autocomplete="current-password"]');
        } catch (_) {
            return false;
        }
    }

    function removeAdBlockWarnings() {
        const nodes = document.querySelectorAll('[role="dialog"],[aria-modal="true"],[class*="modal"],[class*="popup"],[class*="overlay"],[class*="backdrop"]');
        for (const node of nodes) {
            if (!isAdBlockWarning(node) || containsLogin(node)) continue;
            node.style.setProperty('display', 'none', 'important');
            node.style.setProperty('visibility', 'hidden', 'important');
            node.style.setProperty('pointer-events', 'none', 'important');
            node.setAttribute('aria-hidden', 'true');
        }
    }

    function restoreTouchScrolling() {
        // Do not force html/body overflow. Scrolller may own scrolling through a nested
        // viewport; overriding its overflow model can make the page completely immobile.
        const html = document.documentElement;
        if (html) {
            html.style.removeProperty('overflow');
            html.style.removeProperty('overflow-y');
            html.style.removeProperty('height');
            html.style.setProperty('touch-action', 'pan-y pinch-zoom', 'important');
        }
        if (document.body) {
            document.body.style.removeProperty('overflow');
            document.body.style.removeProperty('overflow-y');
            document.body.style.removeProperty('height');
            document.body.style.setProperty('touch-action', 'pan-y pinch-zoom', 'important');
        }
    }

    function removeResidualAds() {
        const selectors = [
            'ins.adsbygoogle',
            'iframe[src*="doubleclick.net"]',
            'iframe[src*="googlesyndication.com"]',
            'iframe[src*="adnxs.com"]',
            '[id^="google_ads_"]',
            '[id*="div-gpt-ad"]',
            '[data-ad-slot]',
            '[data-ad-client]'
        ];
        document.querySelectorAll(selectors.join(',')).forEach(node => node.remove());
    }

    function apply() {
        document.documentElement.setAttribute('data-scrolller-pro', '1');
        removeResidualAds();
        removeAdBlockWarnings();
        restoreTouchScrolling();
    }

    const observer = new MutationObserver(() => requestAnimationFrame(apply));
    observer.observe(document.documentElement, { childList: true, subtree: true });

    const pushState = history.pushState;
    const replaceState = history.replaceState;
    history.pushState = function () {
        const result = pushState.apply(this, arguments);
        setTimeout(apply, 0);
        return result;
    };
    history.replaceState = function () {
        const result = replaceState.apply(this, arguments);
        setTimeout(apply, 0);
        return result;
    };
    addEventListener('popstate', () => setTimeout(apply, 0));

    window.ScrolllerNativeBack = function () {
        return false;
    };

    apply();
})();
