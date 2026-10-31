package com.diyp.next.data.repository;

import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import com.diyp.next.data.model.Channel;
import com.diyp.next.data.model.EpgData;
import com.diyp.next.data.model.EpgItem;
import com.diyp.next.data.parser.EpgParser;
import com.diyp.next.util.BenchMarkManager;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class EpgRepository {
    private static final Pattern STRIP_BRACKETS_PATTERN =
            Pattern.compile("\\[[^\\]]*\\]|\\([^\\)]*\\)|\\【[^\\】]*\\】|\\（[^\\）]*\\）|\\{[^\\}]*\\}");
    // 15s connect, 20s read timeout (1:1 with DIYP original network tolerance)
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();

    private static final LruCache<String, EpgData> memoryCache = new LruCache<>(300);
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final ExecutorService executor = Executors.newFixedThreadPool(4);
    private static final Set<String> activeFetches = new HashSet<>();

    public interface EpgCallback {
        void onLoaded(Channel channel);
    }

    public interface EpgDateCallback {
        void onLoaded(Channel channel, String dateStr, List<EpgItem> epgList);
    }

    public static void queryEpg(Channel channel, EpgCallback callback) {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        queryEpgForDate(channel, today, (c, dateStr, list) -> {
            if (callback != null) callback.onLoaded(c);
        });
    }

    public static void queryEpgForDate(Channel channel, String dateStr, EpgDateCallback callback) {
        if (channel == null || channel.getName() == null || channel.getName().trim().isEmpty()) return;

        if (dateStr == null || dateStr.trim().isEmpty()) {
            dateStr = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        }
        final String finalDateStr = dateStr.trim();
        final String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        final String cacheKey = channel.getName().trim() + "_" + finalDateStr;

        // Check memory cache first
        EpgData cached = memoryCache.get(cacheKey);
        if (cached != null) {
            if (finalDateStr.equals(today)) {
                EpgParser.updateChannelEpgStatus(channel, cached);
            }
            if (callback != null) callback.onLoaded(channel, finalDateStr, cached.getEpgList());
            return;
        }

        // If channel already has loaded EPG list for today, reuse it
        if (finalDateStr.equals(today) && !channel.getFullEpgList().isEmpty()) {
            if (callback != null) callback.onLoaded(channel, finalDateStr, channel.getFullEpgList());
            return;
        }

        synchronized (activeFetches) {
            if (activeFetches.contains(cacheKey)) {
                return; // Already being fetched in background
            }
            activeFetches.add(cacheKey);
        }

        executor.execute(() -> {
            try {
                String baseUrl = channel.getCustomEpgUrl();
                if (baseUrl == null || baseUrl.trim().isEmpty()) {
                    baseUrl = SettingsRepository.get().getCustomEpgUrl();
                }
                if (baseUrl == null || baseUrl.trim().isEmpty()) {
                    baseUrl = SettingsRepository.DEFAULT_EPG_URL;
                }

                if (baseUrl == null || baseUrl.trim().isEmpty()) {
                    mainHandler.post(() -> {
                        if (callback != null) callback.onLoaded(channel, finalDateStr, new ArrayList<>());
                    });
                    return;
                }

                EpgData resultData = null;

                // Priority 1: tvg-name (if provided in M3U)
                String tvgName = channel.getTvgName();
                if (tvgName != null && !tvgName.trim().isEmpty()) {
                    resultData = fetchEpg(baseUrl, tvgName.trim(), finalDateStr);
                }

                // Priority 2: Exact raw channel name (1:1 with DIYP original logic e.g. "CCTV-1 综合", "CCTV-1")
                if (resultData == null || resultData.getEpgList().isEmpty()) {
                    String rawName = channel.getName().trim();
                    if (!rawName.equalsIgnoreCase(tvgName)) {
                        resultData = fetchEpg(baseUrl, rawName, finalDateStr);
                    }
                }

                // Priority 3: Cleaned/normalized name without brackets e.g. "CCTV-1 [4K]" -> "CCTV-1"
                if (resultData == null || resultData.getEpgList().isEmpty()) {
                    String strippedName = STRIP_BRACKETS_PATTERN.matcher(channel.getName()).replaceAll("").trim();
                    if (!strippedName.isEmpty() && !strippedName.equalsIgnoreCase(channel.getName().trim()) && !strippedName.equalsIgnoreCase(tvgName)) {
                        resultData = fetchEpg(baseUrl, strippedName, finalDateStr);
                    }
                }

                // Priority 4: Cleaned benchmark alias (e.g. "cctv1")
                if (resultData == null || resultData.getEpgList().isEmpty()) {
                    String cleanName = BenchMarkManager.cleanName(channel.getName());
                    if (!cleanName.isEmpty() && !cleanName.equalsIgnoreCase(channel.getName().trim())) {
                        resultData = fetchEpg(baseUrl, cleanName, finalDateStr);
                    }
                }

                if (resultData != null && !resultData.getEpgList().isEmpty()) {
                    memoryCache.put(cacheKey, resultData);
                    EpgData finalData = resultData;
                    mainHandler.post(() -> {
                        if (finalDateStr.equals(today)) {
                            EpgParser.updateChannelEpgStatus(channel, finalData);
                        }
                        if (callback != null) callback.onLoaded(channel, finalDateStr, finalData.getEpgList());
                    });
                } else {
                    mainHandler.post(() -> {
                        if (callback != null) callback.onLoaded(channel, finalDateStr, new ArrayList<>());
                    });
                }
            } catch (Exception ignored) {
                mainHandler.post(() -> {
                    if (callback != null) callback.onLoaded(channel, finalDateStr, new ArrayList<>());
                });
            } finally {
                synchronized (activeFetches) {
                    activeFetches.remove(cacheKey);
                }
            }
        });
    }

    private static EpgData fetchEpg(String baseUrl, String queryName, String dateStr) {
        if (baseUrl == null || queryName == null || queryName.isEmpty()) return null;
        try {
            String encodedName = URLEncoder.encode(queryName, "UTF-8").replace("+", "%20");
            String separator = baseUrl.contains("?") ? "&" : "?";
            String finalUrl = baseUrl + separator + "ch=" + encodedName + "&date=" + dateStr;

            String ua = SettingsRepository.get() != null ? SettingsRepository.get().getUserAgent() : SettingsRepository.DEFAULT_USER_AGENT;
            Request request = new Request.Builder()
                    .url(finalUrl)
                    .header("User-Agent", (ua != null && !ua.isEmpty()) ? ua : SettingsRepository.DEFAULT_USER_AGENT)
                    .header("Accept", "*/*")
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String json = response.body().string();
                    EpgData data = EpgParser.parseJson(json);
                    if (data != null && !data.getEpgList().isEmpty()) {
                        return data;
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
