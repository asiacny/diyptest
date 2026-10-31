package com.diyp.next.data.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class Channel {
    private int number;
    private String name;
    private String tvgName = "";
    private final List<SourceItem> sources = new ArrayList<>();
    private final List<String> urls = new ArrayList<>(); // Legacy compatibility
    private int currentUrlIndex;
    private String groupName;
    private String logoUrl;
    private String customEpgUrl;
    private final Map<String, String> headers = new HashMap<>();

    // Ephemeral EPG data for UI
    private String currentProgram = "";
    private String nextProgram = "";
    private int progressPercent = 0;
    private List<EpgItem> fullEpgList = new ArrayList<>();

    public static class SourceItem {
        private final String rawUrl;
        private final String cleanUrl;
        private final String customName;
        private final boolean isExplicitVod;
        private final Map<String, String> headers = new HashMap<>();

        public SourceItem(String rawUrl) {
            String trimmed = (rawUrl != null) ? rawUrl.trim() : "";
            this.rawUrl = trimmed;

            String urlPart = trimmed;
            String headerPart = "";
            if (trimmed.contains("|")) {
                int pipeIdx = trimmed.indexOf('|');
                urlPart = trimmed.substring(0, pipeIdx).trim();
                headerPart = trimmed.substring(pipeIdx + 1).trim();
            }

            // Support Scheme A: URL suffix $lineName (e.g. http://...$电信超清)
            String name = "";
            if (headerPart.contains("$")) {
                int dollarIdx = headerPart.lastIndexOf('$');
                name = headerPart.substring(dollarIdx + 1).trim();
                headerPart = headerPart.substring(0, dollarIdx).trim();
            } else if (urlPart.contains("$")) {
                int dollarIdx = urlPart.lastIndexOf('$');
                name = urlPart.substring(dollarIdx + 1).trim();
                urlPart = urlPart.substring(0, dollarIdx).trim();
            }

            // Check if URL ends with #vod (case-insensitive), and strip these 4 characters from playback cleanUrl
            boolean explicitVod = false;
            if (urlPart.toLowerCase(Locale.ROOT).endsWith("#vod")) {
                explicitVod = true;
                urlPart = urlPart.substring(0, urlPart.length() - 4).trim();
            }

            this.isExplicitVod = explicitVod;
            this.customName = name;
            this.cleanUrl = urlPart;

            if (!headerPart.isEmpty()) {
                parseHeaders(headerPart);
            }
        }

        public SourceItem(String rawUrl, String headerStr) {
            this(rawUrl);
            if (headerStr != null && !headerStr.isEmpty()) {
                parseHeaders(headerStr);
            }
        }

        private void parseHeaders(String headerStr) {
            try {
                String[] pairs = headerStr.split("&");
                for (String pair : pairs) {
                    String[] kv = pair.split("=", 2);
                    if (kv.length == 2) {
                        headers.put(kv[0].trim(), kv[1].trim());
                    }
                }
            } catch (Exception ignored) {}
        }

        public String getRawUrl() { return rawUrl; }
        public String getCleanUrl() { return cleanUrl; }
        public String getCustomName() { return customName; }
        public boolean hasCustomName() { return customName != null && !customName.isEmpty(); }
        public Map<String, String> getHeaders() { return headers; }
        public void addHeader(String key, String value) {
            if (key != null && value != null) {
                headers.put(key.trim(), value.trim());
            }
        }

        public boolean isVodSeekMode() {
            if (isExplicitVod) return true;
            if (cleanUrl != null && !cleanUrl.isEmpty()) {
                String lower = cleanUrl.toLowerCase(Locale.ROOT);
                int queryIdx = lower.indexOf('?');
                String path = (queryIdx != -1) ? lower.substring(0, queryIdx) : lower;
                int hashIdx = path.indexOf('#');
                if (hashIdx != -1) path = path.substring(0, hashIdx);
                if (path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".flv") ||
                    path.endsWith(".avi") || path.endsWith(".mov") || path.endsWith(".wmv") ||
                    path.endsWith(".rmvb") || path.endsWith(".webm") || path.endsWith(".iso") ||
                    path.endsWith(".m4v") || path.endsWith(".m2ts") || path.endsWith(".mts")) {
                    return true;
                }
            }
            return false;
        }

        public boolean isTimeShiftSupported() {
            if (isVodSeekMode()) return false;
            if (cleanUrl == null || cleanUrl.isEmpty()) return false;
            return cleanUrl.contains("/PLTV/") ||
                   cleanUrl.contains("/TVOD/") ||
                   cleanUrl.contains("/gitv_live/") ||
                   cleanUrl.contains("ysten-businessmobile") ||
                   cleanUrl.contains("ysten-business") ||
                   cleanUrl.contains("itv.cmvideo.cn") ||
                   cleanUrl.contains("/live/program/live/") ||
                   cleanUrl.contains("catchup=") ||
                   cleanUrl.contains("playseek=");
        }
    }

    public Channel(int number, String name, String groupName) {
        this.number = number;
        this.name = name != null ? name : "";
        this.groupName = groupName;
        this.currentUrlIndex = 0;
    }

    public int getNumber() {
        return number;
    }

    public void setNumber(int number) {
        this.number = number;
    }

    public String getName() {
        return name != null ? name : "";
    }

    public void setName(String name) {
        this.name = name != null ? name : "";
    }

    public String getTvgName() {
        return tvgName != null ? tvgName : "";
    }

    public void setTvgName(String tvgName) {
        this.tvgName = tvgName != null ? tvgName.trim() : "";
    }

    public List<SourceItem> getSources() {
        return sources;
    }

    public List<String> getUrls() {
        return urls;
    }

    public synchronized void addUrl(String rawUrl) {
        if (rawUrl != null && !rawUrl.trim().isEmpty()) {
            SourceItem item = new SourceItem(rawUrl);
            sources.add(item);
            urls.add(item.getCleanUrl());
        }
    }

    public synchronized void addUrl(String rawUrl, String headerStr) {
        if (rawUrl != null && !rawUrl.trim().isEmpty()) {
            SourceItem item = new SourceItem(rawUrl, headerStr);
            sources.add(item);
            urls.add(item.getCleanUrl());
        }
    }

    public Map<String, String> getCurrentHeaders() {
        Map<String, String> merged = new HashMap<>(this.headers);
        if (!sources.isEmpty() && currentUrlIndex >= 0 && currentUrlIndex < sources.size()) {
            merged.putAll(sources.get(currentUrlIndex).getHeaders());
        }
        return merged;
    }

    public String getCurrentUrl() {
        if (sources.isEmpty()) return "";
        if (currentUrlIndex >= sources.size()) currentUrlIndex = 0;
        return sources.get(currentUrlIndex).getCleanUrl();
    }

    public String getCurrentCleanUrl() {
        return getCurrentUrl();
    }

    public int getCurrentUrlIndex() {
        return currentUrlIndex;
    }

    public void setCurrentUrlIndex(int currentUrlIndex) {
        if (!sources.isEmpty() && currentUrlIndex >= 0 && currentUrlIndex < sources.size()) {
            this.currentUrlIndex = currentUrlIndex;
        }
    }

    public boolean nextUrl() {
        if (sources.size() > 1) {
            currentUrlIndex = (currentUrlIndex + 1) % sources.size();
            return true;
        }
        return false;
    }

    public int getUrlCount() {
        return sources.size();
    }

    /**
     * Get clean line display name without time-shift suffix (e.g. "电信超清" or "源1")
     */
    public String getSourcePureName(int index) {
        if (index < 0 || index >= sources.size()) return "源1";
        SourceItem item = sources.get(index);
        if (item.hasCustomName()) {
            return item.getCustomName();
        }
        return String.format("源%d", index + 1);
    }

    public String getCurrentSourcePureName() {
        return getSourcePureName(currentUrlIndex);
    }

    /**
     * Format line name with coexisting [时移] tag:
     * e.g. "电信超清 · 时移" or "电信超清", "源1/2 · 时移" or "源1/2"
     */
    public String getSourceDisplayName(int index) {
        if (index < 0 || index >= sources.size()) return "源1/1";
        SourceItem item = sources.get(index);
        boolean ts = item.isTimeShiftSupported();

        if (item.hasCustomName()) {
            return ts ? (item.getCustomName() + " · 时移") : item.getCustomName();
        } else {
            return ts ? String.format("源%d/%d · 时移", index + 1, getUrlCount())
                      : String.format("源%d/%d", index + 1, getUrlCount());
        }
    }

    public String getCurrentSourceDisplayName() {
        return getSourceDisplayName(currentUrlIndex);
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public String getLogoUrl() {
        return logoUrl;
    }

    public void setLogoUrl(String logoUrl) {
        this.logoUrl = logoUrl;
    }

    public boolean isVodSeekMode() {
        if (sources.isEmpty()) return false;
        if (currentUrlIndex >= sources.size()) currentUrlIndex = 0;
        return sources.get(currentUrlIndex).isVodSeekMode();
    }

    public boolean isTimeShiftSupported() {
        if (sources.isEmpty()) return false;
        if (currentUrlIndex >= sources.size()) currentUrlIndex = 0;
        return sources.get(currentUrlIndex).isTimeShiftSupported();
    }

    public boolean isCurrentSourceTimeShift() {
        return isTimeShiftSupported();
    }

    public String getTimeShiftUrl(long seekTimestampMs) {
        String liveUrl = getCurrentUrl();
        if (liveUrl == null || liveUrl.isEmpty()) return "";

        long now = System.currentTimeMillis();
        long safeEndTimeMs = now - 20000L; // 20s ago ensures server treats it as closed VOD with #EXT-X-ENDLIST
        if (safeEndTimeMs <= seekTimestampMs) {
            safeEndTimeMs = seekTimestampMs + 60000L;
        }

        java.text.SimpleDateFormat sdfFull = new java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.getDefault());
        String startTime = sdfFull.format(new java.util.Date(seekTimestampMs));
        String endTime = sdfFull.format(new java.util.Date(safeEndTimeMs));

        // 1. PLTV -> TVOD (Standard Telecom/Unicom IPTV)
        if (liveUrl.contains("/PLTV/") || liveUrl.contains("/TVOD/")) {
            String clean = liveUrl.replaceAll("([?&])livemode=[^&]*", "");
            clean = clean.replaceAll("([?&])playseek=[^&]*", "");
            String tvodUrl = clean.replace("/PLTV/", "/TVOD/");
            String sep = tvodUrl.contains("?") ? "&" : "?";
            return tvodUrl + sep + "playseek=" + startTime + "-" + endTime;
        }

        // 2. Ysten lookback
        if (liveUrl.contains("ysten-business") || liveUrl.contains("ysten-businessmobile")) {
            String clean = liveUrl.replaceAll("([?&])playseek=[^&]*", "");
            String tvodUrl = clean.replace("/live/", "/lookback/");
            String sep = tvodUrl.contains("?") ? "&" : "?";
            return tvodUrl + sep + "playseek=" + startTime + "-" + endTime;
        }

        // 3. CMVideo / Migu
        if (liveUrl.contains("itv.cmvideo.cn")) {
            java.text.SimpleDateFormat sdfDate = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault());
            java.text.SimpleDateFormat sdfTime = new java.text.SimpleDateFormat("HHmmss", java.util.Locale.getDefault());
            String st = sdfDate.format(new java.util.Date(seekTimestampMs)) + "T" + sdfTime.format(new java.util.Date(seekTimestampMs)) + ".00Z";
            String et = sdfDate.format(new java.util.Date(safeEndTimeMs)) + "T" + sdfTime.format(new java.util.Date(safeEndTimeMs)) + ".00Z";
            String tvodUrl = liveUrl.replace("&livemode=1", "&livemode=4");
            String sep = tvodUrl.contains("?") ? "&" : "?";
            return tvodUrl + sep + "starttime=" + st + "&endtime=" + et;
        }

        // 4. Catchup / Existing playseek
        if (liveUrl.contains("playseek=")) {
            return liveUrl.replaceAll("playseek=[^&]*", "playseek=" + startTime + "-" + endTime);
        }

        // 5. General fallback
        String clean = liveUrl.replaceAll("([?&])playseek=[^&]*", "");
        String sep = clean.contains("?") ? "&" : "?";
        return clean + sep + "playseek=" + startTime + "-" + endTime;
    }

    public String getCustomEpgUrl() {
        return customEpgUrl;
    }

    public void setCustomEpgUrl(String customEpgUrl) {
        this.customEpgUrl = customEpgUrl;
    }

    public Map<String, String> getHeaders() {
        return getCurrentHeaders();
    }

    public void addHeader(String key, String value) {
        this.headers.put(key, value);
    }

    public String getCurrentProgram() {
        return currentProgram;
    }

    public void setCurrentProgram(String currentProgram) {
        this.currentProgram = currentProgram;
    }

    public String getNextProgram() {
        return nextProgram;
    }

    public void setNextProgram(String nextProgram) {
        this.nextProgram = nextProgram;
    }

    public int getProgressPercent() {
        return progressPercent;
    }

    public void setProgressPercent(int progressPercent) {
        this.progressPercent = progressPercent;
    }

    public List<EpgItem> getFullEpgList() {
        return fullEpgList;
    }

    public void setFullEpgList(List<EpgItem> fullEpgList) {
        this.fullEpgList = fullEpgList;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Channel channel = (Channel) o;
        if (name != null ? !name.equalsIgnoreCase(channel.name) : channel.name != null) return false;
        return groupName != null ? groupName.equals(channel.groupName) : channel.groupName == null;
    }

    @Override
    public int hashCode() {
        int result = name != null ? name.toLowerCase().hashCode() : 0;
        result = 31 * result + (groupName != null ? groupName.hashCode() : 0);
        return result;
    }
}
