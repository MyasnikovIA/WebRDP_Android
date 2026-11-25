package ru.miacomsoft.webrdp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import androidx.core.content.ContextCompat;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) ||
                "android.intent.action.QUICKBOOT_POWERON".equals(a)) {

            SettingsManager s = new SettingsManager(context);
            if (!s.isAutostart()) return;

            Log.i(TAG, "Boot completed. Starting WebRDP...");

            // 1. Принудительно запускаем сервис, чтобы веб-сервер заработал как можно скорее
            // (даже если окно запроса экрана заблокировано системой)
            Intent svc = new Intent(context, ScreenStreamService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, svc);
            } else {
                context.startService(svc);
            }

            // 2. Пытаемся запустить прозрачную Activity для запроса MediaProjection
            // Для Android 10+ это сработает только если выдано разрешение "Поверх других окон"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                Log.e(TAG, "AutoStartActivity blocked: no Overlay permission.");
                return;
            }

            Intent autoStart = new Intent(context, AutoStartActivity.class);
            autoStart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            try {
                context.startActivity(autoStart);
                Log.i(TAG, "AutoStartActivity launched.");
            } catch (Exception e) {
                Log.e(TAG, "Failed to launch AutoStartActivity", e);
            }
        }
    }
}