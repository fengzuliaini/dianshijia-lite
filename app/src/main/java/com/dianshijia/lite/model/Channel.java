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
    private String catchupType;       // M3U 回看类型 (如 append, default, flussonic, shift 等)
    private String catchupSource;     // M3U 回看源模板 (如 ?playseek=${(b)yyyyMMddHHmmss}-${(e)yyyyMMddHHmmss})

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

    public String getCatchupType() {
        return catchupType;
    }

    public void setCatchupType(String catchupType) {
        this.catchupType = catchupType;
    }

    public String getCatchupSource() {
        return catchupSource;
    }

    public void setCatchupSource(String catchupSource) {
        this.catchupSource = catchupSource;
    }

    /**
     * 判断当前频道是否具备回看功能（包含 TVOD 线路、PLTV 运营商时移或 M3U catchup 声明）
     */
    public boolean hasCatchupCapability() {
        if (!tvodUrls.isEmpty()) return true;
        if (catchupSource != null && !catchupSource.isEmpty()) return true;
        if (catchupType != null && !catchupType.isEmpty()) return true;
        String live = getPlayUrl();
        return live != null && live.contains("/PLTV/");
    }

    /**
     * 根据回看节目单与该频道的协议规范，智能构建回看流 URL
     */
    public String buildCatchupUrl(CatchupProgram program) {
        if (program == null) return null;
        String baseLiveUrl = getPlayUrl();
        if (baseLiveUrl == null) return null;

        long startMs = program.beginTimeMs;
        long endMs = program.endTimeMs;
        if (startMs <= 0) {
            startMs = com.dianshijia.lite.util.OkHttpUtils.currentTimeMillis() - 2 * 3600 * 1000;
        }
        if (endMs <= 0 || endMs <= startMs) {
            endMs = startMs + 2 * 3600 * 1000;
        }

        java.text.SimpleDateFormat sdf14 = new java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US);
        java.text.SimpleDateFormat sdfHyphen = new java.text.SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", java.util.Locale.US);
        java.text.SimpleDateFormat sdfUtc = new java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", java.util.Locale.US);
        sdfUtc.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));

        String b14 = (program.beginTime != null && program.beginTime.length() >= 14) ? program.beginTime : sdf14.format(new java.util.Date(startMs));
        String e14 = (program.endTime != null && program.endTime.length() >= 14) ? program.endTime : sdf14.format(new java.util.Date(endMs));

        long startSec = startMs / 1000;
        long endSec = endMs / 1000;
        long durationSec = Math.max(1, endSec - startSec);
        long nowSec = com.dianshijia.lite.util.OkHttpUtils.currentTimeMillis() / 1000;
        long offsetSec = Math.max(0, nowSec - startSec);

        // 1. 如果频道配置了显式的 catchup-source 模板
        if (catchupSource != null && !catchupSource.trim().isEmpty()) {
            String template = catchupSource.trim();
            String replaced = template
                    .replace("${(b)yyyyMMddHHmmss}", b14)
                    .replace("${(e)yyyyMMddHHmmss}", e14)
                    .replace("${(b)yyyy-MM-dd-HH-mm-ss}", sdfHyphen.format(new java.util.Date(startMs)))
                    .replace("${(e)yyyy-MM-dd-HH-mm-ss}", sdfHyphen.format(new java.util.Date(endMs)))
                    .replace("${(b)yyyyMMddTHHmmssZ}", sdfUtc.format(new java.util.Date(startMs)))
                    .replace("${(e)yyyyMMddTHHmmssZ}", sdfUtc.format(new java.util.Date(endMs)))
                    .replace("${start}", String.valueOf(startSec))
                    .replace("${end}", String.valueOf(endSec))
                    .replace("${timestamp}", String.valueOf(startSec))
                    .replace("${duration}", String.valueOf(durationSec))
                    .replace("${offset}", String.valueOf(offsetSec));

            if (replaced.startsWith("http://") || replaced.startsWith("https://")) {
                return replaced;
            } else {
                String sep = (baseLiveUrl.contains("?") || replaced.startsWith("?") || replaced.startsWith("&")) ? "" : "?";
                if (baseLiveUrl.contains("?") && replaced.startsWith("?")) {
                    replaced = "&" + replaced.substring(1);
                }
                return baseLiveUrl + sep + replaced;
            }
        }

        // 2. 检查是否有独立的 TVOD 回看基准地址
        if (!tvodUrls.isEmpty()) {
            String tvod = tvodUrls.get(0);
            String sep = tvod.contains("?") ? "&" : "?";
            return tvod + sep + "playseek=" + b14 + "-" + e14;
        }

        // 3. 电信/移动/联通等主流运营商规范：/PLTV/ -> /TVOD/
        if (baseLiveUrl.contains("/PLTV/")) {
            String tvod = baseLiveUrl.replace("/PLTV/", "/TVOD/");
            String sep = tvod.contains("?") ? "&" : "?";
            return tvod + sep + "playseek=" + b14 + "-" + e14;
        }

        // 4. 通用兜底规范：直接在直播流追加 playseek 参数
        String sep = baseLiveUrl.contains("?") ? "&" : "?";
        return baseLiveUrl + sep + "playseek=" + b14 + "-" + e14;
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
