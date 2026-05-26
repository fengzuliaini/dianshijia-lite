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

    public static void loadChannels(final Context context, final String m3uUrl, final OnParseListener listener) {
        OkHttpClient client = new OkHttpClient();
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
     * 解析 M3U 并进行去重合并及多线路整合
     */
    private static void parseM3u(String content, OnParseListener listener) {
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
            int channelCounter = 1;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                if (line.startsWith("#EXTINF:")) {
                    // 解析分组 group-title
                    String groupTitleKey = "group-title=\"";
                    int groupStartIndex = line.indexOf(groupTitleKey);
                    if (groupStartIndex != -1) {
                        groupStartIndex += groupTitleKey.length();
                        int groupEndIndex = line.indexOf("\"", groupStartIndex);
                        if (groupEndIndex != -1) {
                            currentGroup = line.substring(groupStartIndex, groupEndIndex);
                        }
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
                        String key = currentName;
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

                        // 将 URL 分类添加进对应的直播列表或回看列表，避免重复添加相同 URL
                        if (playUrl.contains("/TVOD/")) {
                            if (!channel.getTvodUrls().contains(playUrl)) {
                                channel.getTvodUrls().add(playUrl);
                            }
                        } else {
                            if (!channel.getLiveUrls().contains(playUrl)) {
                                channel.getLiveUrls().add(playUrl);
                            }
                        }
                    }
                    currentName = ""; // 重置
                }
            }

            if (!allChannels.isEmpty()) {
                listener.onParseSuccess(groupedChannels, allChannels);
            } else {
                listener.onParseFailed(new Exception("Parsed 0 channels from M3u file."));
            }

        } catch (Exception e) {
            Log.e(TAG, "Parse failed", e);
            listener.onParseFailed(e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {}
            }
        }
    }
}
