package com.diyp.next.player;

import android.content.Context;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceView;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.TimestampAdjuster;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import java.lang.reflect.Constructor;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.text.TextOutput;
import androidx.media3.exoplayer.text.TextRenderer;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoFrameMetadataListener;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.extractor.ts.TsExtractor;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.SubtitleView;
import android.util.Log;
import com.diyp.next.data.model.Channel;
import com.diyp.next.data.repository.SettingsRepository;
import com.diyp.next.player.preload.PreloadChannelManager;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@OptIn(markerClass = UnstableApi.class)
public class VideoPlayerManager {
    private final Context context;
    private ExoPlayer player;
    private SurfaceView surfaceView;
    private AspectRatioFrameLayout aspectRatioFrameLayout;
    private SubtitleView subtitleView;

    private Channel currentChannel;
    private String currentStreamUrl;
    private Map<String, String> currentHeaders;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable timeoutRunnable;
    private PlayerListener listener;
    private DynamicLoadControl dynamicLoadControl;
    private boolean isTimeShiftStream = false;
    private boolean needsInitialSeek = false;
    private long timeShiftBaseEpochMs = 0;
    private long pendingResumePositionMs = C.TIME_UNSET;

    // Dynamic PTS Frame Rate Calculation (Scheme A)
    private final Object ptsLock = new Object();
    private long lastPtsUs = C.TIME_UNSET;
    private long ptsDiffSumUs = 0L;
    private int ptsDiffCount = 0;
    private volatile boolean ptsCalculationDone = false;

    private final StreamInfo currentStreamInfo = new StreamInfo();

    public static class StreamInfo {
        public String resolution = "";      // e.g. "1080P", "1080i", "4K", "720P"
        public String fps = "";             // e.g. "50 FPS", "60 FPS"
        public String videoCodec = "";      // e.g. "H.264", "H.265", "AV1"
        public String dynamicRange = "";    // e.g. "10-bit HDR", "HLG", "SDR"
        public String audioCodec = "";      // e.g. "MP2", "AAC", "AC3", "E-AC3"
        public String audioChannels = "";   // e.g. "立体声", "5.1 声道", "单声道"
        public String bitrate = "";         // e.g. "8.9 Mbps"
        public String videoDecode = "";     // e.g. "视频硬解", "视频软解"
        public String audioDecode = "";     // e.g. "音频硬解", "音频软解"

        public void reset() {
            resolution = "";
            fps = "";
            videoCodec = "";
            dynamicRange = "";
            audioCodec = "";
            audioChannels = "";
            bitrate = "";
            // videoDecode/audioDecode persist across streams: a reused decoder fires no
            // new init callback, so clearing them here would lose the correct state
        }

        public String getFormattedText() {
            StringBuilder sb = new StringBuilder();
            if (resolution != null && !resolution.isEmpty()) {
                sb.append(resolution);
            }
            if (fps != null && !fps.isEmpty()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(fps);
            }
            if (videoCodec != null && !videoCodec.isEmpty()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(videoCodec);
            }
            if (dynamicRange != null && !dynamicRange.isEmpty()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(dynamicRange);
            }
            if (audioCodec != null && !audioCodec.isEmpty()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(audioCodec);
            }
            if (audioChannels != null && !audioChannels.isEmpty()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(audioChannels);
            }
            if (bitrate != null && !bitrate.isEmpty()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(bitrate);
            }
            return sb.toString();
        }

        public List<String> getTags() {
            List<String> tags = new ArrayList<>();
            if (resolution != null && !resolution.isEmpty()) tags.add(resolution);
            if (fps != null && !fps.isEmpty()) tags.add(fps);
            if (videoCodec != null && !videoCodec.isEmpty()) tags.add(videoCodec);
            if (dynamicRange != null && !dynamicRange.isEmpty()) tags.add(dynamicRange);
            if (audioCodec != null && !audioCodec.isEmpty()) tags.add(audioCodec);
            if (audioChannels != null && !audioChannels.isEmpty()) tags.add(audioChannels);
            if (bitrate != null && !bitrate.isEmpty()) tags.add(bitrate);
            if (videoDecode != null && !videoDecode.isEmpty()) tags.add(videoDecode);
            if (audioDecode != null && !audioDecode.isEmpty()) tags.add(audioDecode);
            return tags;
        }
    }

    public static class AudioTrackInfo {
        public final TrackGroup trackGroup;
        public final int trackIndex;
        public final String name;
        public final boolean isSelected;

        public AudioTrackInfo(TrackGroup trackGroup, int trackIndex, String name, boolean isSelected) {
            this.trackGroup = trackGroup;
            this.trackIndex = trackIndex;
            this.name = name;
            this.isSelected = isSelected;
        }
    }

    public static class SubtitleTrackInfo {
        public final TrackGroup trackGroup;
        public final int trackIndex;
        public final String name;
        public final boolean isSelected;

        public SubtitleTrackInfo(TrackGroup trackGroup, int trackIndex, String name, boolean isSelected) {
            this.trackGroup = trackGroup;
            this.trackIndex = trackIndex;
            this.name = name;
            this.isSelected = isSelected;
        }
    }

    public interface PlayerListener {
        void onPlaying();
        void onBuffering();
        void onError(String message);
        void onTimeout();
        void onStreamInfoUpdated(StreamInfo info);
        void onSourceFailed();
        default void onRenderedFirstFrame() {}
        default void onStreamPreparing() {}
    }

    public VideoPlayerManager(Context context) {
        this.context = context.getApplicationContext();
        initPlayer();
    }

    public void setListener(PlayerListener listener) {
        this.listener = listener;
    }

    public void attachViews(SurfaceView surfaceView, AspectRatioFrameLayout aspectRatioFrameLayout) {
        attachViews(surfaceView, aspectRatioFrameLayout, null);
    }

    public void attachViews(SurfaceView surfaceView, AspectRatioFrameLayout aspectRatioFrameLayout, SubtitleView subtitleView) {
        this.surfaceView = surfaceView;
        this.aspectRatioFrameLayout = aspectRatioFrameLayout;
        this.subtitleView = subtitleView;
        if (subtitleView != null) {
            subtitleView.setUserDefaultStyle();
            subtitleView.setUserDefaultTextSize();
            subtitleView.setApplyEmbeddedStyles(true);
            subtitleView.setApplyEmbeddedFontSizes(true);
        }
        if (player != null && surfaceView != null) {
            player.setVideoSurfaceView(surfaceView);
        }
        applyAspectRatio(SettingsRepository.get().getScreenScale());
    }

    public void setSubtitleView(SubtitleView subtitleView) {
        this.subtitleView = subtitleView;
        if (subtitleView != null) {
            subtitleView.setUserDefaultStyle();
            subtitleView.setUserDefaultTextSize();
            subtitleView.setApplyEmbeddedStyles(true);
            subtitleView.setApplyEmbeddedFontSizes(true);
        }
    }

