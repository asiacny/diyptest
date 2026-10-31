package com.diyp.next.data.repository;

import android.content.Context;
import android.content.SharedPreferences;

public class SettingsRepository {
    private static final String PREF_NAME = "diyp_settings";
    private static SettingsRepository instance;
    private final SharedPreferences sp;

    // Keys
    public static final String KEY_DECODER_MODE = "decoder_mode";
    public static final String KEY_AUDIO_DECODER_MODE = "audio_decoder_mode";
    public static final String KEY_SCREEN_SCALE = "screen_scale";
    public static final String KEY_TIMEOUT_SECONDS = "timeout_seconds";
    public static final String KEY_REVERSE_SWITCH = "reverse_switch";
    public static final String KEY_BOOT_START = "boot_start";
    public static final String KEY_SHOW_SPEED = "show_speed";
    public static final String KEY_SHOW_TIME = "show_time";
    public static final String KEY_HIDE_CHANNEL_NUM = "hide_channel_num";
    public static final String KEY_EYE_PROTECT = "eye_protect";
    public static final String KEY_QUICK_EXIT = "quick_exit";
    public static final String KEY_CUSTOM_CHANNEL_URL = "custom_channel_url";
    public static final String KEY_CUSTOM_EPG_URL = "custom_epg_url";
    public static final String KEY_SPLASH_IMG_URL = "splash_img_url";
    public static final String KEY_CUSTOM_USER_AGENT = "custom_user_agent";
    public static final String DEFAULT_USER_AGENT = "webkit;Resolution(PAL,720P,1080P)";
    public static final String KEY_LOGO_MODE = "logo_mode"; // 0: 全部, 1: 已有, 2: 不显示
    public static final String KEY_HIDE_BOTTOM_LOGO = "hide_bottom_logo";
    public static final String KEY_CUSTOM_LOGO_URL = "custom_logo_url";
    public static final String KEY_LAST_GROUP_INDEX = "last_group_index";
    public static final String KEY_LAST_CHANNEL_INDEX = "last_channel_index";
    public static final String KEY_DISCONNECT_CONTROL = "disconnect_control";
    public static final String KEY_REMEMBER_PLAY = "remember_play";
    public static final String KEY_REMEMBER_SOURCE = "remember_source";
    public static final String KEY_SAVED_SOURCE_PREFIX = "saved_source_";
    public static final String KEY_PRELOAD_ENABLED = "preload_enabled";
    public static final String KEY_OK_KEY_ACTION = "ok_key_action"; // 0: 打开列表 (默认), 1: 显示信息条

    public static final String[] OK_ACTION_OPTIONS = {"打开列表", "显示信息条"};

    public static final String[] DISCONNECT_OPTIONS = {
        "关闭",
        "30K 5秒", "30K 10秒", "30K 15秒",
        "50K 5秒", "50K 10秒", "50K 15秒",
        "70K 5秒", "70K 10秒", "70K 15秒"
    };

    public static final String[] SCALE_OPTIONS = {
        "16:9 (标准宽屏)",
        "4:3 (标准方屏)",
        "原始比例 (自动适应)",
        "拉伸全屏 (铺满屏幕)"
    };

    public static final String[] TIMEOUT_OPTIONS = {
        "关闭",
        "3秒",
        "5秒",
        "10秒",
        "15秒",
        "20秒",
        "30秒"
    };
    public static final int[] TIMEOUT_VALUES = {0, 3, 5, 10, 15, 20, 30};

    public static final String[] DECODER_OPTIONS = {
        "智能硬解 (硬件优先)",
        "软解优先 (兼容性最好)",
        "系统原生 (Android 原生解码)"
    };

    public static final String[] AUDIO_DECODER_OPTIONS = {
        "智能解码 (硬件优先，自动软解)",
        "强制软解 (兼容性最佳)",
        "系统原生 (仅硬解/直通)"
    };

    // Defaults
    public static final String DEFAULT_CHANNEL_URL = "http://192.168.10.50/diyp/live.php?t=txt";
    public static final String DEFAULT_EPG_URL = "http://192.168.10.50/diyp/epg.php";
    public static final String DEFAULT_SPLASH_URL = "http://192.168.10.50/diyp/img.php";

    public static synchronized void init(Context context) {
        if (instance == null) {
            instance = new SettingsRepository(context.getApplicationContext());
        }
    }

    public static SettingsRepository get() {
        return instance;
    }

