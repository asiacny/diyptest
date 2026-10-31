package com.diyp.next.ui;

import android.app.Activity;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.widget.LinearLayout;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.SubtitleView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.diyp.next.R;
import com.diyp.next.data.model.Channel;
import com.diyp.next.data.model.ChannelGroup;
import com.diyp.next.data.repository.ChannelRepository;
import com.diyp.next.data.repository.EpgRepository;
import com.diyp.next.data.repository.SettingsRepository;
import com.diyp.next.player.VideoPlayerManager;
import com.diyp.next.player.preload.PreloadChannelManager;
import com.diyp.next.ui.adapter.CategoryAdapter;
import com.diyp.next.ui.adapter.ChannelAdapter;
import com.diyp.next.ui.adapter.EpgAdapter;
import com.diyp.next.ui.adapter.EpgDateAdapter;
import com.diyp.next.data.model.EpgItem;
import com.diyp.next.ui.dialog.SettingsDialog;
import com.diyp.next.ui.view.CenterLayoutManager;
import com.diyp.next.ui.view.ScrollTextView;
import com.diyp.next.util.NetSpeedMonitor;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class PlayerActivity extends AppCompatActivity {
    // Views
    private AspectRatioFrameLayout aspectRatioFrame;
    private SurfaceView surfaceView;
    private View viewSurfaceShutter;
    private SubtitleView subtitleView;
    private ImageView ivSplash;
    private View layoutBanner;
    private View layoutMenu;
    private ProgressBar pbLoading;
    private TextView tvStatusToast;
    private TextView tvQuickNum;
    private TextClock tvTopClock;
    private TextView tvNetSpeed;

    // Banner Views
    private TextView bannerTvNumber;
    private TextView bannerTvName;
    private ProgressBar bannerProgressBar;
    private ScrollTextView bannerTvNowPlaying;
    private TextView bannerTvNextPlaying;
    private TextView bannerTvSource;
    private TextView bannerTvTimeshift;
    private ImageView bannerIvLogo;
    private View bannerScrollStreamTags;
    private LinearLayout bannerLayoutStreamTags;
    private float bannerScale = 1f; // 信息框相对 XML 基准宽度的累计缩放比例
    private float bannerBaseWidth; // XML 基准宽度(px)，首次应用时记录

    // Time-Shift Floating Header Views
    private View layoutTimeshiftHeader;
    private TextView tvTimeshiftDynamicStatus;
    private TextView btnTimeshiftSpeed;

    // Time-Shift State
    private boolean isTimeShiftMode = false;
    private boolean isTimeShiftSeeking = false;
    private long timeShiftOffsetSeconds = 0;
    private Runnable timeShiftSeekRunnable = null;
    private final float[] SPEED_STEPS = {1.0f, 1.25f, 1.5f, 2.0f, 3.0f};
    private int currentSpeedIndex = 0;

    // Line Failure Auto-Switch State
    private int failureLoopStartIndex = -1;

    // Menu Views
    private ViewGroup layoutCategoryColumn;
    private View layoutCategoryCollapsed;
    private View layoutCatchupStrip;
    private TextView tvCatchupStripText;
    private View layoutEpgColumn;
    private RecyclerView rvCategories;
    private RecyclerView rvChannels;
    private RecyclerView rvEpg;
    private RecyclerView rvEpgDates;
    private TextView tvEpgEmpty;
    private boolean isEpgExpanded = false;

    // Adapters
    private CategoryAdapter categoryAdapter;
    private ChannelAdapter channelAdapter;
    private EpgAdapter epgAdapter;
    private EpgDateAdapter epgDateAdapter;

    // Layout Managers (Center Viewport Anchored)
    private CenterLayoutManager categoryLayoutManager;
    private CenterLayoutManager channelLayoutManager;
    private CenterLayoutManager epgDateLayoutManager;
    private CenterLayoutManager epgLayoutManager;
    private List<EpgDateAdapter.DateModel> epgDateList = new ArrayList<>();
    private final Set<String> epgLoadingDates = new HashSet<>();
    private RecyclerView.OnChildAttachStateChangeListener pendingCategoryAttachListener;
    private RecyclerView.OnChildAttachStateChangeListener pendingChannelAttachListener;

    // Data
    private List<ChannelGroup> channelGroups = new ArrayList<>();
    private List<Channel> allChannels = new ArrayList<>();
    private int currentGroupIndex = 0;
    private int currentChannelIndex = 0;
    private Channel currentPlayingChannel = null;

    // Managers
    private VideoPlayerManager playerManager;
    private NetSpeedMonitor speedMonitor;
    private final SettingsRepository settings = SettingsRepository.get();

    // Handlers & Runnables
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable hideBannerRunnable;
    private Runnable quickNumJumpRunnable;
    private String quickNumInput = "";
    private long lastBackPressTime = 0;
    private long lastTimeShiftEpochMs = 0;

    // 换台防抖 (300ms) 状态与调度
    private Channel pendingSwitchChannel = null;
    private final Runnable channelSwitchDebounceRunnable = () -> {
        if (pendingSwitchChannel != null) {
            Channel target = pendingSwitchChannel;
            pendingSwitchChannel = null;
            playChannel(target);
        }
    };

    // 时移 / 点播加速度步长与调度
    private long lastSeekTime = 0;
    private int continuousSeekCount = 0;
    private long pendingVodSeekTargetPos = -1;
    private final Runnable vodSeekCommitRunnable = () -> {
        if (playerManager != null && pendingVodSeekTargetPos >= 0) {
            long target = pendingVodSeekTargetPos;
            pendingVodSeekTargetPos = -1;
            playerManager.seekTo(target);
            if (layoutBanner != null && layoutBanner.getVisibility() == View.VISIBLE) {
                showChannelBanner(currentPlayingChannel);
            }
        }
    };

    // SurfaceView 换源残留黑屏保护安全超时 (5s)
    private final Runnable hideShutterSafetyRunnable = () -> {
        if (viewSurfaceShutter != null) {
            viewSurfaceShutter.setVisibility(View.GONE);
        }
    };

    private final Runnable showLoadingRunnable = () -> {
        if (pbLoading != null) {
            pbLoading.setVisibility(View.VISIBLE);
        }
    };

    private void showLoading(boolean show) {
        handler.removeCallbacks(showLoadingRunnable);
        if (show) {
            handler.postDelayed(showLoadingRunnable, 300);
        } else {
            if (pbLoading != null) {
                pbLoading.setVisibility(View.GONE);
            }
        }
    };

    private final Runnable timeShiftClockRunnable = new Runnable() {
        @Override
        public void run() {
            if (isTimeShiftMode) {
                updateTimeShiftStatusText();
                handler.postDelayed(this, 1000);
            }
        }
    };

    // Touch & Gesture detector (1:1 with original app)
    private GestureDetector gestureDetector;
    private boolean isGestureScrolled = false;
    private boolean hasHandledGestureAction = false;
    private int touchScrollMode = 0; // 0: none, 1: timeshift dragging, 2: channel switch, 3: source switch, 4: brightness, 5: volume, 6: vod drag scrubbing
    private long touchStartOffsetSeconds = 0;
    private float touchInitialBrightness = 0.5f;
    private int touchInitialVolume = 0;
    private AudioManager audioManager;

    // Menu 10s Auto Hide
    private final Runnable menuAutoHideRunnable = () -> {
        if (layoutMenu != null && layoutMenu.getVisibility() == View.VISIBLE) {
            hideMenu();
        }
    };

    private void resetMenuAutoHideTimer() {
        if (handler != null) {
            handler.removeCallbacks(menuAutoHideRunnable);
            if (layoutMenu != null && layoutMenu.getVisibility() == View.VISIBLE) {
                handler.postDelayed(menuAutoHideRunnable, 10000);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Fullscreen immersive
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUI();

        setContentView(R.layout.activity_player);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        try {
            if (audioManager != null) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    if (audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) {
                        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
                    }
                }
                int curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                if (curVol <= 0 && maxVol > 0) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (int)(maxVol * 0.7), 0);
                }
            }
        } catch (Exception ignored) {}

        initViews();
        initPlayer();
        initAdapters();
        initGestureDetector();
        loadChannels();
        startSpeedMonitor();
    }

    private void hideSystemUI() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    private void initViews() {
        aspectRatioFrame = findViewById(R.id.aspect_ratio_frame);
        surfaceView = findViewById(R.id.surface_view);
        viewSurfaceShutter = findViewById(R.id.view_surface_shutter);
        subtitleView = findViewById(R.id.subtitle_view);
        ivSplash = findViewById(R.id.iv_splash);
        layoutBanner = findViewById(R.id.layout_channel_banner);
        layoutMenu = findViewById(R.id.layout_channel_menu);
        pbLoading = findViewById(R.id.pb_loading);
        tvStatusToast = findViewById(R.id.tv_status_toast);
        tvQuickNum = findViewById(R.id.tv_quick_channel_num);
        tvTopClock = findViewById(R.id.tv_top_clock);
        tvNetSpeed = findViewById(R.id.tv_net_speed);

        // Banner views
        bannerTvNumber = findViewById(R.id.banner_tv_number);
        bannerTvName = findViewById(R.id.banner_tv_name);
        bannerProgressBar = findViewById(R.id.banner_progress_bar);
        bannerTvNowPlaying = findViewById(R.id.banner_tv_now_playing);
        bannerTvNextPlaying = findViewById(R.id.banner_tv_next_playing);
        bannerTvSource = findViewById(R.id.banner_tv_source);
        bannerTvTimeshift = findViewById(R.id.banner_tv_timeshift);
        bannerIvLogo = findViewById(R.id.banner_iv_logo);
        bannerScrollStreamTags = findViewById(R.id.banner_scroll_stream_tags);
        bannerLayoutStreamTags = findViewById(R.id.banner_layout_stream_tags);

        applyBannerSize();

        // Time-Shift floating header views
        layoutTimeshiftHeader = findViewById(R.id.layout_timeshift_header);
        tvTimeshiftDynamicStatus = findViewById(R.id.tv_timeshift_dynamic_status);
        btnTimeshiftSpeed = findViewById(R.id.btn_timeshift_speed);

        if (btnTimeshiftSpeed != null) {
            btnTimeshiftSpeed.setFocusable(false);
            btnTimeshiftSpeed.setFocusableInTouchMode(false);
            btnTimeshiftSpeed.setOnClickListener(v -> {
                currentSpeedIndex = (currentSpeedIndex + 1) % SPEED_STEPS.length;
                float speed = SPEED_STEPS[currentSpeedIndex];
                playerManager.setPlaybackSpeed(speed);
                btnTimeshiftSpeed.setText(speed + "x");
                showStatusToast(String.format(Locale.getDefault(), "播放倍速已切换至：%.2fx", speed));
            });
        }

        // Menu views
        rvCategories = findViewById(R.id.rv_categories);
        rvChannels = findViewById(R.id.rv_channels);
        rvEpg = findViewById(R.id.rv_epg);
        rvEpgDates = findViewById(R.id.rv_epg_dates);
        tvEpgEmpty = findViewById(R.id.tv_epg_empty);
        layoutCategoryColumn = findViewById(R.id.layout_category_column);
        layoutCategoryCollapsed = findViewById(R.id.layout_category_collapsed);
        layoutCatchupStrip = findViewById(R.id.layout_catchup_strip);
        tvCatchupStripText = findViewById(R.id.tv_catchup_strip_text);
        layoutEpgColumn = findViewById(R.id.layout_epg_column);

        if (layoutCatchupStrip != null) {
            layoutCatchupStrip.setOnClickListener(v -> expandEpgPanel());
        }
        if (layoutCategoryCollapsed != null) {
            layoutCategoryCollapsed.setOnClickListener(v -> collapseEpgPanel());
        }

        // Root view clickability for touch gestures
        View rootView = findViewById(R.id.root_player_view);
        if (rootView != null) {
            rootView.setClickable(true);
            rootView.setFocusable(false);
        }
        if (surfaceView != null) {
            surfaceView.setClickable(false);
            surfaceView.setFocusable(false);
        }

        // Apply settings visibility
        tvTopClock.setVisibility(settings.isShowTime() ? View.VISIBLE : View.GONE);
        tvNetSpeed.setVisibility(settings.isShowSpeed() ? View.VISIBLE : View.GONE);

        // Load boot splash image
        loadSplashImage();
    }

    private void applyBannerSize() {
        if (layoutBanner == null) return;
        int size = settings.getBannerSize();
        float pct = size == 0 ? 0.5f : (size == 1 ? 0.75f : 1.0f);
        int targetWidth = Math.round(getResources().getDisplayMetrics().widthPixels * pct);
        ViewGroup.LayoutParams lp = layoutBanner.getLayoutParams();
        if (lp == null) return;
        if (bannerBaseWidth <= 0) bannerBaseWidth = lp.width;
        float scale = targetWidth / bannerBaseWidth;
        float ratio = scale / bannerScale;
        lp.width = targetWidth;
        layoutBanner.setLayoutParams(lp);
        if (Math.abs(ratio - 1f) > 0.001f) {
            scaleBannerView(layoutBanner, ratio, true);
            bannerScale = scale;
        }
    }

    private void scaleBannerView(View view, float ratio, boolean isRoot) {
        if (view == null) return;
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (!isRoot) {
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                int marginStart = mlp.getMarginStart();
                int marginEnd = mlp.getMarginEnd();
                mlp.setMargins(
                        Math.round(mlp.leftMargin * ratio),
                        Math.round(mlp.topMargin * ratio),
                        Math.round(mlp.rightMargin * ratio),
                        Math.round(mlp.bottomMargin * ratio));
                if (mlp.isMarginRelative()) {
                    mlp.setMarginStart(Math.round(marginStart * ratio));
                    mlp.setMarginEnd(Math.round(marginEnd * ratio));
                }
            }
            if (lp != null) {
                if (lp.width > 0) lp.width = Math.round(lp.width * ratio);
                if (lp.height > 0) lp.height = Math.round(lp.height * ratio);
            }
        }
        if (lp != null) {
            view.setLayoutParams(lp);
        }
        view.setPadding(
                Math.round(view.getPaddingLeft() * ratio),
                Math.round(view.getPaddingTop() * ratio),
                Math.round(view.getPaddingRight() * ratio),
                Math.round(view.getPaddingBottom() * ratio));
        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, tv.getTextSize() * ratio);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                scaleBannerView(group.getChildAt(i), ratio, false);
            }
        }
    }

    private void initPlayer() {
        playerManager = new VideoPlayerManager(this);
        playerManager.attachViews(surfaceView, aspectRatioFrame, subtitleView);
        playerManager.setListener(new VideoPlayerManager.PlayerListener() {
            @Override
            public void onStreamPreparing() {
                runOnUiThread(() -> {
                    if (viewSurfaceShutter != null) {
                        viewSurfaceShutter.setVisibility(View.VISIBLE);
                    }
                    handler.removeCallbacks(hideShutterSafetyRunnable);
                    handler.postDelayed(hideShutterSafetyRunnable, 5000);
                });
            }

            @Override
            public void onRenderedFirstFrame() {
                runOnUiThread(() -> {
                    isTimeShiftSeeking = false;
                    handler.removeCallbacks(hideShutterSafetyRunnable);
                    if (viewSurfaceShutter != null) {
                        viewSurfaceShutter.setVisibility(View.GONE);
                    }
                    dismissSplash();
                });
            }

            @Override
            public void onPlaying() {
                isTimeShiftSeeking = false;
                failureLoopStartIndex = -1;
                showLoading(false);
                tvStatusToast.setVisibility(View.GONE);
                // For audio-only streams where onRenderedFirstFrame never triggers, uncover shutter
                if (playerManager != null && playerManager.getVideoFormat() == null) {
                    handler.removeCallbacks(hideShutterSafetyRunnable);
                    if (viewSurfaceShutter != null) {
                        viewSurfaceShutter.setVisibility(View.GONE);
                    }
                    dismissSplash();
                }
                if (playerManager != null && playerManager.isTimeShiftStream()) {
                    if (!isTimeShiftMode) {
                        isTimeShiftMode = true;
                        if (layoutTimeshiftHeader != null) {
                            layoutTimeshiftHeader.setVisibility(View.VISIBLE);
                        }
                        handler.removeCallbacks(timeShiftClockRunnable);
                        handler.post(timeShiftClockRunnable);
                    }
                }
            }

            @Override
            public void onBuffering() {
                showLoading(true);
            }

            @Override
            public void onError(String message) {
                showLoading(false);
                handler.removeCallbacks(hideShutterSafetyRunnable);
                if (viewSurfaceShutter != null) {
                    viewSurfaceShutter.setVisibility(View.GONE);
                }
                dismissSplash();
                showStatusToast("播放失败: " + message);
            }

            @Override
            public void onTimeout() {
                dismissSplash();
                showStatusToast("连接超时，正在自动切换下一线路...");
            }

            @Override
            public void onStreamInfoUpdated(VideoPlayerManager.StreamInfo info) {
                runOnUiThread(() -> {
                    updateStreamBadges(info);
                });
            }

            @Override
            public void onSourceFailed() {
                runOnUiThread(() -> handleAutoSwitchSource());
            }
        });
    }

    private void updateStreamBadges(VideoPlayerManager.StreamInfo info) {
        if (bannerLayoutStreamTags == null) return;
        bannerLayoutStreamTags.removeAllViews();
        if (info == null) {
            if (bannerScrollStreamTags != null) bannerScrollStreamTags.setVisibility(View.GONE);
            return;
        }
        List<String> tags = info.getTags();
        if (tags.isEmpty()) {
            if (bannerScrollStreamTags != null) bannerScrollStreamTags.setVisibility(View.GONE);
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(this);
        for (String tag : tags) {
            TextView badge = (TextView) inflater.inflate(R.layout.item_stream_badge, bannerLayoutStreamTags, false);
            badge.setText(tag);
            if ("4K".equalsIgnoreCase(tag) || "8K".equalsIgnoreCase(tag) || tag.toUpperCase().contains("HDR") || "HLG".equalsIgnoreCase(tag) || tag.toUpperCase().contains("DOLBY")) {
                badge.setBackgroundResource(R.drawable.bg_stream_badge_gold);
                badge.setTextColor(0xFFFFA726);
            } else {
                badge.setBackgroundResource(R.drawable.bg_stream_badge);
                badge.setTextColor(0xFF00D2FF);
            }
            if (bannerScale != 1f) {
                scaleBannerView(badge, bannerScale, false);
            }
            bannerLayoutStreamTags.addView(badge);
        }

        if (layoutBanner != null && layoutBanner.getVisibility() == View.VISIBLE) {
            if (bannerScrollStreamTags != null) {
                bannerScrollStreamTags.setVisibility(View.VISIBLE);
            }
        }
    }

    private void updateCatchupStripText(Channel channel) {
        if (tvCatchupStripText == null) return;
        if ((channel != null && channel.isVodSeekMode()) || isCurrentMediaVodSeek()) {
            tvCatchupStripText.setText("视\n频\n点\n播");
            return;
        }
        boolean isTimeshift = (channel != null && channel.isTimeShiftSupported());
        tvCatchupStripText.setText(isTimeshift ? "时\n移\n回\n放" : "节\n目\n单");
    }

    private void syncChannelGroupIndex(Channel channel) {
        if (channel == null || channelGroups == null) return;
        for (int gi = 0; gi < channelGroups.size(); gi++) {
            ChannelGroup g = channelGroups.get(gi);
            int ci = -1;
            for (int k = 0; k < g.getChannels().size(); k++) {
                Channel ch = g.getChannels().get(k);
                if (ch == channel || (ch != null && ch.getName() != null && ch.getName().equalsIgnoreCase(channel.getName()))) {
                    ci = k;
                    break;
                }
            }
            if (ci != -1) {
                currentGroupIndex = gi;
                currentChannelIndex = ci;
                if (categoryAdapter != null) categoryAdapter.setSelectedPosition(gi);
                if (channelAdapter != null) {
                    channelAdapter.setData(g.getChannels());
                    channelAdapter.setSelectedPosition(ci);
                }
                if (layoutMenu != null && layoutMenu.getVisibility() == View.VISIBLE) {
                    if (categoryLayoutManager != null) {
                        categoryLayoutManager.scrollToPositionCentered(rvCategories, gi);
                    }
                    if (channelLayoutManager != null) {
                        channelLayoutManager.scrollToPositionCentered(rvChannels, ci);
                    }
                }
                updateCatchupStripText(channel);
                break;
            }
        }
    }

    private void resetEpgDateToToday() {
        if (epgDateAdapter != null && epgDateList != null) {
            int todayIdx = -1;
            for (int i = 0; i < epgDateList.size(); i++) {
                if (epgDateList.get(i).dayOffset == 0) {
                    todayIdx = i;
                    break;
                }
            }
            if (todayIdx != -1) {
                epgDateAdapter.setSelectedPosition(todayIdx);
                int finalIdx = todayIdx;
                if (rvEpgDates != null && isEpgExpanded && rvEpgDates.isShown()) {
                    if (epgDateLayoutManager != null) {
                        epgDateLayoutManager.scrollToPositionCentered(rvEpgDates, finalIdx);
                    } else {
                        rvEpgDates.scrollToPosition(finalIdx);
                    }
                }
            }
        }
    }

    private void initAdapters() {
        // Categories with center viewport anchoring
        categoryAdapter = new CategoryAdapter();
        categoryLayoutManager = new CenterLayoutManager(this);
        rvCategories.setLayoutManager(categoryLayoutManager);
        rvCategories.setAdapter(categoryAdapter);
        categoryAdapter.setListener((position, group) -> {
            resetMenuAutoHideTimer();
            currentGroupIndex = position;
            Runnable updateChannelsRunnable = () -> {
                if (isFinishing() || isDestroyed()) return;
                channelAdapter.setData(group.getChannels());
                channelAdapter.setSelectedPosition(0);
                if (channelLayoutManager != null) {
                    channelLayoutManager.scrollToPositionWithOffset(0, 0);
                }
                if (!group.getChannels().isEmpty()) {
                    loadEpgForChannel(group.getChannels().get(0));
                }
            };

            if (rvChannels != null && rvChannels.isComputingLayout()) {
                rvChannels.post(updateChannelsRunnable);
            } else {
                updateChannelsRunnable.run();
            }

            if (rvCategories != null && rvCategories.isShown()) {
                rvCategories.smoothScrollToPosition(position);
            }
        });

        // Channels with center viewport anchoring
        channelAdapter = new ChannelAdapter();
        channelLayoutManager = new CenterLayoutManager(this);
        rvChannels.setLayoutManager(channelLayoutManager);
        rvChannels.setAdapter(channelAdapter);
        channelAdapter.setListener(new ChannelAdapter.OnChannelClickListener() {
            @Override
            public void onChannelClick(int position, Channel channel) {
                currentChannelIndex = position;
                playChannel(channel);
                hideMenu();
            }

            @Override
            public void onChannelFocused(int position, Channel channel) {
                resetMenuAutoHideTimer();
                resetEpgDateToToday();
                loadEpgForChannel(channel);
                updateCatchupStripText(channel);
                if (rvChannels != null && rvChannels.isShown()) {
                    rvChannels.smoothScrollToPosition(position);
                }
                if (isEpgExpanded && epgDateAdapter != null) {
                    EpgDateAdapter.DateModel curDate = epgDateAdapter.getSelectedItem();
                    if (curDate != null) {
                        loadEpgForChannelAndDate(channel, curDate.dateStr);
                    }
                }
            }
        });

        // EPG Dates with center viewport anchoring
        epgDateAdapter = new EpgDateAdapter();
        epgDateLayoutManager = new CenterLayoutManager(this);
        rvEpgDates.setLayoutManager(epgDateLayoutManager);
        rvEpgDates.setAdapter(epgDateAdapter);
        epgDateList = generateDateList();
        int todayIdx = 0;
        for (int i = 0; i < epgDateList.size(); i++) {
            if (epgDateList.get(i).dayOffset == 0) {
                todayIdx = i;
                break;
            }
        }
        epgDateAdapter.setItems(epgDateList, todayIdx);
        epgDateAdapter.setListener((pos, date) -> {
            resetMenuAutoHideTimer();
            Channel focused = channelAdapter.getSelectedChannel();
            if (focused == null) focused = currentPlayingChannel;
            if (focused != null) {
                jumpToOrLoadEpgDate(focused, date);
            }
        });

        // EPG Programs with center viewport anchoring
        epgAdapter = new EpgAdapter();
        epgLayoutManager = new CenterLayoutManager(this);
        rvEpg.setLayoutManager(epgLayoutManager);
        rvEpg.setAdapter(epgAdapter);
        rvEpg.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                resetMenuAutoHideTimer();
            }

            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                checkEpgDateSyncAndPagination();
            }
        });
        epgAdapter.setOnEpgItemClickListener((pos, item, dateStr) -> {
            Channel target = channelAdapter.getSelectedChannel();
            if (target == null) target = currentPlayingChannel;
            if (target != null) {
                long startTimeMs = parseProgramStartTimeMs(dateStr, item.getStart());
                playTimeshiftProgram(target, item, startTimeMs);
            }
        });
    }

    private void loadChannels() {
        pbLoading.setVisibility(View.VISIBLE);
        ChannelRepository.loadChannels(this, new ChannelRepository.LoadCallback() {
            @Override
            public void onSuccess(List<ChannelGroup> groups) {
                runOnUiThread(() -> {
                    pbLoading.setVisibility(View.GONE);
                    channelGroups = groups;
                    allChannels.clear();
                    for (ChannelGroup g : groups) {
                        allChannels.addAll(g.getChannels());
                    }

                    categoryAdapter.setData(groups);
                    if (!groups.isEmpty()) {
                        int lastG = 0;
                        int lastC = 0;
                        if (settings.isRememberPlay()) {
                            lastG = Math.min(settings.getLastGroupIndex(), groups.size() - 1);
                            if (lastG < 0) lastG = 0;
                        }
                        currentGroupIndex = lastG;
                        categoryAdapter.setSelectedPosition(lastG);

                        ChannelGroup g = groups.get(lastG);
                        channelAdapter.setData(g.getChannels());

                        if (settings.isRememberPlay()) {
                            lastC = Math.min(settings.getLastChannelIndex(), g.getChannels().size() - 1);
                            if (lastC < 0) lastC = 0;
                        }
                        if (lastC >= 0 && !g.getChannels().isEmpty()) {
                            currentChannelIndex = lastC;
                            playChannel(g.getChannels().get(lastC));
                        }
                    }
                });
            }

            @Override
            public void onError(Exception e) {
                runOnUiThread(() -> {
                    dismissSplash();
                    pbLoading.setVisibility(View.GONE);
                    showStatusToast("加载频道列表失败: " + e.getMessage());
                });
            }
        });
    }

    private void playChannel(Channel channel) {
        if (channel == null) return;
        handler.removeCallbacks(channelSwitchDebounceRunnable);
        pendingSwitchChannel = null;
        pendingVodSeekTargetPos = -1;
        handler.removeCallbacks(vodSeekCommitRunnable);

        // SurfaceView 换源残留清理：遮盖黑色挡板，待首帧 onRenderedFirstFrame 就绪后淡出揭开
        if (viewSurfaceShutter != null) {
            viewSurfaceShutter.setVisibility(View.VISIBLE);
        }
        handler.removeCallbacks(hideShutterSafetyRunnable);
        handler.postDelayed(hideShutterSafetyRunnable, 5000);

        syncChannelGroupIndex(channel);
        boolean isChannelChanged = (this.currentPlayingChannel == null || 
                (this.currentPlayingChannel != channel && !channel.getName().equals(this.currentPlayingChannel.getName())));
        this.currentPlayingChannel = channel;
        this.isTimeShiftMode = false;
        this.isTimeShiftSeeking = false;
        this.timeShiftOffsetSeconds = 0;
        this.lastTimeShiftEpochMs = 0;
        channelAdapter.setPlayingChannel(channel);
        updateCatchupStripText(channel);

        // Update settings
        settings.setLastGroupIndex(currentGroupIndex);
        settings.setLastChannelIndex(currentChannelIndex);

        // Restore remembered source index for this channel ONLY when switching to a different channel
        if (isChannelChanged && settings.isRememberSource()) {
            int savedIdx = settings.getSavedSourceIndex(channel.getName());
            if (savedIdx >= 0 && savedIdx < channel.getUrlCount()) {
                channel.setCurrentUrlIndex(savedIdx);
            }
        }

        // Reset timeshift header UI
        if (layoutTimeshiftHeader != null) {
            layoutTimeshiftHeader.setVisibility(View.GONE);
        }
        handler.removeCallbacks(timeShiftClockRunnable);
        if (timeShiftSeekRunnable != null) {
            handler.removeCallbacks(timeShiftSeekRunnable);
        }
        currentSpeedIndex = 0;
        if (btnTimeshiftSpeed != null) {
            btnTimeshiftSpeed.setText("1.0x");
        }

        // Reset EPG date to today and clear old channel's epg
        resetEpgDateToToday();
        if (epgAdapter != null) {
            epgAdapter.clear();
        }

        // Update preload targets before switching so the previous buffered stream can be
        // reclaimed into the pool instead of being torn down
        if (!isTimeShiftMode && allChannels != null && allChannels.size() > 1) {
            PreloadChannelManager.getInstance().updateActiveChannel(channel, allChannels, settings);
        }

        // Start playback
        showLoading(true);
        playerManager.playChannel(channel);

        // Show banner and update EPG
        showChannelBanner(channel);
        loadEpgForChannel(channel);
    }

    private void showChannelBanner(Channel channel) {
        if (channel == null) return;

        applyBannerSize();
        bannerTvNumber.setText(String.valueOf(channel.getNumber()));
        bannerTvName.setText(channel.getName());
        bannerTvSource.setText(channel.getCurrentSourceDisplayName());
        if (bannerTvTimeshift != null) {
            // Suffix already in bannerTvSource; bannerTvTimeshift hidden to prevent duplicate display
            bannerTvTimeshift.setVisibility(View.GONE);
        }

        if (isCurrentMediaVodSeek()) {
            long cur = (pendingVodSeekTargetPos >= 0) ? pendingVodSeekTargetPos : ((playerManager != null) ? playerManager.getCurrentPosition() : 0);
            long dur = (playerManager != null) ? playerManager.getDuration() : 0;
            if (dur > 0) {
                bannerProgressBar.setProgress((int) (cur * 100 / dur));
            }
            bannerTvNowPlaying.setText("当前进度: " + formatTimeMs(cur) + " / " + (dur > 0 ? formatTimeMs(dur) : "--:--"));
            bannerTvNextPlaying.setText("");
        } else if (isTimeShiftMode) {
            long curEpoch = playerManager.getCurrentTimeShiftEpochMs();
            if (curEpoch <= 0) {
                curEpoch = System.currentTimeMillis() + (timeShiftOffsetSeconds * 1000L);
            }
            syncTimeShiftBannerEpg(curEpoch);
        } else {
            bannerProgressBar.setProgress(channel.getProgressPercent());
            String curProg = channel.getCurrentProgram();
            String nextProg = channel.getNextProgram();
            bannerTvNowPlaying.setText((curProg == null || curProg.isEmpty() || curProg.equals("暂无节目信息")) ? "暂无节目信息" : curProg);
            bannerTvNextPlaying.setText((nextProg == null || nextProg.isEmpty()) ? "" : nextProg);
        }

        updateStreamBadges(playerManager != null ? playerManager.getCurrentStreamInfo() : null);

        if (bannerIvLogo != null) {
            if (settings.isHideBottomLogo() || settings.getLogoMode() == 2) {
                bannerIvLogo.setVisibility(View.GONE);
            } else {
                bannerIvLogo.setVisibility(View.VISIBLE);
                com.diyp.next.util.BenchMarkManager.loadLogo(bannerIvLogo, channel.getName(), channel.getLogoUrl());
            }
        }

        layoutBanner.setVisibility(View.VISIBLE);

        // Auto hide after 5 seconds
        if (hideBannerRunnable != null) {
            handler.removeCallbacks(hideBannerRunnable);
        }
        hideBannerRunnable = () -> layoutBanner.setVisibility(View.GONE);
        handler.postDelayed(hideBannerRunnable, 5000);
    }

    private void hideBanner() {
        if (hideBannerRunnable != null) {
            handler.removeCallbacks(hideBannerRunnable);
        }
        if (layoutBanner != null) {
            layoutBanner.setVisibility(View.GONE);
        }
    }

    private void syncTimeShiftBannerEpg(long targetEpochMs) {
        if (currentPlayingChannel == null) return;
        if (targetEpochMs <= 0) {
            targetEpochMs = System.currentTimeMillis() + (timeShiftOffsetSeconds * 1000L);
        }
        final long epochMs = targetEpochMs;
        SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        String dateStr = dateFmt.format(new Date(epochMs));

        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(epochMs);
        int targetMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);

        EpgRepository.queryEpgForDate(currentPlayingChannel, dateStr, (c, dStr, list) -> {
            if (currentPlayingChannel != c || !isTimeShiftMode) return;
            EpgItem curItem = null;
            EpgItem nextItem = null;
            if (list != null && !list.isEmpty()) {
                for (int i = 0; i < list.size(); i++) {
                    EpgItem item = list.get(i);
                    int sMin = item.getStartMinutes();
                    int eMin = item.getEndMinutes();
                    if (eMin < 0 && i + 1 < list.size()) {
                        eMin = list.get(i + 1).getStartMinutes();
                    }
                    if (targetMinutes >= sMin && (eMin <= 0 || targetMinutes < eMin)) {
                        curItem = item;
                        if (i + 1 < list.size()) {
                            nextItem = list.get(i + 1);
                        }
                        break;
                    }
                }
            }

            if (curItem != null) {
                if (bannerTvNowPlaying != null) {
                    bannerTvNowPlaying.setText("【时移回看】" + curItem.getStart() + " " + curItem.getTitle());
                }
                if (bannerTvNextPlaying != null) {
                    if (nextItem != null) {
                        bannerTvNextPlaying.setText("【下个】" + nextItem.getStart() + " " + nextItem.getTitle());
                        bannerTvNextPlaying.setVisibility(View.VISIBLE);
                    } else {
                        bannerTvNextPlaying.setText("");
                    }
                }
                if (bannerProgressBar != null) {
                    int sMin = curItem.getStartMinutes();
                    int eMin = curItem.getEndMinutes();
                    int dur = (eMin > sMin) ? (eMin - sMin) : 60;
                    int prog = Math.min(100, Math.max(0, (targetMinutes - sMin) * 100 / dur));
                    bannerProgressBar.setProgress(prog);
                }
            } else {
                SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
                if (bannerTvNowPlaying != null) {
                    bannerTvNowPlaying.setText("【时移回看】" + timeFmt.format(new Date(epochMs)));
                }
                if (bannerTvNextPlaying != null) {
                    bannerTvNextPlaying.setText("");
                }
            }
        });
    }

    private void loadEpgForChannel(Channel channel) {
        if (channel == null) return;
        EpgRepository.queryEpg(channel, updatedChannel -> {
            try {
                if (currentPlayingChannel != null && (updatedChannel == currentPlayingChannel
                        || (updatedChannel.getName() != null && updatedChannel.getName().equalsIgnoreCase(currentPlayingChannel.getName())))) {
                    if (!isTimeShiftMode) {
                        bannerTvNowPlaying.setText(updatedChannel.getCurrentProgram());
                        bannerTvNextPlaying.setText(updatedChannel.getNextProgram());
                        bannerProgressBar.setProgress(updatedChannel.getProgressPercent());
                    }
                }

                if (layoutMenu != null && layoutMenu.getVisibility() == View.VISIBLE && isEpgExpanded) {
                    EpgDateAdapter.DateModel curDate = (epgDateAdapter != null) ? epgDateAdapter.getSelectedItem() : null;
                    String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
                    if (curDate == null || curDate.dateStr.equals(today)) {
                        if (updatedChannel.getFullEpgList().isEmpty()) {
                            tvEpgEmpty.setVisibility(View.VISIBLE);
                            rvEpg.setVisibility(View.GONE);
                        } else {
                            tvEpgEmpty.setVisibility(View.GONE);
                            rvEpg.setVisibility(View.VISIBLE);
                            rvEpg.setFocusable(true);
                            epgAdapter.setData(updatedChannel.getFullEpgList(), today, "今天");
                            long targetTime = (isTimeShiftMode && lastTimeShiftEpochMs > 0) ? lastTimeShiftEpochMs : System.currentTimeMillis();
                            int curProgIdx = epgAdapter.findProgramIndexForTime(targetTime);
                            if (curProgIdx >= 0) {
                                if (epgLayoutManager != null) {
                                    epgLayoutManager.scrollToPositionCentered(rvEpg, curProgIdx);
                                } else {
                                    ((LinearLayoutManager) rvEpg.getLayoutManager()).scrollToPositionWithOffset(curProgIdx, 60);
                                }
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                Log.e("DIYP", "loadEpgForChannel error", t);
            }
        });
    }

    private List<EpgDateAdapter.DateModel> generateDateList() {
        List<EpgDateAdapter.DateModel> dates = new ArrayList<>();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        String[] weekDays = new String[]{"周日", "周一", "周二", "周三", "周四", "周五", "周六"};

        // 7 days of catchup (-7 to -1), today (0), tomorrow (1)
        for (int offset = -7; offset <= 1; offset++) {
            Calendar c = Calendar.getInstance();
            c.add(Calendar.DAY_OF_YEAR, offset);
            String dateStr = sdf.format(c.getTime());
            String label;
            if (offset == 0) {
                label = "今天";
            } else if (offset == -1) {
                label = "昨天";
            } else if (offset == -2) {
                label = "前天";
            } else if (offset == 1) {
                label = "明天";
            } else {
                int w = c.get(Calendar.DAY_OF_WEEK) - 1;
                if (w < 0 || w >= weekDays.length) w = 0;
                label = weekDays[w];
            }
            dates.add(new EpgDateAdapter.DateModel(label, dateStr, offset));
        }
        return dates;
    }

    private int findDateIndex(String dateStr) {
        if (dateStr == null || epgDateList == null) return -1;
        for (int i = 0; i < epgDateList.size(); i++) {
            if (dateStr.equals(epgDateList.get(i).dateStr)) {
                return i;
            }
        }
        return -1;
    }

    private String getDateLabel(String dateStr) {
        int idx = findDateIndex(dateStr);
        if (idx >= 0 && idx < epgDateList.size()) {
            return epgDateList.get(idx).label;
        }
        return dateStr;
    }

    private long parseProgramStartTimeMs(String dateStr, String timeStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            dateStr = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        }
        if (timeStr == null || timeStr.isEmpty()) return 0;
        try {
            String cleanTime = timeStr.trim();
            if (cleanTime.contains(" ")) {
                cleanTime = cleanTime.substring(cleanTime.lastIndexOf(" ") + 1).trim();
            }
            String full = dateStr.trim() + " " + cleanTime;
            SimpleDateFormat sdf;
            if (cleanTime.split(":").length >= 3) {
                sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            } else {
                sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
            }
            Date d = sdf.parse(full);
            if (d != null) return d.getTime();
        } catch (Exception ignored) {}
        return 0;
    }

    private void jumpToOrLoadEpgDate(Channel focused, EpgDateAdapter.DateModel dateModel) {
        if (focused == null || dateModel == null) return;
        if (epgAdapter.hasDateLoaded(dateModel.dateStr)) {
            int pos = epgAdapter.findPositionForDate(dateModel.dateStr);
            if (pos >= 0) {
                ((LinearLayoutManager) rvEpg.getLayoutManager()).scrollToPositionWithOffset(pos, 0);
                return;
            }
        }
        loadEpgForChannelAndDate(focused, dateModel.dateStr, dateModel.label);
    }

    private void loadEpgForChannelAndDate(Channel channel, String dateStr, String dateLabel) {
        if (channel == null) return;
        epgLoadingDates.clear();
        epgLoadingDates.add(dateStr);
        EpgRepository.queryEpgForDate(channel, dateStr, (c, d, list) -> {
            epgLoadingDates.remove(dateStr);
            if (isFinishing() || isDestroyed()) return;
            if (layoutMenu != null && layoutMenu.getVisibility() == View.VISIBLE && isEpgExpanded) {
                Channel focused = channelAdapter.getSelectedChannel();
                if (focused == null) focused = currentPlayingChannel;
                if (focused != null && (focused == c || focused.getName().equalsIgnoreCase(c.getName()))) {
                    tvEpgEmpty.setVisibility(View.GONE);
                    rvEpg.setVisibility(View.VISIBLE);
                    rvEpg.setFocusable(true);
                    epgAdapter.setData(list, d, dateLabel != null ? dateLabel : getDateLabel(d));
                    long targetTime = (isTimeShiftMode && lastTimeShiftEpochMs > 0) ? lastTimeShiftEpochMs : System.currentTimeMillis();
                    int curProgIdx = epgAdapter.findProgramIndexForTime(targetTime);
                    if (curProgIdx >= 0) {
                        if (epgLayoutManager != null) {
                            epgLayoutManager.scrollToPositionCentered(rvEpg, curProgIdx);
                        } else {
                            ((LinearLayoutManager) rvEpg.getLayoutManager()).scrollToPositionWithOffset(curProgIdx, 60);
                        }
                    } else {
                        ((LinearLayoutManager) rvEpg.getLayoutManager()).scrollToPositionWithOffset(0, 0);
                    }
                }
            }
        });
    }

    private void loadEpgForChannelAndDate(Channel channel, String dateStr) {
        loadEpgForChannelAndDate(channel, dateStr, getDateLabel(dateStr));
    }

    private void checkEpgDateSyncAndPagination() {
        if (rvEpg == null || epgAdapter == null || epgDateAdapter == null || epgDateList == null || epgDateList.isEmpty()) return;
        LinearLayoutManager lm = (LinearLayoutManager) rvEpg.getLayoutManager();
        if (lm == null) return;

        int firstVisible = lm.findFirstVisibleItemPosition();
        int lastVisible = lm.findLastVisibleItemPosition();
        int totalCount = epgAdapter.getItemCount();
        if (firstVisible < 0 || totalCount == 0) return;

        // 1. Sync Date Selection in Date Column (using center visible item)
        int centerPos = (firstVisible + lastVisible) / 2;
        String centerDate = epgAdapter.getDateAtPosition(centerPos);
        if (centerDate != null) {
            int dateIdx = findDateIndex(centerDate);
            if (dateIdx != -1 && epgDateAdapter.getSelectedPosition() != dateIdx) {
                epgDateAdapter.setSelectedPosition(dateIdx);
                rvEpgDates.smoothScrollToPosition(dateIdx);
            }
        }

        // 2. Infinite Seamless Loading
        Channel focused = channelAdapter.getSelectedChannel();
        if (focused == null) focused = currentPlayingChannel;
        if (focused == null) return;

        // If scrolled near bottom, load next date
        if (lastVisible >= totalCount - 4) {
            String lastDate = epgAdapter.getDateAtPosition(totalCount - 1);
            int lastDateIdx = findDateIndex(lastDate);
            if (lastDateIdx != -1 && lastDateIdx < epgDateList.size() - 1) {
                EpgDateAdapter.DateModel nextDateModel = epgDateList.get(lastDateIdx + 1);
                if (!epgAdapter.hasDateLoaded(nextDateModel.dateStr) && !epgLoadingDates.contains(nextDateModel.dateStr)) {
                    epgLoadingDates.add(nextDateModel.dateStr);
                    final String nDate = nextDateModel.dateStr;
                    final String nLabel = nextDateModel.label;
                    EpgRepository.queryEpgForDate(focused, nDate, (c, d, list) -> {
                        epgLoadingDates.remove(nDate);
                        if (isFinishing() || isDestroyed()) return;
                        Channel curF = channelAdapter.getSelectedChannel();
                        if (curF == null) curF = currentPlayingChannel;
                        if (curF != null && (curF == c || curF.getName().equalsIgnoreCase(c.getName()))) {
                            epgAdapter.appendDate(list, nDate, nLabel);
                        }
                    });
                }
            }
        }

        // If scrolled near top, load previous date
        if (firstVisible <= 3) {
            String firstDate = epgAdapter.getDateAtPosition(0);
            int firstDateIdx = findDateIndex(firstDate);
            if (firstDateIdx > 0) {
                EpgDateAdapter.DateModel prevDateModel = epgDateList.get(firstDateIdx - 1);
                if (!epgAdapter.hasDateLoaded(prevDateModel.dateStr) && !epgLoadingDates.contains(prevDateModel.dateStr)) {
                    epgLoadingDates.add(prevDateModel.dateStr);
                    final String pDate = prevDateModel.dateStr;
                    final String pLabel = prevDateModel.label;
                    EpgRepository.queryEpgForDate(focused, pDate, (c, d, list) -> {
                        epgLoadingDates.remove(pDate);
                        if (isFinishing() || isDestroyed()) return;
                        Channel curF = channelAdapter.getSelectedChannel();
                        if (curF == null) curF = currentPlayingChannel;
                        if (curF != null && (curF == c || curF.getName().equalsIgnoreCase(c.getName()))) {
                            View firstView = lm.findViewByPosition(firstVisible);
                            int topOffset = (firstView != null) ? firstView.getTop() : 0;
                            int insertedCount = epgAdapter.prependDate(list, pDate, pLabel);
                            if (insertedCount > 0) {
                                lm.scrollToPositionWithOffset(firstVisible + insertedCount, topOffset);
                            }
                        }
                    });
                }
            }
        }
    }

    private void expandEpgPanel() {
        resetMenuAutoHideTimer();
        isEpgExpanded = true;
        if (layoutCategoryColumn != null) layoutCategoryColumn.setVisibility(View.GONE);
        if (layoutCategoryCollapsed != null) layoutCategoryCollapsed.setVisibility(View.VISIBLE);
        if (layoutCatchupStrip != null) layoutCatchupStrip.setVisibility(View.GONE);
        if (layoutEpgColumn != null) layoutEpgColumn.setVisibility(View.VISIBLE);

        Channel focused = channelAdapter.getSelectedChannel();
        if (focused == null) focused = currentPlayingChannel;

        String targetDateStr;
        if (isTimeShiftMode && lastTimeShiftEpochMs > 0) {
            targetDateStr = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date(lastTimeShiftEpochMs));
        } else {
            targetDateStr = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        }

        int targetDateIdx = findDateIndex(targetDateStr);
        if (targetDateIdx == -1 && epgDateList != null) {
            for (int i = 0; i < epgDateList.size(); i++) {
                if (epgDateList.get(i).dayOffset == 0) {
                    targetDateIdx = i;
                    break;
                }
            }
        }

        if (epgDateAdapter != null && targetDateIdx >= 0) {
            epgDateAdapter.setSelectedPosition(targetDateIdx);
        }

        if (focused != null) {
            loadEpgForChannelAndDate(focused, targetDateStr);
        }

        if (rvEpgDates != null && targetDateIdx >= 0) {
            int screenHeight = (rvEpgDates.getHeight() > 0)
                    ? rvEpgDates.getHeight()
                    : getResources().getDisplayMetrics().heightPixels;
            int itemHeight = (int) (36 * getResources().getDisplayMetrics().density);
            int centerOffset = Math.max(0, (screenHeight - itemHeight) / 2);
            if (epgDateLayoutManager != null) {
                epgDateLayoutManager.scrollToPositionWithOffset(targetDateIdx, centerOffset);
            }

            final int finalDateIdx = targetDateIdx;
            rvEpgDates.post(() -> {
                if (isFinishing() || isDestroyed() || !isEpgExpanded) return;
                if (epgDateLayoutManager != null) {
                    View v = epgDateLayoutManager.findViewByPosition(finalDateIdx);
                    if (v != null) {
                        v.requestFocus();
                        return;
                    }
                }
                rvEpgDates.requestFocus();
            });
        }
    }

    private void collapseEpgPanel() {
        resetMenuAutoHideTimer();
        isEpgExpanded = false;
        epgLoadingDates.clear();
        if (layoutCategoryCollapsed != null) layoutCategoryCollapsed.setVisibility(View.GONE);
        if (layoutCategoryColumn != null) layoutCategoryColumn.setVisibility(View.VISIBLE);
        if (layoutEpgColumn != null) layoutEpgColumn.setVisibility(View.GONE);
        if (layoutCatchupStrip != null) layoutCatchupStrip.setVisibility(View.VISIBLE);

        if (rvChannels != null) {
            rvChannels.requestFocus();
        }
    }

    private void playTimeshiftProgram(Channel channel, EpgItem item, long targetTimestampMs) {
        if (channel == null) return;
        long now = System.currentTimeMillis();
        if (targetTimestampMs > now) {
            showStatusToast("【节目预告】尚未播出: " + item.getTitle());
            return;
        }
        if (!channel.isTimeShiftSupported()) {
            showStatusToast("当前频道线路不支持回看");
            return;
        }

        syncChannelGroupIndex(channel);
        currentPlayingChannel = channel;
        channelAdapter.setPlayingChannel(channel);
        updateCatchupStripText(channel);
        isTimeShiftMode = true;
        lastTimeShiftEpochMs = targetTimestampMs;
        timeShiftOffsetSeconds = (targetTimestampMs - now) / 1000L;
        playerManager.setTimeShiftBaseEpochMs(targetTimestampMs);

        String timeShiftUrl = channel.getTimeShiftUrl(targetTimestampMs);
        showLoading(true);
        playerManager.playStreamUrl(timeShiftUrl, channel.getCurrentHeaders());

        if (layoutTimeshiftHeader != null) {
            layoutTimeshiftHeader.setVisibility(View.VISIBLE);
        }
        updateTimeShiftStatusText();
        handler.removeCallbacks(timeShiftClockRunnable);
        handler.post(timeShiftClockRunnable);

        syncTimeShiftBannerEpg(targetTimestampMs);

        showStatusToast("【回看】正在播放: " + item.getTitle());
        hideMenu();
    }

    private void handleAutoSwitchSource() {
        if (playerManager != null && playerManager.isPlaying()) {
            Log.d("PlayerActivity", "Ignored handleAutoSwitchSource because video is already actively playing.");
            return;
        }

        if (currentPlayingChannel == null || currentPlayingChannel.getUrlCount() <= 1) {
            showStatusToast("当前线路播放失败，暂无备用线路");
            return;
        }

        if (failureLoopStartIndex == -1) {
            failureLoopStartIndex = currentPlayingChannel.getCurrentUrlIndex();
        }

        // Preserve playback position if watching pure video / VOD during failover
        if (playerManager != null && isCurrentMediaVodSeek()) {
            long curPos = playerManager.getCurrentPosition();
            if (curPos > 500) {
                playerManager.setPendingResumePositionMs(curPos);
            }
        }

        currentPlayingChannel.nextUrl();

        if (currentPlayingChannel.getCurrentUrlIndex() == failureLoopStartIndex) {
            // We have looped through all sources and returned to start
            failureLoopStartIndex = -1;
            showStatusToast("该频道所有线路均无法播放，请尝试其他频道");
            return;
        }

        if (settings.isRememberSource()) {
            settings.saveSourceIndex(currentPlayingChannel.getName(), currentPlayingChannel.getCurrentUrlIndex());
        }

        String lineName = currentPlayingChannel.getCurrentSourcePureName();
        showStatusToast(String.format(Locale.getDefault(), "【线路异常】已自动切换至：%s (%d/%d)",
                lineName,
                currentPlayingChannel.getCurrentUrlIndex() + 1,
                currentPlayingChannel.getUrlCount()));

        playChannel(currentPlayingChannel);
    }

    private int lowSpeedCounter = 0;

    private void startSpeedMonitor() {
        speedMonitor = new NetSpeedMonitor();
        speedMonitor.start(kbSpeed -> {
            if (settings.isShowSpeed()) {
                tvNetSpeed.setVisibility(View.VISIBLE);
                tvNetSpeed.setText(kbSpeed + " KB/s");
            } else {
                tvNetSpeed.setVisibility(View.GONE);
            }

            // 断流控制检测 (持续低于设定阈值时自动重连/换源)
            int thresholdKb = settings.getDisconnectThresholdKb();
            int thresholdSec = settings.getDisconnectThresholdSeconds();
            if (thresholdKb > 0 && thresholdSec > 0 && playerManager != null && playerManager.isPlaying() && pbLoading.getVisibility() != View.VISIBLE) {
                if (kbSpeed < thresholdKb) {
                    lowSpeedCounter++;
                    if (lowSpeedCounter >= thresholdSec) {
                        lowSpeedCounter = 0;
                        showStatusToast("【断流控制】检测到持续低网速断流，正在自动重连/换源...");
                        handleAutoSwitchSource();
                    }
                } else {
                    lowSpeedCounter = 0;
                }
            } else {
                lowSpeedCounter = 0;
            }
        });
    }

    private void loadSplashImage() {
        if (ivSplash == null) return;
        if (isFinishing() || isDestroyed()) return;
        String splashUrl = settings.getSplashImgUrl();
        if (splashUrl != null && !splashUrl.trim().isEmpty()) {
            ivSplash.setVisibility(View.VISIBLE);
            Glide.with(this)
                    .load(splashUrl.trim())
                    .into(ivSplash);
            // 6-second fallback auto dismiss
            handler.postDelayed(this::dismissSplash, 6000);
        } else {
            ivSplash.setVisibility(View.GONE);
        }
    }

    private void dismissSplash() {
        if (ivSplash != null && ivSplash.getVisibility() == View.VISIBLE) {
            ivSplash.animate()
                    .alpha(0f)
                    .setDuration(500)
                    .withEndAction(() -> {
                        ivSplash.setVisibility(View.GONE);
                        ivSplash.setAlpha(1.0f);
                    })
                    .start();
        }
    }

    // Toggle and Menu handling
    private void showMenu() {
        dismissSplash();
        hideBanner();

        // 1. Always re-sync group & channel indices to current playing channel
        if (currentPlayingChannel != null) {
            syncChannelGroupIndex(currentPlayingChannel);
        }

        // 2. Block descendant focus on Category column to prevent Android TV focus search from prematurely grabbing Category 0
        if (layoutCategoryColumn != null) {
            layoutCategoryColumn.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }

        layoutMenu.setVisibility(View.VISIBLE);
        resetMenuAutoHideTimer();
        updateCatchupStripText(currentPlayingChannel);

        int screenHeight = (rvChannels != null && rvChannels.getHeight() > 0)
                ? rvChannels.getHeight()
                : getResources().getDisplayMetrics().heightPixels;
        int itemHeight = (int) (52 * getResources().getDisplayMetrics().density);
        int centerOffset = Math.max(0, (screenHeight - itemHeight) / 2);

        if (currentGroupIndex >= 0 && currentGroupIndex < channelGroups.size()) {
            if (categoryAdapter != null) {
                categoryAdapter.setSelectedPosition(currentGroupIndex);
            }
            if (categoryLayoutManager != null) {
                categoryLayoutManager.scrollToPositionWithOffset(currentGroupIndex, centerOffset);
            }

            ChannelGroup group = channelGroups.get(currentGroupIndex);
            if (channelAdapter != null) {
                channelAdapter.setData(group.getChannels());
            }

            int pos = -1;
            if (currentPlayingChannel != null) {
                for (int i = 0; i < group.getChannels().size(); i++) {
                    Channel ch = group.getChannels().get(i);
                    if (ch == currentPlayingChannel || (ch != null && ch.getName() != null && ch.getName().equalsIgnoreCase(currentPlayingChannel.getName()))) {
                        pos = i;
                        break;
                    }
                }
            }
            if (pos == -1 && currentChannelIndex >= 0 && currentChannelIndex < group.getChannels().size()) {
                pos = currentChannelIndex;
            }
            if (pos < 0 && !group.getChannels().isEmpty()) {
                pos = 0;
            }

            if (pos >= 0) {
                currentChannelIndex = pos;
                channelAdapter.setSelectedPosition(pos);
                if (channelLayoutManager != null) {
                    channelLayoutManager.scrollToPositionWithOffset(pos, centerOffset);
                }
                final int finalPos = pos;
                rvChannels.post(() -> {
                    if (isFinishing() || isDestroyed() || layoutMenu == null || layoutMenu.getVisibility() != View.VISIBLE) return;
                    View v = (channelLayoutManager != null) ? channelLayoutManager.findViewByPosition(finalPos) : null;
                    if (v != null) {
                        v.requestFocus();
                        if (layoutCategoryColumn != null) {
                            layoutCategoryColumn.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
                        }
                    } else {
                        // On Android 7.1 / TV box, view might still be in layout pass.
                        // Listen for view attachment so focus directly lands on finalPos without jumping to Category 0!
                        RecyclerView.OnChildAttachStateChangeListener attachListener = new RecyclerView.OnChildAttachStateChangeListener() {
                            @Override
                            public void onChildViewAttachedToWindow(View child) {
                                if (rvChannels.getChildAdapterPosition(child) == finalPos) {
                                    child.requestFocus();
                                    rvChannels.removeOnChildAttachStateChangeListener(this);
                                    if (layoutCategoryColumn != null) {
                                        layoutCategoryColumn.post(() -> {
                                            if (layoutCategoryColumn != null) {
                                                layoutCategoryColumn.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
                                            }
                                        });
                                    }
                                }
                            }

                            @Override
                            public void onChildViewDetachedFromWindow(View child) {}
                        };
                        rvChannels.addOnChildAttachStateChangeListener(attachListener);
                        rvChannels.postDelayed(() -> {
                            rvChannels.removeOnChildAttachStateChangeListener(attachListener);
                            if (layoutCategoryColumn != null) {
                                layoutCategoryColumn.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
                            }
                        }, 500);
                    }
                });
            } else {
                if (layoutCategoryColumn != null) {
                    layoutCategoryColumn.post(() -> {
                        if (layoutCategoryColumn != null) {
                            layoutCategoryColumn.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
                        }
                    });
                }
            }
        } else {
            if (layoutCategoryColumn != null) {
                layoutCategoryColumn.post(() -> {
                    if (layoutCategoryColumn != null) {
                        layoutCategoryColumn.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
                    }
                });
            }
        }
    }

    private void hideMenu() {
        if (handler != null) {
            handler.removeCallbacks(menuAutoHideRunnable);
        }
        if (pendingCategoryAttachListener != null && rvCategories != null) {
            rvCategories.removeOnChildAttachStateChangeListener(pendingCategoryAttachListener);
            pendingCategoryAttachListener = null;
        }
        if (pendingChannelAttachListener != null && rvChannels != null) {
            rvChannels.removeOnChildAttachStateChangeListener(pendingChannelAttachListener);
            pendingChannelAttachListener = null;
        }
        if (isEpgExpanded) {
            collapseEpgPanel();
        }
        if (layoutCategoryColumn != null) {
            layoutCategoryColumn.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        if (layoutMenu != null) {
            layoutMenu.clearFocus();
            layoutMenu.setVisibility(View.GONE);
        }
        hideSystemUI();
    }

    private void navigateToCategoryPosition(int targetPos) {
        if (categoryAdapter == null || rvCategories == null || categoryLayoutManager == null) return;
        int count = categoryAdapter.getItemCount();
        if (targetPos < 0 || targetPos >= count) return;

        if (pendingCategoryAttachListener != null) {
            rvCategories.removeOnChildAttachStateChangeListener(pendingCategoryAttachListener);
            pendingCategoryAttachListener = null;
        }

        categoryLayoutManager.scrollToPositionCentered(rvCategories, targetPos);
        View v = categoryLayoutManager.findViewByPosition(targetPos);
        if (v != null) {
            v.requestFocus();
        } else {
            pendingCategoryAttachListener = new RecyclerView.OnChildAttachStateChangeListener() {
                @Override
                public void onChildViewAttachedToWindow(@NonNull View view) {
                    int pos = rvCategories.getChildAdapterPosition(view);
                    if (pos == targetPos) {
                        if (pendingCategoryAttachListener != null) {
                            rvCategories.removeOnChildAttachStateChangeListener(pendingCategoryAttachListener);
                            pendingCategoryAttachListener = null;
                        }
                        view.requestFocus();
                    }
                }

                @Override
                public void onChildViewDetachedFromWindow(@NonNull View view) {}
            };
            rvCategories.addOnChildAttachStateChangeListener(pendingCategoryAttachListener);
            rvCategories.post(() -> {
                View attached = categoryLayoutManager.findViewByPosition(targetPos);
                if (attached != null && !attached.hasFocus()) {
                    attached.requestFocus();
                }
            });
        }
    }

    private void navigateToChannelPosition(int targetPos) {
        if (channelAdapter == null || rvChannels == null || channelLayoutManager == null) return;
        int count = channelAdapter.getItemCount();
        if (targetPos < 0 || targetPos >= count) return;

        if (pendingChannelAttachListener != null) {
            rvChannels.removeOnChildAttachStateChangeListener(pendingChannelAttachListener);
            pendingChannelAttachListener = null;
        }

        channelLayoutManager.scrollToPositionCentered(rvChannels, targetPos);
        View v = channelLayoutManager.findViewByPosition(targetPos);
        if (v != null) {
            v.requestFocus();
        } else {
            pendingChannelAttachListener = new RecyclerView.OnChildAttachStateChangeListener() {
                @Override
                public void onChildViewAttachedToWindow(@NonNull View view) {
                    int pos = rvChannels.getChildAdapterPosition(view);
                    if (pos == targetPos) {
                        if (pendingChannelAttachListener != null) {
                            rvChannels.removeOnChildAttachStateChangeListener(pendingChannelAttachListener);
                            pendingChannelAttachListener = null;
                        }
                        view.requestFocus();
                    }
                }

                @Override
                public void onChildViewDetachedFromWindow(@NonNull View view) {}
            };
            rvChannels.addOnChildAttachStateChangeListener(pendingChannelAttachListener);
            rvChannels.post(() -> {
                View attached = channelLayoutManager.findViewByPosition(targetPos);
                if (attached != null && !attached.hasFocus()) {
                    attached.requestFocus();
                }
            });
        }
    }

    private void toggleMenu() {
        if (layoutMenu.getVisibility() == View.VISIBLE) {
            hideMenu();
        } else {
            showMenu();
        }
    }

    private void showSettingsDialog() {
        dismissSplash();
        if (isFinishing() || isDestroyed()) return;
        float currentSpeed = (playerManager != null) ? playerManager.getPlaybackSpeed() : 1.0f;
        try {
            new SettingsDialog(this, currentPlayingChannel, currentSpeed, playerManager, new SettingsDialog.SettingsCallback() {
                @Override
                public void onSettingChanged() {
                    playerManager.applyAspectRatio(settings.getScreenScale());
                    tvTopClock.setVisibility(settings.isShowTime() ? View.VISIBLE : View.GONE);
                    tvNetSpeed.setVisibility(settings.isShowSpeed() ? View.VISIBLE : View.GONE);
                    if (rvChannels != null) {
                        rvChannels.post(() -> {
                            if (!isFinishing() && !isDestroyed() && channelAdapter != null && !rvChannels.isComputingLayout()) {
                                channelAdapter.notifyDataSetChanged();
                            }
                        });
                    }
                    if (currentPlayingChannel != null) {
                        showChannelBanner(currentPlayingChannel);
                    }
                    if (currentPlayingChannel != null && !isTimeShiftMode && allChannels != null && allChannels.size() > 1) {
                        PreloadChannelManager.getInstance().updateActiveChannel(currentPlayingChannel, allChannels, settings);
                    }
                }

                @Override
                public void onReloadChannels() {
                    loadChannels();
                }

                @Override
                public void onEnterPictureInPicture() {
                    enterPiPMode();
                }

                @Override
                public void onDecoderChanged(int decoderMode) {
                    showStatusToast("解码方式已切换为: " + SettingsRepository.DECODER_OPTIONS[decoderMode]);
                }

                @Override
                public void onSourceChanged(int sourceIndex) {
                    if (currentPlayingChannel != null) {
                        playChannel(currentPlayingChannel);
                        showStatusToast("已切换到线路: " + currentPlayingChannel.getSourceDisplayName(sourceIndex));
                    }
                }

                @Override
                public void onSpeedChanged(float speed) {
                    if (playerManager != null) {
                        playerManager.setPlaybackSpeed(speed);
                    }
                    if (btnTimeshiftSpeed != null) {
                        btnTimeshiftSpeed.setText(speed + "x");
                    }
                    for (int i = 0; i < SPEED_STEPS.length; i++) {
                        if (Math.abs(SPEED_STEPS[i] - speed) < 0.05f) {
                            currentSpeedIndex = i;
                            break;
                        }
                    }
                    showStatusToast(String.format(Locale.getDefault(), "播放倍速已切换至：%.2fx", speed));
                }
            }).show();
        } catch (Exception ignored) {}
    }

    private void showStatusToast(String msg) {
        tvStatusToast.setText(msg);
        tvStatusToast.setVisibility(View.VISIBLE);
        handler.removeCallbacksAndMessages(tvStatusToast);
        handler.postDelayed(() -> tvStatusToast.setVisibility(View.GONE), 3000);
    }

    // Remote D-Pad and Keyboard Key Events
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        dismissSplash();
        // If Menu is open
        if (layoutMenu.getVisibility() == View.VISIBLE) {
            resetMenuAutoHideTimer();
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                handleBackAction();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (rvEpg != null && rvEpg.hasFocus()) {
                    if (rvEpgDates != null) {
                        int sel = (epgDateAdapter != null) ? epgDateAdapter.getSelectedPosition() : 0;
                        View v = (epgDateLayoutManager != null) ? epgDateLayoutManager.findViewByPosition(sel) : null;
                        if (v != null) v.requestFocus(); else rvEpgDates.requestFocus();
                    }
                    return true;
                } else if (rvEpgDates != null && rvEpgDates.hasFocus()) {
                    if (rvChannels != null) {
                        int sel = (channelAdapter != null) ? channelAdapter.getSelectedPosition() : 0;
                        View v = (channelLayoutManager != null) ? channelLayoutManager.findViewByPosition(sel) : null;
                        if (v != null) v.requestFocus(); else rvChannels.requestFocus();
                    }
                    return true;
                } else if (rvChannels != null && rvChannels.hasFocus()) {
                    if (isEpgExpanded) {
                        collapseEpgPanel();
                    } else if (rvCategories != null) {
                        if (layoutCategoryColumn != null) {
                            layoutCategoryColumn.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
                        }
                        int sel = (categoryAdapter != null) ? categoryAdapter.getSelectedPosition() : 0;
                        View v = (categoryLayoutManager != null) ? categoryLayoutManager.findViewByPosition(sel) : null;
                        if (v != null) v.requestFocus(); else rvCategories.requestFocus();
                    }
                    return true;
                } else if (rvCategories != null && rvCategories.hasFocus()) {
                    hideMenu();
                    return true;
                }
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (rvCategories != null && rvCategories.hasFocus()) {
                    if (rvChannels != null) {
                        int sel = (channelAdapter != null) ? channelAdapter.getSelectedPosition() : 0;
                        View v = (channelLayoutManager != null) ? channelLayoutManager.findViewByPosition(sel) : null;
                        if (v != null) v.requestFocus(); else rvChannels.requestFocus();
                    }
                    return true;
                } else if (rvChannels != null && rvChannels.hasFocus()) {
                    if (!isEpgExpanded) {
                        expandEpgPanel();
                    } else if (rvEpgDates != null) {
                        int sel = (epgDateAdapter != null) ? epgDateAdapter.getSelectedPosition() : 0;
                        View v = (epgDateLayoutManager != null) ? epgDateLayoutManager.findViewByPosition(sel) : null;
                        if (v != null) v.requestFocus(); else rvEpgDates.requestFocus();
                    }
                    return true;
                } else if (rvEpgDates != null && rvEpgDates.hasFocus()) {
                    if (rvEpg != null && rvEpg.getVisibility() == View.VISIBLE) {
                        long targetTime = (isTimeShiftMode && lastTimeShiftEpochMs > 0) ? lastTimeShiftEpochMs : System.currentTimeMillis();
                        int curProgIdx = epgAdapter.findProgramIndexForTime(targetTime);
                        if (curProgIdx >= 0 && epgLayoutManager != null) {
                            epgLayoutManager.scrollToPositionCentered(rvEpg, curProgIdx);
                            rvEpg.post(() -> {
                                if (epgLayoutManager != null) {
                                    View v = epgLayoutManager.findViewByPosition(curProgIdx);
                                    if (v != null) {
                                        v.requestFocus();
                                        return;
                                    }
                                }
                                rvEpg.requestFocus();
                            });
                        } else {
                            rvEpg.requestFocus();
                        }
                    }
                    return true;
                }
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (rvCategories != null && rvCategories.hasFocus()) {
                    View focusedChild = rvCategories.getFocusedChild();
                    int focusedPos = (focusedChild != null) ? rvCategories.getChildAdapterPosition(focusedChild) : RecyclerView.NO_POSITION;
                    if (focusedPos == RecyclerView.NO_POSITION && categoryAdapter != null) {
                        focusedPos = categoryAdapter.getSelectedPosition();
                    }
                    int count = (categoryAdapter != null) ? categoryAdapter.getItemCount() : 0;
                    if (focusedPos == 0 && count > 1) {
                        navigateToCategoryPosition(count - 1);
                        return true;
                    }
                } else if (rvChannels != null && rvChannels.hasFocus()) {
                    View focusedChild = rvChannels.getFocusedChild();
                    int focusedPos = (focusedChild != null) ? rvChannels.getChildAdapterPosition(focusedChild) : RecyclerView.NO_POSITION;
                    if (focusedPos == RecyclerView.NO_POSITION && channelAdapter != null) {
                        focusedPos = channelAdapter.getSelectedPosition();
                    }
                    int count = (channelAdapter != null) ? channelAdapter.getItemCount() : 0;
                    if (focusedPos == 0 && count > 1) {
                        navigateToChannelPosition(count - 1);
                        return true;
                    }
                }
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (rvCategories != null && rvCategories.hasFocus()) {
                    View focusedChild = rvCategories.getFocusedChild();
                    int focusedPos = (focusedChild != null) ? rvCategories.getChildAdapterPosition(focusedChild) : RecyclerView.NO_POSITION;
                    if (focusedPos == RecyclerView.NO_POSITION && categoryAdapter != null) {
                        focusedPos = categoryAdapter.getSelectedPosition();
                    }
                    int count = (categoryAdapter != null) ? categoryAdapter.getItemCount() : 0;
                    if (focusedPos == count - 1 && count > 1) {
                        navigateToCategoryPosition(0);
                        return true;
                    }
                } else if (rvChannels != null && rvChannels.hasFocus()) {
                    View focusedChild = rvChannels.getFocusedChild();
                    int focusedPos = (focusedChild != null) ? rvChannels.getChildAdapterPosition(focusedChild) : RecyclerView.NO_POSITION;
                    if (focusedPos == RecyclerView.NO_POSITION && channelAdapter != null) {
                        focusedPos = channelAdapter.getSelectedPosition();
                    }
                    int count = (channelAdapter != null) ? channelAdapter.getItemCount() : 0;
                    if (focusedPos == count - 1 && count > 1) {
                        navigateToChannelPosition(0);
                        return true;
                    }
                }
            }
            return super.onKeyDown(keyCode, event);
        }

        boolean reverse = settings.isReverseSwitch();

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
                if (reverse) switchChannelPrev(); else switchChannelNext();
                return true;

            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                if (reverse) switchChannelNext(); else switchChannelPrev();
                return true;

            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (isCurrentMediaVodSeek()) {
                    handleVodSeek(-1, event);
                } else if (isTimeShiftMode || (playerManager != null && playerManager.isTimeShiftStream()) || (currentPlayingChannel != null && currentPlayingChannel.isTimeShiftSupported())) {
                    handleTimeShiftSeek(-1, event);
                } else {
                    switchSourcePrevious();
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (isCurrentMediaVodSeek()) {
                    handleVodSeek(1, event);
                } else if (isTimeShiftMode || (playerManager != null && playerManager.isTimeShiftStream()) || (currentPlayingChannel != null && currentPlayingChannel.isTimeShiftSupported())) {
                    handleTimeShiftSeek(1, event);
                } else {
                    switchSourceNext();
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                event.startTracking();
                if (quickNumInput != null && !quickNumInput.isEmpty()) {
                    confirmQuickNumJump();
                    return true;
                }
                // 如果有未执行的换台防抖 (300ms 内连击)，按 OK 立即完成跳台
                if (pendingSwitchChannel != null) {
                    handler.removeCallbacks(channelSwitchDebounceRunnable);
                    Channel target = pendingSwitchChannel;
                    pendingSwitchChannel = null;
                    playChannel(target);
                    return true;
                }
                int okAction = settings.getOkKeyAction();
                if (okAction == 0) { // 打开列表 (默认)
                    if (layoutMenu != null && layoutMenu.getVisibility() == View.VISIBLE) {
                        hideMenu();
                    } else {
                        showMenu();
                    }
                } else { // 显示信息条
                    if (layoutBanner != null && layoutBanner.getVisibility() == View.VISIBLE) {
                        hideBanner();
                        showMenu();
                    } else {
                        if (currentPlayingChannel != null) {
                            showChannelBanner(currentPlayingChannel);
                        }
                    }
                }
                return true;

            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SETTINGS:
                showSettingsDialog();
                return true;

            case KeyEvent.KEYCODE_BACK:
                handleBackAction();
                return true;

            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                if (audioManager != null) {
                    int dir = (keyCode == KeyEvent.KEYCODE_VOLUME_UP) ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER;
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI);
                }
                return true;

            case KeyEvent.KEYCODE_VOLUME_MUTE:
                if (audioManager != null) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
                }
                return true;

            default:
                // Handle Number keys (0 - 9) and Numpad (0 - 9)
                if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                    handleNumberInput(keyCode - KeyEvent.KEYCODE_0);
                    return true;
                } else if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
                    handleNumberInput(keyCode - KeyEvent.KEYCODE_NUMPAD_0);
                    return true;
                }
                break;
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (layoutMenu.getVisibility() == View.VISIBLE) {
                hideMenu();
            }
            showSettingsDialog();
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    // 增加台号 (如 2台 -> 3台) - 支持 300ms 快速换台防抖
    private void switchChannelNext() {
        if (allChannels.isEmpty()) return;
        Channel base = (pendingSwitchChannel != null) ? pendingSwitchChannel : currentPlayingChannel;
        int idx = allChannels.indexOf(base);
        if (idx == -1) idx = 0;
        int nextIdx = (idx + 1) % allChannels.size();
        Channel target = allChannels.get(nextIdx);
        scheduleChannelSwitch(target);
    }

    // 减少台号 (如 2台 -> 1台) - 支持 300ms 快速换台防抖
    private void switchChannelPrev() {
        if (allChannels.isEmpty()) return;
        Channel base = (pendingSwitchChannel != null) ? pendingSwitchChannel : currentPlayingChannel;
        int idx = allChannels.indexOf(base);
        if (idx == -1) idx = 0;
        int nextIdx = (idx - 1 + allChannels.size()) % allChannels.size();
        Channel target = allChannels.get(nextIdx);
        scheduleChannelSwitch(target);
    }

    private void scheduleChannelSwitch(Channel target) {
        if (target == null) return;
        pendingSwitchChannel = target;
        syncChannelGroupIndex(target);
        showChannelBanner(target);
        handler.removeCallbacks(channelSwitchDebounceRunnable);
        handler.postDelayed(channelSwitchDebounceRunnable, 300);
    }

    private void switchSourceNext() {
        if (currentPlayingChannel == null) return;
        if (currentPlayingChannel.getUrlCount() > 1) {
            failureLoopStartIndex = -1;
            // Preserve playback position if watching pure video / VOD
            if (playerManager != null && isCurrentMediaVodSeek()) {
                long curPos = playerManager.getCurrentPosition();
                if (curPos > 500) {
                    playerManager.setPendingResumePositionMs(curPos);
                }
            }
            currentPlayingChannel.nextUrl();
            if (settings.isRememberSource()) {
                settings.saveSourceIndex(currentPlayingChannel.getName(), currentPlayingChannel.getCurrentUrlIndex());
            }
            showStatusToast(String.format(Locale.getDefault(), "已切换线路: %s (%d/%d)",
                    currentPlayingChannel.getCurrentSourcePureName(),
                    currentPlayingChannel.getCurrentUrlIndex() + 1,
                    currentPlayingChannel.getUrlCount()));
            playChannel(currentPlayingChannel);
        } else {
            showStatusToast("当前频道仅有 1 条线路");
        }
    }

    private void switchSourcePrevious() {
        if (currentPlayingChannel == null) return;
        if (currentPlayingChannel.getUrlCount() > 1) {
            failureLoopStartIndex = -1;
            // Preserve playback position if watching pure video / VOD
            if (playerManager != null && isCurrentMediaVodSeek()) {
                long curPos = playerManager.getCurrentPosition();
                if (curPos > 500) {
                    playerManager.setPendingResumePositionMs(curPos);
                }
            }
            int count = currentPlayingChannel.getUrlCount();
            int cur = currentPlayingChannel.getCurrentUrlIndex();
            currentPlayingChannel.setCurrentUrlIndex((cur - 1 + count) % count);
            if (settings.isRememberSource()) {
                settings.saveSourceIndex(currentPlayingChannel.getName(), currentPlayingChannel.getCurrentUrlIndex());
            }
            showStatusToast(String.format(Locale.getDefault(), "已切换线路: %s (%d/%d)",
                    currentPlayingChannel.getCurrentSourcePureName(),
                    currentPlayingChannel.getCurrentUrlIndex() + 1,
                    currentPlayingChannel.getUrlCount()));
            playChannel(currentPlayingChannel);
        } else {
            showStatusToast("当前频道仅有 1 条线路");
        }
    }

    private void confirmQuickNumJump() {
        if (quickNumJumpRunnable != null) {
            handler.removeCallbacks(quickNumJumpRunnable);
        }
        if (tvQuickNum != null) {
            tvQuickNum.setVisibility(View.GONE);
        }
        if (quickNumInput == null || quickNumInput.isEmpty()) return;
        try {
            int targetNum = Integer.parseInt(quickNumInput);
            boolean found = false;
            for (Channel ch : allChannels) {
                if (ch.getNumber() == targetNum) {
                    syncChannelGroupIndex(ch);
                    playChannel(ch);
                    found = true;
                    break;
                }
            }
            if (!found) {
                showStatusToast(String.format(Locale.getDefault(), "台号 %d 不存在", targetNum));
            }
        } catch (Exception ignored) {}
        quickNumInput = "";
    }

    private void handleNumberInput(int digit) {
        handler.removeCallbacks(channelSwitchDebounceRunnable);
        pendingSwitchChannel = null;
        quickNumInput += digit;
        tvQuickNum.setText(quickNumInput);
        tvQuickNum.setVisibility(View.VISIBLE);

        if (quickNumJumpRunnable != null) {
            handler.removeCallbacks(quickNumJumpRunnable);
        }
        quickNumJumpRunnable = this::confirmQuickNumJump;
        handler.postDelayed(quickNumJumpRunnable, 1500);
    }

    private boolean isCurrentMediaVodSeek() {
        if (isTimeShiftMode || (playerManager != null && playerManager.isTimeShiftStream())) {
            return false;
        }
        if (currentPlayingChannel != null && currentPlayingChannel.isVodSeekMode()) {
            return true;
        }
        if (playerManager != null) {
            long dur = playerManager.getDuration();
            if (dur > 0 && !playerManager.isCurrentMediaItemLive()) {
                return true;
            }
        }
        return false;
    }

    private int calculateSeekIntensity(KeyEvent event) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastSeekTime < 800) {
            continuousSeekCount++;
        } else {
            continuousSeekCount = 1;
        }
        lastSeekTime = now;
        int repeatCount = (event != null) ? event.getRepeatCount() : 0;
        return Math.max(repeatCount, continuousSeekCount);
    }

    private void handleVodSeek(int direction, KeyEvent event) {
        if (playerManager == null) return;
        int intensity = calculateSeekIntensity(event);
        long stepSec;
        if (intensity <= 2) {
            stepSec = 10;
        } else if (intensity <= 7) {
            stepSec = 30;
        } else if (intensity <= 15) {
            stepSec = 60; // 1分钟
        } else if (intensity <= 30) {
            stepSec = 180; // 3分钟
        } else {
            stepSec = 300; // 5分钟
        }
        long stepMs = stepSec * 1000L;

        long curPos = (pendingVodSeekTargetPos >= 0) ? pendingVodSeekTargetPos : playerManager.getCurrentPosition();
        long dur = playerManager.getDuration();
        long targetPos = (direction > 0) ? (curPos + stepMs) : (curPos - stepMs);
        if (targetPos < 0) targetPos = 0;
        if (dur > 0 && targetPos > dur) targetPos = dur;
        pendingVodSeekTargetPos = targetPos;

        handler.removeCallbacks(vodSeekCommitRunnable);
        handler.postDelayed(vodSeekCommitRunnable, 150);

        String act = direction > 0 ? "快进" : "快退";
        String stepStr = (stepSec >= 60) ? (stepSec / 60 + "分钟") : (stepSec + "秒");
        String curStr = formatTimeMs(targetPos);
        String durStr = (dur > 0) ? formatTimeMs(dur) : "--:--";
        showStatusToast(String.format(Locale.getDefault(), "%s %s [%s / %s]", act, stepStr, curStr, durStr));

        // Ensure banner is displayed and reset auto-hide timer (consistent with timeshift)
        if (layoutBanner != null) {
            layoutBanner.setVisibility(View.VISIBLE);
            if (hideBannerRunnable != null) {
                handler.removeCallbacks(hideBannerRunnable);
            }
            hideBannerRunnable = () -> layoutBanner.setVisibility(View.GONE);
            handler.postDelayed(hideBannerRunnable, 5000);
        }
        showChannelBanner(currentPlayingChannel);
    }

    private void handleVodSeek(int direction) {
        handleVodSeek(direction, null);
    }

    private String formatTimeMs(long ms) {
        long totalSec = Math.max(0, ms / 1000);
        long hr = totalSec / 3600;
        long min = (totalSec % 3600) / 60;
        long sec = totalSec % 60;
        if (hr > 0) {
            return String.format(Locale.getDefault(), "%02d:%02d:%02d", hr, min, sec);
        } else {
            return String.format(Locale.getDefault(), "%02d:%02d", min, sec);
        }
    }

    private void handleTimeShiftSeek(int direction, KeyEvent event) {
        if (currentPlayingChannel == null) return;

        if (!currentPlayingChannel.isTimeShiftSupported() && !isTimeShiftMode && (playerManager == null || !playerManager.isTimeShiftStream())) {
            if (direction > 0) {
                switchSourceNext();
            } else {
                switchSourcePrevious();
            }
            return;
        }

        int intensity = calculateSeekIntensity(event);
        long stepOffsetSec;
        if (intensity <= 2) {
            stepOffsetSec = 60; // 1分钟
        } else if (intensity <= 7) {
            stepOffsetSec = 180; // 3分钟
        } else if (intensity <= 15) {
            stepOffsetSec = 300; // 5分钟
        } else {
            stepOffsetSec = 600; // 10分钟
        }

        long baseOffset;
        if (isTimeShiftSeeking) {
            baseOffset = timeShiftOffsetSeconds;
        } else if (playerManager != null && (isTimeShiftMode || playerManager.isTimeShiftStream())) {
            long curEpoch = playerManager.getCurrentTimeShiftEpochMs();
            if (curEpoch > 0) {
                baseOffset = (curEpoch - System.currentTimeMillis()) / 1000L;
            } else {
                baseOffset = timeShiftOffsetSeconds;
            }
        } else {
            baseOffset = timeShiftOffsetSeconds;
        }

        applyTimeShiftOffset(baseOffset + (direction * stepOffsetSec));
    }

    private void handleTimeShiftSeek(int direction) {
        handleTimeShiftSeek(direction, null);
    }

    private void applyTimeShiftOffset(long targetOffsetSeconds) {
        if (currentPlayingChannel == null) return;
        if (!currentPlayingChannel.isTimeShiftSupported() && !isTimeShiftMode && (playerManager == null || !playerManager.isTimeShiftStream())) return;

        isTimeShiftMode = true;
        isTimeShiftSeeking = true;
        timeShiftOffsetSeconds = targetOffsetSeconds;
        if (timeShiftOffsetSeconds > 0) {
            timeShiftOffsetSeconds = 0; // Cannot seek ahead of live
        }

        if (timeShiftOffsetSeconds == 0) {
            isTimeShiftSeeking = false;
            exitTimeShiftToLive();
            showStatusToast("【时移回看】已回到实时直播");
            return;
        }

        long now = System.currentTimeMillis();
        long targetTimestampMs = now + (timeShiftOffsetSeconds * 1000L);
        playerManager.setTimeShiftBaseEpochMs(targetTimestampMs);

        if (layoutTimeshiftHeader != null) {
            layoutTimeshiftHeader.setVisibility(View.VISIBLE);
        }
        updateTimeShiftStatusText();
        handler.removeCallbacks(timeShiftClockRunnable);
        handler.post(timeShiftClockRunnable);

        long minsBack = Math.abs(timeShiftOffsetSeconds) / 60;
        SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        String targetTimeStr = timeFmt.format(new Date(targetTimestampMs));
        showStatusToast(String.format(Locale.getDefault(), "【时移回看】回退 %d 分钟 (%s)", minsBack, targetTimeStr));

        // Update banner progress bar & text to follow the gesture
        if (layoutBanner != null) {
            layoutBanner.setVisibility(View.VISIBLE);
            if (hideBannerRunnable != null) {
                handler.removeCallbacks(hideBannerRunnable);
            }
            hideBannerRunnable = () -> layoutBanner.setVisibility(View.GONE);
            handler.postDelayed(hideBannerRunnable, 5000);
        }

        syncTimeShiftBannerEpg(targetTimestampMs);

        if (timeShiftSeekRunnable != null) {
            handler.removeCallbacks(timeShiftSeekRunnable);
        }

        timeShiftSeekRunnable = () -> {
            String timeShiftUrl = currentPlayingChannel.getTimeShiftUrl(targetTimestampMs);
            showLoading(true);
            playerManager.playStreamUrl(timeShiftUrl, currentPlayingChannel.getCurrentHeaders());
        };

        // Debounce seek request by 600ms
        handler.postDelayed(timeShiftSeekRunnable, 600);
    }

    private void updateTimeShiftStatusText() {
        if (!isTimeShiftMode || tvTimeshiftDynamicStatus == null) return;
        long currentEpochMs;
        if (isTimeShiftSeeking) {
            currentEpochMs = System.currentTimeMillis() + (timeShiftOffsetSeconds * 1000L);
        } else {
            currentEpochMs = (playerManager != null) ? playerManager.getCurrentTimeShiftEpochMs() : 0;
            if (currentEpochMs <= 0) {
                currentEpochMs = System.currentTimeMillis() + (timeShiftOffsetSeconds * 1000L);
            } else {
                this.timeShiftOffsetSeconds = (currentEpochMs - System.currentTimeMillis()) / 1000L;
                this.lastTimeShiftEpochMs = currentEpochMs;
            }
        }
        long now = System.currentTimeMillis();
        long lagMs = Math.max(0, now - currentEpochMs);

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        String timeStr = sdf.format(new Date(currentEpochMs));

        long lagSec = lagMs / 1000;
        long hours = lagSec / 3600;
        long mins = (lagSec % 3600) / 60;
        String lagStr;
        if (hours > 0) {
            lagStr = hours + "小时" + mins + "分 前";
        } else {
            lagStr = mins + "分钟 前";
        }

        if (isTimeShiftSeeking) {
            tvTimeshiftDynamicStatus.setText(String.format("● 调节中 | %s (%s)", timeStr, lagStr));
        } else {
            tvTimeshiftDynamicStatus.setText(String.format("● 回看中 | %s (%s)", timeStr, lagStr));
        }

        if (layoutBanner != null && layoutBanner.getVisibility() == View.VISIBLE) {
            syncTimeShiftBannerEpg(currentEpochMs);
        }
    }

    private void exitTimeShiftToLive() {
        isTimeShiftMode = false;
        isTimeShiftSeeking = false;
        timeShiftOffsetSeconds = 0;
        lastTimeShiftEpochMs = 0;
        handler.removeCallbacks(timeShiftClockRunnable);
        if (timeShiftSeekRunnable != null) {
            handler.removeCallbacks(timeShiftSeekRunnable);
        }
        if (layoutTimeshiftHeader != null) {
            layoutTimeshiftHeader.setVisibility(View.GONE);
        }
        currentSpeedIndex = 0;
        if (btnTimeshiftSpeed != null) {
            btnTimeshiftSpeed.setText("1.0x");
        }
        playerManager.setTimeShiftBaseEpochMs(0);
        playerManager.setPlaybackSpeed(1.0f);
        if (bannerProgressBar != null) {
            bannerProgressBar.setProgress(100);
        }
        playChannel(currentPlayingChannel);
    }

    @Override
    public void onBackPressed() {
        handleBackAction();
    }

    private void handleBackAction() {
        dismissSplash();

        // 0. If quick number input is showing, cancel and dismiss it
        if (quickNumInput != null && !quickNumInput.isEmpty()) {
            if (quickNumJumpRunnable != null) {
                handler.removeCallbacks(quickNumJumpRunnable);
            }
            quickNumInput = "";
            if (tvQuickNum != null) {
                tvQuickNum.setVisibility(View.GONE);
            }
            return;
        }

        // 1. If Menu is open
        if (layoutMenu != null && layoutMenu.getVisibility() == View.VISIBLE) {
            resetMenuAutoHideTimer();
            if (isEpgExpanded) {
                collapseEpgPanel();
            } else {
                hideMenu();
            }
            return;
        }

        // 2. If Channel Banner is open
        if (layoutBanner != null && layoutBanner.getVisibility() == View.VISIBLE) {
            hideBanner();
            return;
        }

        // 3. If in Time-Shift playback mode, return to live broadcast
        if (isTimeShiftMode) {
            exitTimeShiftToLive();
            showStatusToast("【时移回看】已返回实时直播");
            return;
        }

        // 4. In normal live state with no menus/banners: require double back press to exit
        long now = System.currentTimeMillis();
        if (now - lastBackPressTime < 2500) {
            finish();
        } else {
            lastBackPressTime = now;
            showStatusToast(getString(R.string.exit_prompt));
            Toast.makeText(this, R.string.exit_prompt, Toast.LENGTH_SHORT).show();
        }
    }

    // Touch & Gesture handling (1:1 with original DIYP)
    private void initGestureDetector() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                isGestureScrolled = false;
                hasHandledGestureAction = false;
                touchScrollMode = 0;
                touchStartOffsetSeconds = timeShiftOffsetSeconds;
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                dismissSplash();
                if (e == null || isGestureScrolled) {
                    return false;
                }
                if (layoutMenu.getVisibility() == View.VISIBLE) {
                    hideMenu();
                    return true;
                }
                int screenWidth = getResources().getDisplayMetrics().widthPixels;
                if (e.getX() < screenWidth / 2.0f) {
                    // Tap on left side: Show channel list menu
                    if (layoutBanner != null && layoutBanner.getVisibility() == View.VISIBLE) {
                        hideBanner();
                    }
                    showMenu();
                } else {
                    // Tap on right side: Show/toggle current channel program info banner
                    if (layoutBanner != null && layoutBanner.getVisibility() == View.VISIBLE) {
                        hideBanner();
                    } else if (currentPlayingChannel != null) {
                        showChannelBanner(currentPlayingChannel);
                    }
                }
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                dismissSplash();
                isGestureScrolled = true;
                if (layoutMenu.getVisibility() == View.VISIBLE) {
                    hideMenu();
                }
                showSettingsDialog();
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                dismissSplash();
                if (layoutMenu.getVisibility() == View.VISIBLE) {
                    return false;
                }
                if (e1 == null || e2 == null) return false;

                float totalDeltaX = e2.getX() - e1.getX();
                float totalDeltaY = e2.getY() - e1.getY();
                float absX = Math.abs(totalDeltaX);
                float absY = Math.abs(totalDeltaY);
                int screenWidth = getResources().getDisplayMetrics().widthPixels;
                int screenHeight = getResources().getDisplayMetrics().heightPixels;

                if (touchScrollMode == 0) {
                    if (absX > 10 || absY > 10) {
                        isGestureScrolled = true;
                        if (absX > absY) {
                            boolean isUpperHalf = e1.getY() < (screenHeight / 2.0f);
                            if (isCurrentMediaVodSeek()) {
                                if (!isUpperHalf) {
                                    touchScrollMode = 6; // VOD drag scrubbing
                                    touchStartOffsetSeconds = (pendingVodSeekTargetPos >= 0) ? pendingVodSeekTargetPos : (playerManager != null ? playerManager.getCurrentPosition() : 0);
                                } else {
                                    touchScrollMode = 3; // Source switch
                                    hasHandledGestureAction = true;
                                    if (totalDeltaX > 0) switchSourceNext(); else switchSourcePrevious();
                                }
                            } else {
                                boolean isTimeShiftReady = isTimeShiftMode || (playerManager != null && playerManager.isTimeShiftStream()) || (currentPlayingChannel != null && currentPlayingChannel.isTimeShiftSupported());
                                if (isTimeShiftReady && (!isUpperHalf || isTimeShiftMode)) {
                                    touchScrollMode = 1; // TimeShift drag
                                    touchStartOffsetSeconds = timeShiftOffsetSeconds;
                                } else {
                                    touchScrollMode = 3; // Source switch
                                    hasHandledGestureAction = true;
                                    if (totalDeltaX > 0) switchSourceNext(); else switchSourcePrevious();
                                }
                            }
                        } else {
                            // Vertical swipe: Left 15% = Brightness, Right 15% = Volume, Center 70% = Channel switch
                            float startX = e1.getX();
                            if (startX < screenWidth * 0.15f) {
                                touchScrollMode = 4; // Brightness
                                WindowManager.LayoutParams lp = getWindow().getAttributes();
                                touchInitialBrightness = (lp.screenBrightness < 0) ? 0.5f : lp.screenBrightness;
                            } else if (startX > screenWidth * 0.85f) {
                                touchScrollMode = 5; // Volume
                                if (audioManager != null) {
                                    touchInitialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                                }
                            } else {
                                touchScrollMode = 2; // Channel switch
                                hasHandledGestureAction = true;
                                boolean reverse = settings.isReverseSwitch();
                                if (totalDeltaY < 0) {
                                    if (reverse) switchChannelPrev(); else switchChannelNext();
                                } else {
                                    if (reverse) switchChannelNext(); else switchChannelPrev();
                                }
                            }
                        }
                    }
                } else if (touchScrollMode == 1) {
                    // Continuous Time-Shift Scrubbing: Drag distance + Hold duration
                    float ratio = totalDeltaX / (float) screenWidth;

                    // Calculate hold duration acceleration factor
                    long holdDurationMs = Math.max(0, e2.getEventTime() - e1.getDownTime());
                    float speedFactor = 1.0f;
                    if (holdDurationMs > 600) {
                        speedFactor += Math.min(2.5f, (holdDurationMs - 600) / 1000.0f);
                    }

                    // A full width swipe corresponds to 3600s (1 hour) * speedFactor
                    long deltaSeconds = (long) (ratio * 3600L * speedFactor);
                    long targetOffset = touchStartOffsetSeconds + deltaSeconds;
                    applyTimeShiftOffset(targetOffset);
                    return true;
                } else if (touchScrollMode == 6) {
                    // Continuous VOD Video Drag Scrubbing
                    if (playerManager == null) return true;
                    float ratio = totalDeltaX / (float) screenWidth;
                    long dur = playerManager.getDuration();
                    long scrubWindowMs = (dur > 0) ? Math.min(dur, Math.max(90000L, dur / 10L)) : 180000L;
                    long deltaMs = (long) (ratio * scrubWindowMs);
                    long targetPos = touchStartOffsetSeconds + deltaMs;
                    if (targetPos < 0) targetPos = 0;
                    if (dur > 0 && targetPos > dur) targetPos = dur;
                    pendingVodSeekTargetPos = targetPos;

                    handler.removeCallbacks(vodSeekCommitRunnable);
                    handler.postDelayed(vodSeekCommitRunnable, 150);

                    String act = deltaMs >= 0 ? "快进" : "快退";
                    String curStr = formatTimeMs(targetPos);
                    String durStr = (dur > 0) ? formatTimeMs(dur) : "--:--";
                    showStatusToast(String.format(Locale.getDefault(), "%s [%s / %s]", act, curStr, durStr));

                    // Ensure banner is displayed and reset auto-hide timer (consistent with timeshift)
                    if (layoutBanner != null) {
                        layoutBanner.setVisibility(View.VISIBLE);
                        if (hideBannerRunnable != null) {
                            handler.removeCallbacks(hideBannerRunnable);
                        }
                        hideBannerRunnable = () -> layoutBanner.setVisibility(View.GONE);
                        handler.postDelayed(hideBannerRunnable, 5000);
                    }
                    showChannelBanner(currentPlayingChannel);
                    return true;
                } else if (touchScrollMode == 4) {
                    // Adjust Brightness (Left 10% vertical drag)
                    float deltaY = e2.getY() - e1.getY();
                    float deltaB = -deltaY / (float) screenHeight;
                    float newB = Math.max(0.01f, Math.min(1.0f, touchInitialBrightness + deltaB));
                    WindowManager.LayoutParams lp = getWindow().getAttributes();
                    lp.screenBrightness = newB;
                    getWindow().setAttributes(lp);
                    showStatusToast(String.format(Locale.getDefault(), "屏幕亮度: %d%%", (int)(newB * 100)));
                    return true;
                } else if (touchScrollMode == 5) {
                    // Adjust Volume (Right 10% vertical drag)
                    if (audioManager != null) {
                        int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                        float deltaY = e2.getY() - e1.getY();
                        float deltaV = -deltaY / (float) screenHeight;
                        int newVol = Math.max(0, Math.min(maxVol, touchInitialVolume + (int)(deltaV * maxVol)));
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0);
                        int pct = (int)((newVol * 100.0f) / maxVol);
                        showStatusToast(String.format(Locale.getDefault(), "媒体音量: %d%%", pct));
                    }
                    return true;
                }
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                dismissSplash();
                if (layoutMenu.getVisibility() == View.VISIBLE || isGestureScrolled || hasHandledGestureAction || touchScrollMode != 0) {
                    return true;
                }
                if (e1 == null || e2 == null) return false;

                float deltaX = e2.getX() - e1.getX();
                float deltaY = e2.getY() - e1.getY();
                float absX = Math.abs(deltaX);
                float absY = Math.abs(deltaY);

                if (absX > absY && absX > 35) {
                    isGestureScrolled = true;
                    hasHandledGestureAction = true;
                    int screenHeight = getResources().getDisplayMetrics().heightPixels;
                    boolean isUpperHalf = e1.getY() < (screenHeight / 2.0f);
                    if (isCurrentMediaVodSeek()) {
                        if (!isUpperHalf) {
                            handleVodSeek(deltaX > 0 ? 1 : -1);
                        } else {
                            if (deltaX > 0) switchSourceNext(); else switchSourcePrevious();
                        }
                    } else if (isTimeShiftMode || (playerManager != null && playerManager.isTimeShiftStream()) || (currentPlayingChannel != null && currentPlayingChannel.isTimeShiftSupported() && (!isUpperHalf || isTimeShiftMode))) {
                        handleTimeShiftSeek(deltaX > 0 ? 1 : -1);
                    } else {
                        if (deltaX > 0) switchSourceNext(); else switchSourcePrevious();
                    }
                    return true;
                } else if (absY > absX && absY > 35) {
                    isGestureScrolled = true;
                    hasHandledGestureAction = true;
                    boolean reverse = settings.isReverseSwitch();
                    if (deltaY < 0) {
                        if (reverse) switchChannelPrev(); else switchChannelNext();
                    } else {
                        if (reverse) switchChannelNext(); else switchChannelPrev();
                    }
                    return true;
                }
                return false;
            }
        });
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        dismissSplash();

        if (layoutMenu != null && layoutMenu.getVisibility() == View.VISIBLE) {
            resetMenuAutoHideTimer();
            // When Menu is open, tapping outside the menu panel closes it
            if (ev != null && ev.getAction() == MotionEvent.ACTION_UP) {
                int menuWidth = layoutMenu.getWidth();
                if (menuWidth > 0 && ev.getX() > menuWidth) {
                    hideMenu();
                    return true;
                }
            }
            return super.dispatchTouchEvent(ev);
        }

        // If touch event is on floating Timeshift controls or Banner, dispatch directly to views
        if (isTouchInView(layoutTimeshiftHeader, ev)) {
            return super.dispatchTouchEvent(ev);
        }

        // Full screen video mode: feed all events to GestureDetector
        boolean handled = false;
        if (gestureDetector != null && ev != null) {
            handled = gestureDetector.onTouchEvent(ev);
        }

        if (ev != null && (ev.getAction() == MotionEvent.ACTION_UP || ev.getAction() == MotionEvent.ACTION_CANCEL)) {
            if (touchScrollMode == 6 && playerManager != null && pendingVodSeekTargetPos >= 0) {
                handler.removeCallbacks(vodSeekCommitRunnable);
                long target = pendingVodSeekTargetPos;
                pendingVodSeekTargetPos = -1;
                playerManager.seekTo(target);
                if (layoutBanner != null && layoutBanner.getVisibility() == View.VISIBLE) {
                    showChannelBanner(currentPlayingChannel);
                }
            }
            touchScrollMode = 0;
            hasHandledGestureAction = false;
        }

        if (handled) {
            return true;
        }

        return super.dispatchTouchEvent(ev);
    }

    private boolean isTouchInView(View view, MotionEvent ev) {
        if (view == null || ev == null || view.getVisibility() != View.VISIBLE) return false;
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        float x = ev.getRawX();
        float y = ev.getRawY();
        return (x >= location[0] && x <= (location[0] + view.getWidth()) &&
                y >= location[1] && y <= (location[1] + view.getHeight()));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (gestureDetector != null && gestureDetector.onTouchEvent(event)) {
            return true;
        }
        return super.onTouchEvent(event);
    }

    private void enterPiPMode() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                android.app.PictureInPictureParams.Builder builder = new android.app.PictureInPictureParams.Builder();
                android.util.Rational aspectRatio = new android.util.Rational(16, 9);
                builder.setAspectRatio(aspectRatio);
                enterPictureInPictureMode(builder.build());
            } catch (Exception e) {
                showStatusToast("开启窗口播放失败: " + e.getMessage());
            }
        } else {
            showStatusToast("当前安卓系统版本较低，不支持窗口播放");
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, android.content.res.Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        if (isInPictureInPictureMode) {
            hideMenu();
            hideBanner();
            if (layoutTimeshiftHeader != null) layoutTimeshiftHeader.setVisibility(View.GONE);
            if (tvTopClock != null) tvTopClock.setVisibility(View.GONE);
            if (tvNetSpeed != null) tvNetSpeed.setVisibility(View.GONE);
            if (pbLoading != null) pbLoading.setVisibility(View.GONE);
            if (tvStatusToast != null) tvStatusToast.setVisibility(View.GONE);
        } else {
            if (tvTopClock != null) tvTopClock.setVisibility(settings.isShowTime() ? View.VISIBLE : View.GONE);
            if (tvNetSpeed != null) tvNetSpeed.setVisibility(settings.isShowSpeed() ? View.VISIBLE : View.GONE);
            if (isTimeShiftMode && layoutTimeshiftHeader != null) {
                layoutTimeshiftHeader.setVisibility(View.VISIBLE);
            }
            hideSystemUI();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Exit to background: immediately stop/pause audio like original DIYP unless in PiP window
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N && isInPictureInPictureMode()) {
            return;
        }
        if (isTimeShiftMode && playerManager != null) {
            long epoch = playerManager.getCurrentTimeShiftEpochMs();
            if (epoch > 0) {
                lastTimeShiftEpochMs = epoch;
            }
        }
        if (playerManager != null) {
            playerManager.pause();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N && isInPictureInPictureMode()) {
            return;
        }
        if (playerManager != null) {
            playerManager.stop();
        }
        PreloadChannelManager.getInstance().stopAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        if (playerManager != null && currentPlayingChannel != null) {
            if (!playerManager.isPlaying()) {
                if (isTimeShiftMode && (lastTimeShiftEpochMs > 0 || timeShiftOffsetSeconds != 0)) {
                    long targetMs = (lastTimeShiftEpochMs > 0) ? lastTimeShiftEpochMs : (System.currentTimeMillis() + timeShiftOffsetSeconds * 1000L);
                    timeShiftOffsetSeconds = (targetMs - System.currentTimeMillis()) / 1000L;
                    String tsUrl = currentPlayingChannel.getTimeShiftUrl(targetMs);
                    playerManager.setTimeShiftBaseEpochMs(targetMs);
                    showLoading(true);
                    playerManager.playStreamUrl(tsUrl, currentPlayingChannel.getCurrentHeaders());
                    if (layoutTimeshiftHeader != null) {
                        layoutTimeshiftHeader.setVisibility(View.VISIBLE);
                    }
                    handler.removeCallbacks(timeShiftClockRunnable);
                    handler.post(timeShiftClockRunnable);
                } else {
                    if (playerManager != null && isCurrentMediaVodSeek()) {
                        long curPos = playerManager.getCurrentPosition();
                        if (curPos > 500) {
                            playerManager.setPendingResumePositionMs(curPos);
                        }
                    }
                    isTimeShiftMode = false;
                    timeShiftOffsetSeconds = 0;
                    lastTimeShiftEpochMs = 0;
                    playerManager.playChannel(currentPlayingChannel);
                }
            }
        }
        if (currentPlayingChannel != null && !isTimeShiftMode && allChannels != null && allChannels.size() > 1) {
            PreloadChannelManager.getInstance().updateActiveChannel(currentPlayingChannel, allChannels, settings);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        PreloadChannelManager.getInstance().stopAll();
        if (speedMonitor != null) speedMonitor.stop();
        if (playerManager != null) playerManager.release();
        handler.removeCallbacks(timeShiftClockRunnable);
        if (timeShiftSeekRunnable != null) {
            handler.removeCallbacks(timeShiftSeekRunnable);
        }
        handler.removeCallbacksAndMessages(null);
    }
}