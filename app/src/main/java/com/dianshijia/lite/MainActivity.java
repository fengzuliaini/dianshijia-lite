package com.dianshijia.lite;

import android.content.Context;
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
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ui.PlayerView;

import com.dianshijia.lite.model.Channel;
import com.dianshijia.lite.parser.M3uParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final String PREFS_NAME = "LiveTVPrefs";
    private static final String KEY_LAST_URL = "last_played_url";
    private static final String M3U_URL = "http://us.3223516.xyz:55000/tv.m3u";

    // 自动隐藏及延时执行常量
    private static final int MSG_HIDE_SIDEBAR = 1;
    private static final int MSG_HIDE_OVERLAY = 2;
    private static final int MSG_TRIGGER_NUM_SWITCH = 3;
    
    private static final long DELAY_AUTO_HIDE = 5000; // 菜单和浮层 5秒无操作后自动消失
    private static final long DELAY_NUM_SWITCH = 2000; // 数字换台延迟 2秒触发

    // UI 布局控件
    private PlayerView playerView;
    private View layoutSidebar;
    private ListView listCategories;
    private ListView listChannels;
    private View layoutInfoOverlay;
    private TextView textOverlayNum;
    private TextView textOverlayName;
    private View layoutLoading;
    private TextView textLoadingStatus;
    private View layoutNumberInput;
    private TextView textNumberInput;

    // ExoPlayer 播放器
    private ExoPlayer player;
    
    // 数据源管理
    private LinkedHashMap<String, List<Channel>> groupedChannels = new LinkedHashMap<>();
    private List<Channel> allChannels = new ArrayList<>();
    private List<String> categories = new ArrayList<>();
    
    private String currentCategory = "";
    private Channel currentChannel = null;

    // 列表适配器
    private CategoryAdapter categoryAdapter;
    private ChannelAdapter channelAdapter;

    // 状态记录
    private boolean isSidebarShowing = false;
    private long lastBackPressTime = 0;
    private final StringBuilder numberInputBuilder = new StringBuilder();

    // 消息与定时处理器
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
            }
            return false;
        }
    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 初始化所有控件视图
        initViews();

        // 配置并播放器实例化
        setupPlayer();

        // 异步下载解析 M3U 直播源
        loadLiveChannels();
    }

    private void initViews() {
        playerView = findViewById(R.id.player_view);
        layoutSidebar = findViewById(R.id.layout_sidebar);
        listCategories = findViewById(R.id.list_categories);
        listChannels = findViewById(R.id.list_channels);
        
        layoutInfoOverlay = findViewById(R.id.layout_info_overlay);
        textOverlayNum = findViewById(R.id.text_overlay_num);
        textOverlayName = findViewById(R.id.text_overlay_name);
        
        layoutLoading = findViewById(R.id.layout_loading);
        textLoadingStatus = findViewById(R.id.text_loading_status);
        
        layoutNumberInput = findViewById(R.id.layout_number_input);
        textNumberInput = findViewById(R.id.text_number_input);

        // 初始化列表及其适配器
        categoryAdapter = new CategoryAdapter();
        listCategories.setAdapter(categoryAdapter);

        channelAdapter = new ChannelAdapter();
        listChannels.setAdapter(channelAdapter);

        // 监听分类列表的焦点与选中事件
        listCategories.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < categories.size()) {
                    updateChannelList(categories.get(position));
                    resetSidebarHideTimer();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // 监听分类列表的点按事件
        listCategories.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                // 分类选定后，自动把遥控焦点移到右侧频道列表
                listChannels.requestFocus();
                resetSidebarHideTimer();
            }
        });

        // 监听频道列表的点按事件（确定播放）
        listChannels.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                List<Channel> channels = groupedChannels.get(currentCategory);
                if (channels != null && position >= 0 && position < channels.size()) {
                    playChannel(channels.get(position));
                    hideSidebar(); // 选中后隐藏菜单
                }
            }
        });
    }

    private void setupPlayer() {
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        playerView.setKeepScreenOn(true); // 保证直播时屏幕不会进入休眠模式

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                switch (state) {
                    case Player.STATE_BUFFERING:
                        layoutLoading.setVisibility(View.VISIBLE);
                        textLoadingStatus.setText(R.string.loading_stream);
                        break;
                    case Player.STATE_READY:
                        layoutLoading.setVisibility(View.GONE);
                        showInfoOverlay();
                        break;
                    case Player.STATE_ENDED:
                        Log.i(TAG, "Playback ended");
                        break;
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                Log.e(TAG, "ExoPlayer Error: ", error);
                layoutLoading.setVisibility(View.VISIBLE);
                textLoadingStatus.setText(R.string.parse_failed);
                
                // 播放失败 3秒后尝试重试
                tvHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (currentChannel != null) {
                            textLoadingStatus.setText(R.string.retry_loading);
                            playChannel(currentChannel);
                        }
                    }
                }, 3000);
            }
        });
    }

    /**
     * 加载直播源
     */
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

                        // 读取记忆播放，优先开播
                        resumeLastWatched();
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

    /**
     * 自动恢复上次播放的频道，如果没有记录，则默认播放第一个频道
     */
    private void resumeLastWatched() {
        if (allChannels.isEmpty()) return;

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String lastUrl = prefs.getString(KEY_LAST_URL, "");

        Channel target = null;
        if (!lastUrl.isEmpty()) {
            for (Channel c : allChannels) {
                if (c.getUrl().equals(lastUrl)) {
                    target = c;
                    break;
                }
            }
        }

        if (target == null) {
            target = allChannels.get(0); // 默认播第一个
        }

        playChannel(target);
    }

    /**
     * 更新频道列表
     */
    private void updateChannelList(String category) {
        currentCategory = category;
        channelAdapter.notifyDataSetChanged();
        
        // 保持频道列表滚动到合适位置
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
     * 播放指定频道
     */
    private void playChannel(Channel channel) {
        if (channel == null) return;
        currentChannel = channel;
        currentCategory = channel.getGroup();

        // 停止当前播放
        player.stop();
        
        // 装载新信号源
        MediaItem mediaItem = MediaItem.fromUri(channel.getUrl());
        player.setMediaItem(mediaItem);
        player.prepare();
        player.play();

        // 显示右上角台号提示
        showInfoOverlay();

        // 异步缓存本次播放历史记录，供下次记忆开机秒播
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_LAST_URL, channel.getUrl()).apply();
    }

    /**
     * 上下键快速切台
     */
    private void switchChannel(boolean next) {
        if (allChannels.isEmpty()) return;

        int currentIndex = allChannels.indexOf(currentChannel);
        int newIndex;
        if (next) {
            newIndex = (currentIndex + 1) % allChannels.size();
        } else {
            newIndex = (currentIndex - 1 + allChannels.size()) % allChannels.size();
        }

        playChannel(allChannels.get(newIndex));
    }

    // ==========================================
    // UI 控制及淡出机制
    // ==========================================

    private void showSidebar() {
        if (isSidebarShowing) return;
        isSidebarShowing = true;
        layoutSidebar.setVisibility(View.VISIBLE);

        // 同步焦点状态
        if (!categories.isEmpty()) {
            int catIndex = categories.indexOf(currentCategory);
            if (catIndex != -1) {
                listCategories.setSelection(catIndex);
            }
        }
        
        // 分类列表先获取焦点
        listCategories.requestFocus();
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
        textOverlayName.setText(currentChannel.getName());
        layoutInfoOverlay.setVisibility(View.VISIBLE);

        tvHandler.removeMessages(MSG_HIDE_OVERLAY);
        tvHandler.sendEmptyMessageDelayed(MSG_HIDE_OVERLAY, DELAY_AUTO_HIDE);
    }

    // ==========================================
    // 遥控键盘与按键逻辑接管
    // ==========================================

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 重置侧边栏隐藏时间
        if (isSidebarShowing) {
            resetSidebarHideTimer();
        }

        // 接管 0-9 数字键（支持遥控数字键盘换台）
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            handleNumberInput(keyCode - KeyEvent.KEYCODE_0);
            return true;
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (!isSidebarShowing) {
                    switchChannel(false); // 向上换台
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (!isSidebarShowing) {
                    switchChannel(true); // 向下换台
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
                if (isSidebarShowing && listChannels.hasFocus()) {
                    // 如果焦点在频道列表，按左键让焦点回到分类列表
                    listCategories.requestFocus();
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (isSidebarShowing && listCategories.hasFocus()) {
                    // 如果焦点在分类列表，按右键让焦点回到频道列表
                    listChannels.requestFocus();
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_BACK:
                if (isSidebarShowing) {
                    hideSidebar();
                    return true;
                } else {
                    // 播放状态下双击返回键安全退出
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

    /**
     * 接收数字键输入并展示提示浮窗
     */
    private void handleNumberInput(int digit) {
        // 取消之前的输入超时
        tvHandler.removeMessages(MSG_TRIGGER_NUM_SWITCH);
        
        // 限制最多输入3位数台号
        if (numberInputBuilder.length() < 3) {
            numberInputBuilder.append(digit);
        }

        layoutNumberInput.setVisibility(View.VISIBLE);
        textNumberInput.setText(numberInputBuilder.toString());

        // 2秒无输入后触发切台
        tvHandler.sendEmptyMessageDelayed(MSG_TRIGGER_NUM_SWITCH, DELAY_NUM_SWITCH);
    }

    /**
     * 延迟处理数字切台
     */
    private void performNumberSwitch() {
        String numStr = numberInputBuilder.toString();
        numberInputBuilder.setLength(0); // 清空以便下次输入
        layoutNumberInput.setVisibility(View.GONE);

        if (numStr.isEmpty()) return;

        // 尝试匹配台号，格式化为带前导零对比，或者直接匹配数值
        int targetNum = Integer.parseInt(numStr);
        String formattedNum = String.format("%03d", targetNum);

        Channel match = null;
        for (Channel c : allChannels) {
            if (c.getNumber().equals(formattedNum) || c.getNumber().equals(numStr)) {
                match = c;
                break;
            }
        }

        if (match != null) {
            playChannel(match);
        } else {
            Toast.makeText(this, "台号 " + numStr + " 不存在", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) {
            player.stop();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.release();
            player = null;
        }
        tvHandler.removeCallbacksAndMessages(null);
    }

    // ==========================================
    // 列表设配器 (Adapters)
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
            
            // 当前选中的分类加深高亮显示
            if (cat.equals(currentCategory)) {
                tv.setSelected(true);
            } else {
                tv.setSelected(false);
            }
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

                // 当前正在播放的频道标记选中高亮状态
                if (c == currentChannel) {
                    convertView.setSelected(true);
                } else {
                    convertView.setSelected(false);
                }
            }
            return convertView;
        }
    }
}
