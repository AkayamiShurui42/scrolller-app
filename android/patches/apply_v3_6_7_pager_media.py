from pathlib import Path


def replace_required(text, old, new, label):
    if old not in text:
        raise SystemExit(f'Missing v3.6.7 pager media target: {label}\n{old[:800]}')
    return text.replace(old, new, 1)

path = Path('app/src/main/java/com/scrolller/adblock/PostPagerAdapter.java')
s = path.read_text()

s = replace_required(
    s,
    '''        private void addMedia(RedditPost post, int position) {
            if ((post.mediaKind == RedditPost.MediaKind.VIDEO || post.mediaKind == RedditPost.MediaKind.GIF)
                    && post.videoUrl != null && !post.videoUrl.isEmpty()) {''',
    '''        private void addMedia(RedditPost post, int position) {
            if (post.videoUrl != null && post.videoUrl.startsWith("redgifs:")) {
                final String unresolved = post.videoUrl;
                final String id = unresolved.substring("redgifs:".length());
                ImageView poster = new ImageView(context);
                poster.setBackgroundColor(Color.BLACK);
                poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
                root.addView(poster, fullParams());
                if (post.posterUrl != null && !post.posterUrl.isEmpty()) {
                    Glide.with(poster).load(post.posterUrl).fitCenter().into(poster);
                }
                RedgifsResolver.resolve(id, new RedgifsResolver.Callback() {
                    @Override
                    public void onResolved(String url) {
                        if (post.videoUrl.equals(unresolved)) post.videoUrl = url;
                        if (boundPosition == position) bind(post, position);
                    }

                    @Override
                    public void onError(String error) {
                        listener.onMediaFailed(post);
                    }
                });
                return;
            }

            if ((post.mediaKind == RedditPost.MediaKind.VIDEO || post.mediaKind == RedditPost.MediaKind.GIF)
                    && post.videoUrl != null && !post.videoUrl.isEmpty()) {''',
    'fullscreen lazy RedGIFs resolver')

s = replace_required(
    s,
    '                player = new ExoPlayer.Builder(context).build();',
    '                player = HighQualityPlayerFactory.create(context, post.videoUrl);',
    'fullscreen highest-quality player')

path.write_text(s)
print('Applied v3.6.7 fullscreen HD media wiring')
