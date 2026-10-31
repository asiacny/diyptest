package com.diyp.next.data.repository;

import android.content.Context;
import com.diyp.next.data.model.ChannelGroup;
import com.diyp.next.data.parser.ChannelParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ChannelRepository {
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build();

    public interface LoadCallback {
        void onSuccess(List<ChannelGroup> groups);
        void onError(Exception e);
    }

    public static void loadChannels(Context context, LoadCallback callback) {
        new Thread(() -> {
            try {
                String customUrl = SettingsRepository.get().getCustomChannelUrl();
                String content = null;

                if (customUrl != null && !customUrl.trim().isEmpty()) {
                    try {
                        Request request = new Request.Builder()
                                .url(customUrl.trim())
                                .header("User-Agent", "DIYP-Next/1.0")
                                .build();
                        try (Response response = client.newCall(request).execute()) {
                            if (response.isSuccessful() && response.body() != null) {
                                content = response.body().string();
                            }
                        }
                    } catch (Exception ignored) {}
                }

                // Fallback to assets
                if (content == null || content.trim().isEmpty()) {
                    content = readAssetFile(context, "default_channels.txt");
                }

                List<ChannelGroup> groups = ChannelParser.parse(content);
                if (groups.isEmpty()) {
                    callback.onError(new Exception("No channels parsed"));
                } else {
                    callback.onSuccess(groups);
                }
            } catch (Exception e) {
                callback.onError(e);
            }
        }).start();
    }

    private static String readAssetFile(Context context, String filename) {
        try (InputStream is = context.getAssets().open(filename);
             InputStreamReader reader = new InputStreamReader(is, "UTF-8");
             StringWriter writer = new StringWriter()) {
            char[] buffer = new char[8192];
            int n;
            while ((n = reader.read(buffer)) != -1) {
                writer.write(buffer, 0, n);
            }
            return writer.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
