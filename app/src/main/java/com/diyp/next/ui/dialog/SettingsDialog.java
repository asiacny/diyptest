package com.diyp.next.ui.dialog;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import com.diyp.next.R;
import com.diyp.next.data.model.Channel;
import com.diyp.next.data.repository.SettingsRepository;
import com.diyp.next.player.VideoPlayerManager;
import java.util.List;

public class SettingsDialog extends Dialog {
    private final SettingsRepository settings = SettingsRepository.get();
    private final SettingsCallback callback;
    private Channel currentChannel;
    private float currentSpeed = 1.0f;
    private VideoPlayerManager playerManager;
    private int currentTabIndex = 0;
    private HorizontalScrollView scrollTabs;
    private Button[] tabs;
    private View[] panels;
    private View[] firstSubmenuItems;
    private Runnable updateTabsState;
    private boolean isProgrammaticTabSwitch = false;

    public interface SettingsCallback {
        void onSettingChanged();
        void onReloadChannels();
        void onEnterPictureInPicture();
        void onSpeedChanged(float speed);
        void onDecoderChanged(int decoderMode);
        void onSourceChanged(int sourceIndex);
    }

    public SettingsDialog(@NonNull Context context, Channel currentChannel, float currentSpeed, VideoPlayerManager playerManager, SettingsCallback callback) {
        super(context, R.style.DialogTheme);
        this.currentChannel = currentChannel;
        this.currentSpeed = currentSpeed;
        this.playerManager = playerManager;
        this.callback = callback;
    }

    public SettingsDialog(@NonNull Context context, Channel currentChannel, float currentSpeed, SettingsCallback callback) {
        this(context, currentChannel, currentSpeed, null, callback);
    }

    public SettingsDialog(@NonNull Context context, Channel currentChannel, SettingsCallback callback) {
        this(context, currentChannel, 1.0f, null, callback);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_settings);
        setCanceledOnTouchOutside(true);

        Window window = getWindow();
        if (window != null) {
            window.setGravity(Gravity.END | Gravity.TOP);
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = (int) (getContext().getResources().getDisplayMetrics().density * 320);
            params.height = WindowManager.LayoutParams.MATCH_PARENT;
            window.setAttributes(params);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        initViews();
    }

