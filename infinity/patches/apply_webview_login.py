from pathlib import Path

main = Path("app/src/main/java/ml/docilealligator/infinityforreddit/activities/MainActivity.java")
login = Path("app/src/main/java/ml/docilealligator/infinityforreddit/activities/LoginActivity.java")

main_text = main.read_text(encoding="utf-8")
login_text = login.read_text(encoding="utf-8")

old = "intent = new Intent(MainActivity.this, AppAuthLoginActivity.class);"
new = "intent = new Intent(MainActivity.this, LoginActivity.class);"

count = main_text.count(old)
if count != 1:
    raise SystemExit(f"Expected exactly one Infinity 8.2.1 Add account AppAuth route, found {count}")

main_text = main_text.replace(old, new, 1)
main.write_text(main_text, encoding="utf-8")

# Guard the important invariant: only the entry activity changes. Infinity's
# existing embedded WebView still performs Reddit OAuth authorization and then
# exchanges the returned code for access/refresh tokens exactly as upstream.
required_login = [
    "binding.webviewLoginActivity.getSettings().setJavaScriptEnabled(true);",
    "Uri baseUri = Uri.parse(APIUtils.OAUTH_URL);",
    "binding.webviewLoginActivity.loadUrl(url);",
    "params.put(APIUtils.GRANT_TYPE_KEY, \"authorization_code\");",
    "String accessToken = responseJSON.getString(APIUtils.ACCESS_TOKEN_KEY);",
    "String refreshToken = responseJSON.getString(APIUtils.REFRESH_TOKEN_KEY);",
    "FetchMyInfo.fetchAccountInfo",
    "ParseAndInsertNewAccount.parseAndInsertNewAccount",
]
missing = [needle for needle in required_login if needle not in login_text]
if missing:
    raise SystemExit("Infinity 8.2.1 WebView OAuth invariants changed upstream: " + ", ".join(missing))

if "new Intent(MainActivity.this, LoginActivity.class);" not in main_text:
    raise SystemExit("WebView login route was not installed")

print("Applied isolated Infinity+ v8.2.1 Add account -> embedded WebView login route")
