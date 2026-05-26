package com.dianshijia.lite.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.dianshijia.lite.MainActivity;

/**
 * 监听设备开机自启动广播，实现开机自动播放直播。
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i(TAG, "BootReceiver received action: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
            
            // 延迟 1-2 秒拉起界面，确保电视系统服务已经完全就绪，减少卡顿
            try {
                Intent mainIntent = new Intent(context, MainActivity.class);
                mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); // 后台拉起必须添加 NEW_TASK 标记
                context.startActivity(mainIntent);
                Log.i(TAG, "DianshijiaLite has been booted successfully.");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start MainActivity on boot", e);
            }
        }
    }
}
