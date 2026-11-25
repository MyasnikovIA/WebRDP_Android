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

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PROJ = 101;
    private static final int REQ_PERM = 102;

    private EditText etHttpPort, etStunPort, etUser, etPass;
    private CheckBox cbAutostart;
    private TextView tvAccStatus, tvOverlayStatus;
    private SettingsManager settings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        settings = new SettingsManager(this);

        etHttpPort    = findViewById(R.id.etHttpPort);
        etStunPort    = findViewById(R.id.etStunPort);
        etUser        = findViewById(R.id.etUser);
        etPass        = findViewById(R.id.etPass);
        cbAutostart   = findViewById(R.id.cbAutostart);

        tvAccStatus   = findViewById(R.id.tvAccStatus);
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus);

        Button btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility);
        Button btnOverlayPermission = findViewById(R.id.btnOverlayPermission);
        Button btnBatteryOptimization = findViewById(R.id.btnBatteryOptimization);
        Button btnAppInfo = findViewById(R.id.btnAppInfo);
        Button btnSystemSettings = findViewById(R.id.btnSystemSettings);

        loadSettings();
        requestRuntimePermissions();

        btnEnableAccessibility.setOnClickListener(v -> {
            Toast.makeText(this, "Найдите 'WebRDP' в списке и включите", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });

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

        // Запрос на игнорирование оптимизации батареи
        btnBatteryOptimization.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent intent = new Intent();
                intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                try {
                    startActivity(intent);
                } catch (Exception e) {
                    toast("Не удалось открыть настройки батареи");
                }
            }
        });

        // Переход в App Info (там часто находится переключатель "Автозапуск" на TV-боксах)
        btnAppInfo.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            try {
                startActivity(intent);
            } catch (Exception e) {
                toast("Не удалось открыть настройки приложения");
            }
        });

        btnSystemSettings.setOnClickListener(v -> {
            Intent tvSettingsIntent = new Intent("android.settings.TV_SETTINGS");
            tvSettingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(tvSettingsIntent);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });

        Button btnSave   = findViewById(R.id.btnSave);
        Button btnStart  = findViewById(R.id.btnStart);
        Button btnStop   = findViewById(R.id.btnStop);

        btnSave.setOnClickListener(v -> { saveSettings(); toast("Настройки сохранены"); });
        btnStart.setOnClickListener(v -> requestProjection());
        btnStop.setOnClickListener(v -> {
            Intent i = new Intent(this, ScreenStreamService.class)
                    .setAction(ScreenStreamService.ACTION_STOP);
            startService(i);
            toast("Сервис остановлен");
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionsStatus();
    }

    private void updatePermissionsStatus() {
        boolean accEnabled = isAccessibilityServiceEnabled();
        tvAccStatus.setText(accEnabled ? "Управление: ВКЛЮЧЕНО" : "Управление: ВЫКЛЮЧЕНО");
        tvAccStatus.setTextColor(accEnabled ? 0xFF388E3C : 0xFFD32F2F);

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
        List<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.RECORD_AUDIO);
        }

        if (!perms.isEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toArray(new String[0]), REQ_PERM);
        }
    }

    private void loadSettings() {
        etHttpPort.setText(String.valueOf(settings.getHttpPort()));
        etStunPort.setText(String.valueOf(settings.getStunPort()));
        etUser.setText(settings.getUsername());
        etPass.setText(settings.getPassword());
        cbAutostart.setChecked(settings.isAutostart());
    }

    private void saveSettings() {
        int hp = parseIntSafe(etHttpPort.getText().toString(), 8085);
        int stp = parseIntSafe(etStunPort.getText().toString(), 3478);
        String u = etUser.getText().toString().trim();
        String p = etPass.getText().toString();
        if (TextUtils.isEmpty(u)) u = "admin";
        if (TextUtils.isEmpty(p)) p = "admin";
        settings.setHttpPort(hp);
        settings.setStunPort(stp);
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