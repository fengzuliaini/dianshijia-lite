package com.dianshijia.lite.parser;

import android.content.Context;
import android.util.Log;

import com.dianshijia.lite.model.Channel;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * M3U 播放列表解析器（重构去重版：支持同一个频道的直播/回看多线路收集）
 */
public class M3uParser {
    private static final String TAG = "M3uParser";
    private static final String CACHE_FILE_NAME = "tv_channels.m3u";

    public interface OnParseListener {
        void onParseSuccess(LinkedHashMap<String, List<Channel>> groupedChannels, List<Channel> allChannels);
        void onParseFailed(Exception e);
    }

    public static void loadFromAssets(final Context context, final String fileName, final OnParseListener listener) {
        try {
            java.io.InputStream is = context.getAssets().open(fileName);
            BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            Log.i(TAG, "Loaded assets file successfully: " + fileName);
            parseM3u(sb.toString(), listener);
        } catch (Exception e) {
            Log.e(TAG, "Load from assets failed", e);
            listener.onParseFailed(e);
        }
    }

    public static void loadChannels(final Context context, final String m3uUrl, final OnParseListener listener) {
        OkHttpClient client = com.dianshijia.lite.util.OkHttpUtils.getOkHttpClient();
        Request request = new Request.Builder()
                .url(m3uUrl)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Request failed, fallback to local cache", e);
                tryLocalCache(context, listener, e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    Log.e(TAG, "Response code error: " + response.code());
                    tryLocalCache(context, listener, new IOException("HTTP code " + response.code()));
                    return;
                }

                String content = response.body().string();
                saveToCache(context, content);
                parseM3u(content, listener);
            }
        });
    }

    private static void tryLocalCache(Context context, OnParseListener listener, Exception originalException) {
        File cacheFile = new File(context.getCacheDir(), CACHE_FILE_NAME);
        if (cacheFile.exists()) {
            try {
                StringBuilder sb = new StringBuilder();
                BufferedReader br = new BufferedReader(new FileReader(cacheFile));
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                br.close();
                Log.i(TAG, "Loaded from local cache successfully");
                parseM3u(sb.toString(), listener);
            } catch (Exception e) {
                Log.e(TAG, "Read cache failed", e);
                listener.onParseFailed(originalException);
            }
        } else {
            listener.onParseFailed(originalException);
        }
    }

    private static void saveToCache(Context context, String content) {
        try {
            File cacheFile = new File(context.getCacheDir(), CACHE_FILE_NAME);
            FileWriter writer = new FileWriter(cacheFile);
            writer.write(content);
            writer.flush();
            writer.close();
            Log.i(TAG, "M3u cached to: " + cacheFile.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Save cache failed", e);
        }
    }

    /**
     * 解析 M3U 并进行去重合并及多线路整合（自适应分流 TXT / M3U 格式）
     */
    private static void parseM3u(String content, OnParseListener listener) {
        if (content == null) {
            listener.onParseFailed(new Exception("Content is null"));
            return;
        }
        String trimmed = content.trim();
        if (!trimmed.startsWith("#EXTM3U") && !trimmed.startsWith("#EXTINF")) {
            parseTxt(content, listener);
            return;
        }

        LinkedHashMap<String, List<Channel>> groupedChannels = new LinkedHashMap<>();
        List<Channel> allChannels = new ArrayList<>();
        
        // 用于合并去重的 Map，Key 格式为 "分类组名_频道名"
        Map<String, Channel> channelMap = new LinkedHashMap<>();

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new StringReader(content));
            String line;
            String currentGroup = "其他频道";
            String currentName = "";
            String globalCatchup = null;
            String globalCatchupSource = null;
            String currentCatchup = null;
            String currentCatchupSource = null;
            String currentTvgId = null;
            int channelCounter = 1;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                if (line.startsWith("#EXTM3U")) {
                    globalCatchup = parseAttribute(line, "catchup");
                    globalCatchupSource = parseAttribute(line, "catchup-source");
                    continue;
                }

                if (line.startsWith("#EXTINF:")) {
                    String group = parseAttribute(line, "group-title");
                    if (group != null && !group.isEmpty()) {
                        currentGroup = group;
                    }

                    currentTvgId = parseAttribute(line, "tvg-id");
                    if (currentTvgId == null) {
                        currentTvgId = parseAttribute(line, "tvg-name");
                    }

                    currentCatchup = parseAttribute(line, "catchup");
                    if (currentCatchup == null) {
                        currentCatchup = globalCatchup;
                    }

                    currentCatchupSource = parseAttribute(line, "catchup-source");
                    if (currentCatchupSource == null) {
                        currentCatchupSource = globalCatchupSource;
                    }

                    // 解析频道名称
                    int commaIndex = line.lastIndexOf(",");
                    if (commaIndex != -1 && commaIndex < line.length() - 1) {
                        currentName = line.substring(commaIndex + 1).trim();
                    } else {
                        currentName = "未知频道";
                    }
                } else if (!line.startsWith("#")) {
                    String playUrl = line;
                    if (!currentName.isEmpty() && playUrl.startsWith("http")) {
                        String key = currentGroup + "_" + currentName;
                        Channel channel = channelMap.get(key);
                        
                        // 如果是第一次遇到该频道，则新建频道对象
                        if (channel == null) {
                            String numberStr = String.format("%03d", channelCounter++);
                            channel = new Channel(numberStr, currentName, currentGroup);
                            channelMap.put(key, channel);
                            
                            allChannels.add(channel);

                            if (!groupedChannels.containsKey(currentGroup)) {
                                groupedChannels.put(currentGroup, new ArrayList<Channel>());
                            }
                            groupedChannels.get(currentGroup).add(channel);
                        }

                        if (currentTvgId != null && (channel.getEpgId() == null || channel.getEpgId().isEmpty())) {
                            channel.setEpgId(currentTvgId);
                        }
                        if (currentCatchup != null && channel.getCatchupType() == null) {
                            channel.setCatchupType(currentCatchup);
                        }
                        if (currentCatchupSource != null && channel.getCatchupSource() == null) {
                            channel.setCatchupSource(currentCatchupSource);
                        }

                        // 区分普通直播流与纯 TVOD 时移流
                        if (playUrl.contains("/TVOD/")) {
                            // 纯 TVOD 源仅作为回看线路备选，避免直播被错误切到 TVOD 导致黑屏
                            if (!channel.getTvodUrls().contains(playUrl)) {
                                channel.getTvodUrls().add(playUrl);
                            }
                        } else {
                            // 普通直播源
                            if (!channel.getLiveUrls().contains(playUrl)) {
                                channel.getLiveUrls().add(playUrl);
                            }
                            // 如果包含 /PLTV/，自动为其构建配套的 TVOD 回看源
                            if (playUrl.contains("/PLTV/")) {
                                String tvodDerived = playUrl.replace("/PLTV/", "/TVOD/");
                                if (!channel.getTvodUrls().contains(tvodDerived)) {
                                    channel.getTvodUrls().add(tvodDerived);
                                }
                            }
                        }
                    }
                    currentName = ""; // 重置
                    currentCatchup = null;
                    currentCatchupSource = null;
                    currentTvgId = null;
                }
            }

            // 对所有频道的直播线路进行智能优先级排序（高清晰度 PLTV 优先，老电视 H.265 沉底）
            sortChannels(allChannels);

            if (!allChannels.isEmpty()) {
                listener.onParseSuccess(groupedChannels, allChannels);
            } else {
                listener.onParseFailed(new Exception("Parsed 0 channels from M3u file."));
            }

        } catch (Exception e) {
            Log.e(TAG, "Parse M3U failed", e);
            listener.onParseFailed(e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {}
            }
        }
    }

    /**
     * 从 EXTINF 等标记行中提取指定属性值
     */
    private static String parseAttribute(String line, String key) {
        if (line == null || key == null) return null;
        String pattern = key + "=\"";
        int start = line.indexOf(pattern);
        if (start != -1) {
            start += pattern.length();
            int end = line.indexOf("\"", start);
            if (end != -1) {
                return line.substring(start, end);
            }
        }
        return null;
    }

    /**
     * 解析 TXT 格式并进行合并去重及智能排序
     */
    private static void parseTxt(String content, OnParseListener listener) {
        LinkedHashMap<String, List<Channel>> groupedChannels = new LinkedHashMap<>();
        List<Channel> allChannels = new ArrayList<>();
        Map<String, Channel> channelMap = new LinkedHashMap<>();

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new StringReader(content));
            String line;
            String currentGroup = "其他频道";
            int channelCounter = 1;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                // 支持检测 ",#genre#" 类似的分类名行
                if (line.contains(",#genre#") || line.contains(", #genre#") || line.contains(",  #genre#")) {
                    int commaIndex = line.indexOf(",");
                    if (commaIndex != -1) {
                        currentGroup = line.substring(0, commaIndex).trim();
                    }
                    continue;
                }

                // 解析频道名称和播放源 URL 列表
                int commaIndex = line.indexOf(",");
                if (commaIndex != -1) {
                    String currentName = line.substring(0, commaIndex).trim();
                    String urlsPart = line.substring(commaIndex + 1).trim();

                    if (!currentName.isEmpty() && !urlsPart.isEmpty()) {
                        String[] urls = urlsPart.split("#");
                        String key = currentGroup + "_" + currentName;
                        Channel channel = channelMap.get(key);

                        if (channel == null) {
                            String numberStr = String.format("%03d", channelCounter++);
                            channel = new Channel(numberStr, currentName, currentGroup);
                            channelMap.put(key, channel);

                            allChannels.add(channel);

                            if (!groupedChannels.containsKey(currentGroup)) {
                                groupedChannels.put(currentGroup, new ArrayList<Channel>());
                            }
                            groupedChannels.get(currentGroup).add(channel);
                        }

                        for (String playUrl : urls) {
                            playUrl = playUrl.trim();
                            if (playUrl.isEmpty() || !playUrl.startsWith("http")) {
                                continue;
                            }

                            // 区分普通直播流与纯 TVOD 时移流
                            if (playUrl.contains("/TVOD/")) {
                                if (!channel.getTvodUrls().contains(playUrl)) {
                                    channel.getTvodUrls().add(playUrl);
                                }
                            } else {
                                if (!channel.getLiveUrls().contains(playUrl)) {
                                    channel.getLiveUrls().add(playUrl);
                                }
                                if (playUrl.contains("/PLTV/")) {
                                    String tvodDerived = playUrl.replace("/PLTV/", "/TVOD/");
                                    if (!channel.getTvodUrls().contains(tvodDerived)) {
                                        channel.getTvodUrls().add(tvodDerived);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 对所有频道的直播线路进行智能优先级排序（高清晰度 PLTV 优先，老电视 H.265 沉底）
            sortChannels(allChannels);

            if (!allChannels.isEmpty()) {
                listener.onParseSuccess(groupedChannels, allChannels);
            } else {
                listener.onParseFailed(new Exception("Parsed 0 channels from TXT file."));
            }

        } catch (Exception e) {
            Log.e(TAG, "Parse TXT failed", e);
            listener.onParseFailed(e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {}
            }
        }
    }

    /**
     * 智能重排频道下的直播线路（高清晰度 PLTV 优先，老电视 H.265 沉底，空源保底容灾）
     */
    private static void sortChannels(List<Channel> allChannels) {
        for (Channel channel : allChannels) {
            List<String> urls = channel.getLiveUrls();
            // 如果仅有 TVOD 线路没有单独直播源，尝试将 TVOD 转换为常规直播流作为保底
            if (urls.isEmpty() && !channel.getTvodUrls().isEmpty()) {
                for (String tvod : channel.getTvodUrls()) {
                    if (tvod.contains("/TVOD/")) {
                        urls.add(tvod.replace("/TVOD/", "/PLTV/"));
                    } else {
                        urls.add(tvod);
                    }
                }
            }

            if (urls.size() > 1) {
                java.util.Collections.sort(urls, new java.util.Comparator<String>() {
                    @Override
                    public int compare(String u1, String u2) {
                        int w1 = getUrlWeight(u1);
                        int w2 = getUrlWeight(u2);
                        return w1 - w2;
                    }

                    private int getUrlWeight(String url) {
                        String lower = url.toLowerCase(java.util.Locale.US);
                        boolean isH265 = lower.contains("h265") || lower.contains("hevc");
                        boolean isTvod = url.contains("/TVOD/");

                        // 老电视 SDK_INT <= 22（Android 5.1及以下），对 H.265/HEVC 加大权重值使其沉底
                        if (android.os.Build.VERSION.SDK_INT <= 22) {
                            if (isH265) {
                                return isTvod ? 5 : 4;
                            } else {
                                return isTvod ? 3 : 1;
                            }
                        } else {
                            return isTvod ? 3 : 1;
                        }
                    }
                });
            }
        }
    }
}
