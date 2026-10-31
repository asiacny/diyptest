package com.diyp.next.data.parser;

import com.diyp.next.data.model.Channel;
import com.diyp.next.data.model.EpgData;
import com.diyp.next.data.model.EpgItem;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class EpgParser {

    public static EpgData parseJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            // Strip BOM if present
            json = json.trim();
            if (json.startsWith("\uFEFF")) {
                json = json.substring(1).trim();
            }

            EpgData epgData = new EpgData();
            List<EpgItem> items = new ArrayList<>();

            if (json.startsWith("[")) {
                // Direct JSON Array e.g. [{"title":"...","start":"08:00"}]
                JSONArray arr = new JSONArray(json);
                parseArray(arr, items);
            } else if (json.startsWith("{")) {
                JSONObject obj = new JSONObject(json);
                epgData.setChannelName(obj.optString("channel_name", obj.optString("name", "")));
                epgData.setDate(obj.optString("date", ""));

                // Support various EPG schema field names
                JSONArray arr = obj.optJSONArray("epg_data");
                if (arr == null) arr = obj.optJSONArray("data");
                if (arr == null) arr = obj.optJSONArray("epg");
                if (arr == null) arr = obj.optJSONArray("list");

                if (arr != null) {
                    parseArray(arr, items);
                }
            }

            epgData.setEpgList(items);
            return epgData;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static void parseArray(JSONArray arr, List<EpgItem> items) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject itemObj = arr.optJSONObject(i);
            if (itemObj != null) {
                String title = itemObj.optString("title", itemObj.optString("name", ""));
                String start = itemObj.optString("start", itemObj.optString("start_time", ""));
                String end = itemObj.optString("end", itemObj.optString("end_time", ""));
                if (!title.isEmpty()) {
                    items.add(new EpgItem(start, end, title));
                }
            }
        }
    }

    /**
     * Compute current playing program, next program, and progress for a channel
     */
    public static void updateChannelEpgStatus(Channel channel, EpgData epgData) {
        if (channel == null || epgData == null) return;

        List<EpgItem> items = epgData.getEpgList();
        channel.setFullEpgList(items);

        if (items.isEmpty()) {
            channel.setCurrentProgram("暂无节目信息");
            channel.setNextProgram("");
            channel.setProgressPercent(0);
            return;
        }

        Calendar now = Calendar.getInstance();
        int nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);

        EpgItem current = null;
        EpgItem next = null;

        for (int i = 0; i < items.size(); i++) {
            EpgItem item = items.get(i);
            int startMin = item.getStartMinutes();
            int endMin = item.getEndMinutes();

            // If endMin is missing or invalid, inherit from next item's startMin
            if (endMin < 0 && i + 1 < items.size()) {
                endMin = items.get(i + 1).getStartMinutes();
            }
            if (endMin < 0) {
                endMin = 24 * 60; // Default to end of day
            }
            if (startMin < 0) {
                startMin = 0;
            }

            // Cross-midnight adjustment
            if (endMin < startMin) {
                endMin += 24 * 60;
            }

            if (nowMinutes >= startMin && nowMinutes < endMin) {
                current = item;
                if (i + 1 < items.size()) {
                    next = items.get(i + 1);
                }
                break;
            }
        }

        if (current != null) {
            channel.setCurrentProgram(current.getTitle());
            int startMin = Math.max(0, current.getStartMinutes());
            int endMin = current.getEndMinutes();
            if (endMin < 0 && next != null) endMin = next.getStartMinutes();
            if (endMin < 0) endMin = 24 * 60;
            if (endMin < startMin) endMin += 24 * 60;

            int duration = endMin - startMin;
            int elapsed = nowMinutes - startMin;
            if (duration > 0 && elapsed >= 0) {
                int percent = Math.min(100, Math.max(0, (elapsed * 100) / duration));
                channel.setProgressPercent(percent);
            }
        } else {
            // Find first upcoming or last item
            for (int i = 0; i < items.size(); i++) {
                int s = items.get(i).getStartMinutes();
                if (s >= 0 && s > nowMinutes) {
                    next = items.get(i);
                    break;
                }
            }
            EpgItem lastStarted = null;
            for (int i = items.size() - 1; i >= 0; i--) {
                int s = items.get(i).getStartMinutes();
                if (s >= 0 && s <= nowMinutes) {
                    lastStarted = items.get(i);
                    break;
                }
            }
            if (lastStarted != null) {
                channel.setCurrentProgram(lastStarted.getTitle());
            } else {
                channel.setCurrentProgram(items.get(0).getTitle());
            }
            channel.setProgressPercent(0);
        }

        if (next != null) {
            channel.setNextProgram(next.getTitle());
        } else {
            channel.setNextProgram("");
        }
    }
}