    private void initViews() {
        // Scheme 2: 顶部横向 Tab 胶囊与分栏容器
        scrollTabs = findViewById(R.id.scroll_settings_tabs);
        Button tabPlayback = findViewById(R.id.tab_playback);
        Button tabBehavior = findViewById(R.id.tab_behavior);
        Button tabUi = findViewById(R.id.tab_ui);
        Button tabSystem = findViewById(R.id.tab_system);

        View panelPlayback = findViewById(R.id.scroll_tab_playback);
        View panelBehavior = findViewById(R.id.scroll_tab_behavior);
        View panelUi = findViewById(R.id.scroll_tab_ui);
        View panelSystem = findViewById(R.id.scroll_tab_system);

        tabs = new Button[]{tabPlayback, tabBehavior, tabUi, tabSystem};
        panels = new View[]{panelPlayback, panelBehavior, panelUi, panelSystem};

        updateTabsState = () -> {
            for (int i = 0; i < tabs.length; i++) {
                if (tabs[i] == null || panels[i] == null) continue;
                boolean isSelected = (i == currentTabIndex);
                tabs[i].setSelected(isSelected);
                tabs[i].setTextColor(isSelected ? 0xFF00E5FF : 0xCCFFFFFF);
                panels[i].setVisibility(isSelected ? View.VISIBLE : View.GONE);
            }
            scrollToCurrentTab(currentTabIndex);
        };

        for (int i = 0; i < tabs.length; i++) {
            final int idx = i;
            if (tabs[i] != null) {
                tabs[i].setOnClickListener(v -> {
                    currentTabIndex = idx;
                    updateTabsState.run();
                });
                tabs[i].setOnFocusChangeListener((v, hasFocus) -> {
                    if (isProgrammaticTabSwitch) return;
                    if (hasFocus) {
                        currentTabIndex = idx;
                        updateTabsState.run();
                    }
                });
            }
        }
        updateTabsState.run();

        Button btnDecoder = findViewById(R.id.btn_setting_decoder);
        Button btnAudioDecoder = findViewById(R.id.btn_setting_audio_decoder);
        Button btnScale = findViewById(R.id.btn_setting_scale);
        Button btnTimeout = findViewById(R.id.btn_setting_timeout);
        Button btnDisconnect = findViewById(R.id.btn_setting_disconnect);
        Button btnSpeedRate = findViewById(R.id.btn_setting_speed_rate);
        Button btnPip = findViewById(R.id.btn_setting_pip);
        Button btnRememberPlay = findViewById(R.id.btn_setting_remember_play);
        Button btnRememberSource = findViewById(R.id.btn_setting_remember_source);
        Button btnPreload = findViewById(R.id.btn_setting_preload);
        Button btnSource = findViewById(R.id.btn_setting_source);
        Button btnAudioTrack = findViewById(R.id.btn_setting_audio_track);
        Button btnSubtitle = findViewById(R.id.btn_setting_subtitle);
        Button btnLogoMode = findViewById(R.id.btn_setting_logo_mode);
        Button btnHideBottomLogo = findViewById(R.id.btn_setting_hide_bottom_logo);
        Button btnOkAction = findViewById(R.id.btn_setting_ok_action);
        Button btnReverse = findViewById(R.id.btn_setting_reverse);
        Button btnBoot = findViewById(R.id.btn_setting_boot);
        Button btnSpeed = findViewById(R.id.btn_setting_speed);
        Button btnTime = findViewById(R.id.btn_setting_time);
        Button btnInterface = findViewById(R.id.btn_setting_interface);
        Button btnReset = findViewById(R.id.btn_setting_reset);

        firstSubmenuItems = new View[]{btnSource, btnTimeout, btnLogoMode, btnDecoder};
        for (int i = 0; i < tabs.length; i++) {
            if (tabs[i] != null && firstSubmenuItems[i] != null) {
                tabs[i].setNextFocusDownId(firstSubmenuItems[i].getId());
                firstSubmenuItems[i].setNextFocusUpId(tabs[i].getId());
            }
        }

        // 打开菜单时，焦点默认落在第一个分类的第一个子菜单（当前线路）
        if (btnSource != null) {
            btnSource.post(() -> btnSource.requestFocus());
        }
        setOnShowListener(dialog -> {
            if (scrollTabs != null) {
                scrollTabs.scrollTo(0, 0);
            }
            if (btnSource != null) {
                btnSource.post(() -> btnSource.requestFocus());
            }
        });

        // Update initial button labels
        updateDecoderLabel(btnDecoder);
        updateAudioDecoderLabel(btnAudioDecoder);
        updateScaleLabel(btnScale);
        updateTimeoutLabel(btnTimeout);
        updateDisconnectLabel(btnDisconnect);
        updateRememberPlayLabel(btnRememberPlay);
        updateRememberSourceLabel(btnRememberSource);
        updatePreloadLabel(btnPreload);
        updateSourceLabel(btnSource);
        updateAudioTrackLabel(btnAudioTrack);
        updateSubtitleLabel(btnSubtitle);
        updateLogoModeLabel(btnLogoMode);
        updateHideBottomLogoLabel(btnHideBottomLogo);
        updateOkActionLabel(btnOkAction);
        updateReverseLabel(btnReverse);
        updateBootLabel(btnBoot);
        updateSpeedLabel(btnSpeed);
        updateTimeLabel(btnTime);

        // 视频解码 二级菜单
        if (btnDecoder != null) {
            btnDecoder.setOnClickListener(v -> {
                OptionSelectionDialog.show(getContext(), "视频解码", SettingsRepository.DECODER_OPTIONS, settings.getDecoderMode(), (idx, text) -> {
                    settings.setDecoderMode(idx);
                    updateDecoderLabel(btnDecoder);
                    if (playerManager != null) {
                        playerManager.reinitPlayer();
                        playerManager.reloadCurrentStream();
                    }
                    if (callback != null) {
                        callback.onDecoderChanged(idx);
                        callback.onSettingChanged();
                    }
                });
            });
        }

        // 音频解码 二级菜单
        if (btnAudioDecoder != null) {
            btnAudioDecoder.setOnClickListener(v -> {
                OptionSelectionDialog.show(getContext(), "音频解码", SettingsRepository.AUDIO_DECODER_OPTIONS, settings.getAudioDecoderMode(), (idx, text) -> {
                    settings.setAudioDecoderMode(idx);
                    updateAudioDecoderLabel(btnAudioDecoder);
                    if (playerManager != null) {
                        playerManager.reinitPlayer();
                        playerManager.reloadCurrentStream();
                    }
                    if (callback != null) {
                        callback.onSettingChanged();
                    }
                });
            });
        }

        // 画面比例 二级菜单
        btnScale.setOnClickListener(v -> {
            OptionSelectionDialog.show(getContext(), "画面比例", SettingsRepository.SCALE_OPTIONS, settings.getScreenScale(), (idx, text) -> {
                settings.setScreenScale(idx);
                updateScaleLabel(btnScale);
                if (callback != null) callback.onSettingChanged();
            });
        });

        // 超时换源 二级菜单
        btnTimeout.setOnClickListener(v -> {
            int curSec = settings.getTimeoutSeconds();
            int curIdx = 0;
            for (int i = 0; i < SettingsRepository.TIMEOUT_VALUES.length; i++) {
                if (SettingsRepository.TIMEOUT_VALUES[i] == curSec) {
                    curIdx = i;
                    break;
                }
            }
            OptionSelectionDialog.show(getContext(), "超时换源", SettingsRepository.TIMEOUT_OPTIONS, curIdx, (idx, text) -> {
                settings.setTimeoutSeconds(SettingsRepository.TIMEOUT_VALUES[idx]);
                updateTimeoutLabel(btnTimeout);
                if (callback != null) callback.onSettingChanged();
            });
        });

        // 断流控制 二级菜单
        if (btnDisconnect != null) {
            btnDisconnect.setOnClickListener(v -> {
                OptionSelectionDialog.show(getContext(), "断流控制", SettingsRepository.DISCONNECT_OPTIONS, settings.getDisconnectControlIndex(), (idx, text) -> {
                    settings.setDisconnectControlIndex(idx);
                    updateDisconnectLabel(btnDisconnect);
                    if (callback != null) callback.onSettingChanged();
                });
            });
        }

        // 播放倍速 二级菜单 (仅在时移源下展示)
        if (btnSpeedRate != null) {
            if (currentChannel != null && currentChannel.isTimeShiftSupported()) {
                btnSpeedRate.setVisibility(View.VISIBLE);
                updateSpeedRateLabel(btnSpeedRate);
                btnSpeedRate.setOnClickListener(v -> {
                    final float[] SPEEDS = {1.0f, 1.25f, 1.5f, 2.0f, 3.0f};
                    final String[] SPEED_NAMES = {"1.0x (正常倍速)", "1.25x (1.25倍速)", "1.5x (1.5倍速)", "2.0x (2.0倍速)", "3.0x (3.0倍速)"};
                    int curIdx = 0;
                    for (int i = 0; i < SPEEDS.length; i++) {
                        if (Math.abs(SPEEDS[i] - currentSpeed) < 0.05f) {
                            curIdx = i;
                            break;
                        }
                    }
                    OptionSelectionDialog.show(getContext(), "播放倍速", SPEED_NAMES, curIdx, (idx, text) -> {
                        currentSpeed = SPEEDS[idx];
                        updateSpeedRateLabel(btnSpeedRate);
                        if (callback != null) callback.onSpeedChanged(currentSpeed);
                    });
                });
            } else {
                btnSpeedRate.setVisibility(View.GONE);
            }
        }

        // 窗口播放 (画中画)
        if (btnPip != null) {
            btnPip.setOnClickListener(v -> {
                dismiss();
                if (callback != null) callback.onEnterPictureInPicture();
            });
        }

        // 记忆播放
        if (btnRememberPlay != null) {
            btnRememberPlay.setOnClickListener(v -> {
                boolean rp = !settings.isRememberPlay();
                settings.setRememberPlay(rp);
                updateRememberPlayLabel(btnRememberPlay);
                if (callback != null) callback.onSettingChanged();
            });
        }

        // 记忆多源
        if (btnRememberSource != null) {
            btnRememberSource.setOnClickListener(v -> {
                boolean rs = !settings.isRememberSource();
                settings.setRememberSource(rs);
                updateRememberSourceLabel(btnRememberSource);
                if (callback != null) callback.onSettingChanged();
            });
        }

        // 极速秒切 (频道智能预热)
        if (btnPreload != null) {
            btnPreload.setOnClickListener(v -> {
                boolean p = !settings.isPreloadEnabled();
                settings.setPreloadEnabled(p);
                updatePreloadLabel(btnPreload);
                if (callback != null) callback.onSettingChanged();
            });
        }

        // 线路切换 (二级菜单)
        btnSource.setOnClickListener(v -> {
            if (currentChannel != null && currentChannel.getUrlCount() > 0) {
                int count = currentChannel.getUrlCount();
                String[] items = new String[count];
                for (int i = 0; i < count; i++) {
                    items[i] = currentChannel.getSourceDisplayName(i);
                }
                int curIdx = currentChannel.getCurrentUrlIndex();
                OptionSelectionDialog.show(getContext(), "线路选择", items, curIdx, (idx, text) -> {
                    if (playerManager != null && (!playerManager.isCurrentMediaItemLive() || playerManager.getDuration() > 0)) {
                        long curPos = playerManager.getCurrentPosition();
                        if (curPos > 500) {
                            playerManager.setPendingResumePositionMs(curPos);
                        }
                    }
                    currentChannel.setCurrentUrlIndex(idx);
                    settings.saveSourceIndex(currentChannel.getName(), idx);
                    updateSourceLabel(btnSource);
                    if (callback != null) {
                        callback.onSourceChanged(idx);
                        callback.onSettingChanged();
                    }
                });
            }
        });

        // 音轨选择 (二级菜单)
        if (btnAudioTrack != null) {
            btnAudioTrack.setOnClickListener(v -> {
                if (playerManager == null) {
                    Toast.makeText(getContext(), "播放器未就绪", Toast.LENGTH_SHORT).show();
                    return;
                }
                List<VideoPlayerManager.AudioTrackInfo> tracks = playerManager.getAudioTracks();
                if (tracks == null || tracks.isEmpty()) {
                    String[] defaultItem = new String[]{"默认音轨 (自动)"};
                    OptionSelectionDialog.show(getContext(), "音轨选择", defaultItem, 0, (idx, text) -> {
                        // Keep default
                    });
                    return;
                }

                String[] items = new String[tracks.size()];
                int curIdx = 0;
                for (int i = 0; i < tracks.size(); i++) {
                    items[i] = tracks.get(i).name;
                    if (tracks.get(i).isSelected) {
                        curIdx = i;
                    }
                }

                OptionSelectionDialog.show(getContext(), "音轨选择", items, curIdx, (idx, text) -> {
                    playerManager.selectAudioTrack(tracks.get(idx));
                    updateAudioTrackLabel(btnAudioTrack);
                    if (callback != null) callback.onSettingChanged();
                });
            });
        }

        // 字幕选择 (二级菜单)
        if (btnSubtitle != null) {
            btnSubtitle.setOnClickListener(v -> {
                if (playerManager == null) {
                    Toast.makeText(getContext(), "播放器未就绪", Toast.LENGTH_SHORT).show();
                    return;
                }
                List<VideoPlayerManager.SubtitleTrackInfo> tracks = playerManager.getSubtitleTracks();
                if (tracks == null || tracks.isEmpty()) {
                    String[] defaultItem = new String[]{"无可用字幕 (关闭)"};
                    OptionSelectionDialog.show(getContext(), "字幕选择", defaultItem, 0, (idx, text) -> {
                        playerManager.disableSubtitles();
                        updateSubtitleLabel(btnSubtitle);
                    });
                    return;
                }

                String[] items = new String[tracks.size() + 1];
                items[0] = "关闭字幕";
                int curIdx = 0;
                for (int i = 0; i < tracks.size(); i++) {
                    items[i + 1] = tracks.get(i).name;
                    if (tracks.get(i).isSelected && playerManager.isSubtitleEnabled()) {
                        curIdx = i + 1;
                    }
                }

                OptionSelectionDialog.show(getContext(), "字幕选择", items, curIdx, (idx, text) -> {
                    if (idx == 0) {
                        playerManager.disableSubtitles();
                        Toast.makeText(getContext(), "已关闭字幕", Toast.LENGTH_SHORT).show();
                    } else {
                        VideoPlayerManager.SubtitleTrackInfo target = tracks.get(idx - 1);
                        playerManager.selectSubtitleTrack(target);
                        Toast.makeText(getContext(), "已选择: " + target.name, Toast.LENGTH_SHORT).show();
                    }
                    updateSubtitleLabel(btnSubtitle);
                    if (callback != null) callback.onSettingChanged();
                });
            });
        }

        if (btnLogoMode != null) {
            btnLogoMode.setOnClickListener(v -> {
                int mode = (settings.getLogoMode() + 1) % 3;
                settings.setLogoMode(mode);
                updateLogoModeLabel(btnLogoMode);
                if (callback != null) callback.onSettingChanged();
            });
        }

        if (btnHideBottomLogo != null) {
            btnHideBottomLogo.setOnClickListener(v -> {
                boolean hide = !settings.isHideBottomLogo();
                settings.setHideBottomLogo(hide);
                updateHideBottomLogoLabel(btnHideBottomLogo);
                if (callback != null) callback.onSettingChanged();
            });
        }

        if (btnOkAction != null) {
            btnOkAction.setOnClickListener(v -> {
                int cur = settings.getOkKeyAction();
                OptionSelectionDialog.show(getContext(), "OK键功能", SettingsRepository.OK_ACTION_OPTIONS, cur, (idx, text) -> {
                    settings.setOkKeyAction(idx);
                    updateOkActionLabel(btnOkAction);
                    if (callback != null) callback.onSettingChanged();
                });
            });
        }

        btnReverse.setOnClickListener(v -> {
            boolean r = !settings.isReverseSwitch();
            settings.setReverseSwitch(r);
            updateReverseLabel(btnReverse);
            if (callback != null) callback.onSettingChanged();
        });

        btnBoot.setOnClickListener(v -> {
            boolean b = !settings.isBootStart();
            settings.setBootStart(b);
            updateBootLabel(btnBoot);
        });

        btnSpeed.setOnClickListener(v -> {
            boolean s = !settings.isShowSpeed();
            settings.setShowSpeed(s);
            updateSpeedLabel(btnSpeed);
            if (callback != null) callback.onSettingChanged();
        });

        btnTime.setOnClickListener(v -> {
            boolean t = !settings.isShowTime();
            settings.setShowTime(t);
            updateTimeLabel(btnTime);
            if (callback != null) callback.onSettingChanged();
        });

        // 接口设置 二级菜单
        if (btnInterface != null) {
            btnInterface.setOnClickListener(v -> {
                final String[] options = new String[]{"节目地址", "EPG接口", "启动背景", "UserAgent设置", "外部台标库"};
                OptionSelectionDialog.show(getContext(), "接口设置", options, -1, (idx, text) -> {
                    switch (idx) {
                        case 0:
                            CustomUrlDialog.show(getContext(), "设置节目地址", settings.getCustomChannelUrl(), url -> {
                                settings.setCustomChannelUrl(url);
                                if (callback != null) callback.onReloadChannels();
                            });
                            break;
                        case 1:
                            CustomUrlDialog.show(getContext(), "设置EPG接口地址", settings.getCustomEpgUrl(), url -> {
                                settings.setCustomEpgUrl(url);
                                if (callback != null) callback.onSettingChanged();
                            });
                            break;
                        case 2:
                            CustomUrlDialog.show(getContext(), "设置启动背景图片地址", settings.getSplashImgUrl(), url -> {
                                settings.setSplashImgUrl(url);
                                if (callback != null) callback.onSettingChanged();
                            });
                            break;
                        case 3:
                            CustomUrlDialog.show(getContext(), "UserAgent设置 (留空默认)", settings.getUserAgent(), ua -> {
                                settings.setUserAgent(ua);
                                if (playerManager != null) {
                                    playerManager.reloadCurrentStream();
                                }
                                if (callback != null) callback.onSettingChanged();
                            });
                            break;
                        case 4:
                            CustomUrlDialog.show(getContext(), "设置外部台标库地址", settings.getCustomLogoUrl(), url -> {
                                settings.setCustomLogoUrl(url);
                                if (callback != null) callback.onSettingChanged();
                            });
                            break;
                    }
                });
            });
        }

        btnReset.setOnClickListener(v -> {
            settings.setCustomChannelUrl(SettingsRepository.DEFAULT_CHANNEL_URL);
            settings.setCustomEpgUrl(SettingsRepository.DEFAULT_EPG_URL);
            settings.setSplashImgUrl(SettingsRepository.DEFAULT_SPLASH_URL);
            settings.setUserAgent(SettingsRepository.DEFAULT_USER_AGENT);
            settings.setDecoderMode(0);
            settings.setAudioDecoderMode(0);
            settings.setScreenScale(0);
            settings.setTimeoutSeconds(5);
            settings.setDisconnectControlIndex(0);
            settings.setRememberPlay(true);
            settings.setRememberSource(true);
            settings.setOkKeyAction(0);
            settings.setLogoMode(0);
            settings.setHideBottomLogo(false);
            settings.setCustomLogoUrl("");
            settings.setReverseSwitch(false);
            settings.setShowSpeed(true);
            settings.setShowTime(true);
            dismiss();
            if (playerManager != null) {
                playerManager.reinitPlayer();
            }
            if (callback != null) callback.onReloadChannels();
        });

    }