    private void initPlayer() {
        int decoderMode = SettingsRepository.get().getDecoderMode();

        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context) {
            @Override
            protected void buildVideoRenderers(
                    Context context,
                    int extensionRendererMode,
                    MediaCodecSelector mediaCodecSelector,
                    boolean enableDecoderFallback,
                    Handler eventHandler,
                    VideoRendererEventListener eventListener,
                    long allowedVideoJoiningTimeMs,
                    ArrayList<Renderer> out
            ) {
                MediaCodecVideoRenderer videoRenderer = new MediaCodecVideoRenderer(
                        context,
                        getCodecAdapterFactory(),
                        orderVideoDecoders(mediaCodecSelector, decoderMode),
                        allowedVideoJoiningTimeMs,
                        enableDecoderFallback,
                        eventHandler,
                        eventListener,
                        MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
                ) {
                    @Override
                    protected void onCodecInitialized(
                            String name,
                            MediaCodecAdapter.Configuration configuration,
                            long initializedTimestampMs,
                            long initializationDurationMs) {
                        super.onCodecInitialized(name, configuration, initializedTimestampMs, initializationDurationMs);
                        updateDecodeMode(true, name, getCodecInfo());
                    }

                    @Override
                    protected DecoderReuseEvaluation canReuseCodec(
                            MediaCodecInfo codecInfo, Format oldFormat, Format newFormat) {
                        String name = (codecInfo != null && codecInfo.name != null) ? codecInfo.name.toLowerCase(Locale.ROOT) : "";
                        // Amlogic hardware OMX decoders (amlogic.avc/hevc.decoder.awesome) have a known vendor firmware bug
                        // where output buffers cannot be dynamically reallocated across stream format changes, causing VPU
                        // buffer desync and screen tearing (花屏). Forcing REUSE_RESULT_NO makes ExoPlayer safely instantiate
                        // a fresh clean decoder instance on channel/format switches with 0 extra CPU overhead.
                        if (name.contains("amlogic")) {
                            return new DecoderReuseEvaluation(
                                    codecInfo.name,
                                    oldFormat,
                                    newFormat,
                                    DecoderReuseEvaluation.REUSE_RESULT_NO,
                                    DecoderReuseEvaluation.DISCARD_REASON_WORKAROUND
                            );
                        }
                        return super.canReuseCodec(codecInfo, oldFormat, newFormat);
                    }

                    @Override
                    protected boolean codecNeedsSetOutputSurfaceWorkaround(String name) {
                        if (name != null && name.toLowerCase(Locale.ROOT).contains("amlogic")) {
                            return true;
                        }
                        return super.codecNeedsSetOutputSurfaceWorkaround(name);
                    }
                };
                out.add(videoRenderer);
            }

            @Override
            protected void buildAudioRenderers(
                    Context context,
                    int extensionRendererMode,
                    MediaCodecSelector mediaCodecSelector,
                    boolean enableDecoderFallback,
                    AudioSink audioSink,
                    Handler eventHandler,
                    AudioRendererEventListener eventListener,
                    ArrayList<Renderer> out
            ) {
                int audioMode = SettingsRepository.get().getAudioDecoderMode();

                // Safe MediaCodecSelector: filter out known broken/bogus system decoders (e.g. google.ac3.decoder, google.mp2.decoder on Amlogic/Phicomm)
                MediaCodecSelector safeAudioSelector = (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) -> {
                    List<MediaCodecInfo> decoders =
                            mediaCodecSelector.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
                    if (decoders == null || decoders.isEmpty()) {
                        return Collections.emptyList();
                    }
                    List<MediaCodecInfo> filtered = new ArrayList<>(decoders.size());
                    for (MediaCodecInfo info : decoders) {
                        String name = info.name.toLowerCase(Locale.ROOT);
                        if (name.contains("google.ac3") || name.contains("ac3.decoder") ||
                            name.contains("google.eac3") || name.contains("eac3.decoder") ||
                            name.contains("google.mp2") || name.contains("mp2.decoder") ||
                            name.contains("google.dts") || name.contains("dts.decoder")) {
                            continue;
                        }
                        filtered.add(info);
                    }
                    return filtered;
                };

                if (audioMode == 0) { // 智能解码 (硬件优先，自动软解)
                    out.add(createTrackedAudioRenderer(
                            context,
                            safeAudioSelector,
                            enableDecoderFallback,
                            eventHandler,
                            eventListener,
                            audioSink
                    ));

                    try {
                        Class<?> clazz = Class.forName("androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer");
                        Constructor<?> constructor = clazz.getConstructor(
                                Handler.class,
                                AudioRendererEventListener.class,
                                AudioSink.class
                        );
                        Renderer ffmpegRenderer = (Renderer) constructor.newInstance(eventHandler, trackedFfmpegListener(eventListener), audioSink);
                        out.add(ffmpegRenderer);
                    } catch (Exception ignored) {}
                } else if (audioMode == 1) { // 强制软解 (FFmpeg)
                    // 1. First add FfmpegAudioRenderer directly at index 0
                    try {
                        Class<?> clazz = Class.forName("androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer");
                        Constructor<?> constructor = clazz.getConstructor(
                                Handler.class,
                                AudioRendererEventListener.class,
                                AudioSink.class
                        );
                        Renderer ffmpegRenderer = (Renderer) constructor.newInstance(eventHandler, trackedFfmpegListener(eventListener), audioSink);
                        out.add(ffmpegRenderer);
                        Log.i("VideoPlayerManager", "Loaded FfmpegAudioRenderer as primary audio renderer");
                    } catch (Exception e) {
                        Log.w("VideoPlayerManager", "FfmpegAudioRenderer load failed", e);
                    }

                    // 2. Add MediaCodecAudioRenderer with safe selector as fallback
                    out.add(createTrackedAudioRenderer(
                            context,
                            safeAudioSelector,
                            enableDecoderFallback,
                            eventHandler,
                            eventListener,
                            audioSink
                    ));
                } else { // 系统原生 (仅系统硬解/RAW直通)
                    out.add(createTrackedAudioRenderer(
                            context,
                            mediaCodecSelector,
                            enableDecoderFallback,
                            eventHandler,
                            eventListener,
                            audioSink
                    ));
                }
            }

            private MediaCodecAudioRenderer createTrackedAudioRenderer(
                    Context context,
                    MediaCodecSelector selector,
                    boolean enableDecoderFallback,
                    Handler eventHandler,
                    AudioRendererEventListener eventListener,
                    AudioSink audioSink
            ) {
                return new MediaCodecAudioRenderer(
                        context,
                        getCodecAdapterFactory(),
                        selector,
                        enableDecoderFallback,
                        eventHandler,
                        eventListener,
                        audioSink
                ) {
                    @Override
                    protected void onCodecInitialized(
                            String name,
                            MediaCodecAdapter.Configuration configuration,
                            long initializedTimestampMs,
                            long initializationDurationMs) {
                        super.onCodecInitialized(name, configuration, initializedTimestampMs, initializationDurationMs);
                        updateDecodeMode(false, name, getCodecInfo());
                    }
                };
            }

            @Override
            protected void buildTextRenderers(
                    Context context,
                    TextOutput output,
                    Looper outputLooper,
                    int extensionRendererMode,
                    ArrayList<Renderer> out
            ) {
                TextRenderer tr = new TextRenderer(output, outputLooper);
                tr.experimentalSetLegacyDecodingEnabled(true);
                out.add(tr);
            }
        };
        renderersFactory.setEnableDecoderFallback(true);

