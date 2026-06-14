package com.dianshijia.lite.parser;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Xml;

import com.dianshijia.lite.model.Channel;
import com.dianshijia.lite.model.CatchupProgram;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * EPG 节目单下载与 XMLTV 格式流式解析器
 * 支持 GZIP 解压、缓存策略（Stale-While-Revalidate）、以及归一化频道映射
 */
public class EpgParser {
    private static final String TAG = "EpgParser";
    private static final String CACHE_FILE_NAME = "epg_all.xml.gz";
    private static final long CACHE_EXPIRE_TIME = 12 * 60 * 60 * 1000; // 12小时缓存过期时间

    private static final OkHttpClient okHttpClient = com.dianshijia.lite.util.OkHttpUtils.getOkHttpClient();

    public static void loadEpg(final Context context, final String epgUrl, final List<Channel> channels, final Runnable onComplete) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                File cacheFile = new File(context.getCacheDir(), CACHE_FILE_NAME);
                boolean hasCache = cacheFile.exists();
                boolean cacheExpired = !hasCache || (System.currentTimeMillis() - cacheFile.lastModified() > CACHE_EXPIRE_TIME);

                // 1. 如果存在本地缓存，优先加载渲染（秒开体验，Stale）
                if (hasCache) {
                    Log.i(TAG, "发现本地 EPG 缓存，立即启动解析渲染...");
                    parseLocalCache(cacheFile, channels, onComplete);
                }

