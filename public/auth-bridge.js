/* Native Scrolller website authentication bridge for the Android APK. */
(function () {
  if (window.scrolllerWebsiteAuthUiInstalled) return;
  window.scrolllerWebsiteAuthUiInstalled = true;

  function nativeAuthAvailable() {
    return typeof window.NativeAuth !== 'undefined';
  }

  function openWebsiteLogin(event) {
    if (!nativeAuthAvailable()) return;
    event.preventDefault();
    event.stopPropagation();
    event.stopImmediatePropagation();
    try {
      window.NativeAuth.openLogin();
    } catch (error) {
      console.error('Unable to open Scrolller website login:', error);
      if (typeof showToast === 'function') {
        showToast('Unable to open Scrolller sign-in.');
      }
    }
  }

  function install() {
    const signIn = document.getElementById('signin-trigger-btn');
    if (signIn) {
      signIn.textContent = 'Sign In with Scrolller';
      signIn.title = 'Open the real Scrolller website sign-in inside this app';
      signIn.addEventListener('click', openWebsiteLogin, true);
    }

    const oldModal = document.getElementById('signin-modal');
    if (oldModal && nativeAuthAvailable()) {
      // The APK uses the website login instead of manual bearer-token entry.
      oldModal.classList.add('hidden');
    }

    const signOut = document.getElementById('signout-btn');
    if (signOut && nativeAuthAvailable()) {
      signOut.addEventListener('click', () => {
        try { window.NativeAuth.clearToken(); } catch (_) {}
      }, true);
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', install, { once: true });
  } else {
    install();
  }
})();
