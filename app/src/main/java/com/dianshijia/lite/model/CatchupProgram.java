package com.dianshijia.lite.model;

/**
 * 回看/EPG节目数据实体模型
 */
public class CatchupProgram {
    public String timeLabel;    // 界面显示，例如："19:00 - 19:30 新闻联播"
    public String beginTime;    // playseek 开始，格式为 "yyyyMMddHHmmss"
    public String endTime;      // playseek 结束，格式为 "yyyyMMddHHmmss"
    public boolean isLive;      // 是否是“返回实时直播”的占位项
}