                // 2. 如果缓存过期或无缓存，则在后台静默下载最新 EPG 并再次刷新 (Revalidate)
                if (cacheExpired) {
                    Log.i(TAG, "EPG 缓存不存在或已过期，启动后台下载更新: " + epgUrl);
                    downloadAndParseEpg(context, epgUrl, cacheFile, channels, onComplete);
                }
            }
        }).start();
    }

    private static void parseLocalCache(File cacheFile, List<Channel> channels, final Runnable onComplete) {
        InputStream is = null;
        GZIPInputStream gzis = null;
        try {
            is = new FileInputStream(cacheFile);
            gzis = new GZIPInputStream(is);
            doParseXml(gzis, channels);
            Log.i(TAG, "本地 EPG 缓存解析成功");
            
            // 在主线程执行完成回调
            if (onComplete != null) {
                new Handler(Looper.getMainLooper()).post(onComplete);
            }
        } catch (Exception e) {
            Log.e(TAG, "解析本地 EPG 缓存失败", e);
        } finally {
            closeQuietly(gzis);
            closeQuietly(is);
        }
    }

    private static void downloadAndParseEpg(final Context context, String url, final File cacheFile, final List<Channel> channels, final Runnable onComplete) {
        Request request = new Request.Builder().url(url).build();
        try {
            Response response = okHttpClient.newCall(request).execute();
            if (response.isSuccessful() && response.body() != null) {
                // 先写入临时文件，避免写入中断损坏已有的缓存文件
                File tmpFile = new File(context.getCacheDir(), CACHE_FILE_NAME + ".tmp");
                InputStream is = response.body().byteStream();
                FileOutputStream fos = new FileOutputStream(tmpFile);
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) != -1) {
                    fos.write(buf, 0, len);
                }
                fos.flush();
                fos.close();
                is.close();

                // 重命名临时文件覆盖旧缓存
                if (tmpFile.renameTo(cacheFile)) {
                    Log.i(TAG, "最新 EPG 节目单下载并缓存成功，重新解析最新数据...");
                    parseLocalCache(cacheFile, channels, onComplete);
                }
            } else {
                Log.e(TAG, "下载 EPG 失败，HTTP 错误码: " + response.code());
            }
        } catch (Exception e) {
            Log.e(TAG, "下载或保存 EPG 文件失败", e);
        }
    }

    private static void doParseXml(InputStream is, List<Channel> channels) throws Exception {
        Map<String, String> displayNameToIdMap = new HashMap<>();
        Map<String, String> normalizedNameToIdMap = new HashMap<>();

        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(is, "UTF-8");
        int eventType = parser.getEventType();

        String currentChannelId = null;
        String currentText = null;
        CatchupProgram currentProgram = null;

        boolean mappingDone = false;
        Map<String, Channel> epgIdToChannelMap = new HashMap<>();

        long now = System.currentTimeMillis();
        SimpleDateFormat xmlDateFormat = new SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault());

        SimpleDateFormat sdfTime = new SimpleDateFormat("HH:mm", Locale.getDefault());
        SimpleDateFormat sdfDay = new SimpleDateFormat("MM-dd", Locale.getDefault());

        while (eventType != XmlPullParser.END_DOCUMENT) {
            String tag = parser.getName();
            switch (eventType) {
                case XmlPullParser.START_TAG:
                    if ("channel".equals(tag)) {
                        currentChannelId = parser.getAttributeValue(null, "id");
                    } else if ("programme".equals(tag)) {
                        // 一旦遇到第一个 programme 标签，说明所有的 channel 映射表都已经收集完毕
                        // 此时，我们对 M3U 里的所有 Channel 进行一次映射绑定
                        if (!mappingDone) {
                            performChannelMapping(channels, displayNameToIdMap, normalizedNameToIdMap, epgIdToChannelMap);
                            mappingDone = true;
                        }

                        String channelId = parser.getAttributeValue(null, "channel");
                        if (channelId != null && epgIdToChannelMap.containsKey(channelId)) {
                            String startVal = parser.getAttributeValue(null, "start");
                            String stopVal = parser.getAttributeValue(null, "stop");
                            if (startVal != null && startVal.length() >= 14 && stopVal != null && stopVal.length() >= 14) {
                                String start14 = startVal.substring(0, 14);
                                String stop14 = stopVal.substring(0, 14);

                                try {
                                    Date startDate = xmlDateFormat.parse(start14);
                                    Date endDate = xmlDateFormat.parse(stop14);
                                    long startTimeMs = startDate.getTime();
                                    long endTimeMs = endDate.getTime();

                                    // 收集全天节目（不再只过滤历史节目，以提供完整的当前/未来节目数据）
                                    currentProgram = new CatchupProgram();
                                    currentProgram.beginTime = start14;
                                    currentProgram.endTime = stop14;
                                    currentProgram.beginTimeMs = startTimeMs;
                                    currentProgram.endTimeMs = endTimeMs;
                                    currentProgram.isLive = false;

                                    String dayLabel = getDayLabel(startDate, now, sdfDay);
                                    currentProgram.timeLabel = dayLabel + " " + sdfTime.format(startDate) + " - " + sdfTime.format(endDate);
                                } catch (Exception e) {
                                    currentProgram = null;
                                }
                            }
                        } else {
                            currentProgram = null;
                        }
                    }
                    break;

                case XmlPullParser.TEXT:
                    currentText = parser.getText();
                    break;

                case XmlPullParser.END_TAG:
                    if ("display-name".equals(tag) && currentChannelId != null && currentText != null) {
                        String name = currentText.trim();
                        displayNameToIdMap.put(name.toLowerCase(), currentChannelId);
                        
                        String normName = normalizeChannelName(name);
                        if (!normName.isEmpty()) {
                            normalizedNameToIdMap.put(normName, currentChannelId);
                        }
                    } else if ("channel".equals(tag)) {
                        currentChannelId = null;
                    } else if ("title".equals(tag) && currentProgram != null && currentText != null) {
                        String title = currentText.trim();
                        currentProgram.programName = title;
                        // 将节目名称拼接到 timeLabel 中，例如 "今天 19:00 - 19:30 新闻联播"
                        currentProgram.timeLabel = currentProgram.timeLabel + " " + title;
                    } else if ("programme".equals(tag)) {
                        if (currentProgram != null) {
                            String channelId = parser.getAttributeValue(null, "channel");
                            Channel channel = epgIdToChannelMap.get(channelId);
                            if (channel != null) {
                                channel.getEpgPrograms().add(currentProgram);
                            }
                            currentProgram = null;
                        }
                    }
                    break;
            }
            eventType = parser.next();
        }
    }

    private static void performChannelMapping(List<Channel> channels,
                                              Map<String, String> displayNameToIdMap,
                                              Map<String, String> normalizedNameToIdMap,
                                              Map<String, Channel> epgIdToChannelMap) {
        epgIdToChannelMap.clear();
        for (Channel c : channels) {
            c.getEpgPrograms().clear(); // 清理以前的数据

            String nameLower = c.getName().toLowerCase();
            String nameNorm = normalizeChannelName(c.getName());

            // 1. 优先尝试精确匹配
            String matchedId = displayNameToIdMap.get(nameLower);
            if (matchedId == null) {
                // 2. 其次尝试归一化匹配
                matchedId = normalizedNameToIdMap.get(nameNorm);
            }

            if (matchedId != null) {
                c.setEpgId(matchedId);
                epgIdToChannelMap.put(matchedId, c);
            }
        }
        
        // 对每个频道的节目单进行按时间戳升序排序，防 XMLTV 乱序
        for (Channel c : channels) {
            if (!c.getEpgPrograms().isEmpty()) {
                java.util.Collections.sort(c.getEpgPrograms(), new java.util.Comparator<CatchupProgram>() {
                    @Override
                    public int compare(CatchupProgram p1, CatchupProgram p2) {
                        return Long.compare(p1.beginTimeMs, p2.beginTimeMs);
                    }
                });
            }
        }
        Log.i(TAG, "EPG 匹配完成，成功匹配 " + epgIdToChannelMap.size() + "/" + channels.size() + " 个频道");
    }

    /**
     * 归一化频道名以提高匹配率
     * 去除空格、横杠以及“高清”、“超清”、“[高清]”、“综合”、“频道”等电视盒子中常见的修饰词
     */
    private static String normalizeChannelName(String name) {
        if (name == null) return "";
        return name.toLowerCase()
                   .replaceAll("\\s+", "")
                   .replaceAll("-", "")
                   .replaceAll("综合", "")
                   .replaceAll("频道", "")
                   .replaceAll("高清", "")
                   .replaceAll("超清", "")
                   .replaceAll("\\[.*\\]", "")
                   .replaceAll("\\(.*\\)", "");
    }

    private static String getDayLabel(Date date, long now, SimpleDateFormat sdfDay) {
        Calendar calNow = Calendar.getInstance();
        calNow.setTimeInMillis(now);

        Calendar calDate = Calendar.getInstance();
        calDate.setTime(date);

        if (calNow.get(Calendar.YEAR) == calDate.get(Calendar.YEAR) &&
            calNow.get(Calendar.DAY_OF_YEAR) == calDate.get(Calendar.DAY_OF_YEAR)) {
            return "今天";
        }

        Calendar calYesterday = Calendar.getInstance();
        calYesterday.setTimeInMillis(now);
        calYesterday.add(Calendar.DAY_OF_YEAR, -1);
        if (calYesterday.get(Calendar.YEAR) == calDate.get(Calendar.YEAR) &&
            calYesterday.get(Calendar.DAY_OF_YEAR) == calDate.get(Calendar.DAY_OF_YEAR)) {
            return "昨天";
        }

        Calendar calBeforeYesterday = Calendar.getInstance();
        calBeforeYesterday.setTimeInMillis(now);
        calBeforeYesterday.add(Calendar.DAY_OF_YEAR, -2);
        if (calBeforeYesterday.get(Calendar.YEAR) == calDate.get(Calendar.YEAR) &&
            calBeforeYesterday.get(Calendar.DAY_OF_YEAR) == calDate.get(Calendar.DAY_OF_YEAR)) {
            return "前天";
        }

        return sdfDay.format(date);
    }

    private static void closeQuietly(InputStream is) {
        if (is != null) {
            try {
                is.close();
            } catch (IOException ignored) {}
        }
    }
}
