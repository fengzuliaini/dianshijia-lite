package com.dianshijia.lite.model;

/**
 * 频道数据实体模型
 */
public class Channel {
    private String number; // 频道台号（如 "1", "01" 或 "001"）
    private String name;   // 频道名称（如 "CCTV1"）
    private String url;    // 播放源 HLS 直播地址 (m3u8)
    private String group;  // 频道分类组（如 "央视频道", "全国卫视"）

    public Channel(String number, String name, String url, String group) {
        this.number = number;
        this.name = name;
        this.url = url;
        this.group = group;
    }

    public String getNumber() {
        return number;
    }

    public void setNumber(String number) {
        this.number = number;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }
}