        // Configure TS Extractor for fast startup without prolonged probing
        DefaultExtractorsFactory extractorsFactory = new DefaultExtractorsFactory()
                .setTextTrackTranscodingEnabled(false)
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                        | DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
                        | DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS
                        | DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM);

        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(context, extractorsFactory)
                .experimentalParseSubtitlesDuringExtraction(false);

        // TrackSelector: Always pick audio track; disable subtitles by default to ensure clean instant playback
        DefaultTrackSelector trackSelector = new DefaultTrackSelector(context);
        trackSelector.setParameters(trackSelector.buildUponParameters()
                .setExceedRendererCapabilitiesIfNecessary(true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true));

        // Dynamic Dual-Track LoadControl: 250ms startup for Live, 2000ms buffer for Timeshift/VOD
        dynamicLoadControl = new DynamicLoadControl();

        player = new ExoPlayer.Builder(context, renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
                .setTrackSelector(trackSelector)
                .setLoadControl(dynamicLoadControl)
                .build();

        player.setVideoFrameMetadataListener((presentationTimeUs, releaseTimeNs, format, mediaFormat) -> {
            handleVideoFramePts(presentationTimeUs, format);
        });

        // Configure AudioAttributes for system media volume routing with proper AudioFocus
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build();
        player.setAudioAttributes(audioAttributes, true);
        player.setVolume(1.0f);
        player.setPlayWhenReady(true);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                switch (playbackState) {
                    case Player.STATE_READY:
                        cancelTimeout();
                        if (isTimeShiftStream && needsInitialSeek) {
                            player.seekTo(0, 0L);
                            needsInitialSeek = false;
                        }
                        // Refresh formats from actual active decoders
                        Format vf = player.getVideoFormat();
                        if (vf != null) extractVideoInfo(vf);
                        Format af = player.getAudioFormat();
                        if (af != null) extractAudioInfo(af);
                        if (listener != null) {
                            listener.onStreamInfoUpdated(currentStreamInfo);
                            listener.onPlaying();
                        }
                        break;
                    case Player.STATE_BUFFERING:
                        if (listener != null) listener.onBuffering();
                        break;
                    case Player.STATE_ENDED:
                        // Scheme 1: Loop replay for non-live static video files
                        if (player != null) {
                            player.seekTo(0, 0L);
                            player.play();
                        }
                        break;
                    case Player.STATE_IDLE:
                        break;
                }
            }

            @Override
            public void onTracksChanged(Tracks tracks) {
                boolean changed = false;
                for (Tracks.Group group : tracks.getGroups()) {
                    boolean isAudio = (group.getType() == C.TRACK_TYPE_AUDIO);
                    boolean isVideo = (group.getType() == C.TRACK_TYPE_VIDEO);
                    if (isAudio || isVideo) {
                        for (int i = 0; i < group.length; i++) {
                            Format format = group.getTrackFormat(i);
                            Log.i("VideoPlayerManager", "Track: type=" + (isVideo ? "VIDEO" : "AUDIO")
                                    + ", selected=" + group.isTrackSelected(i)
                                    + ", groupSelected=" + group.isSelected()
                                    + ", mime=" + format.sampleMimeType
                                    + ", codecs=" + format.codecs
                                    + ", ch=" + format.channelCount
                                    + ", rate=" + format.sampleRate);
                        }
                    }
                    if (group.isSelected()) {
                        for (int i = 0; i < group.length; i++) {
                            if (group.isTrackSelected(i)) {
                                Format format = group.getTrackFormat(i);
                                if (isVideo) {
                                    if (extractVideoInfo(format)) changed = true;
                                } else if (isAudio) {
                                    if (extractAudioInfo(format)) changed = true;
                                }
                            }
                        }
                    }
                }
                if (changed && listener != null) {
                    listener.onStreamInfoUpdated(currentStreamInfo);
                }
            }

            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    updateResolutionAndFps(videoSize.width, videoSize.height, -1f);
                    if (listener != null) {
                        listener.onStreamInfoUpdated(currentStreamInfo);
                    }
                }
            }

            @Override
            public void onTimelineChanged(androidx.media3.common.Timeline timeline, int reason) {
                if (isTimeShiftStream && needsInitialSeek && !timeline.isEmpty()) {
                    player.seekTo(0, 0L);
                    needsInitialSeek = false;
                }
            }

            @Override
            public void onRenderedFirstFrame() {
                if (listener != null) {
                    listener.onRenderedFirstFrame();
                }
            }

            @Override
            public void onCues(CueGroup cueGroup) {
                if (subtitleView != null && cueGroup != null) {
                    subtitleView.setCues(cueGroup.cues);
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                // If error is caused by a non-fatal subtitle/text parser exception (e.g. SsaParser IllegalStateException),
                // do NOT switch lines or fail playback! Automatically disable subtitles and let the video continue seamlessly.
                if (isSubtitleException(error)) {
                    Log.w("VideoPlayerManager", "Non-fatal subtitle exception intercepted: " + error.getMessage() + ". Disabling subtitle track to maintain smooth playback.");
                    disableSubtitles();
                    return;
                }

                cancelTimeout();
                if (listener != null) {
                    listener.onError(error.getMessage() != null ? error.getMessage() : "播放异常");
                    listener.onSourceFailed();
                }
            }
        });
    }

    private static boolean isSoftwareDecoder(MediaCodecInfo info) {
        return info.softwareOnly || !info.hardwareAccelerated;
    }

    private static MediaCodecSelector orderVideoDecoders(MediaCodecSelector delegate, int decoderMode) {
        if (decoderMode == 2) {
            return delegate;
        }
        final boolean softwareFirst = (decoderMode == 1);
        return (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) -> {
            List<MediaCodecInfo> infos = delegate.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
            if (infos == null || infos.size() < 2) {
                return infos;
            }
            // MediaCodecUtil returns cached unmodifiable lists, so copy before stable partition
            List<MediaCodecInfo> ordered = new ArrayList<>(infos);
            Collections.sort(ordered, (a, b) -> softwareFirst
                    ? Boolean.compare(isSoftwareDecoder(b), isSoftwareDecoder(a))
                    : Boolean.compare(isSoftwareDecoder(a), isSoftwareDecoder(b)));
            return ordered;
        };
    }

    private AudioRendererEventListener trackedFfmpegListener(@Nullable AudioRendererEventListener delegate) {
        return new AudioRendererEventListener() {
            @Override
            public void onAudioDecoderInitialized(String decoderName, long initializedTimestampMs, long initializationDurationMs) {
                updateDecodeMode(false, decoderName, null);
                if (delegate != null) {
                    delegate.onAudioDecoderInitialized(decoderName, initializedTimestampMs, initializationDurationMs);
                }
            }

            @Override
            public void onAudioCodecError(Exception audioCodecError) {
                if (delegate != null) {
                    delegate.onAudioCodecError(audioCodecError);
                }
            }
        };
    }

    private void updateDecodeMode(boolean video, String decoderName, MediaCodecInfo codecInfo) {
        String mode = classifyDecodeMode(decoderName, codecInfo);
        if (mode.isEmpty()) return;
        String tag = (video ? "视频" : "音频") + mode;
        mainHandler.post(() -> {
            if (video) {
                if (tag.equals(currentStreamInfo.videoDecode)) return;
                currentStreamInfo.videoDecode = tag;
            } else {
                if (tag.equals(currentStreamInfo.audioDecode)) return;
                currentStreamInfo.audioDecode = tag;
            }
            if (listener != null) {
                listener.onStreamInfoUpdated(currentStreamInfo);
            }
        });
    }

    private static String classifyDecodeMode(String decoderName, MediaCodecInfo codecInfo) {
        if (codecInfo != null) {
            return (codecInfo.hardwareAccelerated && !codecInfo.softwareOnly) ? "硬解" : "软解";
        }
        if (decoderName != null && decoderName.toLowerCase(Locale.ROOT).startsWith("ffmpeg")) {
            return "软解";
        }
        return "";
    }

    private boolean extractVideoInfo(Format format) {
        if (format == null) return false;
        boolean changed = false;

        // Resolution & FPS
        int width = format.width;
        int height = format.height;
        float frameRate = format.frameRate;
        if (width > 0 && height > 0) {
            updateResolutionAndFps(width, height, frameRate);
            changed = true;
        }

        // Video Codec
        String videoCodecName = formatVideoCodec(format.sampleMimeType, format.codecs);
        if (!videoCodecName.isEmpty() && !videoCodecName.equals(currentStreamInfo.videoCodec)) {
            currentStreamInfo.videoCodec = videoCodecName;
            changed = true;
        }

        // Color & Dynamic Range
        String hdr = formatColorInfo(format.colorInfo, format.sampleMimeType, format.codecs);
        if (!hdr.isEmpty() && !hdr.equals(currentStreamInfo.dynamicRange)) {
            currentStreamInfo.dynamicRange = hdr;
            changed = true;
        }

        // Bitrate
        int bitrate = format.bitrate != Format.NO_VALUE ? format.bitrate : format.peakBitrate;
        if (bitrate > 0) {
            String brStr = formatBitrate(bitrate);
            if (!brStr.equals(currentStreamInfo.bitrate)) {
                currentStreamInfo.bitrate = brStr;
                changed = true;
            }
        }

        return changed;
    }

    private void updateResolutionAndFps(int width, int height, float frameRate) {
        String res;
        if (height >= 2160 || width >= 3840) {
            res = "4K";
        } else if (height >= 1080 || width >= 1920) {
            // In broadcast IPTV, 25fps 1080 is typically 1080i (50 interlaced fields)
            if ("25 FPS".equals(currentStreamInfo.fps) || (frameRate > 0 && Math.abs(frameRate - 25.0f) < 0.5f)) {
                res = "1080i";
            } else if ("1080i".equals(currentStreamInfo.resolution)) {
                res = "1080i";
            } else {
                res = "1080P";
            }
        } else if (height >= 720 || width >= 1280) {
            res = "720P";
        } else if (height >= 576) {
            res = "576P";
        } else if (height >= 480) {
            res = "480P";
        } else {
            res = width + "x" + height;
        }
        currentStreamInfo.resolution = res;

        if (frameRate > 0) {
            currentStreamInfo.fps = Math.round(frameRate) + " FPS";
        }
    }

    private void resetPtsCalculation() {
        synchronized (ptsLock) {
            lastPtsUs = C.TIME_UNSET;
            ptsDiffSumUs = 0L;
            ptsDiffCount = 0;
            ptsCalculationDone = false;
        }
    }

    private void handleVideoFramePts(long presentationTimeUs, Format format) {
        if (ptsCalculationDone) {
            return;
        }

        // If static frameRate was already extracted from container format, stop PTS calculation
        if (currentStreamInfo.fps != null && !currentStreamInfo.fps.isEmpty()) {
            ptsCalculationDone = true;
            return;
        }

        synchronized (ptsLock) {
            if (ptsCalculationDone) {
                return;
            }

            if (lastPtsUs == C.TIME_UNSET) {
                lastPtsUs = presentationTimeUs;
                return;
            }

            long diffUs = presentationTimeUs - lastPtsUs;
            lastPtsUs = presentationTimeUs;

            // Frame interval must be within broadcast reasonable range [10ms, 100ms] (10 FPS ~ 100 FPS)
            if (diffUs >= 10000L && diffUs <= 100000L) {
                ptsDiffSumUs += diffUs;
                ptsDiffCount++;

                // Collect 8 consecutive valid intervals (~0.16s for 50fps, ~0.32s for 25fps)
                if (ptsDiffCount >= 8) {
                    ptsCalculationDone = true;
                    double avgDiffUs = (double) ptsDiffSumUs / ptsDiffCount;
                    double calcFps = 1000000.0 / avgDiffUs;

                    String detectedFps;
                    if (Math.abs(calcFps - 50.0) < 3.5) {
                        detectedFps = "50 FPS";
                    } else if (Math.abs(calcFps - 60.0) < 3.5 || Math.abs(calcFps - 59.94) < 3.5) {
                        detectedFps = "60 FPS";
                    } else if (Math.abs(calcFps - 25.0) < 2.5) {
                        detectedFps = "25 FPS";
                    } else if (Math.abs(calcFps - 30.0) < 2.5 || Math.abs(calcFps - 29.97) < 2.5) {
                        detectedFps = "30 FPS";
                    } else if (Math.abs(calcFps - 24.0) < 2.0 || Math.abs(calcFps - 23.976) < 2.0) {
                        detectedFps = "24 FPS";
                    } else {
                        detectedFps = Math.round(calcFps) + " FPS";
                    }

                    mainHandler.post(() -> {
                        boolean changed = false;
                        if (!detectedFps.equals(currentStreamInfo.fps)) {
                            currentStreamInfo.fps = detectedFps;
                            changed = true;
                        }
                        if ("25 FPS".equals(detectedFps) && "1080P".equals(currentStreamInfo.resolution)) {
                            currentStreamInfo.resolution = "1080i";
                            changed = true;
                        }
                        if (changed && listener != null) {
                            listener.onStreamInfoUpdated(currentStreamInfo);
                        }
                    });
                }
            } else {
                // Timestamp discontinuity or seek, reset sampling window
                ptsDiffSumUs = 0L;
                ptsDiffCount = 0;
            }
        }
    }

    private static final Map<String, String> AUDIO_CODEC_MAP;
    private static final Map<String, String> VIDEO_CODEC_MAP;

    static {
        Map<String, String> aMap = new HashMap<>(32);
        aMap.put(MimeTypes.AUDIO_AAC, "AAC");
        aMap.put(MimeTypes.AUDIO_AC3, "AC3");
        aMap.put(MimeTypes.AUDIO_E_AC3, "E-AC3");
        aMap.put(MimeTypes.AUDIO_E_AC3_JOC, "E-AC3");
        aMap.put(MimeTypes.AUDIO_TRUEHD, "TrueHD");
        aMap.put(MimeTypes.AUDIO_DTS, "DTS");
        aMap.put(MimeTypes.AUDIO_DTS_HD, "DTS");
        aMap.put(MimeTypes.AUDIO_DTS_EXPRESS, "DTS");
        aMap.put(MimeTypes.AUDIO_MPEG, "MP3");
        aMap.put(MimeTypes.AUDIO_MPEG_L1, "MP1");
        aMap.put(MimeTypes.AUDIO_MPEG_L2, "MP2");
        aMap.put(MimeTypes.AUDIO_FLAC, "FLAC");
        aMap.put(MimeTypes.AUDIO_ALAC, "ALAC");
        aMap.put(MimeTypes.AUDIO_OPUS, "Opus");
        aMap.put(MimeTypes.AUDIO_VORBIS, "Vorbis");
        aMap.put(MimeTypes.AUDIO_AMR_NB, "AMR");
        aMap.put(MimeTypes.AUDIO_AMR_WB, "AMR");
        aMap.put(MimeTypes.AUDIO_RAW, "PCM");
        aMap.put(MimeTypes.AUDIO_MLAW, "PCM");
        aMap.put(MimeTypes.AUDIO_ALAW, "PCM");
        aMap.put("audio/x-ms-wma", "WMA");
        aMap.put("audio/wma", "WMA");
        aMap.put("audio/x-ape", "APE");
        aMap.put("audio/ape", "APE");
        aMap.put("audio/vnd.rn-realaudio", "RealAudio");
        aMap.put("audio/x-pn-realaudio", "RealAudio");
        aMap.put("audio/x-wav", "WAV");
        AUDIO_CODEC_MAP = Collections.unmodifiableMap(aMap);

        Map<String, String> vMap = new HashMap<>(16);
        vMap.put(MimeTypes.VIDEO_H264, "H.264");
        vMap.put(MimeTypes.VIDEO_H265, "H.265");
        vMap.put(MimeTypes.VIDEO_AV1, "AV1");
        vMap.put(MimeTypes.VIDEO_VP9, "VP9");
        vMap.put(MimeTypes.VIDEO_VP8, "VP8");
        vMap.put(MimeTypes.VIDEO_MPEG2, "MPEG2");
        vMap.put(MimeTypes.VIDEO_MP4V, "MPEG4");
        vMap.put(MimeTypes.VIDEO_VC1, "VC-1");
        vMap.put(MimeTypes.VIDEO_DOLBY_VISION, "H.265");
        vMap.put("video/avs", "AVS+");
        vMap.put("video/avs-plus", "AVS+");
        vMap.put("video/avs2", "AVS2");
        vMap.put("video/avs3", "AVS3");
        VIDEO_CODEC_MAP = Collections.unmodifiableMap(vMap);
    }

    private String formatVideoCodec(String mime, String codecs) {
        if (mime != null && !mime.isEmpty()) {
            String cached = VIDEO_CODEC_MAP.get(mime);
            if (cached != null) return cached;

            String m = mime.toLowerCase(Locale.ROOT);
            if (m.contains("dolby-vision")) return "H.265";
            if (m.contains("avc") || m.contains("h264")) return "H.264";
            if (m.contains("hevc") || m.contains("h265")) return "H.265";
            if (m.contains("av01") || m.contains("av1")) return "AV1";
            if (m.contains("mpeg2") || m.contains("mp2v")) return "MPEG2";
            if (m.contains("mp4v") || m.contains("mpeg4") || m.contains("xvid") || m.contains("divx")) return "MPEG4";
            if (m.contains("vc1") || m.contains("wmv")) return "VC-1";
            if (m.contains("avs2") || m.contains("avs3")) return "AVS2";
            if (m.contains("avs")) return "AVS+";
            if (m.contains("vp9")) return "VP9";
            if (m.contains("vp8")) return "VP8";
        }
        if (codecs != null && !codecs.isEmpty()) {
            String c = codecs.toLowerCase(Locale.ROOT);
            if (c.contains("avc") || c.contains("h264")) return "H.264";
            if (c.contains("hev") || c.contains("hvc") || c.contains("h265") || c.contains("dvh") || c.contains("dva")) return "H.265";
            if (c.contains("av01")) return "AV1";
            if (c.contains("vp09") || c.contains("vp9")) return "VP9";
            if (c.contains("vc1") || c.contains("wmv")) return "VC-1";
            if (c.contains("mp4v")) return "MPEG4";
        }
        return "";
    }

    private String formatColorInfo(ColorInfo colorInfo, String mime, String codecs) {
        if (mime != null && mime.toLowerCase(Locale.ROOT).contains("dolby-vision")) {
            return "Dolby Vision";
        }
        if (codecs != null) {
            String c = codecs.toLowerCase(Locale.ROOT);
            if (c.contains("dvh1") || c.contains("dvhe") || c.contains("dav1")) {
                return "Dolby Vision";
            }
        }
        if (colorInfo == null) return "SDR";
        int transfer = colorInfo.colorTransfer;
        if (transfer == C.COLOR_TRANSFER_ST2084) {
            return "10-bit HDR";
        } else if (transfer == C.COLOR_TRANSFER_HLG) {
            return "HLG";
        } else if (transfer == C.COLOR_TRANSFER_SDR) {
            return "SDR";
        }
        return "SDR";
    }

    private boolean extractAudioInfo(Format format) {
        if (format == null) return false;
        boolean changed = false;

        String audioCodecName = formatAudioCodec(format.sampleMimeType);
        if (!audioCodecName.isEmpty() && !audioCodecName.equals(currentStreamInfo.audioCodec)) {
            currentStreamInfo.audioCodec = audioCodecName;
            changed = true;
        }

        int channels = format.channelCount;
        if (channels > 0) {
            String chStr;
            if (channels == 1) chStr = "单声道";
            else if (channels == 2) chStr = "立体声";
            else if (channels == 6) chStr = "5.1 声道";
            else if (channels == 8) chStr = "7.1 声道";
            else chStr = channels + "声道";

            if (!chStr.equals(currentStreamInfo.audioChannels)) {
                currentStreamInfo.audioChannels = chStr;
                changed = true;
            }
        }

        return changed;
    }

    private String formatAudioCodec(String mime) {
        if (mime == null || mime.isEmpty()) return "";
        // 1. O(1) Zero-allocation fast-path exact map lookup (covers 95%+ of streams)
        String cached = AUDIO_CODEC_MAP.get(mime);
        if (cached != null) return cached;

        // 2. Fallback path for custom or non-standard container sub-strings
        String m = mime.toLowerCase(Locale.ROOT);
        if (m.contains("mpeg-l2") || m.contains("mp2") || m.contains("mpeg-2")) return "MP2";
        if (m.contains("mp4a") || m.contains("aac")) return "AAC";
        if (m.contains("eac3") || m.contains("e-ac-3") || m.contains("eac3-joc")) return "E-AC3";
        if (m.contains("ac3") || m.contains("ac-3")) return "AC3";
        if (m.contains("dts")) return "DTS";
        if (m.contains("flac")) return "FLAC";
        if (m.contains("alac")) return "ALAC";
        if (m.contains("ape")) return "APE";
        if (m.contains("wma")) return "WMA";
        if (m.contains("opus")) return "Opus";
        if (m.contains("vorbis") || m.contains("ogg")) return "Vorbis";
        if (m.contains("mpeg") || m.contains("mp3")) return "MP3";
        if (m.contains("truehd") || m.contains("mlp")) return "TrueHD";
        if (m.contains("3gpp") || m.contains("amr")) return "AMR";
        if (m.contains("pcm") || m.contains("raw") || m.contains("g711") || m.contains("alaw") || m.contains("mlaw")) return "PCM";
        if (m.contains("realaudio") || m.contains("cook") || m.contains("ra")) return "RealAudio";
        return "";
    }

    /**
     * Query all available audio tracks in the current playing stream
     */
    public List<AudioTrackInfo> getAudioTracks() {
        List<AudioTrackInfo> list = new ArrayList<>();
        if (player == null) return list;

        Tracks tracks = player.getCurrentTracks();
        int trackNum = 1;
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() == C.TRACK_TYPE_AUDIO) {
                for (int i = 0; i < group.length; i++) {
                    Format format = group.getTrackFormat(i);
                    String name = buildAudioTrackName(format, trackNum);
                    boolean isSelected = group.isTrackSelected(i);
                    list.add(new AudioTrackInfo(group.getMediaTrackGroup(), i, name, isSelected));
                    trackNum++;
                }
            }
        }
        return list;
    }

    /**
     * Select a specific audio track
     */
    public void selectAudioTrack(AudioTrackInfo trackInfo) {
        if (player == null || trackInfo == null || trackInfo.trackGroup == null) return;
        TrackSelectionOverride override = new TrackSelectionOverride(
                trackInfo.trackGroup,
                trackInfo.trackIndex
        );
        player.setTrackSelectionParameters(
                player.getTrackSelectionParameters()
                        .buildUpon()
                        .setOverrideForType(override)
                        .build()
        );
    }

    /**
     * Reset audio track to auto / system default
     */
    public void resetAudioTrackToDefault() {
        if (player == null) return;
        player.setTrackSelectionParameters(
                player.getTrackSelectionParameters()
                        .buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                        .build()
        );
    }

    private String buildAudioTrackName(Format format, int trackNum) {
        if (format == null) {
            return "音轨 " + trackNum;
        }

        String label = (format.label != null) ? format.label.trim() : "";
        if ("und".equalsIgnoreCase(label) || "unknown".equalsIgnoreCase(label)) {
            label = "";
        }

        String lang = formatLanguage(format.language);

        String codec = formatAudioCodec(format.sampleMimeType);
        String channels = "";
        if (format.channelCount > 0) {
            if (format.channelCount == 1) channels = "单声道";
            else if (format.channelCount == 2) channels = "立体声";
            else if (format.channelCount == 6) channels = "5.1 声道";
            else if (format.channelCount == 8) channels = "7.1 声道";
            else channels = format.channelCount + "声道";
        }

        StringBuilder detailBuilder = new StringBuilder();
        if (!codec.isEmpty()) {
            detailBuilder.append(codec);
        }
        if (!channels.isEmpty()) {
            if (detailBuilder.length() > 0) detailBuilder.append(" ");
            detailBuilder.append(channels);
        }
        String details = detailBuilder.toString();

        if (!label.isEmpty()) {
            if (!details.isEmpty() && !label.toLowerCase(Locale.ROOT).contains(codec.toLowerCase(Locale.ROOT))) {
                return "音轨 " + trackNum + ": " + label + " (" + details + ")";
            } else {
                return "音轨 " + trackNum + ": " + label;
            }
        }

        if (!lang.isEmpty()) {
            if (!details.isEmpty()) {
                return "音轨 " + trackNum + ": " + lang + " (" + details + ")";
            } else {
                return "音轨 " + trackNum + ": " + lang;
            }
        }

        if (!details.isEmpty()) {
            return "音轨 " + trackNum + " (" + details + ")";
        }

        return "音轨 " + trackNum;
    }

    /**
     * Query all available subtitle tracks (in-stream and external M3U8)
     */
    public List<SubtitleTrackInfo> getSubtitleTracks() {
        List<SubtitleTrackInfo> list = new ArrayList<>();
        if (player == null) return list;

        Tracks tracks = player.getCurrentTracks();
        int trackNum = 1;
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() == C.TRACK_TYPE_TEXT) {
                for (int i = 0; i < group.length; i++) {
                    Format format = group.getTrackFormat(i);
                    String name = buildSubtitleTrackName(format, trackNum);
                    boolean isSelected = group.isTrackSelected(i);
                    list.add(new SubtitleTrackInfo(group.getMediaTrackGroup(), i, name, isSelected));
                    trackNum++;
                }
            }
        }
        return list;
    }

    /**
     * Select a specific subtitle track
     */
    public void selectSubtitleTrack(SubtitleTrackInfo trackInfo) {
        if (player == null || trackInfo == null || trackInfo.trackGroup == null) return;
        TrackSelectionOverride override = new TrackSelectionOverride(
                trackInfo.trackGroup,
                trackInfo.trackIndex
        );
        player.setTrackSelectionParameters(
                player.getTrackSelectionParameters()
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(override)
                        .build()
        );
    }

    /**
     * Disable subtitle rendering
     */
    public void disableSubtitles() {
        if (player == null) return;
        player.setTrackSelectionParameters(
                player.getTrackSelectionParameters()
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .build()
        );
        if (subtitleView != null) {
            subtitleView.setCues(Collections.emptyList());
        }
    }

    /**
     * Check if subtitles are currently active
     */
    public boolean isSubtitleEnabled() {
        if (player == null) return false;
        Tracks tracks = player.getCurrentTracks();
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() == C.TRACK_TYPE_TEXT && group.isSelected()) {
                return true;
            }
        }
        return false;
    }

    private String buildSubtitleTrackName(Format format, int trackNum) {
        if (format == null) {
            return "字幕 " + trackNum;
        }

        String label = (format.label != null) ? format.label.trim() : "";
        if ("und".equalsIgnoreCase(label) || "unknown".equalsIgnoreCase(label)) {
            label = "";
        }

        String lang = formatLanguage(format.language);

        String mime = (format.sampleMimeType != null) ? format.sampleMimeType.toLowerCase(Locale.ROOT) : "";
        String mimeTag = "";
        if (mime.contains("subrip") || mime.contains("x-subrip")) {
            mimeTag = "SRT";
        } else if (mime.contains("ssa") || mime.contains("ass")) {
            mimeTag = "ASS";
        } else if (mime.contains("vtt")) {
            mimeTag = "VTT";
        } else if (mime.contains("pgs") || mime.contains("hdmv")) {
            mimeTag = "PGS";
        }

        StringBuilder sb = new StringBuilder();
        if (!label.isEmpty()) {
            sb.append(label);
        } else if (!lang.isEmpty()) {
            sb.append(lang);
        } else {
            sb.append("字幕 ").append(trackNum);
        }

        if (!mimeTag.isEmpty() && !sb.toString().toUpperCase(Locale.ROOT).contains(mimeTag)) {
            sb.append(" (").append(mimeTag).append(")");
        }

        return sb.toString();
    }

    private boolean isSubtitleException(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String name = current.getClass().getName();
            String msg = current.getMessage();
            if (name.contains("SsaParser") || name.contains("Subtitle") || name.contains("TextRenderer") ||
                (msg != null && (msg.contains("SsaParser") || msg.contains("SubtitleTranscoding") || msg.contains("text.ssa") || msg.contains("Legacy decoding") || msg.contains("can't handle")))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String formatLanguage(String lang) {
        if (lang == null || lang.trim().isEmpty() || "und".equalsIgnoreCase(lang.trim())) {
            return "";
        }
        String l = lang.trim().toLowerCase(Locale.ROOT);
        if (l.equals("zh") || l.equals("chi") || l.equals("zho") || l.equals("cmn") || l.contains("mandarin") || l.contains("chinese")) {
            return "国语";
        }
        if (l.equals("yue") || l.contains("cantonese")) {
            return "粤语";
        }
        if (l.equals("en") || l.equals("eng") || l.contains("english")) {
            return "英语";
        }
        if (l.equals("ja") || l.equals("jp") || l.equals("jpn") || l.contains("japanese")) {
            return "日语";
        }
        if (l.equals("ko") || l.equals("kor") || l.contains("korean")) {
            return "韩语";
        }
        if (l.equals("fr") || l.equals("fra") || l.equals("fre") || l.contains("french")) {
            return "法语";
        }
        if (l.equals("de") || l.equals("deu") || l.equals("ger") || l.contains("german")) {
            return "德语";
        }
        if (l.equals("ru") || l.equals("rus") || l.contains("russian")) {
            return "俄语";
        }
        if (l.equals("es") || l.equals("spa") || l.contains("spanish")) {
            return "西班牙语";
        }
        if (l.equals("min") || l.contains("hokkien") || l.contains("taiwanese")) {
            return "闽南语";
        }
        if (l.equals("hak") || l.contains("hakka")) {
            return "客家语";
        }
        try {
            Locale loc = new Locale(l);
            String display = loc.getDisplayLanguage(Locale.CHINESE);
            if (display != null && !display.isEmpty() && !display.equalsIgnoreCase(l)) {
                return display;
            }
        } catch (Exception ignored) {}
        return lang;
    }

    private String formatBitrate(int bitrate) {
        if (bitrate >= 1000000) {
            return String.format(Locale.getDefault(), "%.1f Mbps", bitrate / 1000000.0f);
        } else {
            return (bitrate / 1000) + " Kbps";
        }
    }

    /**
     * Instant channel switch without destroying player context
     */
    public void playChannel(Channel channel) {
        if (channel == null || channel.getCurrentUrl().isEmpty()) {
            if (listener != null) {
                listener.onError("无效播放地址");
                listener.onSourceFailed();
            }
            return;
        }

        // If switching to a DIFFERENT channel entity, clear pending resume position
        if (this.currentChannel != null && channel != null &&
                !channel.getName().equalsIgnoreCase(this.currentChannel.getName())) {
            this.pendingResumePositionMs = C.TIME_UNSET;
        }

        this.isTimeShiftStream = false;
        this.timeShiftBaseEpochMs = 0;
        this.currentChannel = channel;
        playStreamUrl(channel.getCurrentUrl(), channel.getHeaders());
    }

    /**
     * Play any stream URL directly (used for Live and Time-Shift TVOD)
     */
    public void playStreamUrl(String streamUrl, Map<String, String> headers) {
        if (streamUrl == null || streamUrl.trim().isEmpty()) {
            if (listener != null) {
                listener.onError("播放地址为空");
                listener.onSourceFailed();
            }
            return;
        }

        this.currentStreamUrl = streamUrl;
        this.currentHeaders = headers;

        // Reset stream parameters info for the new stream
        currentStreamInfo.reset();
        resetPtsCalculation();
        if (player != null) {
            player.setTrackSelectionParameters(
                    player.getTrackSelectionParameters()
                            .buildUpon()
                            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                            .build()
            );
        }
        if (subtitleView != null) {
            subtitleView.setCues(Collections.emptyList());
        }
        if (listener != null) {
            listener.onStreamPreparing();
            listener.onStreamInfoUpdated(currentStreamInfo);
        }

        // Setup HTTP DataSource with custom headers and 5s fast timeout
        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(5000)
                .setReadTimeoutMs(5000)
                .setAllowCrossProtocolRedirects(true);

        // Apply headers: stream independent UA first -> global UserAgent setting -> default
        String ua = null;
        if (headers != null) {
            if (headers.containsKey("User-Agent")) ua = headers.get("User-Agent");
            else if (headers.containsKey("user-agent")) ua = headers.get("user-agent");
        }
        if (ua == null || ua.trim().isEmpty()) {
            String globalUa = SettingsRepository.get().getUserAgent();
            ua = (globalUa != null && !globalUa.trim().isEmpty()) ? globalUa.trim() : SettingsRepository.DEFAULT_USER_AGENT;
        }
        httpFactory.setUserAgent(ua);

        if (headers != null && !headers.isEmpty()) {
            httpFactory.setDefaultRequestProperties(headers);
        }

        boolean isTimeShift = isTimeShiftStream || streamUrl.contains("playseek=") || streamUrl.contains("/TVOD/") ||
                streamUrl.contains("/lookback/") || streamUrl.contains("starttime=") ||
                streamUrl.contains("timeshift=") || streamUrl.contains("catchup=");

        String lower = streamUrl.toLowerCase(Locale.ROOT);
        int qIdx = lower.indexOf('?');
        String path = (qIdx != -1) ? lower.substring(0, qIdx) : lower;
        int hIdx = path.indexOf('#');
        if (hIdx != -1) path = path.substring(0, hIdx);

        boolean isM2ts = path.endsWith(".m2ts") || path.endsWith(".mts");

        boolean isVod = false;
        if (currentChannel != null && currentChannel.isVodSeekMode()) {
            isVod = true;
        } else {
            if (path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".flv") ||
                path.endsWith(".avi") || path.endsWith(".mov") || path.endsWith(".wmv") ||
                path.endsWith(".rmvb") || path.endsWith(".webm") || path.endsWith(".iso") ||
                path.endsWith(".m4v") || isM2ts) {
                isVod = true;
            }
        }

        boolean isTimeShiftOrVod = isTimeShift || isVod;
        if (dynamicLoadControl != null) {
            dynamicLoadControl.setLiveMode(!isTimeShiftOrVod);
        }

        DefaultDataSource.Factory defaultDataSourceFactory = new DefaultDataSource.Factory(context, httpFactory);

        // Instant Switch: Check if preloaded DataSource is ready
        DataSource.Factory effectiveDataSourceFactory = defaultDataSourceFactory;
        if (!isTimeShiftOrVod && SettingsRepository.get().isPreloadEnabled()) {
            DataSource.Factory preloadFactory = PreloadChannelManager.getInstance().takePreload(streamUrl);
            if (preloadFactory != null) {
                effectiveDataSourceFactory = preloadFactory;
                Log.i("VideoPlayerManager", "Hit preloaded stream buffer for instant switch: " + streamUrl);
            }
        }

        if (isM2ts) {
            effectiveDataSourceFactory = new M2tsDataSource.Factory(effectiveDataSourceFactory);
            Log.i("VideoPlayerManager", "Wrapped DataSource with M2tsDataSource (BDAV 192-byte stripping) for: " + streamUrl);
        }

        ExtractorsFactory effectiveExtractorsFactory;
        if (isM2ts) {
            int tsFlags = DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                    | DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
                    | DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS;
            effectiveExtractorsFactory = () -> new Extractor[] {
                    new TsExtractor(
                            TsExtractor.MODE_SINGLE_PMT,
                            new TimestampAdjuster(0),
                            new BdavPayloadReaderFactory(tsFlags)
                    )
            };
            Log.i("VideoPlayerManager", "Configured BdavPayloadReaderFactory + TsExtractor for M2TS BDAV audio streams (DTS-HD/AC-3): " + streamUrl);
        } else {
            effectiveExtractorsFactory = new DefaultExtractorsFactory()
                    .setTextTrackTranscodingEnabled(false)
                    .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                            | DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
                            | DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS
                            | DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM);
        }

        DefaultMediaSourceFactory sourceFactory = new DefaultMediaSourceFactory(effectiveDataSourceFactory, effectiveExtractorsFactory)
                .experimentalParseSubtitlesDuringExtraction(false);

        long resumePos = this.pendingResumePositionMs;
        this.pendingResumePositionMs = C.TIME_UNSET;

        MediaItem.Builder itemBuilder = new MediaItem.Builder().setUri(Uri.parse(streamUrl.trim()));
        if (isTimeShift) {
            this.isTimeShiftStream = true;
            this.needsInitialSeek = (resumePos <= 0);
            long parsedEpoch = parseEpochFromStreamUrl(streamUrl);
            if (parsedEpoch > 0) {
                this.timeShiftBaseEpochMs = parsedEpoch;
            }
            itemBuilder.setLiveConfiguration(new MediaItem.LiveConfiguration.Builder()
                    .setMaxPlaybackSpeed(3.0f)
                    .setMinPlaybackSpeed(0.5f)
                    .setTargetOffsetMs(C.TIME_UNSET)
                    .build());
        } else {
            this.isTimeShiftStream = false;
            this.needsInitialSeek = false;
            this.timeShiftBaseEpochMs = 0;
            // Reset playback speed to 1.0x when returning to live
            setPlaybackSpeed(1.0f);
        }

        MediaSource mediaSource = sourceFactory.createMediaSource(itemBuilder.build());

        // Fast prepare and play: reset decoder buffers cleanly across stream switches to prevent buffer desync
        player.setMediaSource(mediaSource);
        if (resumePos > 0) {
            player.seekTo(resumePos);
        } else if (isTimeShift) {
            player.seekTo(0, 0L);
        }
        player.prepare();
        player.setVolume(1.0f);
        player.setPlayWhenReady(true);

        startTimeoutTimer();
    }

    public void setTimeShiftBaseEpochMs(long baseEpochMs) {
        this.timeShiftBaseEpochMs = baseEpochMs;
    }

    public static long parseEpochFromStreamUrl(String streamUrl) {
        if (streamUrl == null) return 0;
        try {
            if (streamUrl.contains("playseek=")) {
                int idx = streamUrl.indexOf("playseek=") + 9;
                int endIdx = streamUrl.indexOf("-", idx);
                if (endIdx > idx) {
                    String timeStr = streamUrl.substring(idx, endIdx).trim();
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault());
                    Date d = sdf.parse(timeStr);
                    if (d != null) return d.getTime();
                }
            } else if (streamUrl.contains("starttime=")) {
                int idx = streamUrl.indexOf("starttime=") + 10;
                int endIdx = streamUrl.indexOf("&", idx);
                String timeStr = (endIdx > idx) ? streamUrl.substring(idx, endIdx) : streamUrl.substring(idx);
                timeStr = timeStr.replace(".00Z", "").replace("T", "");
                SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault());
                Date d = sdf.parse(timeStr);
                if (d != null) return d.getTime();
            }
        } catch (Exception ignored) {}
        return 0;
    }

    public boolean isTimeShiftStream() {
        return isTimeShiftStream;
    }

    public long getCurrentTimeShiftEpochMs() {
        if (isTimeShiftStream && timeShiftBaseEpochMs > 0 && player != null) {
            return timeShiftBaseEpochMs + player.getCurrentPosition();
        }
        return 0;
    }

    public long getCurrentPosition() {
        return player != null ? player.getCurrentPosition() : 0;
    }

    public long getDuration() {
        return player != null ? player.getDuration() : 0;
    }

    public void seekTo(long positionMs) {
        if (player != null) {
            player.seekTo(positionMs);
        }
    }

    public boolean isCurrentMediaItemLive() {
        return player != null && player.isCurrentMediaItemLive();
    }

    public StreamInfo getCurrentStreamInfo() {
        return currentStreamInfo;
    }

    public Format getVideoFormat() {
        return player != null ? player.getVideoFormat() : null;
    }

    public void setPlaybackSpeed(float speed) {
        if (player != null) {
            player.setPlaybackParameters(new PlaybackParameters(speed));
        }
    }

    public float getPlaybackSpeed() {
        if (player != null) {
            return player.getPlaybackParameters().speed;
        }
        return 1.0f;
    }

    public void applyAspectRatio(int scaleMode) {
        if (aspectRatioFrameLayout == null) return;
        switch (scaleMode) {
            case 0: // 16:9
                aspectRatioFrameLayout.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
                aspectRatioFrameLayout.setAspectRatio(16f / 9f);
                break;
            case 1: // 4:3
                aspectRatioFrameLayout.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
                aspectRatioFrameLayout.setAspectRatio(4f / 3f);
                break;
            case 2: // Original
                aspectRatioFrameLayout.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
                aspectRatioFrameLayout.setAspectRatio(0f);
                break;
            case 3: // Full stretch
                aspectRatioFrameLayout.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
                break;
        }
    }

    private void startTimeoutTimer() {
        cancelTimeout();
        int timeoutSec = SettingsRepository.get().getTimeoutSeconds();
        // 0 means disabled
        if (timeoutSec <= 0) {
            return;
        }
        timeoutRunnable = () -> {
            if (listener != null) {
                listener.onTimeout();
                listener.onSourceFailed();
            }
        };
        mainHandler.postDelayed(timeoutRunnable, timeoutSec * 1000L);
    }

    private void cancelTimeout() {
        if (timeoutRunnable != null) {
            mainHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }

    public void pause() {
        if (player != null) {
            player.pause();
        }
    }

    public void play() {
        if (player != null) {
            player.play();
        }
    }

    public void setPendingResumePositionMs(long pos) {
        this.pendingResumePositionMs = pos;
    }

    public long getPendingResumePositionMs() {
        return pendingResumePositionMs;
    }

    public void stop() {
        cancelTimeout();
        resetPtsCalculation();
        pendingResumePositionMs = C.TIME_UNSET;
        if (player != null) {
            player.stop();
        }
    }

    public boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    public void release() {
        cancelTimeout();
        pendingResumePositionMs = C.TIME_UNSET;
        if (player != null) {
            player.stop();
            player.release();
            player = null;
        }
    }

    public void reinitPlayer() {
        cancelTimeout();
        resetPtsCalculation();
        float currentSpeed = getPlaybackSpeed();
        if (player != null) {
            long curPos = player.getCurrentPosition();
            boolean isLive = player.isCurrentMediaItemLive();
            long dur = player.getDuration();
            // Preserve playback progress for pure video links, VOD, or timeshift streams
            if (pendingResumePositionMs > 0 && (curPos <= 0 || curPos == C.TIME_UNSET)) {
                // Keep previously stored resume position if current player hasn't progressed
            } else if ((!isLive || isTimeShiftStream || dur > 0) && curPos > 0) {
                if (dur > 0 && curPos >= dur - 1000) {
                    this.pendingResumePositionMs = C.TIME_UNSET;
                } else {
                    this.pendingResumePositionMs = curPos;
                }
            }
            try {
                player.stop();
                player.release();
            } catch (Exception ignored) {}
            player = null;
        }
        initPlayer();
        if (player != null && surfaceView != null) {
            player.setVideoSurfaceView(surfaceView);
        }
        applyAspectRatio(SettingsRepository.get().getScreenScale());
        if (currentSpeed > 0 && Math.abs(currentSpeed - 1.0f) > 0.01f) {
            setPlaybackSpeed(currentSpeed);
        }
    }

    public void reloadCurrentStream() {
        if (player != null && pendingResumePositionMs <= 0) {
            long curPos = player.getCurrentPosition();
            boolean isLive = player.isCurrentMediaItemLive();
            long dur = player.getDuration();
            if ((!isLive || isTimeShiftStream || dur > 0) && curPos > 0) {
                if (dur > 0 && curPos >= dur - 1000) {
                    this.pendingResumePositionMs = C.TIME_UNSET;
                } else {
                    this.pendingResumePositionMs = curPos;
                }
            }
        }
        // Prioritize current active stream URL (preserves timeshift and custom VOD streams)
        if (currentStreamUrl != null && !currentStreamUrl.isEmpty()) {
            playStreamUrl(currentStreamUrl, currentHeaders);
        } else if (currentChannel != null) {
            playChannel(currentChannel);
        }
    }
}