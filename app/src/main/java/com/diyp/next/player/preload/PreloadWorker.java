package com.diyp.next.player.preload;

import android.util.Log;
import com.diyp.next.data.repository.SettingsRepository;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/**
 * Background network worker for a single preloaded stream.
 * Connects via HTTP, verifies headers (Gate 2 fuse), and pipes TS bytes into GopRingBuffer.
 */
public class PreloadWorker implements Runnable {
    private static final String TAG = "PreloadWorker";

    private final String channelName;
    private final int sourceIndex;
    private final String streamUrl;
    private final Map<String, String> headers;
    private final GopRingBuffer ringBuffer;
    private final long ringGeneration;

    private volatile boolean isRunning = true;
    private volatile boolean isAborted = false;
    private volatile boolean isConnected = false;

    private HttpURLConnection connection;
    private InputStream inputStream;
    private Thread workerThread;
    private boolean bufferReleased = false;

    public PreloadWorker(String channelName, int sourceIndex, String streamUrl, Map<String, String> headers) {
        this.channelName = channelName;
        this.sourceIndex = sourceIndex;
        this.streamUrl = streamUrl;
        this.headers = headers;
        this.ringBuffer = GopRingBuffer.obtain();
        this.ringGeneration = ringBuffer.getGeneration();
    }

    public synchronized void start() {
        if (workerThread == null) {
            workerThread = new Thread(this, "PreloadWorker-" + (channelName != null ? channelName : "Stream"));
            workerThread.setDaemon(true);
            workerThread.start();
        }
    }

    @Override
    public void run() {
        if (!PreloadFilter.isPreloadEligible(streamUrl)) {
            Log.w(TAG, "Preload aborted: URL not eligible under whitelist/filter: " + streamUrl);
            isAborted = true;
            return;
        }

        try {
            URL url = new URL(streamUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(true);

            // Apply request headers
            if (headers != null && !headers.isEmpty()) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }
            if (headers == null || (!headers.containsKey("User-Agent") && !headers.containsKey("user-agent"))) {
                String ua = SettingsRepository.get() != null ? SettingsRepository.get().getUserAgent() : SettingsRepository.DEFAULT_USER_AGENT;
                connection.setRequestProperty("User-Agent", (ua != null && !ua.isEmpty()) ? ua : SettingsRepository.DEFAULT_USER_AGENT);
            }

            connection.connect();
            int code = connection.getResponseCode();

            // Dynamic Response Inspection (Gate 2 Fuse)
            String contentType = connection.getContentType();
            String acceptRanges = connection.getHeaderField("Accept-Ranges");
            long contentLength = connection.getContentLengthLong();

            if (PreloadFilter.isStaticOrVodResponse(code, contentType, acceptRanges, contentLength)) {
                Log.w(TAG, "Preload aborted: dynamic response identified as static/VOD (code=" + code + ", ct=" + contentType + ", len=" + contentLength + ")");
                isAborted = true;
                disconnectSilently();
                return;
            }

            inputStream = new BufferedInputStream(connection.getInputStream(), 32768);
            isConnected = true;
            Log.i(TAG, "Preload connected for channel: " + channelName + ", url=" + streamUrl);

            byte[] buf = new byte[8192];
            while (isRunning && !isAborted) {
                int read = inputStream.read(buf, 0, buf.length);
                if (read < 0) {
                    break; // Stream ended
                }
                ringBuffer.write(buf, 0, read, ringGeneration);
            }
        } catch (Exception e) {
            if (isRunning) {
                Log.w(TAG, "Preload worker stopped on exception for " + channelName + ": " + e.getMessage());
            }
        } finally {
            disconnectSilently();
            freezeRingBuffer();
        }
    }

    public synchronized void stop() {
        isRunning = false;
        disconnectSilently();
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
        releaseRingBuffer();
    }

    private synchronized void freezeRingBuffer() {
        if (!bufferReleased) {
            ringBuffer.close();
        }
    }

    private synchronized void releaseRingBuffer() {
        if (bufferReleased) {
            return;
        }
        bufferReleased = true;
        ringBuffer.release();
    }

    private void disconnectSilently() {
        try {
            if (inputStream != null) {
                inputStream.close();
                inputStream = null;
            }
        } catch (Exception ignored) {}

        try {
            if (connection != null) {
                connection.disconnect();
                connection = null;
            }
        } catch (Exception ignored) {}
    }

    public String getStreamUrl() {
        return streamUrl;
    }

    public String getChannelName() {
        return channelName;
    }

    public int getSourceIndex() {
        return sourceIndex;
    }

    public GopRingBuffer getRingBuffer() {
        return ringBuffer;
    }

    public boolean isReady() {
        return isConnected && !isAborted && ringBuffer.hasKeyframe()
                && ringBuffer.isOwnedBy(ringGeneration);
    }

    public boolean isUsable() {
        return isRunning && isReady();
    }

    public boolean isAborted() {
        return isAborted;
    }
}
