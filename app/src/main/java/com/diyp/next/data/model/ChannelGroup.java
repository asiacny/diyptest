package com.diyp.next.data.model;

import java.util.ArrayList;
import java.util.List;

public class ChannelGroup {
    private String name;
    private String customEpgUrl;
    private List<Channel> channels;

    public ChannelGroup(String name) {
        this.name = name;
        this.channels = new ArrayList<>();
    }

    public ChannelGroup(String name, String customEpgUrl) {
        this.name = name;
        this.customEpgUrl = customEpgUrl;
        this.channels = new ArrayList<>();
    }

    public String getName() {
        return name != null ? name : "";
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCustomEpgUrl() {
        return customEpgUrl;
    }

    public void setCustomEpgUrl(String customEpgUrl) {
        this.customEpgUrl = customEpgUrl;
    }

    public List<Channel> getChannels() {
        return channels;
    }

    public void addChannel(Channel channel) {
        this.channels.add(channel);
    }
}