    private void switchToTab(int targetTab, boolean focusFirstItem) {
        if (tabs == null || targetTab < 0 || targetTab >= tabs.length) return;
        if (targetTab == currentTabIndex) return;

        isProgrammaticTabSwitch = true;
        currentTabIndex = targetTab;

        // 1. 先将目标分类面板设为可见，使其子控件具备获焦前提
        if (panels != null && targetTab < panels.length && panels[targetTab] != null) {
            panels[targetTab].setVisibility(View.VISIBLE);
        }

        // 2. 安全转移焦点：在旧面板仍未被隐藏前，立即同步将焦点赋予新分类的第一个子菜单项
        if (focusFirstItem && firstSubmenuItems != null && targetTab < firstSubmenuItems.length) {
            View targetView = firstSubmenuItems[targetTab];
            if (targetView != null) {
                targetView.requestFocus();
            }
        }

        // 3. 焦点已安全落入新容器，此时隐藏其他所有分类面板，系统绝不会触发丢焦与向上回落寻焦
        if (panels != null) {
            for (int i = 0; i < panels.length; i++) {
                if (panels[i] != null && i != targetTab) {
                    panels[i].setVisibility(View.GONE);
                }
            }
        }

        // 4. 更新 Tab 胶囊的高亮选中状态
        if (tabs != null) {
            for (int i = 0; i < tabs.length; i++) {
                if (tabs[i] != null) {
                    boolean isSelected = (i == currentTabIndex);
                    tabs[i].setSelected(isSelected);
                    tabs[i].setTextColor(isSelected ? 0xFF00E5FF : 0xCCFFFFFF);
                }
            }
        }

        // 5. 自动平滑滚动顶部分类栏，使当前选中的分类胶囊完整且居中可见
        scrollToCurrentTab(targetTab);

        // 6. 转移完成后解除临时保护标志
        if (tabs[targetTab] != null) {
            tabs[targetTab].post(() -> isProgrammaticTabSwitch = false);
        } else {
            isProgrammaticTabSwitch = false;
        }
    }

