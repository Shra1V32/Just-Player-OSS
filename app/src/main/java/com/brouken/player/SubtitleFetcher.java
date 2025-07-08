package com.brouken.player;

import android.net.Uri;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

class SubtitleFetcher {

    private static final int MAX_CONCURRENT_REQUESTS = 3;
    private static final int TIMEOUT_SECONDS = 8;
    private static final long CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutes
    
    // Static cache shared across instances
    private static final Map<String, CacheEntry> urlCache = new ConcurrentHashMap<>();
    
    private PlayerActivity activity;
    private final List<Uri> prioritizedUrls;
    private final AtomicBoolean subtitleFound = new AtomicBoolean(false);
    private final AtomicInteger pendingRequests = new AtomicInteger(0);
    private final ExecutorService executorService;
    private OkHttpClient httpClient;

    private static class CacheEntry {
        final boolean exists;
        final long timestamp;
        
        CacheEntry(boolean exists) {
            this.exists = exists;
            this.timestamp = System.currentTimeMillis();
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }

    public SubtitleFetcher(PlayerActivity activity, List<Uri> urls) {
        this.activity = activity;
        this.prioritizedUrls = prioritizeUrls(urls);
        this.executorService = Executors.newFixedThreadPool(MAX_CONCURRENT_REQUESTS);
        
        // Create HTTP client with proper timeouts
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Prioritize URLs to check most likely subtitles first
     */
    private List<Uri> prioritizeUrls(List<Uri> urls) {
        List<Uri> prioritized = new ArrayList<>();
        String[] deviceLanguages = Utils.getDeviceLanguages();
        
        // Priority 1: Device language + SRT
        for (String lang : deviceLanguages) {
            for (Uri url : urls) {
                String path = url.getPath();
                if (path != null && path.contains("." + lang + ".srt")) {
                    prioritized.add(url);
                }
            }
        }
        
        // Priority 2: SRT without language
        for (Uri url : urls) {
            String path = url.getPath();
            if (path != null && path.endsWith(".srt") && !prioritized.contains(url)) {
                prioritized.add(url);
            }
        }
        
        // Priority 3: Device language + other formats
        for (String lang : deviceLanguages) {
            for (Uri url : urls) {
                String path = url.getPath();
                if (path != null && path.contains("." + lang + ".") && !prioritized.contains(url)) {
                    prioritized.add(url);
                }
            }
        }
        
        // Priority 4: Everything else
        for (Uri url : urls) {
            if (!prioritized.contains(url)) {
                prioritized.add(url);
            }
        }
        
        return prioritized;
    }

    /**
     * Start asynchronous subtitle fetching - non-blocking
     */
    public void start() {
        if (prioritizedUrls.isEmpty()) {
            return;
        }
        
        // Clean expired cache entries
        cleanCache();
        
        // Start progressive search in background
        executorService.execute(this::startProgressiveSearch);
    }

    private void cleanCache() {
        urlCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    private void startProgressiveSearch() {
        // Process URLs in batches to avoid overwhelming the server
        int batchSize = Math.min(MAX_CONCURRENT_REQUESTS, prioritizedUrls.size());
        
        for (int i = 0; i < prioritizedUrls.size() && !subtitleFound.get(); i += batchSize) {
            int endIndex = Math.min(i + batchSize, prioritizedUrls.size());
            List<Uri> batch = prioritizedUrls.subList(i, endIndex);
            
            // Process current batch
            processBatch(batch);
            
            // Wait for batch to complete before starting next batch
            waitForBatchCompletion();
            
            // If subtitle found, stop searching
            if (subtitleFound.get()) {
                break;
            }
        }
        
        // Cleanup
        executorService.shutdown();
    }

    private void processBatch(List<Uri> batch) {
        for (Uri url : batch) {
            if (subtitleFound.get()) {
                break;
            }
            
            String urlString = url.toString();
            
            // Check cache first
            CacheEntry cached = urlCache.get(urlString);
            if (cached != null && !cached.isExpired()) {
                if (cached.exists) {
                    downloadSubtitle(url);
                }
                continue;
            }
            
            // Validate URL
            if (HttpUrl.parse(urlString) == null) {
                urlCache.put(urlString, new CacheEntry(false));
                continue;
            }
            
            // Check if URL exists using HEAD request
            pendingRequests.incrementAndGet();
            checkUrlExists(url);
        }
    }

    private void waitForBatchCompletion() {
        try {
            // Wait for current batch to complete with timeout
            long startTime = System.currentTimeMillis();
            while (pendingRequests.get() > 0 && !subtitleFound.get()) {
                Thread.sleep(100);
                
                // Timeout safety
                if (System.currentTimeMillis() - startTime > TIMEOUT_SECONDS * 1000L + 2000) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void checkUrlExists(Uri url) {
        String urlString = url.toString();
        
        // Use HEAD request to check existence without downloading
        Request request = new Request.Builder()
                .url(urlString)
                .head()
                .build();
        
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                urlCache.put(urlString, new CacheEntry(false));
                pendingRequests.decrementAndGet();
                Utils.log("Subtitle check failed for: " + urlString + " - " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                boolean exists = response.isSuccessful();
                urlCache.put(urlString, new CacheEntry(exists));
                
                Utils.log(response.code() + ": " + urlString);
                
                if (exists && !subtitleFound.get()) {
                    // Download the subtitle
                    executorService.execute(() -> downloadSubtitle(url));
                }
                
                response.close();
                pendingRequests.decrementAndGet();
            }
        });
    }

    private void downloadSubtitle(Uri url) {
        if (subtitleFound.getAndSet(true)) {
            return; // Another thread already found a subtitle
        }
        
        String urlString = url.toString();
        Utils.log("Downloading subtitle: " + urlString);
        
        Request request = new Request.Builder()
                .url(urlString)
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            final ResponseBody responseBody = response.body();
            
            if (responseBody == null || responseBody.contentLength() > 2_000_000) {
                subtitleFound.set(false); // Allow other threads to try
                return;
            }
            
            InputStream inputStream = responseBody.byteStream();
            Uri convertedSubtitleUri = Utils.convertInputStreamToUTF(activity, url, inputStream);
            
            if (convertedSubtitleUri == null) {
                subtitleFound.set(false); // Allow other threads to try
                return;
            }
            
            // Update UI on main thread
            activity.runOnUiThread(() -> applySubtitle(convertedSubtitleUri));
            
        } catch (IOException e) {
            Utils.log("Failed to download subtitle: " + e.toString());
            subtitleFound.set(false); // Allow other threads to try
            e.printStackTrace();
        }
    }

    private void applySubtitle(Uri convertedSubtitleUri) {
        activity.mPrefs.updateSubtitle(convertedSubtitleUri);
        
        if (PlayerActivity.player != null) {
            MediaItem mediaItem = PlayerActivity.player.getCurrentMediaItem();
            if (mediaItem != null) {
                MediaItem.SubtitleConfiguration subtitle = SubtitleUtils.buildSubtitle(
                    activity, convertedSubtitleUri, null, true);
                mediaItem = mediaItem.buildUpon()
                    .setSubtitleConfigurations(Collections.singletonList(subtitle))
                    .build();
                PlayerActivity.player.setMediaItem(mediaItem, false);
                
                if (BuildConfig.DEBUG) {
                    Toast.makeText(activity, "Subtitle found", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    /**
     * Cancel any pending subtitle fetching
     */
    public void cancel() {
        subtitleFound.set(true); // Stop further processing
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdownNow();
        }
    }
}
