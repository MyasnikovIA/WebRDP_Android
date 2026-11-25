package ru.miacomsoft.webrdp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

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

            // Проверяем разрешение "Поверх других окон" (обязательно для запуска Activity из фона на Android 10+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                Log.e(TAG, "Автозапуск отменен: нет разрешения 'Поверх других окон' (Overlay Permission).");
                return;
            }

            Log.i(TAG, "Запуск AutoStartActivity...");
            Intent autoStart = new Intent(context, AutoStartActivity.class);
            autoStart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(autoStart);
        }
    }
}