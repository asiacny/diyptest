package com.diyp.next.data.parser;

import com.diyp.next.data.model.Channel;
import com.diyp.next.data.model.ChannelGroup;
import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ChannelParser {

    /**
     * Parse DIYP TXT format and standard M3U/M3U8 format with auto-detection
     */
    public static List<ChannelGroup> parse(String content) {
        if (content == null || content.isEmpty()) {
            return new ArrayList<>();
        }

        // Fast head sample without duplicating whole content string
        int len = content.length();
        int start = 0;
        while (start < len && Character.isWhitespace(content.charAt(start))) {
            start++;
        }
        if (start >= len) {
            return new ArrayList<>();
        }

        int checkLen = Math.min(len - start, 2048);
        String headSample = content.substring(start, start + checkLen).toUpperCase(Locale.ROOT);
        if (headSample.startsWith("#EXTM3U") || headSample.contains("#EXTINF:")) {
            return parseM3u(content);
        } else {
            return parseDiypTxt(content);
        }
    }

    /**
     * Parse standard DIYP TXT format:
     * 央视频道,#genre#
     * CCTV-1,http://...$高清#http://...$超清
     */
    public static List<ChannelGroup> parseDiypTxt(String content) {
        List<ChannelGroup> groups = new ArrayList<>();
        ChannelGroup currentGroup = new ChannelGroup("默认频道");
        groups.add(currentGroup);

        int channelIndex = 1;
        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || (line.startsWith("#") && !line.contains("#genre#"))) {
                    continue;
                }

                if (line.contains(",#genre#")) {
                    // Category line: 央视频道,#genre# or 央视频道,#genre#,http://epg/
                    String[] parts = line.split(",#genre#");
                    String groupName = parts[0].trim();
                    String customEpg = "";
                    if (parts.length > 1) {
                        String after = parts[1].trim();
                        if (after.startsWith(",")) {
                            customEpg = after.substring(1).trim();
                        }
                    }

                    currentGroup = new ChannelGroup(groupName);
                    if (!customEpg.isEmpty()) {
                        currentGroup.setCustomEpgUrl(customEpg);
                    }
                    groups.add(currentGroup);
                } else if (line.contains(",")) {
                    // Channel line: CCTV-1,http://url1$高清#http://url2$超清
                    int commaIndex = line.indexOf(",");
                    String name = line.substring(0, commaIndex).trim();
                    String urlPart = line.substring(commaIndex + 1).trim();

                    // Check if consecutive entry has same name in current group (Scheme A multi-line merge)
                    Channel channel = null;
                    if (!currentGroup.getChannels().isEmpty()) {
                        Channel lastCh = currentGroup.getChannels().get(currentGroup.getChannels().size() - 1);
                        if (lastCh.getName().equalsIgnoreCase(name)) {
                            channel = lastCh;
                        }
                    }

                    if (channel == null) {
                        channel = new Channel(channelIndex++, name, currentGroup.getName());
                        channel.setCustomEpgUrl(currentGroup.getCustomEpgUrl());
                        currentGroup.addChannel(channel);
                    }

                    // Parse multiple sources separated by #
                    String[] sources = urlPart.split("#");
                    for (String source : sources) {
                        source = source.trim();
                        if (!source.isEmpty()) {
                            // SourceItem handles url|User-Agent=xxx&Referer=yyy independently
                            channel.addUrl(source);
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Clean up empty groups
        List<ChannelGroup> result = new ArrayList<>();
        for (ChannelGroup g : groups) {
            if (!g.getChannels().isEmpty()) {
                result.add(g);
            }
        }
        return result;
    }

    /**
     * Parse standard M3U/M3U8 playlists with Scheme A multi-line merge and tvg-name support
     */
    public static List<ChannelGroup> parseM3u(String content) {
        List<ChannelGroup> groups = new ArrayList<>();
        Map<String, ChannelGroup> groupMap = new HashMap<>();
        ChannelGroup defaultGroup = new ChannelGroup("直播频道");
        groups.add(defaultGroup);
        groupMap.put("直播频道", defaultGroup);
        ChannelGroup lastGroup = defaultGroup;

        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            String line;
            String currentName = "";
            String currentTvgName = "";
            String currentGroupTitle = "直播频道";
            String currentLogo = "";
            int channelIndex = 1;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("#EXTM3U") || line.startsWith("#extm3u")) {
                    String globalEpg = extractTagValue(line, "x-tvg-url", "");
                    if (globalEpg.isEmpty()) {
                        globalEpg = extractTagValue(line, "url-tvg", "");
                    }
                    if (!globalEpg.isEmpty()) {
                        defaultGroup.setCustomEpgUrl(globalEpg);
                    }
                } else if (line.startsWith("#EXTINF:") || line.startsWith("#extinf:")) {
                    // 1. Extract group-title
                    currentGroupTitle = extractTagValue(line, "group-title", "直播频道");

                    // 2. Extract tvg-logo
                    currentLogo = extractTagValue(line, "tvg-logo", "");

                    // 3. Extract tvg-name (Priority 1 for EPG)
                    currentTvgName = extractTagValue(line, "tvg-name", "");

                    // 4. Channel title after comma
                    int comma = line.lastIndexOf(",");
                    if (comma != -1 && comma < line.length() - 1) {
                        currentName = line.substring(comma + 1).trim();
                    } else {
                        currentName = !currentTvgName.isEmpty() ? currentTvgName : "未知频道";
                    }
                } else if (!line.startsWith("#")) {
                    // It's a stream URL
                    if (!currentName.isEmpty()) {
                        ChannelGroup targetGroup = null;
                        if (lastGroup != null && lastGroup.getName().equalsIgnoreCase(currentGroupTitle)) {
                            targetGroup = lastGroup;
                        } else {
                            targetGroup = groupMap.get(currentGroupTitle);
                            if (targetGroup == null) {
                                for (ChannelGroup g : groups) {
                                    if (g.getName().equalsIgnoreCase(currentGroupTitle)) {
                                        targetGroup = g;
                                        break;
                                    }
                                }
                            }
                        }
                        if (targetGroup == null) {
                            targetGroup = new ChannelGroup(currentGroupTitle);
                            targetGroup.setCustomEpgUrl(defaultGroup.getCustomEpgUrl());
                            groups.add(targetGroup);
                            groupMap.put(currentGroupTitle, targetGroup);
                        } else if (targetGroup.getCustomEpgUrl() == null || targetGroup.getCustomEpgUrl().isEmpty()) {
                            targetGroup.setCustomEpgUrl(defaultGroup.getCustomEpgUrl());
                        }
                        lastGroup = targetGroup;

                        // Scheme A: Multi-line same-name merge into multiple sources
                        Channel targetChannel = null;
                        if (!targetGroup.getChannels().isEmpty()) {
                            Channel lastChannel = targetGroup.getChannels().get(targetGroup.getChannels().size() - 1);
                            if (lastChannel.getName().equalsIgnoreCase(currentName)) {
                                targetChannel = lastChannel;
                            }
                        }

                        if (targetChannel != null) {
                            targetChannel.addUrl(line);
                            if (targetChannel.getTvgName().isEmpty() && !currentTvgName.isEmpty()) {
                                targetChannel.setTvgName(currentTvgName);
                            }
                            if ((targetChannel.getLogoUrl() == null || targetChannel.getLogoUrl().isEmpty()) && !currentLogo.isEmpty()) {
                                targetChannel.setLogoUrl(currentLogo);
                            }
                        } else {
                            Channel newChannel = new Channel(channelIndex++, currentName, currentGroupTitle);
                            newChannel.setTvgName(currentTvgName);
                            newChannel.setLogoUrl(currentLogo);
                            newChannel.setCustomEpgUrl(targetGroup.getCustomEpgUrl());
                            newChannel.addUrl(line);
                            targetGroup.addChannel(newChannel);
                        }

                        // Reset ephemeral line variables
                        currentName = "";
                        currentTvgName = "";
                        currentLogo = "";
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        List<ChannelGroup> result = new ArrayList<>();
        for (ChannelGroup g : groups) {
            if (!g.getChannels().isEmpty()) {
                result.add(g);
            }
        }
        return result;
    }

    private static String extractTagValue(String line, String tagName, String defaultValue) {
        String pattern = tagName + "=\"";
        int start = line.indexOf(pattern);
        if (start != -1) {
            int valStart = start + pattern.length();
            int end = line.indexOf("\"", valStart);
            if (end > valStart) {
                return line.substring(valStart, end).trim();
            }
        }
        return defaultValue;
    }

    private static void parseHeaders(Channel channel, String headerStr) {
        try {
            String[] pairs = headerStr.split("&");
            for (String pair : pairs) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) {
                    channel.addHeader(kv[0].trim(), kv[1].trim());
                }
            }
        } catch (Exception ignored) {}
    }
}
