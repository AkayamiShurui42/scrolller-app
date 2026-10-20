# AI Gallery: acceptance and safety contract

Target branch: `feat/ai-nsfw-gallery-v1`. Android build from the existing Reddit/Scrolller application, but **do not expose legacy Scrolller, Reddit, RedGIFs, or unrestricted website views in AI Gallery**.

## Sources
- Sankaku Complex: require `ai-created`; preserve quality ordering where available.
- CreateHentai: verify an AI-generation marker per post; `topall` alone is NOT evidence.
- Rule34: require verified AI-generation tags/metadata; do not rely on user query text.

## Mandatory fail-closed checks
1. Only adult, AI-generated, NSFW still images. Reject unknown age, AI provenance, rating, or media type.
2. Exclude gore, graphic violence, mutilation, and blood/violence fetish content. Exclude minors, ambiguous-age depictions, and sexual violence.
3. Apply source-side filters when supported AND revalidate every returned post. Search terms, sort changes, pagination, cached data, bookmarks, and deep links must not bypass filters.
4. Do not fall back to unrestricted external websites when an API fails. Display an error/empty state instead.
5. No video, GIF, animated WebP, or album containing disallowed media.
6. Source adapters must use bounded requests, timeout, retry/backoff, deduplication, pagination, and graceful rate-limit handling.

## UI
- Unified search, source picker, source-specific sorting where supported, image grid, fullscreen viewer.
- No unrestricted media toggle.
- Keep source attribution and clear errors.

## Required tests before APK release
- Unit tests for each disallowed class, missing tags/metadata, search injection, pagination, cached posts, and fallback failure.
- API contract tests for all three sources.
- Build signed installable APK in GitHub Actions, publish artifact and SHA-256; install and smoke-test on Android.
- Never describe a website fallback or untested APK as completed integration.

**Status:** specification committed; implementation and verified release pending.
