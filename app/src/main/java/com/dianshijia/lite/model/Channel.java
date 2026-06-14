package com.dianshijia.lite.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 频道数据实体模型 (重构版：支持多线路及回看)
 */
public class Channel {
    private String number;            // 频道台号
    private String name;              // 频道名称
    private String group;             // 频道分类组
    private List<String> liveUrls = new ArrayList<>(); // 直播线路 URL 集合
    private List<String> tvodUrls = new ArrayList<>(); // 回看线路 URL 集合
    private int currentLineIndex = 0; // 当前选中的直播线路索引
    private String epgId;             // 关联 XML 节目单的 EPG 频道 ID
    private List<CatchupProgram> epgPrograms = new ArrayList<>(); // 真实的 EPG 历史节目单集合

    public Channel(String number, String name, String group) {
        this.number = number;
        this.name = name;
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

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public List<String> getLiveUrls() {
        return liveUrls;
    }

    public List<String> getTvodUrls() {
        return tvodUrls;
    }

    public int getCurrentLineIndex() {
        return currentLineIndex;
    }

    public void setCurrentLineIndex(int index) {
        this.currentLineIndex = index;
    }

    /**
     * 获取当前选中的直播播放源 URL
     */
    public String getPlayUrl() {
        if (liveUrls.isEmpty()) {
            return null;
        }
        if (currentLineIndex < 0 || currentLineIndex >= liveUrls.size()) {
            currentLineIndex = 0;
        }
        return liveUrls.get(currentLineIndex);
    }

    /**
     * 获取回看的基准播放源 URL (优先使用 TVOD 线路，如果没有，则尝试自动将直播源 PLTV 转为 TVOD)
     */
    public String getTvodUrl() {
        if (!tvodUrls.isEmpty()) {
            return tvodUrls.get(0);
        }
        String live = getPlayUrl();
        if (live != null && live.contains("/PLTV/")) {
            return live.replace("/PLTV/", "/TVOD/");
        }
        return live;
    }

    public String getEpgId() {
        return epgId;
    }

    public void setEpgId(String epgId) {
        this.epgId = epgId;
    }

    public List<CatchupProgram> getEpgPrograms() {
        return epgPrograms;
    }

    private boolean isFavorite = false; // 频道是否已被收藏

    public boolean isFavorite() {
        return isFavorite;
    }

    public void setFavorite(boolean favorite) {
        this.isFavorite = favorite;
    }

    /**
     * 获取当前正在播放的真实 EPG 节目（过滤空占位符）
     */
    public CatchupProgram getCurrentProgram() {
        long now = com.dianshijia.lite.util.OkHttpUtils.currentTimeMillis();
        for (CatchupProgram prog : epgPrograms) {
            if (prog.beginTimeMs <= now && now < prog.endTimeMs) {
                if (prog.programName != null && !prog.programName.isEmpty()) {
                    return prog;
                }
            }
        }
        return null;
    }

    /**
     * 获取下一个待播放的真实 EPG 节目
     */
    public CatchupProgram getNextProgram() {
        long now = com.dianshijia.lite.util.OkHttpUtils.currentTimeMillis();
        for (int i = 0; i < epgPrograms.size(); i++) {
            CatchupProgram prog = epgPrograms.get(i);
            if (prog.beginTimeMs <= now && now < prog.endTimeMs) {
                if (i + 1 < epgPrograms.size()) {
                    CatchupProgram next = epgPrograms.get(i + 1);
                    if (next.programName != null && !next.programName.isEmpty()) {
                        return next;
                    }
                }
                break;
            }
        }
        return null;
    }
}
