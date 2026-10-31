package com.diyp.next.player.preload;

import android.net.Uri;
import java.util.Locale;

/**
 * Preload security and eligibility filter.
 * Strict dual-gate enforcement:
 * Gate 1: Pre-connection whitelist filtering (Host must match 192.168.*.* or 3.3.3.3, otherwise 0 network traffic).
 * Gate 2: Timeshift and VOD abort/fuse (URL keywords or static VOD response headers terminate preload immediately).
 */
public class PreloadFilter {

    /**
     * Gate 1: Check if host matches 192.168.*.* or 3.3.3.3.
     * URLs not matching these two IP ranges MUST NOT send any DNS, TCP, or HTTP request.
     */
    public static boolean isWhitelistHost(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }
        try {
            Uri uri = Uri.parse(url.trim());
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            host = host.trim();
            if (host.equalsIgnoreCase("3.3.3.3")) {
                return true;
            }
            if (host.startsWith("192.168.")) {
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * Gate 2 (URL Inspection): Check if stream is timeshift (TVOD) or static VOD video.
     */
    public static boolean isTimeshiftOrVodUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return true;
        }
        String lower = url.trim().toLowerCase(Locale.ROOT);

        // Timeshift markers
        if (lower.contains("playseek=") ||
            lower.contains("starttime=") ||
            lower.contains("/tvod/") ||
            lower.contains("/lookback/") ||
            lower.contains("catchup=") ||
            lower.contains("timeshift=")) {
            return true;
        }

        // VOD markers
        if (lower.endsWith("#vod") || lower.contains("#vod")) {
            return true;
        }

        // Extract path before query parameters for file extension inspection
        String path = lower;
        int queryIdx = path.indexOf('?');
        if (queryIdx != -1) {
            path = path.substring(0, queryIdx);
        }
        int hashIdx = path.indexOf('#');
        if (hashIdx != -1) {
            path = path.substring(0, hashIdx);
        }

        if (path.endsWith(".mp4") ||
            path.endsWith(".mkv") ||
            path.endsWith(".flv") ||
            path.endsWith(".avi") ||
            path.endsWith(".mov") ||
            path.endsWith(".wmv") ||
            path.endsWith(".rmvb") ||
            path.endsWith(".webm") ||
            path.endsWith(".m2ts") ||
            path.endsWith(".mts")) {
            return true;
        }

        return false;
    }

    /**
     * Primary eligibility check before opening any network socket.
     */
    public static boolean isPreloadEligible(String url) {
        return isWhitelistHost(url) && !isTimeshiftOrVodUrl(url);
    }

    /**
     * Gate 2 (Dynamic Response Inspection): Check if HTTP response indicates static VOD file or invalid stream.
     */
    public static boolean isStaticOrVodResponse(int responseCode, String contentType, String acceptRanges, long contentLength) {
        if (responseCode != 200 && responseCode != 206) {
            return true; // Not a valid streaming response
        }

        if (contentType != null) {
            String ct = contentType.toLowerCase(Locale.ROOT);
            if (ct.contains("video/mp4") ||
                ct.contains("video/x-matroska") ||
                ct.contains("video/quicktime") ||
                ct.contains("video/x-msvideo") ||
                ct.contains("video/x-flv")) {
                return true;
            }
        }

        // Static large file indication
        if (acceptRanges != null && acceptRanges.equalsIgnoreCase("bytes") && contentLength > 30_000_000L) {
            return true;
        }

        return false;
    }
}
