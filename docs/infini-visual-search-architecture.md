# Visual search parity research: infini.wtf

Updated: 2026-10-28

## What can actually be verified

The public `https://infini.wtf/popular` page advertises **AI visual search** based on depicted image/video content, not Reddit post titles. It currently advertises roughly 1.35 million indexed Reddit posts. The supplied HTML snapshot of `/search/chubby?nsfw=true` shows:

- A GET search form targeting `/search/`.
- Hotwire Stimulus controllers for query badges and autocomplete.
- Filters for media type (images, GIFs, video), NSFW, gay/trans, and time.
- Incremental server-rendered batches through `?format=turbo_stream&page=2`, with 10 results per page.
- An upload UI to search by an existing image.

None of those HTML/JS routes exposes the visual-search model, the server's vector index, or an API suitable for use as a dependable dependency. The website's developer publicly identified a CLIP-family image/text model as its visual-search technology. Do not pretend a browser-source download can retrieve private backend implementations.

Background: [CLIP paper](https://arxiv.org/abs/2103.00020), [OpenAI CLIP code](https://github.com/openai/CLIP).

## Why our current Reddit Native search differs

`MainActivity.remoteSearchPath()` calls Reddit `/search.json` with a text query. `FuzzySearch` then matches titles, subreddit names, author, and other metadata for local discovery. Neither path interprets the contents of the image pixels or samples a video. Searching for *blonde female wearing dress* can match a title with those words even if the media lacks that combination, and miss an uncaptioned image that matches perfectly.

`SearchRandomOrder` shuffles matched posts without changing whether they match the phrase. Sorting is deliberately separate from relevance qualification.

## Architecture to reach visual-content precision

1. **Build a media index outside the APK**, rather than querying every image live on the phone:
   - Gather permitted Reddit submissions, thumbnails, original images, galleries, and GIF/video keyframes.
   - Respect source policies, remove deleted/private content, and index only permitted material.
   - Normalize URLs and store canonical Reddit IDs, source community, creation time, media kind, NSFW status, dimensions, content hash.
   - Generate embeddings using a licensed CLIP/SigLIP-compatible image-text model. For videos, index representative keyframes and pool multiple frame embeddings, not just the video poster.
   - Store embeddings in a vector index, and make new posts asynchronously indexable.

2. **Encode and retrieve**:
   - Encode the full user text in the *same* embedding space as the images.
   - Run cosine-similarity / nearest-neighbor retrieval (e.g., HNSW or a vector-capable database). Apply subreddit, media, safety and time constraints.
   - Retrieve a larger candidate set and deduplicate reposts using canonical Reddit IDs and media hashes.
   - Enforce a configurable *minimum visual relevance*, instead of filling the results with weak matches.

3. **Composition-sensitive rerank for precise descriptions**:
   - A single CLIP similarity score does not guarantee each component is present. For *blonde female wearing dress*, verify **blonde hair + person + dress on that person** rather than independent object hits.
   - Use a stronger vision-language reranker or structured attribute verification on top candidate images; reject candidates missing any required part.
   - Rank by visual confidence before the Random shuffle. Random should shuffle **only qualified matches**.
   - Calibrate the cutoff against positive and negative examples. Admit that some searches can return fewer or zero results rather than inserting unrelated media.

4. **Native client integration**:
   - Create a versioned `VisualSearchProvider` contract returning stable post ID, visual score, media identity and next-page token.
   - Route all results into the current app's read-and-hide, privacy/safety and deduplication checks.
   - Continue using the existing bottom search sheet, search history and Random initial order.
   - Never insert paginated matches before already displayed/consumed items.
   - Support cancellations keyed to `searchGeneration` and immutable query/filter snapshots.
   - Use an explicit choice for fallback to Reddit text-only search when visual service is unavailable; never label fallback as visual search.

5. **Operate and measure**:
   - Run repeatable tests for phrase precision: *blonde hair + dress*, *black cat on couch*, *red car in snow*, and negative distractors (e.g. blonde woman without dress; brunette wearing dress).
   - Measure Precision@10 and miss rates from an annotated dataset, not just a few screenshots.
   - Ensure legal hosting and model licensing for potentially NSFW content; do not upload private user media to a third-party inference service without explicit informed action.
   - An initial deployment may index a smaller permitted corpus, with a clear corpus coverage indicator.

## Current implementation boundary

As of v4.0.8 the visual embedding backend **has not been implemented**. The app still uses Reddit text search plus local metadata matching; this document captures the necessary separate server/model work. The v4.0.8 change is limited to correct forward-only search navigation, not semantic image understanding.

Do not copy Infini proprietary code or depend on undocumented private routes. Implement the model/index pipeline independently using permitted open components.
