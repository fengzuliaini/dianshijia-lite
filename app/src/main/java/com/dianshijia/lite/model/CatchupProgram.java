package com.dianshijia.lite.model;

/**
 * 回看/EPG节目数据实体模型
 */
public class CatchupProgram {
    public String timeLabel;    // 界面显示，例如："19:00 - 19:30 新闻联播"
    public String beginTime;    // playseek 开始，格式为 "yyyyMMddHHmmss"
    public String endTime;      // playseek 结束，格式为 "yyyyMMddHHmmss"
    public boolean isLive;      // 是否是“返回实时直播”的占位项
    public long beginTimeMs;    // 开始时间戳 (毫秒)
    public long endTimeMs;      // 结束时间戳 (毫秒)
    public String programName;  // 纯节目名称，例如："新闻联播"
    public String dateKey;      // 所属日期标识，例如："2026-10-19"
    public String dateDisplay;  // 所属日期显示，例如："今天 10-19" 或 "周一 10-19"
}