    private void scrollToCurrentTab(int tabIndex) {
        if (scrollTabs == null || tabs == null || tabIndex < 0 || tabIndex >= tabs.length) return;
        Button targetTab = tabs[tabIndex];
        if (targetTab == null) return;
        targetTab.post(() -> {
            int targetLeft = targetTab.getLeft();
            int targetWidth = targetTab.getWidth();
            int scrollWidth = scrollTabs.getWidth();
            int scrollX = targetLeft - (scrollWidth - targetWidth) / 2;
            scrollTabs.smoothScrollTo(Math.max(0, scrollX), 0);
        });
    }

    @Override
    public boolean dispatchKeyEvent(@NonNull KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            View currentFocus = getCurrentFocus();
            boolean isFocusOnTabs = false;
            if (tabs != null) {
                for (Button tab : tabs) {
                    if (tab != null && tab == currentFocus) {
                        isFocusOnTabs = true;
                        break;
                    }
                }
            }
            if (!isFocusOnTabs && tabs != null && tabs.length > 0) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    int targetTab;
                    if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        targetTab = (currentTabIndex + 1) % tabs.length;
                    } else {
                        targetTab = (currentTabIndex - 1 + tabs.length) % tabs.length;
                    }
                    switchToTab(targetTab, true);
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void updateDecoderLabel(Button b) {
        if (b == null) return;
        int mode = settings.getDecoderMode();
        if (mode < 0 || mode >= SettingsRepository.DECODER_OPTIONS.length) mode = 0;
        b.setText("视频解码: " + SettingsRepository.DECODER_OPTIONS[mode]);
    }