    private SettingsRepository(Context context) {
        this.sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public int getDecoderMode() {
        return sp.getInt(KEY_DECODER_MODE, 0); // 0: Auto/Hardware, 1: Soft, 2: Native
    }

    public void setDecoderMode(int mode) {
        sp.edit().putInt(KEY_DECODER_MODE, mode).apply();
    }

    public int getAudioDecoderMode() {
        return sp.getInt(KEY_AUDIO_DECODER_MODE, 0); // 0: Auto/Hardware (Default), 1: FFmpeg Soft, 2: Native
    }

    public void setAudioDecoderMode(int mode) {
        sp.edit().putInt(KEY_AUDIO_DECODER_MODE, mode).apply();
    }

    public int getScreenScale() {
        return sp.getInt(KEY_SCREEN_SCALE, 0); // 0: 16:9, 1: 4:3, 2: Original, 3: Stretch
    }

    public void setScreenScale(int scale) {
        sp.edit().putInt(KEY_SCREEN_SCALE, scale).apply();
    }

    public int getTimeoutSeconds() {
        return sp.getInt(KEY_TIMEOUT_SECONDS, 5); // Default 5 seconds
    }

    public void setTimeoutSeconds(int seconds) {
        sp.edit().putInt(KEY_TIMEOUT_SECONDS, seconds).apply();
    }

    public boolean isReverseSwitch() {
        return sp.getBoolean(KEY_REVERSE_SWITCH, false);
    }

    public void setReverseSwitch(boolean reverse) {
        sp.edit().putBoolean(KEY_REVERSE_SWITCH, reverse).apply();
    }

    public boolean isBootStart() {
        return sp.getBoolean(KEY_BOOT_START, false);
    }

    public void setBootStart(boolean boot) {
        sp.edit().putBoolean(KEY_BOOT_START, boot).apply();
    }

    public boolean isShowSpeed() {
        return sp.getBoolean(KEY_SHOW_SPEED, true);
    }

    public void setShowSpeed(boolean show) {
        sp.edit().putBoolean(KEY_SHOW_SPEED, show).apply();
    }

    public boolean isShowTime() {
        return sp.getBoolean(KEY_SHOW_TIME, true);
    }

    public void setShowTime(boolean show) {
        sp.edit().putBoolean(KEY_SHOW_TIME, show).apply();
    }

    public boolean isHideChannelNum() {
        return sp.getBoolean(KEY_HIDE_CHANNEL_NUM, false);
    }

    public void setHideChannelNum(boolean hide) {
        sp.edit().putBoolean(KEY_HIDE_CHANNEL_NUM, hide).apply();
    }

    public boolean isEyeProtect() {
        return sp.getBoolean(KEY_EYE_PROTECT, false);
    }

    public void setEyeProtect(boolean protect) {
        sp.edit().putBoolean(KEY_EYE_PROTECT, protect).apply();
    }

    public boolean isQuickExit() {
        return sp.getBoolean(KEY_QUICK_EXIT, false);
    }

    public void setQuickExit(boolean quick) {
        sp.edit().putBoolean(KEY_QUICK_EXIT, quick).apply();
    }

    public String getCustomChannelUrl() {
        String url = sp.getString(KEY_CUSTOM_CHANNEL_URL, DEFAULT_CHANNEL_URL);
        if (url == null || url.trim().isEmpty()) {
            return DEFAULT_CHANNEL_URL;
        }
        return url.trim();
    }

    public void setCustomChannelUrl(String url) {
        sp.edit().putString(KEY_CUSTOM_CHANNEL_URL, url != null ? url.trim() : "").apply();
    }

    public String getCustomEpgUrl() {
        String url = sp.getString(KEY_CUSTOM_EPG_URL, DEFAULT_EPG_URL);
        if (url == null || url.trim().isEmpty()) {
            return DEFAULT_EPG_URL;
        }
        return url.trim();
    }

    public void setCustomEpgUrl(String url) {
        sp.edit().putString(KEY_CUSTOM_EPG_URL, url != null ? url.trim() : "").apply();
    }

    public String getSplashImgUrl() {
        String url = sp.getString(KEY_SPLASH_IMG_URL, DEFAULT_SPLASH_URL);
        if (url == null || url.trim().isEmpty()) {
            return DEFAULT_SPLASH_URL;
        }
        return url.trim();
    }

    public void setSplashImgUrl(String url) {
        sp.edit().putString(KEY_SPLASH_IMG_URL, url != null ? url.trim() : "").apply();
    }

    public String getUserAgent() {
        String ua = sp.getString(KEY_CUSTOM_USER_AGENT, DEFAULT_USER_AGENT);
        if (ua == null || ua.trim().isEmpty() || "MTV".equalsIgnoreCase(ua.trim())) {
            return DEFAULT_USER_AGENT;
        }
        return ua.trim();
    }

    public void setUserAgent(String ua) {
        sp.edit().putString(KEY_CUSTOM_USER_AGENT, ua != null ? ua.trim() : "").apply();
    }

    public int getLogoMode() {
        return sp.getInt(KEY_LOGO_MODE, 0); // 0: 全部, 1: 仅已有, 2: 不显示
    }

    public void setLogoMode(int mode) {
        sp.edit().putInt(KEY_LOGO_MODE, mode).apply();
    }

    public boolean isHideBottomLogo() {
        return sp.getBoolean(KEY_HIDE_BOTTOM_LOGO, false);
    }

    public void setHideBottomLogo(boolean hide) {
        sp.edit().putBoolean(KEY_HIDE_BOTTOM_LOGO, hide).apply();
    }

    public String getCustomLogoUrl() {
        return sp.getString(KEY_CUSTOM_LOGO_URL, "");
    }

    public void setCustomLogoUrl(String url) {
        sp.edit().putString(KEY_CUSTOM_LOGO_URL, url != null ? url.trim() : "").apply();
    }

    public int getLastGroupIndex() {
        return sp.getInt(KEY_LAST_GROUP_INDEX, 0);
    }

    public void setLastGroupIndex(int index) {
        sp.edit().putInt(KEY_LAST_GROUP_INDEX, index).apply();
    }

    public int getLastChannelIndex() {
        return sp.getInt(KEY_LAST_CHANNEL_INDEX, 0);
    }

    public void setLastChannelIndex(int index) {
        sp.edit().putInt(KEY_LAST_CHANNEL_INDEX, index).apply();
    }

    public int getDisconnectControlIndex() {
        return sp.getInt(KEY_DISCONNECT_CONTROL, 0); // Default 0: 关闭
    }

    public void setDisconnectControlIndex(int index) {
        sp.edit().putInt(KEY_DISCONNECT_CONTROL, index).apply();
    }

    public int getDisconnectThresholdKb() {
        int idx = getDisconnectControlIndex();
        if (idx >= 1 && idx <= 3) return 30;
        if (idx >= 4 && idx <= 6) return 50;
        if (idx >= 7 && idx <= 9) return 70;
        return 0;
    }

    public int getDisconnectThresholdSeconds() {
        int idx = getDisconnectControlIndex();
        if (idx == 1 || idx == 4 || idx == 7) return 5;
        if (idx == 2 || idx == 5 || idx == 8) return 10;
        if (idx == 3 || idx == 6 || idx == 9) return 15;
        return 0;
    }

    public boolean isRememberPlay() {
        return sp.getBoolean(KEY_REMEMBER_PLAY, true);
    }

    public void setRememberPlay(boolean remember) {
        sp.edit().putBoolean(KEY_REMEMBER_PLAY, remember).apply();
    }

    public boolean isRememberSource() {
        return sp.getBoolean(KEY_REMEMBER_SOURCE, true);
    }

    public void setRememberSource(boolean remember) {
        sp.edit().putBoolean(KEY_REMEMBER_SOURCE, remember).apply();
    }

    public int getSavedSourceIndex(String channelName) {
        if (channelName == null || !isRememberSource()) return 0;
        return sp.getInt(KEY_SAVED_SOURCE_PREFIX + channelName.trim(), 0);
    }

    public void saveSourceIndex(String channelName, int index) {
        if (channelName != null && isRememberSource()) {
            sp.edit().putInt(KEY_SAVED_SOURCE_PREFIX + channelName.trim(), index).apply();
        }
    }

    public boolean isPreloadEnabled() {
        return sp.getBoolean(KEY_PRELOAD_ENABLED, true);
    }

    public void setPreloadEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_PRELOAD_ENABLED, enabled).apply();
    }

    public int getOkKeyAction() {
        return sp.getInt(KEY_OK_KEY_ACTION, 0); // 默认 0: 打开列表
    }

    public void setOkKeyAction(int action) {
        sp.edit().putInt(KEY_OK_KEY_ACTION, action).apply();
    }
}
