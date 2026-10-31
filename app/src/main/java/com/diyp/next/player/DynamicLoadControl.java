package com.diyp.next.player;

import androidx.media3.common.C;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.DefaultAllocator;

/**
 * Dynamic Dual-Track LoadControl for DIYP-Next:
 * - Track 1 (Live mode): bufferForPlaybackMs = 250ms, bufferForPlaybackAfterRebufferMs = 1500ms
 *   Combined with GopRingBuffer preloading, enables near-instantaneous live startup (<20ms) without static frame pause.
 * - Track 2 (Timeshift TVOD & VOD mode): bufferForPlaybackMs = 2000ms, bufferForPlaybackAfterRebufferMs = 4000ms
 *   Preserves deep buffering buffer and 15s back-buffer for high-speed scrub (1.5x/2.0x) and timeline seek stability.
 */
@UnstableApi
public class DynamicLoadControl implements LoadControl {
    private final DefaultAllocator allocator;
    private final DefaultLoadControl liveControl;
    private final DefaultLoadControl vodControl;

    private volatile boolean isLiveMode = true;

    public DynamicLoadControl() {
        this.allocator = new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE);

        // Track 1: Live multicast & HTTP live streaming
        this.liveControl = new DefaultLoadControl.Builder()
                .setAllocator(allocator)
                .setBufferDurationsMs(
                        15000, // minBufferMs: 15s
                        60000, // maxBufferMs: 60s
                        250,   // bufferForPlaybackMs: 250ms (Ultra-fast startup)
                        1500   // bufferForPlaybackAfterRebufferMs: 1500ms
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        // Track 2: Timeshift (回看/TVOD) & VOD video files
        this.vodControl = new DefaultLoadControl.Builder()
                .setAllocator(allocator)
                .setBufferDurationsMs(
                        15000, // minBufferMs: 15s
                        60000, // maxBufferMs: 60s
                        2000,  // bufferForPlaybackMs: 2000ms (High-speed scrub stability)
                        4000   // bufferForPlaybackAfterRebufferMs: 4000ms
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .setBackBuffer(15000, false)
                .build();
    }

    public void setLiveMode(boolean isLive) {
        this.isLiveMode = isLive;
    }

    public boolean isLiveMode() {
        return isLiveMode;
    }

    private LoadControl getActiveControl() {
        return isLiveMode ? liveControl : vodControl;
    }

    @Override
    public Allocator getAllocator() {
        return allocator;
    }

    @Override
    public void onPrepared(PlayerId playerId) {
        liveControl.onPrepared(playerId);
        vodControl.onPrepared(playerId);
    }

    @Override
    public void onTracksSelected(
            PlayerId playerId,
            Timeline timeline,
            MediaPeriodId mediaPeriodId,
            Renderer[] renderers,
            TrackGroupArray trackGroups,
            ExoTrackSelection[] trackSelections) {
        liveControl.onTracksSelected(playerId, timeline, mediaPeriodId, renderers, trackGroups, trackSelections);
        vodControl.onTracksSelected(playerId, timeline, mediaPeriodId, renderers, trackGroups, trackSelections);
    }

    @Override
    public void onStopped(PlayerId playerId) {
        liveControl.onStopped(playerId);
        vodControl.onStopped(playerId);
    }

    @Override
    public void onReleased(PlayerId playerId) {
        liveControl.onReleased(playerId);
        vodControl.onReleased(playerId);
    }

    @Override
    public long getBackBufferDurationUs(PlayerId playerId) {
        return getActiveControl().getBackBufferDurationUs(playerId);
    }

    @Override
    public boolean retainBackBufferFromKeyframe(PlayerId playerId) {
        return getActiveControl().retainBackBufferFromKeyframe(playerId);
    }

    @Override
    public boolean shouldContinueLoading(Parameters parameters) {
        return getActiveControl().shouldContinueLoading(parameters);
    }

    @Override
    public boolean shouldStartPlayback(Parameters parameters) {
        return getActiveControl().shouldStartPlayback(parameters);
    }
}
