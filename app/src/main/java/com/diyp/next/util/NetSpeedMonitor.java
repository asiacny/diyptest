package com.diyp.next.util;

import android.net.TrafficStats;
import android.os.Handler;
import android.os.Looper;

public class NetSpeedMonitor {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastTotalRxBytes = 0;
    private long lastTimeStamp = 0;
    private boolean isRunning = false;
    private SpeedCallback callback;

    public interface SpeedCallback {
        void onSpeedUpdate(long kbPerSecond);
    }

    public void start(SpeedCallback callback) {
        this.callback = callback;
        this.isRunning = true;
        this.lastTotalRxBytes = TrafficStats.getTotalRxBytes();
        this.lastTimeStamp = System.currentTimeMillis();
        handler.removeCallbacks(tickRunnable);
        handler.postDelayed(tickRunnable, 1000);
    }

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;
            long nowRxBytes = TrafficStats.getTotalRxBytes();
            long nowTimeStamp = System.currentTimeMillis();

            long bytesDiff = nowRxBytes - lastTotalRxBytes;
            long timeDiff = nowTimeStamp - lastTimeStamp;

            if (timeDiff > 0 && bytesDiff >= 0) {
                long speedKb = (bytesDiff * 1000) / (timeDiff * 1024);
                if (callback != null) {
                    callback.onSpeedUpdate(speedKb);
                }
            }

            lastTotalRxBytes = nowRxBytes;
            lastTimeStamp = nowTimeStamp;
            if (isRunning) {
                handler.postDelayed(this, 1000);
            }
        }
    };

    public void stop() {
        this.isRunning = false;
        handler.removeCallbacks(tickRunnable);
    }
}
