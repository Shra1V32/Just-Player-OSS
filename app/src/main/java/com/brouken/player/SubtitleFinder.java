package com.brouken.player;

import android.net.Uri;

import androidx.media3.common.util.Util;

import java.util.ArrayList;
import java.util.List;

import okhttp3.HttpUrl;

public class SubtitleFinder {

    private PlayerActivity activity;
    private Uri baseUri;
    private String path;
    private final List<Uri> urls;
    private SubtitleFetcher subtitleFetcher;

    public SubtitleFinder(PlayerActivity activity, Uri uri) {
        this.activity = activity;
        path = uri.getPath();
        path = path.substring(0, path.lastIndexOf('.'));
        baseUri = uri;
        urls = new ArrayList<>();
    }

    public static boolean isUriCompatible(Uri uri) {
        String pth = uri.getPath();
        if (pth != null) {
            return pth.lastIndexOf('.') > -1;
        }
        return false;
    }

    private void addLanguage(String lang, String suffix) {
        urls.add(buildUri(lang + "." + suffix));
        urls.add(buildUri(Util.normalizeLanguageCode(lang) + "." + suffix));
    }

    private Uri buildUri(String suffix) {
        final String newPath = path + "." + suffix;
        return baseUri.buildUpon().path(newPath).build();
    }

    /**
     * Start asynchronous subtitle search - non-blocking
     */
    public void start() {
        // Prevent IllegalArgumentException in okhttp3.Request.Builder
        if (HttpUrl.parse(baseUri.toString()) == null) {
            return;
        }

        // Build prioritized URL list
        buildPrioritizedUrls();
        
        // Start asynchronous fetching
        subtitleFetcher = new SubtitleFetcher(activity, urls);
        subtitleFetcher.start();
    }

    /**
     * Build URLs in a smarter order to reduce unnecessary requests
     */
    private void buildPrioritizedUrls() {
        String[] deviceLanguages = Utils.getDeviceLanguages();
        
        // Priority 1: Device languages with SRT (most common)
        for (String language : deviceLanguages) {
            addLanguage(language, "srt");
        }
        
        // Priority 2: Generic SRT
        urls.add(buildUri("srt"));
        
        // Priority 3: Device languages with other formats
        for (String language : deviceLanguages) {
            addLanguage(language, "ssa");
            addLanguage(language, "ass");
        }
        
        // Priority 4: Generic other formats
        urls.add(buildUri("ssa"));
        urls.add(buildUri("ass"));
        
        // Priority 5: VTT (less common)
        for (String language : deviceLanguages) {
            addLanguage(language, "vtt");
        }
        urls.add(buildUri("vtt"));
    }

    /**
     * Cancel any ongoing subtitle search
     */
    public void cancel() {
        if (subtitleFetcher != null) {
            subtitleFetcher.cancel();
        }
    }
}
