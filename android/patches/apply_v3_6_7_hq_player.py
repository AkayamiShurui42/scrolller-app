from pathlib import Path

path = Path('app/src/main/java/com/scrolller/adblock/HighQualityPlayerFactory.java')
path.write_text(r'''package com.scrolller.adblock;

import android.content.Context;

import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

import java.util.HashMap;
import java.util.Map;

final class HighQualityPlayerFactory {
    private HighQualityPlayerFactory() {}

    static ExoPlayer create(Context context, String mediaUrl) {
        DefaultTrackSelector selector = new DefaultTrackSelector(context);
        selector.setParameters(
                selector.buildUponParameters()
                        .setForceHighestSupportedBitrate(true));

        ExoPlayer.Builder builder = new ExoPlayer.Builder(context)
                .setTrackSelector(selector);

        if (isRedgifsMedia(mediaUrl)) {
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", "https://www.redgifs.com/");
            headers.put("Origin", "https://www.redgifs.com");
            headers.put("Accept", "*/*");

            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                    .setUserAgent("Mozilla/5.0 (Linux; Android 16) RedditMedia/3.6.7")
                    .setAllowCrossProtocolRedirects(true)
                    .setDefaultRequestProperties(headers);
            DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(context)
                    .setDataSourceFactory(http);
            builder.setMediaSourceFactory(mediaSourceFactory);
        }

        return builder.build();
    }

    private static boolean isRedgifsMedia(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.contains("redgifs.com") || lower.contains("redgifsusercontent.com");
    }
}
''')
print('Wrote v3.6.7 highest-quality Media3 player factory')