    private void updateAudioDecoderLabel(Button b) {
        if (b == null) return;
        int mode = settings.getAudioDecoderMode();
        if (mode < 0 || mode >= SettingsRepository.AUDIO_DECODER_OPTIONS.length) mode = 0;
        b.setText("音频解码: " + SettingsRepository.AUDIO_DECODER_OPTIONS[mode]);
    }

    private void updateScaleLabel(Button b) {
        if (b == null) return;
        int scale = settings.getScreenScale();
        if (scale < 0 || scale >= SettingsRepository.SCALE_OPTIONS.length) scale = 0;
        b.setText("画面比例: " + SettingsRepository.SCALE_OPTIONS[scale]);
    }

    private void updateTimeoutLabel(Button b) {
        if (b == null) return;
        int t = settings.getTimeoutSeconds();
        b.setText("超时换源: " + (t == 0 ? "关闭" : t + "秒"));
    }

    private void updateDisconnectLabel(Button b) {
        if (b == null) return;
        int idx = settings.getDisconnectControlIndex();
        if (idx < 0 || idx >= SettingsRepository.DISCONNECT_OPTIONS.length) idx = 0;
        b.setText("断流控制: " + SettingsRepository.DISCONNECT_OPTIONS[idx]);
    }

    private void updateSpeedRateLabel(Button b) {
        if (b == null) return;
        b.setText(String.format("播放倍速: %.2fx", currentSpeed));
    }

