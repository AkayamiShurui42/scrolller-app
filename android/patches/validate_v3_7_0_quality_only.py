from pathlib import Path

root = Path('android')
main = (root / 'app/src/main/java/com/scrolller/adblock/MainActivity.java').read_text()
gradle = (root / 'app/build.gradle').read_text()
hq = (root / 'app/src/main/java/com/scrolller/adblock/HighQualityPlayerFactory.java').read_text()
scrolller = (root / 'app/src/main/java/com/scrolller/adblock/ScrolllerClient.java').read_text()

checks = [
    ('quality feed selector', 'Quality crawl' in main),
    ('quality context', 'context.equals("quality")' in main),
    ('quality loader', 'loadQualityFeed' in main),
    ('quality generation guard', 'qualityCrawlGeneration' in main),
    ('grouped subscription probes', 'qualityProbeGroups' in main),
    ('all/year/month quality windows', 'String[] windows = {"all", "year", "month"}' in main),
    ('learned source selection', 'bestQualitySources(12)' in main),
    ('deep source crawl', 'crawlQualityDeep' in main),
    ('deep New source', '/new.json?limit=100&raw_json=1&show=all' in main),
    ('request pacing', 'root.postDelayed' in main and '220L' in main),
    ('quality scoring', 'qualityScoreForChild' in main),
    ('gallery original dimensions', 'media_metadata' in main and 'optInt("x", 0)' in main and 'optInt("y", 0)' in main),
    ('gallery average area threshold', 'averageArea >= 3500000L' in main),
    ('8MP quality threshold', 'maxArea >= 8000000L' in main),
    ('16MP ultra threshold', 'maxArea >= 16000000L' in main),
    ('2400px image edge threshold', 'maxEdge >= 2400' in main or 'longEdge < 2400' in main),
    ('video 900px short edge threshold', 'shortEdge < 900' in main),
    ('video 1600px long edge threshold', 'longEdge < 1600' in main),
    ('quality ranking map', 'qualityScores' in main),
    ('quality source learning map', 'qualitySourceHits' in main),
    ('Hidden rejection preserved', 'hiddenPosts.containsKey(post.id)' in main),
    ('Saved rejection preserved', 'isSavedForUnread(post)' in main),
    ('content rejection preserved', 'isContentBlocked(post)' in main),
    ('saved identity set preserved', 'savedPostIds' in main),
    ('media-ready read gate preserved', 'mediaReadyPostIds.contains(previous.id)' in main),
    ('LGBTQ preference preserved', 'blockLgbtTopics' in main),
    ('gore preference preserved', 'blockGoreContent' in main),
    ('adaptive Media3 preserved', 'setForceHighestSupportedBitrate(false)' in hq),
    ('Scrolller source preserved', 'https://api.scrolller.com/admin' in scrolller),
    ('stable package', 'applicationId "com.crimson.redditmedia"' in gradle),
    ('versionCode 28', 'versionCode 28' in gradle),
    ('versionName 3.7.0', 'versionName "3.7.0"' in gradle),
    ('no federated Search coordinator', 'runFederatedSearch' not in main),
    ('no Arctic Shift search client generated', not (root / 'app/src/main/java/com/scrolller/adblock/ArcticShiftClient.java').exists()),
]

failed = []
for label, ok in checks:
    print(('OK      ' if ok else 'FAIL    ') + label)
    if not ok:
        failed.append(label)

# Automatic swipe/read must never mutate the live pager. Saving is allowed to
# remove a post immediately because Save is an explicit user state transition.
track_start = main.find('private void trackFullscreenVisit')
track_end = main.find('\n    private ', track_start + 10) if track_start >= 0 else -1
track = main[track_start:track_end] if track_start >= 0 and track_end > track_start else ''
if 'removePostById' in track:
    failed.append('automatic swipe/read path mutates live adapter')
    print('FAIL    automatic swipe/read path mutates live adapter')
else:
    print('OK      automatic swipe/read path keeps active adapters immutable')

if failed:
    raise SystemExit('v3.7.0 quality-only validation failed: ' + ', '.join(failed))
print('v3.7.0 quality-only behavior validation passed')
