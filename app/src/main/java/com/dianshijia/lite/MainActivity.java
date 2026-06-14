package com.dianshijia.lite;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import android.view.Surface;
import android.view.TextureView;

import tv.danmaku.ijk.media.player.IjkMediaPlayer;
import tv.danmaku.ijk.media.player.IMediaPlayer;

import com.dianshijia.lite.model.Channel;
import com.dianshijia.lite.model.CatchupProgram;
import com.dianshijia.lite.parser.M3uParser;
import com.dianshijia.lite.parser.EpgParser;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final String PREFS_NAME = "LiveTVPrefs";
    private static final String KEY_LAST_URL = "last_played_url";
    private static final String M3U_URL = "https://my.3223516.xyz/tv.m3u";
    private static final String EPG_URL = "https://e.erw.cc/all.xml.gz";

    // 自动隐藏及延时执行常量
    private static final int MSG_HIDE_SIDEBAR = 1;
    private static final int MSG_HIDE_OVERLAY = 2;
    private static final int MSG_TRIGGER_NUM_SWITCH = 3;
    private static final int MSG_HIDE_CONTROLLER = 4;
    
    private static final long DELAY_AUTO_HIDE = 5000;  // 5秒无操作自动隐藏
    private static final long DELAY_NUM_SWITCH = 2000; // 数字键输入后2秒切台

    // UI 布局控件
    private TextureView playerView;
    private View layoutSidebar;
    private ListView listCategories;
    private ListView listChannels;
    private ListView listCatchup; // 第三列：回看节目单
    
    private View layoutInfoOverlay;
    private TextView textOverlayNum;
    private TextView textOverlayName;
    
    private View layoutLoading;
    private TextView textLoadingStatus;
    
    private View layoutNumberInput;
    private TextView textNumberInput;

    // 底部回看控制条
    private View layoutController;
    private TextView btnPlayPause;
    private TextView textTimeCurrent;
    private TextView textTimeTotal;
    private SeekBar seekBar;
    private TextView textBackToLive;

    // IjkPlayer 播放器与渲染表面
    private IjkMediaPlayer ijkPlayer;
    private Surface playSurface;
    
    // 数据源
    private final LinkedHashMap<String, List<Channel>> groupedChannels = new LinkedHashMap<>();
    private final List<Channel> allChannels = new ArrayList<>();
    private final List<String> categories = new ArrayList<>();
    private final List<CatchupProgram> catchupList = new ArrayList<>();
    
    private String currentCategory = "";
    private Channel currentChannel = null;
    private CatchupProgram currentCatchupProgram = null;

    // 列表适配器
    private CategoryAdapter categoryAdapter;
    private ChannelAdapter channelAdapter;
    private CatchupAdapter catchupAdapter;

    // 状态控制记录
    private boolean isSidebarShowing = false;
    private boolean isCatchupMode = false; // 是否处于回看播放状态
    private boolean isUserSeeking = false;  // 用户是否正在手动拖动进度条
    private long lastBackPressTime = 0;
    private final StringBuilder numberInputBuilder = new StringBuilder();

    private long tempSeekPosition = -1; // 缓动 Seek 的临时进度值
    private Runnable autoSwitchLineRunnable = null; // 自动换源/换线任务
    private LocalProxyServer proxyServer;

    private Runnable retryPlayRunnable = null; // 播放失败自动重试的任务

    // 自动更新提示与进度控件
    private androidx.appcompat.app.AlertDialog progressDialog;
    private ProgressBar downloadProgressBar;
    private TextView textDownloadPercent;

    // 真正执行 Seek 跳转的 Action (缓动 Seek)
    private final Runnable confirmSeekAction = new Runnable() {
        @Override
        public void run() {
            if (ijkPlayer != null && tempSeekPosition != -1) {
                ijkPlayer.seekTo(tempSeekPosition);
                tempSeekPosition = -1;
                showController(); // Seek后重新开始5秒倒计时隐藏控制条
            }
        }
    };



    // 定时刷新播放进度条 the Runnable
    private final Runnable updateProgressAction = new Runnable() {
        @Override
        public void run() {
            if (ijkPlayer != null && isCatchupMode && !isUserSeeking && tempSeekPosition == -1) {
                long position = ijkPlayer.getCurrentPosition();
                long duration = ijkPlayer.getDuration();
                if (duration > 0) {
                    int progress = (int) (position * 100 / duration);
                    seekBar.setProgress(progress);
                    textTimeCurrent.setText(formatTime(position));
                    textTimeTotal.setText(formatTime(duration));
                }
            }
            tvHandler.postDelayed(this, 1000);
        }
    };

    // 统一的 Handler
    private final Handler tvHandler = new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(@NonNull Message msg) {
            switch (msg.what) {
                case MSG_HIDE_SIDEBAR:
                    hideSidebar();
                    return true;
                case MSG_HIDE_OVERLAY:
                    layoutInfoOverlay.setVisibility(View.GONE);
                    return true;
                case MSG_TRIGGER_NUM_SWITCH:
                    performNumberSwitch();
                    return true;
                case MSG_HIDE_CONTROLLER:
                    if (isCatchupMode) {
                        layoutController.setVisibility(View.GONE);
                    }
                    return true;
            }
            return false;
        }
    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        enableTls12Helper();
        
        // 启动本地 Https 兼容代理服务，为老电视提供零协议与解密门槛的 HTTP 回环流
        try {
            proxyServer = new LocalProxyServer();
            proxyServer.start();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start local proxy server", e);
        }

        setContentView(R.layout.activity_main);

        initViews();
        setupPlayer();
        loadLiveChannels();
        checkAppUpdate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 启动进度条轮询刷新
        tvHandler.post(updateProgressAction);
    }

    @Override
    protected void onPause() {
        super.onPause();
        tvHandler.removeCallbacks(updateProgressAction);
    }

    private void initViews() {
        playerView = findViewById(R.id.player_view);
        playerView.setFocusable(false);
        playerView.setFocusableInTouchMode(false);
        playerView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture surfaceTexture, int width, int height) {
                playSurface = new Surface(surfaceTexture);
                if (ijkPlayer != null) {
                    ijkPlayer.setSurface(playSurface);
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture surface, int width, int height) {}

            @Override
            public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture surface) {
                if (playSurface != null) {
                    playSurface.release();
                    playSurface = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture surface) {}
        });
        layoutSidebar = findViewById(R.id.layout_sidebar);
        listCategories = findViewById(R.id.list_categories);
        listChannels = findViewById(R.id.list_channels);
        listCatchup = findViewById(R.id.list_catchup);
        
        layoutInfoOverlay = findViewById(R.id.layout_info_overlay);
        textOverlayNum = findViewById(R.id.text_overlay_num);
        textOverlayName = findViewById(R.id.text_overlay_name);
        
        layoutLoading = findViewById(R.id.layout_loading);
        textLoadingStatus = findViewById(R.id.text_loading_status);
        
        layoutNumberInput = findViewById(R.id.layout_number_input);
        textNumberInput = findViewById(R.id.text_number_input);

        // 控制栏视图绑定
        layoutController = findViewById(R.id.layout_controller);
        btnPlayPause = findViewById(R.id.btn_play_pause);
        textTimeCurrent = findViewById(R.id.text_time_current);
        textTimeTotal = findViewById(R.id.text_time_total);
        seekBar = findViewById(R.id.seek_bar);
        textBackToLive = findViewById(R.id.text_back_to_live);

        // 初始化适配器
        categoryAdapter = new CategoryAdapter();
        listCategories.setAdapter(categoryAdapter);

        channelAdapter = new ChannelAdapter();
        listChannels.setAdapter(channelAdapter);

        catchupAdapter = new CatchupAdapter();
        listCatchup.setAdapter(catchupAdapter);

        // 触屏交互支持：点击全屏画面呼出或收起侧边栏，同时也会激活回看控制栏的显示
        playerView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isSidebarShowing) {
                    hideSidebar();
                } else {
                    showSidebar();
                }
                if (isCatchupMode) {
                    showController();
                }
            }
        });

        // 遥控方向联动：当遥控在分类列表中移动时，刷新频道列表，并控制回看列表自动收起或根据对应频道显示
        listCategories.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < categories.size()) {
                    updateChannelList(categories.get(position));
                    listCatchup.setVisibility(View.GONE); // 分类切换，先关闭第三列回看
                    resetSidebarHideTimer();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // 分类列表点按响应
        listCategories.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < categories.size()) {
                    updateChannelList(categories.get(position));
                }
                listChannels.requestFocus();
                resetSidebarHideTimer();
            }
        });

        // 频道列表选中监听：在后台默默更新对应频道的回看节目列表
        listChannels.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                List<Channel> channels = groupedChannels.get(currentCategory);
                if (channels != null && position >= 0 && position < channels.size()) {
                    Channel channel = channels.get(position);
                    updateCatchupList(channel); // 静默更新回看列表数据，不显示 UI
                    resetSidebarHideTimer();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // 频道列表触屏点按监听：点击后自动开启直播，并同步拉起该频道的历史回看备用列表
        listChannels.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                List<Channel> channels = groupedChannels.get(currentCategory);
                if (channels != null && position >= 0 && position < channels.size()) {
                    Channel channel = channels.get(position);
                    isCatchupMode = false; // 触屏点台，默认直接切回直播模式
                    layoutController.setVisibility(View.GONE);
                    
                    playChannel(channel);

                    if (channel.getTvodUrl() != null) {
                        updateCatchupList(channel);
                        listCatchup.setVisibility(View.VISIBLE);
                    } else {
                        listCatchup.setVisibility(View.GONE);
                    }
                    resetSidebarHideTimer();
                }
            }
        });

        // 回看列表项点选处理（确定加载历史回放）
        listCatchup.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < catchupList.size()) {
                    CatchupProgram prog = catchupList.get(position);
                    if (prog.isLive) {
                        // 返回直播
                        isCatchupMode = false;
                        layoutController.setVisibility(View.GONE);
                        playChannel(currentChannel);
                    } else {
                        // 启动回看播放
                        playCatchup(currentChannel, prog);
                    }
                    hideSidebar();
                }
            }
        });

        // 底部进度控制栏触屏拖动 Seekbar 响应
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    isUserSeeking = true;
                    showController(); // 拖动时重置隐藏定时器
                    if (ijkPlayer != null) {
                        long targetPos = (ijkPlayer.getDuration() * progress) / 100;
                        textTimeCurrent.setText(formatTime(targetPos));
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                isUserSeeking = true;
                tvHandler.removeMessages(MSG_HIDE_CONTROLLER);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                isUserSeeking = false;
                if (ijkPlayer != null) {
                    long duration = ijkPlayer.getDuration();
                    long targetPos = (duration * seekBar.getProgress()) / 100;
                    ijkPlayer.seekTo(targetPos);
                }
                showController(); // 开启延迟隐藏控制条
            }
        });

        // 播放与暂停文字按钮点击
        btnPlayPause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlayPause();
            }
        });

        // 返回直播点击
        textBackToLive.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isCatchupMode = false;
                layoutController.setVisibility(View.GONE);
                playChannel(currentChannel);
            }
        });
    }

    private void setupPlayer() {
        try {
            IjkMediaPlayer.loadLibrariesOnce(null);
            IjkMediaPlayer.native_profileBegin("libijkplayer.so");
            Log.i(TAG, "IjkMediaPlayer native libraries loaded successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load ijkplayer native libraries", e);
            Toast.makeText(this, "播放内核加载失败，请检查安装包兼容性", Toast.LENGTH_LONG).show();
        }
    }

    private void stopIjkPlayer() {
        try {
            if (ijkPlayer != null) {
                ijkPlayer.stop();
            }
        } catch (Exception e) {
            Log.e(TAG, "stopIjkPlayer failed", e);
        }
    }

    private void releaseIjkPlayer() {
        try {
            if (ijkPlayer != null) {
                ijkPlayer.stop();
                ijkPlayer.setDisplay(null);
                ijkPlayer.setSurface(null);
                ijkPlayer.release();
                ijkPlayer = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "releaseIjkPlayer failed", e);
        }
    }

    private void loadLiveChannels() {
        M3uParser.loadChannels(this, M3U_URL, new M3uParser.OnParseListener() {
            @Override
            public void onParseSuccess(final LinkedHashMap<String, List<Channel>> parsedGrouped, final List<Channel> parsedAll) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        groupedChannels.clear();
                        groupedChannels.putAll(parsedGrouped);
                        allChannels.clear();
                        allChannels.addAll(parsedAll);

                        categories.clear();
                        categories.addAll(groupedChannels.keySet());

                        categoryAdapter.notifyDataSetChanged();
                        resumeLastWatched();

                        // 异步下载与解析 EPG 节目单
                        EpgParser.loadEpg(MainActivity.this, EPG_URL, allChannels, new Runnable() {
                            @Override
                            public void run() {
                                Log.i(TAG, "EPG XMLTV 节目单已成功加载，静默刷新当前频道回看单");
                                if (currentChannel != null) {
                                    updateCatchupList(currentChannel);
                                }
                            }
                        });
                    }
                });
            }

            @Override
            public void onParseFailed(Exception e) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        layoutLoading.setVisibility(View.VISIBLE);
                        textLoadingStatus.setText(R.string.parse_failed);
                    }
                });
            }
        });
    }

    private void resumeLastWatched() {
        if (allChannels.isEmpty()) return;

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String lastNumber = prefs.getString("last_played_channel_number", "");
        String lastUrl = prefs.getString(KEY_LAST_URL, "");

        Channel target = null;
        
        // 1. 优先使用频道台号匹配频道
        if (!lastNumber.isEmpty()) {
            for (Channel c : allChannels) {
                if (c.getNumber().equals(lastNumber)) {
                    target = c;
                    break;
                }
            }
        }

        // 2. 兼容旧版本：若未找到，兜底使用 URL 匹配频道
        if (target == null && !lastUrl.isEmpty()) {
            for (Channel c : allChannels) {
                if (c.getLiveUrls().contains(lastUrl)) {
                    target = c;
                    target.setCurrentLineIndex(c.getLiveUrls().indexOf(lastUrl));
                    break;
                }
            }
        }

        // 3. 全局默认第一个频道
        if (target == null) {
            target = allChannels.get(0);
        }

        // 4. 恢复并同步该频道上次选中的具体线路号，彻底解决 H.265 过滤或动态 Token 导致线路总是重置的 Bug
        int savedLineIndex = prefs.getInt("last_line_index_" + target.getNumber(), -1);
        if (savedLineIndex != -1 && savedLineIndex < target.getLiveUrls().size()) {
            target.setCurrentLineIndex(savedLineIndex);
        } else {
            target.setCurrentLineIndex(0);
        }

        playChannel(target);
    }

    private void updateChannelList(String category) {
        currentCategory = category;
        channelAdapter.notifyDataSetChanged();
        
        if (currentChannel != null && currentChannel.getGroup().equals(category)) {
            List<Channel> channels = groupedChannels.get(category);
            if (channels != null) {
                int index = channels.indexOf(currentChannel);
                if (index != -1) {
                    listChannels.setSelection(index);
                }
            }
        } else {
            listChannels.setSelection(0);
        }
    }

    /**
     * 播放指定频道直播 (支持指定多线号)
     */
    private void playChannel(Channel channel) {
        if (channel == null) return;
        if (autoSwitchLineRunnable != null) {
            tvHandler.removeCallbacks(autoSwitchLineRunnable);
            autoSwitchLineRunnable = null;
        }
        if (retryPlayRunnable != null) {
            tvHandler.removeCallbacks(retryPlayRunnable);
            retryPlayRunnable = null;
        }
        currentChannel = channel;
        currentCategory = channel.getGroup();

        String url = channel.getPlayUrl();
        if (url == null) {
            Toast.makeText(this, "该频道暂无播放信号", Toast.LENGTH_SHORT).show();
            return;
        }

        url = proxyUrlIfNeeded(url);
        startVideo(url);

        // 提示框展现台号 + 频道名 + 线路 (例: CCTV1 [线路 1/3])
        String lineInfo = "";
        if (channel.getLiveUrls().size() > 1) {
            lineInfo = " (线路 " + (channel.getCurrentLineIndex() + 1) + "/" + channel.getLiveUrls().size() + ")";
        }
        textOverlayName.setText(channel.getName() + lineInfo);
        showInfoOverlay();

        // 直播模式下强制隐藏进度条
        layoutController.setVisibility(View.GONE);

        // 缓存当前的播放状态，以便开机自启续播
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_LAST_URL, channel.getPlayUrl());
        editor.putString("last_played_channel_number", channel.getNumber());
        editor.putInt("last_line_index_" + channel.getNumber(), channel.getCurrentLineIndex());
        editor.apply();
    }

    private void playCatchup(Channel channel, CatchupProgram program) {
        if (channel == null || program == null) return;
        if (autoSwitchLineRunnable != null) {
            tvHandler.removeCallbacks(autoSwitchLineRunnable);
            autoSwitchLineRunnable = null;
        }
        if (retryPlayRunnable != null) {
            tvHandler.removeCallbacks(retryPlayRunnable);
            retryPlayRunnable = null;
        }
        isCatchupMode = true;
        currentCatchupProgram = program;

        String tvodBaseUrl = channel.getTvodUrl();
        if (tvodBaseUrl == null) {
            Toast.makeText(this, "该频道暂无回看流信号", Toast.LENGTH_SHORT).show();
            return;
        }

        // 拼接 IPTV TVOD playseek 回看协议参数：URL 末尾追加 playseek=开始时间-结束时间
        String separator = tvodBaseUrl.contains("?") ? "&" : "?";
        String catchupUrl = tvodBaseUrl + separator + "playseek=" + program.beginTime + "-" + program.endTime;

        catchupUrl = proxyUrlIfNeeded(catchupUrl);
        Log.i(TAG, "Playing catchup URL: " + catchupUrl);

        startVideo(catchupUrl);

        // 提示浮窗中展示
        textOverlayName.setText(channel.getName() + " [历史回放]");
        showInfoOverlay();

        // 展现底部回看操作控制条
        btnPlayPause.setText("⏸");
        showController();
    }

    private void startVideo(final String url) {
        releaseIjkPlayer();

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                layoutLoading.setVisibility(View.VISIBLE);
                textLoadingStatus.setText(R.string.loading_stream);
            }
        });

        try {
            ijkPlayer = new IjkMediaPlayer();

            // 启用 MediaCodec 硬解 (1为开启)；如硬解失败，ijkplayer 自动在底层无缝切换至 FFmpeg 软解，极富抗灾性
            ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1);
            ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1);
            ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1);

            // 禁用 OpenSL ES，改用系统兼容性最强的原生 AudioTrack 框架以适配老电视音频驱动
            ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 0);
            ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "overlay-format", IjkMediaPlayer.SDL_FCC_RV32);
            
            // 允许跳帧以防音视频不同步
            ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1);
            ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1);

            // 禁用 packet-buffering 缓冲区，取消多余网络缓存延迟，实现秒切换台
            ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 0);

            // 针对 HLS (M3U8) 直播流进行播放探测优化
            ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "http-detect-range-support", 0);
            ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 1000000); // 1秒分析时长
            ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 1024 * 64);      // 64KB探测首包大小
            ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "flush_packets", 1);

            // 配置防盗链 User-Agent
            String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/102.0.0.0 Safari/537.36";
            ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "user-agent", userAgent);

            if (playSurface != null) {
                ijkPlayer.setSurface(playSurface);
            }

            // 监听：准备完成
            ijkPlayer.setOnPreparedListener(new IMediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(IMediaPlayer mp) {
                    Log.i(TAG, "IjkPlayer onPrepared, start playing");
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            layoutLoading.setVisibility(View.GONE);
                            showInfoOverlay();
                        }
                    });
                    mp.start();
                }
            });

            // 监听：播放出错，执行自动换线 (切源) 或延时重试
            ijkPlayer.setOnErrorListener(new IMediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(IMediaPlayer mp, int what, int extra) {
                    Log.e(TAG, "IjkPlayer Error occurred: what=" + what + ", extra=" + extra);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!isCatchupMode && currentChannel != null && currentChannel.getLiveUrls().size() > 1) {
                                layoutLoading.setVisibility(View.VISIBLE);
                                textLoadingStatus.setText("当前线路加载失败，正在尝试自动换线...");

                                if (autoSwitchLineRunnable != null) {
                                    tvHandler.removeCallbacks(autoSwitchLineRunnable);
                                }
                                autoSwitchLineRunnable = new Runnable() {
                                    @Override
                                    public void run() {
                                        switchLine(true);
                                    }
                                };
                                tvHandler.postDelayed(autoSwitchLineRunnable, 3000);
                            } else {
                                layoutLoading.setVisibility(View.VISIBLE);
                                textLoadingStatus.setText(R.string.parse_failed);

                                if (retryPlayRunnable != null) {
                                    tvHandler.removeCallbacks(retryPlayRunnable);
                                }
                                retryPlayRunnable = new Runnable() {
                                    @Override
                                    public void run() {
                                        retryPlayRunnable = null;
                                        if (isCatchupMode && currentCatchupProgram != null) {
                                            playCatchup(currentChannel, currentCatchupProgram);
                                        } else if (currentChannel != null) {
                                            playChannel(currentChannel);
                                        }
                                    }
                                };
                                tvHandler.postDelayed(retryPlayRunnable, 3000);
                            }
                        }
                    });
                    return true; // 返回true代表我们内部已处理完毕
                }
            });

            // 监听：缓冲信息更新以实时展现加载状态
            ijkPlayer.setOnInfoListener(new IMediaPlayer.OnInfoListener() {
                @Override
                public boolean onInfo(IMediaPlayer mp, int what, int extra) {
                    if (what == IMediaPlayer.MEDIA_INFO_BUFFERING_START) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                layoutLoading.setVisibility(View.VISIBLE);
                                textLoadingStatus.setText(R.string.loading_stream);
                            }
                        });
                    } else if (what == IMediaPlayer.MEDIA_INFO_BUFFERING_END) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                layoutLoading.setVisibility(View.GONE);
                            }
                        });
                    }
                    return true;
                }
            });

            // 监听：回看节目播放完毕切换回直播状态
            ijkPlayer.setOnCompletionListener(new IMediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(IMediaPlayer mp) {
                    if (isCatchupMode) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                isCatchupMode = false;
                                layoutController.setVisibility(View.GONE);
                                playChannel(currentChannel);
                                Toast.makeText(MainActivity.this, "回看节目已播完，自动切回直播", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }
            });

            ijkPlayer.setDataSource(url);
            ijkPlayer.prepareAsync();

        } catch (Exception e) {
            Log.e(TAG, "IjkPlayer setup failed", e);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    layoutLoading.setVisibility(View.VISIBLE);
                    textLoadingStatus.setText(R.string.parse_failed);
                }
            });
        }
    }

    /**
     * 手动切换线路
     */
    private void switchLine(boolean next) {
        if (currentChannel == null || isCatchupMode) return;
        List<String> lines = currentChannel.getLiveUrls();
        if (lines.size() <= 1) {
            Toast.makeText(this, "该频道只有单条线路", Toast.LENGTH_SHORT).show();
            return;
        }

        int currentIndex = currentChannel.getCurrentLineIndex();
        int nextIndex;
        if (next) {
            nextIndex = (currentIndex + 1) % lines.size();
        } else {
            nextIndex = (currentIndex - 1 + lines.size()) % lines.size();
        }

        currentChannel.setCurrentLineIndex(nextIndex);
        playChannel(currentChannel);
    }

    private void switchChannel(boolean next) {
        if (allChannels.isEmpty()) return;

        int currentIndex = allChannels.indexOf(currentChannel);
        int newIndex;
        if (next) {
            newIndex = (currentIndex + 1) % allChannels.size();
        } else {
            newIndex = (currentIndex - 1 + allChannels.size()) % allChannels.size();
        }

        isCatchupMode = false; // 换台自动重置回直播模式
        layoutController.setVisibility(View.GONE);
        playChannel(allChannels.get(newIndex));
    }

    /**
     * 自动推演生成频道最近 24 小时的虚拟节目单
     */
    private void updateCatchupList(Channel channel) {
        if (channel == null) return;
        catchupList.clear();

        // 1. 新增第一项：“返回实时直播”的快捷触发键
        CatchupProgram live = new CatchupProgram();
        live.timeLabel = "[直播] 返回实时直播";
        live.isLive = true;
        catchupList.add(live);

        // 2. 优先使用真实的 EPG 节目单
        List<CatchupProgram> epgProgs = channel.getEpgPrograms();
        if (epgProgs != null && !epgProgs.isEmpty()) {
            // 倒序加入列表，使最新播完的节目排在最前面
            for (int i = epgProgs.size() - 1; i >= 0; i--) {
                catchupList.add(epgProgs.get(i));
            }
        } else {
            // 无 EPG 数据时，采用 24 小时虚拟节目单兜底
            long now = System.currentTimeMillis();
            SimpleDateFormat sdfLabel = new SimpleDateFormat("HH:mm", Locale.getDefault());
            SimpleDateFormat sdfParam = new SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault());
            SimpleDateFormat sdfDate = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

            long timeBlock = 2 * 60 * 60 * 1000; // 2小时的毫秒数
            
            // 偶数小时对齐（如 16:30 对齐至 16:00 起）
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(now);
            int hour = cal.get(Calendar.HOUR_OF_DAY);
            int alignHour = (hour / 2) * 2;
            cal.set(Calendar.HOUR_OF_DAY, alignHour);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);

            long baseTime = cal.getTimeInMillis();
            String todayStr = sdfDate.format(new Date(now));

            for (int i = 0; i < 12; i++) {
                long start = baseTime - i * timeBlock;
                long end = start + timeBlock;

                // 越过未来的时间段
                if (start > now) {
                    continue;
                }

                CatchupProgram p = new CatchupProgram();
                String dateStr = sdfDate.format(new Date(start));
                String dayLabel = dateStr.equals(todayStr) ? "今天" : "昨天";

                p.timeLabel = dayLabel + " " + sdfLabel.format(new Date(start)) + " - " + sdfLabel.format(new Date(end));
                p.beginTime = sdfParam.format(new Date(start));
                p.endTime = sdfParam.format(new Date(end));
                p.isLive = false;
                catchupList.add(p);
            }
        }

        catchupAdapter.notifyDataSetChanged();
    }

    // ==========================================
    // UI 控制及淡出机制
    // ==========================================

    private void showSidebar() {
        if (isSidebarShowing) return;
        isSidebarShowing = true;
        layoutSidebar.setVisibility(View.VISIBLE);
        
        // 打开侧边栏菜单时，默认隐藏回看列表，直到用户按右键拉出
        listCatchup.setVisibility(View.GONE);

        if (!categories.isEmpty()) {
            int catIndex = categories.indexOf(currentCategory);
            if (catIndex != -1) {
                listCategories.setSelection(catIndex);
            }
        }

        // 使用 post 异步队列就绪机制，保证在 View 彻底完成测量与可见后瞬间锁定焦点，解决首次呼出菜单无法操作的问题
        listChannels.post(new Runnable() {
            @Override
            public void run() {
                listChannels.requestFocus();
                listChannels.requestFocusFromTouch(); // 双重保障聚焦
                if (currentChannel != null) {
                    List<Channel> channels = groupedChannels.get(currentCategory);
                    if (channels != null) {
                        int chIndex = channels.indexOf(currentChannel);
                        if (chIndex != -1) {
                            listChannels.setSelection(chIndex);
                        }
                    }
                }
            }
        });
        resetSidebarHideTimer();
    }

    private void hideSidebar() {
        if (!isSidebarShowing) return;
        isSidebarShowing = false;
        layoutSidebar.setVisibility(View.GONE);

        tvHandler.removeMessages(MSG_HIDE_SIDEBAR);
    }

    private void resetSidebarHideTimer() {
        tvHandler.removeMessages(MSG_HIDE_SIDEBAR);
        tvHandler.sendEmptyMessageDelayed(MSG_HIDE_SIDEBAR, DELAY_AUTO_HIDE);
    }

    private void showInfoOverlay() {
        if (currentChannel == null) return;
        
        textOverlayNum.setText(currentChannel.getNumber());
        layoutInfoOverlay.setVisibility(View.VISIBLE);

        tvHandler.removeMessages(MSG_HIDE_OVERLAY);
        tvHandler.sendEmptyMessageDelayed(MSG_HIDE_OVERLAY, DELAY_AUTO_HIDE);
    }

    private void showController() {
        if (!isCatchupMode) return;
        layoutController.setVisibility(View.VISIBLE);
        tvHandler.removeMessages(MSG_HIDE_CONTROLLER);
        tvHandler.sendEmptyMessageDelayed(MSG_HIDE_CONTROLLER, DELAY_AUTO_HIDE);
    }

    private void togglePlayPause() {
        if (ijkPlayer == null || !isCatchupMode) return;
        if (ijkPlayer.isPlaying()) {
            ijkPlayer.pause();
            btnPlayPause.setText("▶");
        } else {
            ijkPlayer.start();
            btnPlayPause.setText("⏸");
        }
        showController();
    }



    private String formatTime(long ms) {
        long totalSeconds = ms / 1000;
        long seconds = totalSeconds % 60;
        long minutes = (totalSeconds / 60) % 60;
        long hours = totalSeconds / 3600;
        if (hours > 0) {
            return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
        }
    }

    // ==========================================
    // 遥控键盘交互核心逻辑接管
    // ==========================================

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isSidebarShowing) {
            resetSidebarHideTimer();
        }

        // 1. 如果数字键盘显示，且按了确定键：立即换台
        if (layoutNumberInput.getVisibility() == View.VISIBLE && 
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            tvHandler.removeMessages(MSG_TRIGGER_NUM_SWITCH);
            performNumberSwitch();
            return true;
        }

        // 2. 接管数字输入键 (0-9)
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            handleNumberInput(keyCode - KeyEvent.KEYCODE_0);
            return true;
        }

        // 3. 回看播放状态下的全屏遥控逻辑接管
        if (isCatchupMode && !isSidebarShowing) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    // 回看模式下按上下键：直接退出回看并换台 (切回直播频道)
                    isCatchupMode = false;
                    layoutController.setVisibility(View.GONE);
                    switchChannel(keyCode == KeyEvent.KEYCODE_DPAD_DOWN);
                    return true;

                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    // 回看模式下按左右键：实现流畅的缓动 Seek 快退/快进
                    long duration = ijkPlayer != null ? ijkPlayer.getDuration() : 0;
                    if (duration > 0) {
                        if (tempSeekPosition == -1) {
                            tempSeekPosition = ijkPlayer.getCurrentPosition();
                        }
                        long offset = (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) ? -30000 : 30000;
                        tempSeekPosition += offset;
                        if (tempSeekPosition < 0) tempSeekPosition = 0;
                        if (tempSeekPosition > duration) tempSeekPosition = duration;

                        // 显示进度条控制栏
                        showController();

                        // 实时移动 SeekBar 进度和刷新当前时间文本（防卡顿）
                        seekBar.setProgress((int) (tempSeekPosition * 100 / duration));
                        textTimeCurrent.setText(formatTime(tempSeekPosition));

                        // 防抖：重置 1.0 秒延迟真正触发 seekTo
                        tvHandler.removeCallbacks(confirmSeekAction);
                        tvHandler.postDelayed(confirmSeekAction, 1000);
                    }
                    return true;

                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    // 确定键在回看状态下为 播放/暂停
                    togglePlayPause();
                    return true;

                case KeyEvent.KEYCODE_BACK:
                    if (layoutController.getVisibility() == View.VISIBLE) {
                        layoutController.setVisibility(View.GONE);
                        return true;
                    } else {
                        // 一键返回直播
                        isCatchupMode = false;
                        playChannel(currentChannel);
                        Toast.makeText(this, "已退出回看，返回实时直播", Toast.LENGTH_SHORT).show();
                        return true;
                    }
            }
        }

        // 4. 直播模式或菜单状态下的遥控键接管
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (!isSidebarShowing) {
                    switchChannel(false); // 直播向上换台
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (!isSidebarShowing) {
                    switchChannel(true); // 直播向下换台
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (!isSidebarShowing) {
                    showSidebar();
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (isSidebarShowing) {
                    if (listCatchup.hasFocus()) {
                        // 回看列表向左：回到频道列表并收起回看列表
                        listCatchup.setVisibility(View.GONE);
                        listChannels.requestFocus();
                        return true;
                    } else if (listChannels.hasFocus()) {
                        // 频道列表向左：回到分类列表
                        listCategories.requestFocus();
                        return true;
                    }
                } else {
                    // 直播状态按左键：切换上一个线路
                    switchLine(false);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (isSidebarShowing) {
                    if (listCategories.hasFocus()) {
                        // 分类列表向右：进入频道列表
                        listChannels.requestFocus();
                        return true;
                    } else if (listChannels.hasFocus()) {
                        // 频道列表向右：拉出回看节目单并进入焦点
                        if (currentChannel != null && (!currentChannel.getEpgPrograms().isEmpty() || currentChannel.getTvodUrl() != null)) {
                            // 确保在拉出时刷新节目单
                            updateCatchupList(currentChannel);
                            listCatchup.setVisibility(View.VISIBLE);
                            listCatchup.requestFocus();
                            listCatchup.setSelection(0); // 默认高亮“返回直播”项
                        } else {
                            Toast.makeText(this, "该频道暂无回看节目单", Toast.LENGTH_SHORT).show();
                        }
                        return true;
                    }
                } else {
                    // 直播状态按右键：切换下一个线路
                    switchLine(true);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_BACK:
                // 1. 如果数字换台输入框可见，按返回键先取消并隐藏它
                if (layoutNumberInput.getVisibility() == View.VISIBLE) {
                    tvHandler.removeMessages(MSG_TRIGGER_NUM_SWITCH);
                    numberInputBuilder.setLength(0);
                    layoutNumberInput.setVisibility(View.GONE);
                    return true;
                }
                // 2. 如果侧边栏处于显示状态
                if (isSidebarShowing) {
                    // 若第三列（回看单）聚焦，按返回键仅关闭第三列并退回到第二列（频道列表）
                    if (listCatchup.getVisibility() == View.VISIBLE && listCatchup.hasFocus()) {
                        listCatchup.setVisibility(View.GONE);
                        listChannels.requestFocus();
                        return true;
                    }
                    // 否则隐藏整个侧边栏
                    hideSidebar();
                    return true;
                } else if (layoutController.getVisibility() == View.VISIBLE) {
                    layoutController.setVisibility(View.GONE);
                    return true;
                } else {
                    // 常规双击退出程序
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastBackPressTime < 2000) {
                        finish();
                    } else {
                        lastBackPressTime = currentTime;
                        Toast.makeText(this, R.string.press_again_exit, Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void handleNumberInput(int digit) {
        tvHandler.removeMessages(MSG_TRIGGER_NUM_SWITCH);
        
        if (numberInputBuilder.length() < 3) {
            numberInputBuilder.append(digit);
        }

        layoutNumberInput.setVisibility(View.VISIBLE);
        textNumberInput.setText(numberInputBuilder.toString());

        tvHandler.sendEmptyMessageDelayed(MSG_TRIGGER_NUM_SWITCH, DELAY_NUM_SWITCH);
    }

    private void performNumberSwitch() {
        String numStr = numberInputBuilder.toString();
        numberInputBuilder.setLength(0);
        layoutNumberInput.setVisibility(View.GONE);

        if (numStr.isEmpty()) return;

        int targetNum = Integer.parseInt(numStr);
        String formattedNum = String.format(Locale.getDefault(), "%03d", targetNum);

        Channel match = null;
        for (Channel c : allChannels) {
            if (c.getNumber().equals(formattedNum) || c.getNumber().equals(numStr)) {
                match = c;
                break;
            }
        }

        if (match != null) {
            isCatchupMode = false;
            layoutController.setVisibility(View.GONE);
            playChannel(match);
        } else {
            Toast.makeText(this, "台号 " + numStr + " 不存在", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopIjkPlayer();
    }

    @Override
    protected void onDestroy() {
        if (proxyServer != null) {
            proxyServer.stop();
            proxyServer = null;
        }
        super.onDestroy();
        releaseIjkPlayer();
        tvHandler.removeCallbacksAndMessages(null);
    }

    private static String getProxyUrl(String originalUrl, int port) {
        if (originalUrl == null) return null;
        String encodedUrl = android.net.Uri.encode(originalUrl);
        String ext = "";
        int questionMark = originalUrl.indexOf('?');
        String pathOnly = (questionMark != -1) ? originalUrl.substring(0, questionMark) : originalUrl;
        int lastDot = pathOnly.lastIndexOf('.');
        if (lastDot != -1) {
            ext = pathOnly.substring(lastDot);
        }
        // 为了防止非字母数字字符或者太长，限制一下后缀名长度（比如 2 到 10 个字符）且符合后缀特征
        if (ext.length() > 1 && ext.length() < 10 && ext.matches("\\.[a-zA-Z0-9]+")) {
            return "http://127.0.0.1:" + port + "/proxy" + ext + "?url=" + encodedUrl;
        }
        return "http://127.0.0.1:" + port + "/proxy?url=" + encodedUrl;
    }

    private String proxyUrlIfNeeded(String url) {
        if (url == null) return null;
        // V1.3.9: 仅对 https:// 加密源开启本地代理，纯 http:// 优质源直连播放以获得最高传输性能与连接稳定性
        if (android.os.Build.VERSION.SDK_INT <= 22 && url.startsWith("https://")) {
            if (proxyServer != null && proxyServer.isRunning()) {
                return getProxyUrl(url, proxyServer.getPort());
            }
        }
        return url;
    }

    // ==========================================
    // 列表适配器 (Adapters)
    // ==========================================

    private class CategoryAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return categories.size();
        }

        @Override
        public Object getItem(int position) {
            return categories.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_category, parent, false);
            }
            TextView tv = (TextView) convertView;
            String cat = categories.get(position);
            tv.setText(cat);
            
            tv.setSelected(cat.equals(currentCategory));
            return convertView;
        }
    }

    private class ChannelAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            List<Channel> list = groupedChannels.get(currentCategory);
            return list != null ? list.size() : 0;
        }

        @Override
        public Object getItem(int position) {
            List<Channel> list = groupedChannels.get(currentCategory);
            return list != null ? list.get(position) : null;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_channel, parent, false);
            }
            
            List<Channel> list = groupedChannels.get(currentCategory);
            if (list != null && position >= 0 && position < list.size()) {
                Channel c = list.get(position);
                TextView numTv = convertView.findViewById(R.id.text_channel_number);
                TextView nameTv = convertView.findViewById(R.id.text_channel_name);

                numTv.setText(c.getNumber());
                nameTv.setText(c.getName());

                convertView.setSelected(c == currentChannel);
            }
            return convertView;
        }
    }

    private class CatchupAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return catchupList.size();
        }

        @Override
        public Object getItem(int position) {
            return catchupList.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_category, parent, false);
            }
            TextView tv = (TextView) convertView;
            CatchupProgram prog = catchupList.get(position);
            tv.setText(prog.timeLabel);
            
            // 返回直播高亮亮金黄色
            if (prog.isLive) {
                tv.setTextColor(getResources().getColor(R.color.accent_gold));
            } else {
                tv.setTextColor(getResources().getColor(R.color.white));
            }

            boolean isSelected = isCatchupMode && (prog == currentCatchupProgram || 
                                 (currentCatchupProgram != null && prog != null &&
                                  !prog.isLive && !currentCatchupProgram.isLive &&
                                  prog.beginTime.equals(currentCatchupProgram.beginTime)));
            tv.setSelected(isSelected);
            return convertView;
        }
    }

    private void enableTls12Helper() {
        if (android.os.Build.VERSION.SDK_INT >= 16 && android.os.Build.VERSION.SDK_INT <= 20) {
            try {
                javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLSv1.2");
                sslContext.init(null, null, null);
                javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(
                    new TLSSocketFactory(sslContext.getSocketFactory())
                );
                Log.i(TAG, "Successfully enabled TLSv1.2 on legacy Android device");
            } catch (Exception e) {
                Log.e(TAG, "Failed to enable TLSv1.2", e);
            }
        }
    }

    private static class TLSSocketFactory extends javax.net.ssl.SSLSocketFactory {
        private final javax.net.ssl.SSLSocketFactory delegate;

        public TLSSocketFactory(javax.net.ssl.SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        private java.net.Socket enableTLS(java.net.Socket socket) {
            if (socket instanceof javax.net.ssl.SSLSocket) {
                ((javax.net.ssl.SSLSocket) socket).setEnabledProtocols(new String[]{"TLSv1.1", "TLSv1.2"});
            }
            return socket;
        }

        @Override
        public java.net.Socket createSocket(java.net.Socket s, String host, int port, boolean autoClose) throws java.io.IOException {
            return enableTLS(delegate.createSocket(s, host, port, autoClose));
        }

        @Override
        public java.net.Socket createSocket(String host, int port) throws java.io.IOException {
            return enableTLS(delegate.createSocket(host, port));
        }

        @Override
        public java.net.Socket createSocket(String host, int port, java.net.InetAddress localHost, int localPort) throws java.io.IOException {
            return enableTLS(delegate.createSocket(host, port, localHost, localPort));
        }

        @Override
        public java.net.Socket createSocket(java.net.InetAddress host, int port) throws java.io.IOException {
            return enableTLS(delegate.createSocket(host, port));
        }

        @Override
        public java.net.Socket createSocket(java.net.InetAddress address, int port, java.net.InetAddress localAddress, int localPort) throws java.io.IOException {
            return enableTLS(delegate.createSocket(address, port, localAddress, localPort));
        }
    }

    private static class LocalProxyServer {
        private java.net.ServerSocket serverSocket;
        private int port = 0;
        private boolean isRunning = false;
        private final okhttp3.OkHttpClient okHttpClient;

        public LocalProxyServer() {
            okhttp3.OkHttpClient.Builder builder = new okhttp3.OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS);
            
            try {
                // 1. 全局配置忽略所有 SSL 证书的信任管理器，彻底消灭老电视根证书过期导致的 SSL 握手异常
                final javax.net.ssl.TrustManager[] trustAllCerts = new javax.net.ssl.TrustManager[]{
                    new javax.net.ssl.X509TrustManager() {
                        @Override
                        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                        @Override
                        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                        @Override
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[0];
                        }
                    }
                };

                // 2. 初始化 SSLContext，支持 TLS 1.2
                javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLSv1.2");
                sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
                
                // 3. 对老电视 (API <= 20) 注入强制开启 TLS 1.2 / 1.1 的 Socket 工厂，对高版本使用标准套件
                if (android.os.Build.VERSION.SDK_INT <= 20) {
                    builder.sslSocketFactory(new TLSSocketFactory(sslContext.getSocketFactory()), (javax.net.ssl.X509TrustManager) trustAllCerts[0]);
                } else {
                    builder.sslSocketFactory(sslContext.getSocketFactory(), (javax.net.ssl.X509TrustManager) trustAllCerts[0]);
                }
                
                // 4. 忽略域名验证以提高兼容度
                builder.hostnameVerifier(new javax.net.ssl.HostnameVerifier() {
                    @Override
                    public boolean verify(String hostname, javax.net.ssl.SSLSession session) {
                        return true;
                    }
                });
            } catch (Exception e) {
                Log.e("LocalProxyServer", "Failed to setup trust-all SSL configuration", e);
            }
            this.okHttpClient = builder.build();
        }

        public void start() throws java.io.IOException {
            serverSocket = new java.net.ServerSocket(0);
            port = serverSocket.getLocalPort();
            isRunning = true;
            
            new Thread(new Runnable() {
                @Override
                public void run() {
                    while (isRunning) {
                        try {
                            final java.net.Socket clientSocket = serverSocket.accept();
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    handleRequest(clientSocket);
                                }
                            }).start();
                        } catch (java.io.IOException ignored) {}
                    }
                }
            }).start();
            Log.i("LocalProxyServer", "Proxy server started on port: " + port);
        }

        public int getPort() {
            return port;
        }

        public boolean isRunning() {
            return isRunning;
        }

        public void stop() {
            isRunning = false;
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (java.io.IOException ignored) {}
            }
        }

        /**
         * V1.3.0: 逐行解析 M3U8 内容，对每一行非注释 URL 行进行代理改写。
         * 同时支持绝对路径 (http:// / https://) 和相对路径 (如 segment.ts?xxx)。
         * @param content M3U8 原始文本
         * @param localPort 本地代理端口
         * @param finalBaseUrl OkHttp 跟随 302 重定向后的最终 URL，用于解析相对路径
         */
        private String rewriteM3u8Content(String content, int localPort, String finalBaseUrl) {
            // 计算 base URL (去掉最后一个 / 之后的文件名部分)
            String baseUrl = "";
            if (finalBaseUrl != null) {
                int lastSlash = finalBaseUrl.lastIndexOf('/');
                if (lastSlash > 0) {
                    baseUrl = finalBaseUrl.substring(0, lastSlash + 1);
                }
            }

            String[] lines = content.split("\n");
            StringBuilder sb = new StringBuilder();
            boolean skipNextLine = false;
            for (String line : lines) {
                String trimmed = line.trim();
                
                if (skipNextLine) {
                    skipNextLine = false;
                    continue; // 过滤 H.265 子流对应的 URL 行
                }
                
                if (trimmed.isEmpty()) {
                    sb.append(line).append("\n");
                } else if (trimmed.startsWith("#")) {
                    // 如果在 API <= 22 设备上，发现自适应流包含 hev1/hvc1/h265，则过滤该流
                    if (trimmed.startsWith("#EXT-X-STREAM-INF:")) {
                        boolean isH265 = trimmed.toLowerCase(Locale.US).contains("hev1") ||
                                         trimmed.toLowerCase(Locale.US).contains("hvc1") ||
                                         trimmed.toLowerCase(Locale.US).contains("h265");
                        if (isH265 && android.os.Build.VERSION.SDK_INT <= 22) {
                            skipNextLine = true; // 下一行 URL 需过滤
                            continue;
                        }
                    }
                    sb.append(line).append("\n");
                } else {
                    // URL 行：可能是绝对路径或相对路径
                    String absoluteUrl;
                    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                        absoluteUrl = trimmed;
                    } else {
                        // 相对路径，基于最终重定向 URL 拼接
                        absoluteUrl = baseUrl + trimmed;
                    }
                    // 改写为本地代理地址
                    String proxyUrl = getProxyUrl(absoluteUrl, localPort);
                    sb.append(proxyUrl).append("\n");
                }
            }
            return sb.toString();
        }

        private void handleRequest(java.net.Socket clientSocket) {
            java.io.BufferedReader in = null;
            java.io.OutputStream out = null;
            try {
                in = new java.io.BufferedReader(new java.io.InputStreamReader(clientSocket.getInputStream()));
                out = clientSocket.getOutputStream();

                String requestLine = in.readLine();
                if (requestLine == null || !requestLine.startsWith("GET")) {
                    return;
                }

                String path = requestLine.split(" ")[1];
                int urlIndex = path.indexOf("url=");
                if (urlIndex == -1) {
                    out.write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n".getBytes());
                    out.flush();
                    return;
                }

                String targetUrl = android.net.Uri.decode(path.substring(urlIndex + 4));
                Log.d("LocalProxyServer", "Proxying request for: " + targetUrl);

                okhttp3.Request.Builder requestBuilder = new okhttp3.Request.Builder()
                        .url(targetUrl);

                // 读取客户端发来的 Headers，排除 Host 后透传给远端服务器，解决防盗链与拉流参数兼容问题
                String headerLine;
                while ((headerLine = in.readLine()) != null && !headerLine.trim().isEmpty()) {
                    int colonIndex = headerLine.indexOf(':');
                    if (colonIndex != -1) {
                        String name = headerLine.substring(0, colonIndex).trim();
                        String value = headerLine.substring(colonIndex + 1).trim();
                        if (!name.equalsIgnoreCase("Host")) {
                            requestBuilder.header(name, value);
                        }
                    }
                }

                // 保证有兜底的 User-Agent
                okhttp3.Request request = requestBuilder.build();
                if (request.header("User-Agent") == null) {
                    request = request.newBuilder()
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/102.0.0.0 Safari/537.36")
                            .build();
                }

                okhttp3.Response response = okHttpClient.newCall(request).execute();

                // V1.3.0: 获取 OkHttp 跟随 302 重定向后的最终 URL，用于 M3U8 相对路径的 base 拼接
                String finalUrl = response.request().url().toString();
                Log.d("LocalProxyServer", "Final URL after redirects: " + finalUrl);
                
                String message = response.message();
                if (message == null || message.trim().isEmpty()) {
                    message = "OK";
                }
                String statusLine = "HTTP/1.1 " + response.code() + " " + message + "\r\n";
                out.write(statusLine.getBytes());
                out.write("Connection: close\r\n".getBytes()); // 增加 Connection: close 强制关闭连接

                // 透传远端响应的 Headers（如 Set-Cookie）给客户端，以支持防盗链会话校验
                for (int i = 0; i < response.headers().size(); i++) {
                    String name = response.headers().name(i);
                    String value = response.headers().value(i);
                    if (!name.equalsIgnoreCase("Content-Length") && 
                        !name.equalsIgnoreCase("Content-Type") && 
                        !name.equalsIgnoreCase("Connection") && 
                        !name.equalsIgnoreCase("Transfer-Encoding")) {
                        out.write((name + ": " + value + "\r\n").getBytes());
                    }
                }

                okhttp3.MediaType contentType = null;
                if (response.body() != null) {
                    contentType = response.body().contentType();
                }

                // 智能判定是否是 HLS (M3U8) 索引流
                boolean isM3u8 = targetUrl.contains(".m3u8") || finalUrl.contains(".m3u8") ||
                                 (contentType != null && (contentType.toString().contains("mpegurl") || contentType.toString().contains("mpegURL")));

                if (isM3u8 && response.body() != null) {
                    // 读取 M3U8 的全部文本
                    String m3u8Content = response.body().string();
                    // V1.3.0 核心：使用最终 URL 作为 base，逐行改写所有 URL（含相对路径）为本地代理地址
                    String rewrittenContent = rewriteM3u8Content(m3u8Content, port, finalUrl);
                    
                    byte[] m3u8Bytes = rewrittenContent.getBytes("UTF-8");
                    out.write(("Content-Type: " + (contentType != null ? contentType.toString() : "application/vnd.apple.mpegurl") + "\r\n").getBytes());
                    out.write(("Content-Length: " + m3u8Bytes.length + "\r\n").getBytes());
                    out.write("\r\n".getBytes());
                    out.write(m3u8Bytes);
                    out.flush();
                } else {
                    // 视频 TS 分片或普通媒体二进制流，以极速、零内存占用的二进制 Pipe 方式回吐数据
                    if (contentType != null) {
                        out.write(("Content-Type: " + contentType.toString() + "\r\n").getBytes());
                    }
                    if (response.body() != null) {
                        long contentLength = response.body().contentLength();
                        if (contentLength >= 0) {
                            out.write(("Content-Length: " + contentLength + "\r\n").getBytes());
                        }
                    }
                    out.write("\r\n".getBytes());
                    out.flush();

                    if (response.body() != null) {
                        java.io.InputStream responseStream = response.body().byteStream();
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = responseStream.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                        }
                        out.flush();
                    }
                }
                response.close();

            } catch (Exception e) {
                Log.e("LocalProxyServer", "Proxy error", e);
                try {
                    if (out != null) {
                        out.write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".getBytes());
                        out.flush();
                    }
                } catch (java.io.IOException ignored) {}
            } finally {
                try {
                    if (in != null) in.close();
                    if (out != null) out.close();
                    clientSocket.close();
                } catch (java.io.IOException ignored) {}
            }
        }
    }

    // ==========================================
    // 自动更新检测与执行逻辑
    // ==========================================
    private static class UpdateInfo {
        int versionCode;
        String versionName;
        String apkUrl;
        String updateMessage;
    }

    private void checkAppUpdate() {
        String updateUrl = "https://my.3223516.xyz/update.json";
        okhttp3.OkHttpClient client = com.dianshijia.lite.util.OkHttpUtils.getOkHttpClient();
        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(updateUrl)
                .build();
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, java.io.IOException e) {
                Log.e(TAG, "Update check failed", e);
            }

            @Override
            public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                if (response.isSuccessful() && response.body() != null) {
                    final String json = response.body().string();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            parseUpdateJson(json);
                        }
                    });
                }
            }
        });
    }

    private void parseUpdateJson(String json) {
        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            UpdateInfo info = gson.fromJson(json, UpdateInfo.class);
            if (info != null) {
                int currentVersionCode = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
                if (info.versionCode > currentVersionCode) {
                    showUpdateDialog(info);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse update json", e);
        }
    }

    private void showUpdateDialog(final UpdateInfo info) {
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_DianshijiaLite_Dialog);
        builder.setTitle("发现新版本 " + info.versionName);
        builder.setMessage(info.updateMessage);
        builder.setCancelable(false);
        builder.setPositiveButton("立即更新", new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface dialog, int which) {
                startDownloadApk(info.apkUrl);
            }
        });
        builder.setNegativeButton("以后再说", new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface dialog, int which) {
                dialog.dismiss();
            }
        });
        androidx.appcompat.app.AlertDialog dialog = builder.create();
        dialog.show();
    }

    private void showDownloadProgressDialog() {
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_DianshijiaLite_Dialog);
        View view = LayoutInflater.from(this).inflate(R.layout.layout_update_progress, null);
        downloadProgressBar = view.findViewById(R.id.progress_bar);
        textDownloadPercent = view.findViewById(R.id.text_download_percent);
        builder.setView(view);
        builder.setCancelable(false);
        progressDialog = builder.create();
        progressDialog.show();
    }

    private void startDownloadApk(final String url) {
        showDownloadProgressDialog();

        okhttp3.OkHttpClient client = com.dianshijia.lite.util.OkHttpUtils.getOkHttpClient();
        okhttp3.Request request = new okhttp3.Request.Builder().url(url).build();
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, final java.io.IOException e) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (progressDialog != null && progressDialog.isShowing()) {
                            progressDialog.dismiss();
                        }
                        Toast.makeText(MainActivity.this, "下载更新失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            }

            @Override
            public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                if (!response.isSuccessful()) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (progressDialog != null && progressDialog.isShowing()) {
                                progressDialog.dismiss();
                            }
                            Toast.makeText(MainActivity.this, "下载更新失败: 响应错误", Toast.LENGTH_LONG).show();
                        }
                    });
                    return;
                }

                java.io.InputStream is = null;
                java.io.FileOutputStream fos = null;
                try {
                    is = response.body().byteStream();
                    long total = response.body().contentLength();
                    
                    final File apkFile = new File(getExternalCacheDir(), "update.apk");
                    fos = new java.io.FileOutputStream(apkFile);

                    byte[] buf = new byte[4096];
                    long sum = 0;
                    int len;
                    while ((len = is.read(buf)) != -1) {
                        fos.write(buf, 0, len);
                        sum += len;
                        final int progress = total > 0 ? (int) (sum * 100 / total) : 0;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (downloadProgressBar != null) {
                                    downloadProgressBar.setProgress(progress);
                                }
                                if (textDownloadPercent != null) {
                                    textDownloadPercent.setText(progress + "%");
                                }
                            }
                        });
                    }
                    fos.flush();

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (progressDialog != null && progressDialog.isShowing()) {
                                progressDialog.dismiss();
                            }
                            installApk(apkFile);
                        }
                    });

                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (progressDialog != null && progressDialog.isShowing()) {
                                progressDialog.dismiss();
                            }
                            Toast.makeText(MainActivity.this, "保存更新文件出错: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                } finally {
                    try {
                        if (is != null) is.close();
                    } catch (Exception ignored) {}
                    try {
                        if (fos != null) fos.close();
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    private void installApk(File apkFile) {
        if (!apkFile.exists()) return;
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            android.net.Uri apkUri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apkFile);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        } else {
            intent.setDataAndType(android.net.Uri.fromFile(apkFile), "application/vnd.android.package-archive");
        }
        try {
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start install activity", e);
            Toast.makeText(this, "启动安装失败，请手动在文件管理器中安装", Toast.LENGTH_LONG).show();
        }
    }
}
