package com.diyp.next.data.model;

import com.google.gson.annotations.SerializedName;

public class EpgItem {
    @SerializedName("title")
    private String title;

    @SerializedName("start")
    private String start;

    @SerializedName("end")
    private String end;

    private transient int cachedStartMinutes = -2;
    private transient int cachedEndMinutes = -2;

    public EpgItem() {}

    public EpgItem(String start, String end, String title) {
        this.start = start;
        this.end = end;
        this.title = title;
    }

    public String getTitle() {
        return title != null ? title : "";
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getStart() {
        return start != null ? start : "";
    }

    public void setStart(String start) {
        this.start = start;
        this.cachedStartMinutes = -2;
    }

    public String getEnd() {
        return end != null ? end : "";
    }

    public void setEnd(String end) {
        this.end = end;
        this.cachedEndMinutes = -2;
    }

    /**
     * Convert "HH:mm" / "HH:mm:ss" or "yyyy-MM-dd HH:mm" time string to minutes from start of day
     */
    public int getStartMinutes() {
        if (cachedStartMinutes == -2) {
            cachedStartMinutes = parseTimeToMinutes(start);
        }
        return cachedStartMinutes;
    }

    public int getEndMinutes() {
        if (cachedEndMinutes == -2) {
            cachedEndMinutes = parseTimeToMinutes(end);
        }
        return cachedEndMinutes;
    }

    public static int parseTimeToMinutes(String timeStr) {
        if (timeStr == null || !timeStr.contains(":")) return -1;
        try {
            timeStr = timeStr.trim();
            if (timeStr.contains(" ")) {
                timeStr = timeStr.substring(timeStr.lastIndexOf(" ") + 1).trim();
            }
            String[] parts = timeStr.split(":");
            if (parts.length >= 2) {
                int hour = Integer.parseInt(parts[0].trim());
                int min = Integer.parseInt(parts[1].trim());
                return hour * 60 + min;
            }
        } catch (Exception ignored) {}
        return -1;
    }
}
