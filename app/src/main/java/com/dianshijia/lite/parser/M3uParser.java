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
 * M3U 播放列表解析器，支持网络异步拉取与本地缓存读取。
 */
public class M3uParser {
    private static final String TAG = "M3uParser";
    private static final String CACHE_FILE_NAME = "tv_channels.m3u";

    public interface OnParseListener {
        void onParseSuccess(LinkedHashMap<String, List<Channel>> groupedChannels, List<Channel> allChannels);
        void onParseFailed(Exception e);
    }

    /**
     * 加载直播源列表：优先在线请求，失败时加载本地缓存
     */
    public static void loadChannels(final Context context, final String m3uUrl, final OnParseListener listener) {
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(m3uUrl)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Request failed, fallback to local cache", e);
                // 离线状态或网络异常：读取本地缓存
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
                // 缓存到本地
                saveToCache(context, content);
                // 解析
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
     * 解析 M3U 字符串的核心逻辑
     */
    private static void parseM3u(String content, OnParseListener listener) {
        LinkedHashMap<String, List<Channel>> groupedChannels = new LinkedHashMap<>();
        List<Channel> allChannels = new ArrayList<>();

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new StringReader(content));
            String line;
            String currentGroup = "其他频道"; // 默认分组名
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

                    // 解析频道名称（通常在最后一个逗号之后）
                    int commaIndex = line.lastIndexOf(",");
                    if (commaIndex != -1 && commaIndex < line.length() - 1) {
                        currentName = line.substring(commaIndex + 1).trim();
                    } else {
                        currentName = "未知频道";
                    }
                } else if (!line.startsWith("#")) {
                    // 直播流播放链接地址
                    String playUrl = line;
                    if (!currentName.isEmpty() && playUrl.startsWith("http")) {
                        // 格式化频道编号为 3 位，如 "001", "002"
                        String numberStr = String.format("%03d", channelCounter++);
                        Channel channel = new Channel(numberStr, currentName, playUrl, currentGroup);
                        
                        allChannels.add(channel);

                        // 按分类分组存储
                        if (!groupedChannels.containsKey(currentGroup)) {
                            groupedChannels.put(currentGroup, new ArrayList<Channel>());
                        }
                        groupedChannels.get(currentGroup).add(channel);
                    }
                    // 重置，防止 URL 行没有对应的 EXTINF
                    currentName = "";
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
