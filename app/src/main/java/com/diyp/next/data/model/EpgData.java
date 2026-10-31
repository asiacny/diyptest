package com.diyp.next.data.model;

import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.List;

public class EpgData {
    @SerializedName("channel_name")
    private String channelName;

    @SerializedName("date")
    private String date;

    @SerializedName("epg_data")
    private List<EpgItem> epgList;

    public EpgData() {
        this.epgList = new ArrayList<>();
    }

    public String getChannelName() {
        return channelName;
    }

    public void setChannelName(String channelName) {
        this.channelName = channelName;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public List<EpgItem> getEpgList() {
        return epgList != null ? epgList : new ArrayList<>();
    }

    public void setEpgList(List<EpgItem> epgList) {
        this.epgList = epgList;
    }
}
