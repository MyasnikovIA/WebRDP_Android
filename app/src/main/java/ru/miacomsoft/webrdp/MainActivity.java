package ru.miacomsoft.webrdp;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PROJ = 101;
    private static final int REQ_PERM = 102;

    private EditText etHttpPort, etSignalPort, etUser, etPass;
    private CheckBox cbAutostart;
    private TextView tvAccStatus, tvOverlayStatus;
    private SettingsManager settings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        settings = new SettingsManager(this);

        etHttpPort    = findViewById(R.id.etHttpPort);
        etSignalPort  = findViewById(R.id.etSignalPort);
        etUser        = findViewById(R.id.etUser);
        etPass        = findViewById(R.id.etPass);
        cbAutostart   = findViewById(R.id.cbAutostart);

        tvAccStatus   = findViewById(R.id.tvAccStatus);
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus);

        Button btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility);
        Button btnOverlayPermission = findViewById(R.id.btnOverlayPermission);
        Button btnSystemSettings = findViewById(R.id.btnSystemSettings);

        loadSettings();
        requestRuntimePermissions();

        // Кнопка включения спец. возможностей
        btnEnableAccessibility.setOnClickListener(v -> {
            Toast.makeText(this, "Найдите 'WebRDP' в списке и включите", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });

        // Кнопка выдачи разрешения «Поверх других окон»
        btnOverlayPermission.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } else {
                    toast("Разрешение уже выдано");
                }
            } else {
                toast("На вашей версии Android это разрешение не требуется");
            }
        });

        // Кнопка открытия КОРНЕВЫХ настроек Android (сначала TV, потом универсальные)
        btnSystemSettings.setOnClickListener(v -> {
            // Попытка 1: Открыть настройки, специфичные для Android TV
            Intent tvSettingsIntent = new Intent("android.settings.TV_SETTINGS");
            tvSettingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            try {
                startActivity(tvSettingsIntent);
            } catch (Exception e) {
                // Попытка 2: Если TV-настройки не найдены, открываем универсальные
                Intent universalIntent = new Intent(Settings.ACTION_SETTINGS);
                universalIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(universalIntent);
                } catch (Exception ex) {
                    toast("Не удалось открыть настройки Android");
                }
            }
        });

        Button btnSave   = findViewById(R.id.btnSave);
        Button btnStart  = findViewById(R.id.btnStart);
        Button btnStop   = findViewById(R.id.btnStop);
        Button btnRestart= findViewById(R.id.btnRestart);

        btnSave.setOnClickListener(v -> { saveSettings(); toast("Настройки сохранены"); });
        btnStart.setOnClickListener(v -> requestProjection());
        btnStop.setOnClickListener(v -> {
            Intent i = new Intent(this, ScreenStreamService.class)
                    .setAction(ScreenStreamService.ACTION_STOP);
            startService(i);
            toast("Сервис остановлен");
        });
        btnRestart.setOnClickListener(v -> {
            saveSettings();
            Intent i = new Intent(this, ScreenStreamService.class)
                    .setAction(ScreenStreamService.ACTION_RESTART);
            startService(i);
            toast("Сервис перезапущен с новыми настройками");
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionsStatus();
    }

    private void updatePermissionsStatus() {
        // 1. Проверка Accessibility
        boolean accEnabled = isAccessibilityServiceEnabled();
        tvAccStatus.setText(accEnabled ? "Управление: ВКЛЮЧЕНО" : "Управление: ВЫКЛЮЧЕНО");
        tvAccStatus.setTextColor(accEnabled ? 0xFF388E3C : 0xFFD32F2F);

        // 2. Проверка Overlay (Поверх других окон)
        boolean overlayEnabled = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            overlayEnabled = Settings.canDrawOverlays(this);
        }
        tvOverlayStatus.setText(overlayEnabled ? "Автозапуск экрана: РАЗРЕШЕН" : "Автозапуск экрана: ЗАПРЕЩЕН");
        tvOverlayStatus.setTextColor(overlayEnabled ? 0xFF388E3C : 0xFFD32F2F);
    }

    private boolean isAccessibilityServiceEnabled() {
        String service = getPackageName() + "/" + RemoteControlAccessibilityService.class.getCanonicalName();
        try {
            String enabledServices = Settings.Secure.getString(
                    getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabledServices == null) return false;
            TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
            splitter.setString(enabledServices);
            while (splitter.hasNext()) {
                if (splitter.next().equalsIgnoreCase(service)) return true;
            }
        } catch (Exception e) { e.printStackTrace(); }
        return false;
    }

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_PERM);
        }
    }

    private void loadSettings() {
        etHttpPort.setText(String.valueOf(settings.getHttpPort()));
        etSignalPort.setText(String.valueOf(settings.getSignalPort()));
        etUser.setText(settings.getUsername());
        etPass.setText(settings.getPassword());
        cbAutostart.setChecked(settings.isAutostart());
    }

    private void saveSettings() {
        int hp = parseIntSafe(etHttpPort.getText().toString(), 8085);
        int sp = parseIntSafe(etSignalPort.getText().toString(), 8086);
        String u = etUser.getText().toString().trim();
        String p = etPass.getText().toString();
        if (TextUtils.isEmpty(u)) u = "admin";
        if (TextUtils.isEmpty(p)) p = "admin";
        settings.setHttpPort(hp);
        settings.setSignalPort(sp);
        settings.setUsername(u);
        settings.setPassword(p);
        settings.setAutostart(cbAutostart.isChecked());
    }

    private int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private void requestProjection() {
        saveSettings();
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJ);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PROJ) {
            if (resultCode == RESULT_OK && data != null) {
                Intent svc = new Intent(this, ScreenStreamService.class)
                        .setAction(ScreenStreamService.ACTION_START)
                        .putExtra(ScreenStreamService.EXTRA_RESULT_CODE, resultCode)
                        .putExtra(ScreenStreamService.EXTRA_RESULT_DATA, data);
                ContextCompat.startForegroundService(this, svc);
                toast("Трансляция запускается…");
            } else {
                toast("Разрешение на захват экрана не получено");
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] p, @NonNull int[] g) {
        super.onRequestPermissionsResult(req, p, g);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}