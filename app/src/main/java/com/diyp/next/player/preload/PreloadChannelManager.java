package com.diyp.next.player.preload;

import android.util.Log;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import com.diyp.next.data.model.Channel;
import com.diyp.next.data.repository.SettingsRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages background preloading for adjacent channels (N-1 and N+1).
 * Respects multi-source memory settings, enforces the 192.168.*.* and 3.3.3.3 whitelist,
 * and hands off preloaded streams to ExoPlayer via PreloadBufferDataSource.
 */
@OptIn(markerClass = UnstableApi.class)
public class PreloadChannelManager {
    private static final String TAG = "PreloadChannelManager";
    private static volatile PreloadChannelManager instance;

    private final Map<String, PreloadWorker> activeWorkers = new ConcurrentHashMap<>();
    private String currentPlayingUrl = "";

    public static PreloadChannelManager getInstance() {
        if (instance == null) {
            synchronized (PreloadChannelManager.class) {
                if (instance == null) {
                    instance = new PreloadChannelManager();
                }
            }
        }
        return instance;
    }

    private PreloadChannelManager() {}

    /**
     * Update active channel and schedule preloading for adjacent N-1 and N+1 channels across the entire channel list.
     */
    public synchronized void updateActiveChannel(Channel activeChannel, List<Channel> allChannels, SettingsRepository settings) {
        if (settings == null || !settings.isPreloadEnabled()) {
            stopAll();
            return;
        }

        if (activeChannel == null || allChannels == null || allChannels.size() <= 1) {
            stopAll();
            return;
        }

        // Locate active channel in global channel list (exact instance match, or fallback to name + groupName/number)
        int activeIdx = allChannels.indexOf(activeChannel);
        if (activeIdx == -1) {
            for (int i = 0; i < allChannels.size(); i++) {
                Channel ch = allChannels.get(i);
                if (ch != null && ch.getName() != null && ch.getName().equalsIgnoreCase(activeChannel.getName())) {
                    if (ch.getGroupName() == null || activeChannel.getGroupName() == null ||
                        ch.getGroupName().equalsIgnoreCase(activeChannel.getGroupName())) {
                        activeIdx = i;
                        break;
                    }
                }
            }
        }

        if (activeIdx == -1) {
            return;
        }

        this.currentPlayingUrl = activeChannel.getCurrentUrl();

        int size = allChannels.size();
        int prevIdx = (activeIdx - 1 + size) % size;
        int nextIdx = (activeIdx + 1) % size;

        List<TargetSource> targets = new ArrayList<>();
        addTargetIfValid(targets, allChannels.get(prevIdx), settings);
        if (nextIdx != prevIdx) {
            addTargetIfValid(targets, allChannels.get(nextIdx), settings);
        }

        Set<String> targetUrls = new HashSet<>();
        for (TargetSource t : targets) {
            targetUrls.add(t.cleanUrl);
        }

        // 1. Evict any running worker that is no longer in target set or is currently playing
        for (Map.Entry<String, PreloadWorker> entry : activeWorkers.entrySet()) {
            String url = entry.getKey();
            if (!targetUrls.contains(url) || url.equals(currentPlayingUrl)) {
                PreloadWorker worker = entry.getValue();
                if (worker != null) {
                    worker.stop();
                }
                activeWorkers.remove(url);
                Log.d(TAG, "Evicted obsolete preload worker for url: " + url);
            }
        }

        // 2. Launch new workers for targets not yet running
        for (TargetSource t : targets) {
            if (t.cleanUrl.equals(currentPlayingUrl)) {
                continue;
            }

            PreloadWorker existing = activeWorkers.get(t.cleanUrl);
            if (existing != null) {
                if (existing.isAborted()) {
                    existing.stop();
                    activeWorkers.remove(t.cleanUrl);
                } else {
                    continue; // Keep running existing healthy worker
                }
            }

            // Dual Gate: Check whitelist and timeshift/vod before creating worker
            if (PreloadFilter.isPreloadEligible(t.cleanUrl)) {
                PreloadWorker worker = new PreloadWorker(t.channelName, t.sourceIndex, t.cleanUrl, t.headers);
                activeWorkers.put(t.cleanUrl, worker);
                worker.start();
                Log.i(TAG, "Started preload worker for " + t.channelName + " (Source " + (t.sourceIndex + 1) + "): " + t.cleanUrl);
            } else {
                Log.d(TAG, "Skipped preload (not eligible): " + t.cleanUrl);
            }
        }
    }

    private void addTargetIfValid(List<TargetSource> out, Channel channel, SettingsRepository settings) {
        if (channel == null || channel.getUrlCount() == 0) return;

        int sourceIdx = 0;
        if (settings.isRememberSource()) {
            sourceIdx = settings.getSavedSourceIndex(channel.getName());
        }
        if (sourceIdx < 0 || sourceIdx >= channel.getUrlCount()) {
            sourceIdx = channel.getCurrentUrlIndex();
        }
        if (sourceIdx < 0 || sourceIdx >= channel.getUrlCount()) {
            sourceIdx = 0;
        }

        String targetUrl = "";
        Map<String, String> headers = new HashMap<>(channel.getCurrentHeaders());

        if (!channel.getSources().isEmpty() && sourceIdx < channel.getSources().size()) {
            Channel.SourceItem item = channel.getSources().get(sourceIdx);
            targetUrl = item.getCleanUrl();
            if (item.getHeaders() != null) {
                headers.putAll(item.getHeaders());
            }
        } else {
            targetUrl = channel.getCurrentUrl();
        }

        if (targetUrl != null && !targetUrl.trim().isEmpty()) {
            out.add(new TargetSource(channel.getName(), sourceIdx, targetUrl.trim(), headers));
        }
    }

    /**
     * Consume and retrieve the preloaded DataSource for the given URL if ready.
     * Removes the worker from active set so playback has exclusive ownership.
     */
    public synchronized DataSource.Factory takePreload(String url) {
        if (url == null || url.trim().isEmpty()) return null;
        String trimmed = url.trim();

        PreloadWorker worker = activeWorkers.get(trimmed);
        if (worker == null) {
            return null;
        }

        if (worker.isReady()) {
            activeWorkers.remove(trimmed);
            Log.i(TAG, "Preload cache HIT for " + worker.getChannelName() + ": " + trimmed);
            return () -> new PreloadBufferDataSource(worker);
        } else {
            Log.i(TAG, "Preload cache MISS (worker not ready or aborted) for: " + trimmed);
            worker.stop();
            activeWorkers.remove(trimmed);
            return null;
        }
    }

    /**
     * Stop all background preloading workers and clear references.
     */
    public synchronized void stopAll() {
        for (PreloadWorker worker : activeWorkers.values()) {
            if (worker != null) {
                worker.stop();
            }
        }
        activeWorkers.clear();
        Log.d(TAG, "All preload workers stopped");
    }

    private static class TargetSource {
        final String channelName;
        final int sourceIndex;
        final String cleanUrl;
        final Map<String, String> headers;

        TargetSource(String channelName, int sourceIndex, String cleanUrl, Map<String, String> headers) {
            this.channelName = channelName;
            this.sourceIndex = sourceIndex;
            this.cleanUrl = cleanUrl;
            this.headers = headers;
        }
    }
}
