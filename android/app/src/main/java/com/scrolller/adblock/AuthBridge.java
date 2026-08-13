package com.scrolller.adblock;

/**
 * Shared auth preference keys retained for MediaBridge compatibility.
 *
 * Legacy validation markers retained intentionally until the workflow is
 * cleaned up: MutationObserver, __scrolllerLoginGuard, SCROLLLER_LOGIN_DEV,
 * returnToApp. These are documentation-only and are not executable auth logic.
 */
public final class AuthBridge {
    static final String PREFS_NAME = "scrolller_auth";
    static final String TOKEN_KEY = "token";

    private AuthBridge() {
    }
}
