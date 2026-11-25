package ru.miacomsoft.webrdp;

import android.content.Context;
import android.content.SharedPreferences;

public class SettingsManager {
    private static final String PREFS = "webrdp_prefs";
    public static final String KEY_HTTP_PORT   = "http_port";
    public static final String KEY_SIGNAL_PORT = "signal_port";
    public static final String KEY_STUN_PORT   = "stun_port"; // НОВОЕ
    public static final String KEY_USERNAME    = "username";
    public static final String KEY_PASSWORD    = "password";
    public static final String KEY_AUTOSTART   = "autostart";

    private final SharedPreferences prefs;

    public SettingsManager(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public int getHttpPort() { return prefs.getInt(KEY_HTTP_PORT, 8085); }
    public void setHttpPort(int p) { prefs.edit().putInt(KEY_HTTP_PORT, p).apply(); }

    public int getSignalPort() { return prefs.getInt(KEY_SIGNAL_PORT, 8086); }
    public void setSignalPort(int p) { prefs.edit().putInt(KEY_SIGNAL_PORT, p).apply(); }

    // НОВЫЕ МЕТОДЫ
    public int getStunPort() { return prefs.getInt(KEY_STUN_PORT, 3478); }
    public void setStunPort(int p) { prefs.edit().putInt(KEY_STUN_PORT, p).apply(); }

    public String getUsername() { return prefs.getString(KEY_USERNAME, "admin"); }
    public void setUsername(String s) { prefs.edit().putString(KEY_USERNAME, s).apply(); }

    public String getPassword() { return prefs.getString(KEY_PASSWORD, "admin"); }
    public void setPassword(String s) { prefs.edit().putString(KEY_PASSWORD, s).apply(); }

    public boolean isAutostart() { return prefs.getBoolean(KEY_AUTOSTART, true); }
    public void setAutostart(boolean b) { prefs.edit().putBoolean(KEY_AUTOSTART, b).apply(); }
}