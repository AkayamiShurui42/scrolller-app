from pathlib import Path


def replace_required(text, old, new, label):
    if old not in text:
        raise SystemExit(f'Missing v3.6.7 Stream media target: {label}\n{old[:800]}')
    return text.replace(old, new, 1)

path = Path('app/src/main/java/com/scrolller/adblock/GridPostAdapter.java')
s = path.read_text()

s = replace_required(
    s,
    '''            if ((post.mediaKind == RedditPost.MediaKind.VIDEO || post.mediaKind == RedditPost.MediaKind.GIF)
                    && post.videoUrl != null && !post.videoUrl.isEmpty()) {
                addStreamPlayer(post);
                return;
            }

            ImageView image = new ImageView(context);''',
    '''            if (post.videoUrl != null && post.videoUrl.startsWith("redgifs:")) {
                final String unresolved = post.videoUrl;
                final String id = unresolved.substring("redgifs:".length());
                ImageView image = new ImageView(context);
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                image.setBackgroundColor(Color.BLACK);
                root.addView(image, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                if (post.posterUrl != null && !post.posterUrl.isEmpty()) {
                    Glide.with(image).load(post.posterUrl).fitCenter().into(image);
                }
                RedgifsResolver.resolve(id, new RedgifsResolver.Callback() {
                    @Override
                    public void onResolved(String url) {
                        if (post.videoUrl.equals(unresolved)) post.videoUrl = url;
                        int index = posts.indexOf(post);
                        if (index >= 0) notifyItemChanged(index);
                    }

                    @Override
                    public void onError(String error) {
                        // Keep the Reddit preview visible if provider resolution fails.
                    }
                });
                return;
            }

            if ((post.mediaKind == RedditPost.MediaKind.VIDEO || post.mediaKind == RedditPost.MediaKind.GIF)
                    && post.videoUrl != null && !post.videoUrl.isEmpty()) {
                addStreamPlayer(post);
                return;
            }

            ImageView image = new ImageView(context);''',
    'Stream lazy RedGIFs resolver')

s = replace_required(
    s,
    '            player = new ExoPlayer.Builder(context).build();',
    '            player = HighQualityPlayerFactory.create(context, post.videoUrl);',
    'Stream highest-quality player')

path.write_text(s)
print('Applied v3.6.7 Stream HD media wiring')