    private void updateRememberPlayLabel(Button b) {
        if (b == null) return;
        b.setText("记忆播放: " + (settings.isRememberPlay() ? "开启 (记忆上次频道)" : "关闭 (从首台起播)"));
    }

    private void updateRememberSourceLabel(Button b) {
        if (b == null) return;
        b.setText("记忆多源: " + (settings.isRememberSource() ? "开启 (记忆各频道线路)" : "关闭 (默认使用第一条)"));
    }

    private void updatePreloadLabel(Button b) {
        if (b == null) return;
        b.setText("极速秒切: " + (settings.isPreloadEnabled() ? "开启 (智能预热上下台)" : "关闭 (按键常规加载)"));
    }

    private void updateSourceLabel(Button b) {
        if (b == null) return;
        if (currentChannel != null) {
            b.setText(String.format("当前线路: %s (%d/%d 点击选择)",
                    currentChannel.getCurrentSourcePureName(),
                    currentChannel.getCurrentUrlIndex() + 1,
                    currentChannel.getUrlCount()));
        } else {
            b.setText("当前线路: 无");
        }
    }

    private void updateAudioTrackLabel(Button b) {
        if (b == null) return;
        if (playerManager == null) {
            b.setText("音轨选择: 默认音轨");
            return;
        }

        List<VideoPlayerManager.AudioTrackInfo> tracks = playerManager.getAudioTracks();
        if (tracks == null || tracks.isEmpty()) {
            b.setText("音轨选择: 默认音轨");
            return;
        }

        VideoPlayerManager.AudioTrackInfo selected = null;
        for (VideoPlayerManager.AudioTrackInfo t : tracks) {
            if (t.isSelected) {
                selected = t;
                break;
            }
        }

        if (selected != null) {
            b.setText("音轨选择: " + selected.name);
        } else {
            b.setText("音轨选择: " + tracks.get(0).name);
        }
    }

