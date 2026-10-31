package com.diyp.next.util;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;
import com.bumptech.glide.Glide;
import com.diyp.next.data.repository.SettingsRepository;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * High-performance Channel Logo Manager (1:1 with DIYP BenchMark system)
 * Uses O(1) in-memory alias mapping and LruCache for instant sub-millisecond logo resolution.
 */
public class BenchMarkManager {
    private static final Pattern BRACKETS_PATTERN =
            Pattern.compile("\\[[^\\]]*\\]|\\([^\\)]*\\)|\\【[^\\】]*\\】|\\（[^\\）]*\\）|\\{[^\\}]*\\}");
    private static final Pattern LEADING_NUMS_PATTERN =
            Pattern.compile("^[0-9]+[\\.\\s\\-_、]*");
    private static final Pattern SPACES_PATTERN =
            Pattern.compile("\\s+");

    // In-memory mapping: normalized alias/name -> logo asset filename (e.g., "cctv1" -> "CCTV1.png")
    private static final Map<String, String> aliasToLogoMap = new HashMap<>(3500);
    private static final LruCache<String, Bitmap> bitmapCache = new LruCache<>(200);
    private static boolean isInitialized = false;

    public static synchronized void ensureInit(Context context) {
        if (!isInitialized && context != null) {
            init(context.getApplicationContext());
        }
    }

    public static synchronized void init(Context context) {
        if (isInitialized || context == null) return;
        try {
            AssetManager assets = context.getAssets();
            try (InputStream is = assets.open("BenchMark/BenchMark.txt");
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || !line.contains("#")) continue;

                    String[] parts = line.split("#", 2);
                    String target = parts[0].trim();
                    if (target.isEmpty()) continue;

                    String fileName = target.endsWith(".png") ? target : target + ".png";
                    String targetClean = cleanName(target);
                    if (!targetClean.isEmpty()) {
                        aliasToLogoMap.put(targetClean, fileName);
                    }

                    if (parts.length > 1) {
                        String[] aliases = parts[1].split(",");
                        for (String alias : aliases) {
                            String aliasClean = cleanName(alias.trim());
                            if (!aliasClean.isEmpty()) {
                                aliasToLogoMap.put(aliasClean, fileName);
                            }
                        }
                    }
                }
            }
            isInitialized = true;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * O(1) Instant logo retrieval by channel name
     */
    public static Bitmap getLogo(Context context, String channelName) {
        if (context == null || channelName == null || channelName.trim().isEmpty()) return null;
        ensureInit(context);

        String clean = cleanName(channelName);
        String fileName = aliasToLogoMap.get(clean);
        if (fileName == null || fileName.isEmpty()) {
            return null;
        }

        Bitmap cached = bitmapCache.get(fileName);
        if (cached != null) return cached;

        try (InputStream is = context.getAssets().open("BenchMark/" + fileName)) {
            Bitmap bm = BitmapFactory.decodeStream(is);
            if (bm != null) {
                bitmapCache.put(fileName, bm);
                return bm;
            }
        } catch (Exception ignored) {}

        return null;
    }

    public static String getLogoUrl(String channelName) {
        if (channelName == null || channelName.trim().isEmpty()) return null;
        SettingsRepository settings = SettingsRepository.get();
        if (settings != null && settings.getCustomLogoUrl() != null && !settings.getCustomLogoUrl().trim().isEmpty()) {
            String baseUrl = settings.getCustomLogoUrl().trim();
            if (!baseUrl.endsWith("/")) baseUrl += "/";
            return baseUrl + channelName.trim() + ".png";
        }
        return null;
    }

    /**
     * Bind channel logo: Local BenchMark Asset first -> M3U custom URL next -> GONE
     */
    public static void loadLogo(ImageView imageView, String channelName, String customLogoUrl) {
        if (imageView == null) return;
        Context context = imageView.getContext();
        if (context == null) return;
        if (context instanceof android.app.Activity) {
            android.app.Activity act = (android.app.Activity) context;
            if (act.isFinishing() || act.isDestroyed()) return;
        }

        SettingsRepository settings = SettingsRepository.get();
        int logoMode = (settings != null) ? settings.getLogoMode() : 0; // 0: 全部显示, 1: 仅已有, 2: 不显示

        if (logoMode == 2) {
            imageView.setImageDrawable(null);
            imageView.setVisibility(View.GONE);
            return;
        }

        // 1. Primary: Local asset BenchMark logo (High-performance 0ms O(1) resolution)
        Bitmap bm = getLogo(context, channelName);
        if (bm != null) {
            imageView.setImageBitmap(bm);
            imageView.setVisibility(View.VISIBLE);
            return;
        }

        // 2. Secondary: Explicit Custom URL from M3U tvg-logo
        if (customLogoUrl != null && !customLogoUrl.trim().isEmpty() &&
                (customLogoUrl.startsWith("http://") || customLogoUrl.startsWith("https://"))) {
            imageView.setVisibility(View.VISIBLE);
            Glide.with(context)
                    .load(customLogoUrl.trim())
                    .into(imageView);
            return;
        }

        // 3. Optional: Custom external logo server from Settings
        if (settings != null && settings.getCustomLogoUrl() != null && !settings.getCustomLogoUrl().trim().isEmpty()) {
            String baseUrl = settings.getCustomLogoUrl().trim();
            if (!baseUrl.endsWith("/")) baseUrl += "/";
            String fullLogoUrl = baseUrl + channelName.trim() + ".png";
            imageView.setVisibility(View.VISIBLE);
            Glide.with(context)
                    .load(fullLogoUrl)
                    .into(imageView);
            return;
        }

        // 4. Fallback: No logo found, hide view cleanly without any Canvas/avatar overhead
        imageView.setImageDrawable(null);
        imageView.setVisibility(View.GONE);
    }

    /**
     * Comprehensive fast channel normalizer (strips brackets, ISP, resolution, fps tags)
     */
    public static String cleanName(String name) {
        if (name == null) return "";
        String s = BRACKETS_PATTERN.matcher(name).replaceAll("");
        s = LEADING_NUMS_PATTERN.matcher(s).replaceAll("");
        s = SPACES_PATTERN.matcher(s.toLowerCase(Locale.ROOT)).replaceAll("");
        return s
                .replace("-", "")
                .replace("_", "")
                .replace("高清", "")
                .replace("超清", "")
                .replace("标清", "")
                .replace("超高清", "")
                .replace("4k", "")
                .replace("8k", "")
                .replace("1080p", "")
                .replace("720p", "")
                .replace("50fps", "")
                .replace("60fps", "")
                .replace("hd", "")
                .replace("fhd", "")
                .replace("uhd", "")
                .replace("频道", "")
                .replace("台", "")
                .replace("电信", "")
                .replace("移动", "")
                .replace("联通", "")
                .replace("广电", "")
                .replace("ipv6", "")
                .replace("ipv4", "")
                .replace("回看", "")
                .replace("时移", "")
                .replace("单播", "")
                .replace("组播", "")
                .replace("示例", "");
    }
}
