package ru.miacomsoft.webrdp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

public class AutoStartActivity extends Activity {
    private static final int REQ_PROJ = 991;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Запускаем запрос прав на запись экрана
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJ);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PROJ) {
            if (resultCode == RESULT_OK && data != null) {
                // Передаем данные сервису
                Intent svc = new Intent(this, ScreenStreamService.class)
                        .setAction(ScreenStreamService.ACTION_START)
                        .putExtra(ScreenStreamService.EXTRA_RESULT_CODE, resultCode)
                        .putExtra(ScreenStreamService.EXTRA_RESULT_DATA, data);
                ContextCompat.startForegroundService(this, svc);
            }
            // Закрываем невидимый экран
            finish();
        }
    }
}