    private void updateSubtitleLabel(Button b) {
        if (b == null) return;
        if (playerManager == null || !playerManager.isSubtitleEnabled()) {
            b.setText("字幕选择: 关闭");
            return;
        }

        List<VideoPlayerManager.SubtitleTrackInfo> tracks = playerManager.getSubtitleTracks();
        if (tracks == null || tracks.isEmpty()) {
            b.setText("字幕选择: 无可用字幕");
            return;
        }

        VideoPlayerManager.SubtitleTrackInfo selected = null;
        for (VideoPlayerManager.SubtitleTrackInfo t : tracks) {
            if (t.isSelected) {
                selected = t;
                break;
            }
        }

        if (selected != null) {
            b.setText("字幕选择: " + selected.name);
        } else {
            b.setText("字幕选择: 开启");
        }
    }

    private void updateLogoModeLabel(Button b) {
        if (b == null) return;
        String[] modes = {"显示所有台标", "仅显示已有台标", "不显示台标"};
        int mode = settings.getLogoMode();
        if (mode < 0 || mode >= modes.length) mode = 0;
        b.setText("台标设置: " + modes[mode]);
    }

    private void updateHideBottomLogoLabel(Button b) {
        if (b == null) return;
        b.setText("底栏台标: " + (settings.isHideBottomLogo() ? "隐藏" : "开启显示"));
    }

    private void updateOkActionLabel(Button b) {
        if (b == null) return;
        int action = settings.getOkKeyAction();
        String actStr = (action >= 0 && action < SettingsRepository.OK_ACTION_OPTIONS.length)
                ? SettingsRepository.OK_ACTION_OPTIONS[action] : "打开列表";
        b.setText("OK键功能: " + actStr);
    }

    private void updateReverseLabel(Button b) {
        b.setText("换台方向: " + (settings.isReverseSwitch() ? "反转 (上减下加)" : "正常 (上加下减)"));
    }

    private void updateBootLabel(Button b) {
        b.setText("开机自启: " + (settings.isBootStart() ? "开启" : "关闭"));
    }

    private void updateSpeedLabel(Button b) {
        b.setText("显示网速: " + (settings.isShowSpeed() ? "开启" : "关闭"));
    }

    private void updateTimeLabel(Button b) {
        b.setText("显示时间: " + (settings.isShowTime() ? "开启" : "关闭"));
    }
}