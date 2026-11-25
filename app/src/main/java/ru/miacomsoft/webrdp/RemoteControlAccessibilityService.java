package ru.miacomsoft.webrdp;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;
import java.util.List;

public class RemoteControlAccessibilityService extends AccessibilityService {
    private static final String TAG = "RCAccessibility";
    private static volatile RemoteControlAccessibilityService instance;

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "Accessibility service connected");
    }

    @Override public void onDestroy() { instance = null; super.onDestroy(); }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence className = event.getClassName();
            if (className != null && className.toString().contains("MediaProjectionPermissionActivity")) {
                Log.i(TAG, "Обнаружено окно запроса захвата экрана. Пытаемся нажать Start now...");
                clickStartNow(getRootInActiveWindow());
            }
        }
    }

    private void clickStartNow(AccessibilityNodeInfo node) {
        if (node == null) return;

        List<AccessibilityNodeInfo> buttons = node.findAccessibilityNodeInfosByText("Start now");
        if (buttons.isEmpty()) buttons = node.findAccessibilityNodeInfosByText("Начать сейчас");
        if (buttons.isEmpty()) buttons = node.findAccessibilityNodeInfosByText("Начать");

        for (AccessibilityNodeInfo btn : buttons) {
            if (btn.isClickable()) {
                btn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                Log.i(TAG, "Кнопка подтверждения нажата!");
                return;
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                clickStartNow(child);
                child.recycle();
            }
        }
    }

    @Override public void onInterrupt() {}

    public static void handleMessage(String json) {
        RemoteControlAccessibilityService s = instance;
        if (s == null) { Log.w(TAG, "Service not connected"); return; }
        try {
            JSONObject o = new JSONObject(json);
            String t = o.optString("t");
            switch (t) {
                case "tap":
                    s.tap(normX(o.optDouble("x")), normY(o.optDouble("y")));
                    break;
                case "back":
                    s.performGlobalAction(GLOBAL_ACTION_BACK);
                    break;
                case "longpress":
                    s.longPress(normX(o.optDouble("x")), normY(o.optDouble("y")), o.optLong("ms", 800));
                    break;
                case "swipe":
                    s.swipe(normX(o.optDouble("x1")), normY(o.optDouble("y1")),
                            normX(o.optDouble("x2")), normY(o.optDouble("y2")),
                            o.optLong("ms", 300));
                    break;
                case "scroll":
                    s.scroll(o.optDouble("x"), o.optDouble("y"), o.optDouble("deltaY"));
                    break;
                case "key":
                    s.handleKey(o.optString("key"));
                    break;
                case "text":
                    s.appendText(o.optString("text"));
                    break;
            }
        } catch (Exception e) { Log.e(TAG, "handleMessage", e); }
    }

    private static float normX(double x) {
        DisplayMetrics dm = instance.getResources().getDisplayMetrics();
        return (float) (x * dm.widthPixels);
    }
    private static float normY(double y) {
        DisplayMetrics dm = instance.getResources().getDisplayMetrics();
        return (float) (y * dm.heightPixels);
    }

    private void tap(float x, float y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        Path p = new Path();
        p.moveTo(x, y);
        p.lineTo(x + 1, y + 1);
        GestureDescription.StrokeDescription st = new GestureDescription.StrokeDescription(p, 0, 100);
        dispatchGesture(new GestureDescription.Builder().addStroke(st).build(), null, null);
    }

    private void longPress(float x, float y, long ms) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        Path p = new Path(); p.moveTo(x, y);
        GestureDescription.StrokeDescription st = new GestureDescription.StrokeDescription(p, 0, ms);
        dispatchGesture(new GestureDescription.Builder().addStroke(st).build(), null, null);
    }

    private void swipe(float x1, float y1, float x2, float y2, long ms) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        Path p = new Path(); p.moveTo(x1, y1); p.lineTo(x2, y2);
        GestureDescription.StrokeDescription st = new GestureDescription.StrokeDescription(p, 0, Math.max(50, ms));
        dispatchGesture(new GestureDescription.Builder().addStroke(st).build(), null, null);
    }

    // --- ЛОГИКА ПРОКРУТКИ ---
    private void scroll(double nx, double ny, double deltaY) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;

        // Определяем направление:
        // deltaY > 0 (скролл вниз) -> свайп вверх (от ny+0.15 к ny-0.15)
        // deltaY < 0 (скролл вверх) -> свайп вниз (от ny-0.15 к ny+0.15)
        float startY = deltaY > 0 ? (float)(ny + 0.15) : (float)(ny - 0.15);
        float endY   = deltaY > 0 ? (float)(ny - 0.15) : (float)(ny + 0.15);

        // Не даем свайпу выйти за пределы экрана (оставляем отступы 5%)
        if (startY < 0.05f) { endY += (0.05f - startY); startY = 0.05f; }
        if (startY > 0.95f) { endY -= (startY - 0.95f); startY = 0.95f; }
        if (endY < 0.05f) { startY += (0.05f - endY); endY = 0.05f; }
        if (endY > 0.95f) { startY -= (endY - 0.95f); endY = 0.95f; }

        // Выполняем быстрый свайп (80мс) для эффекта прокрутки
        swipe(normX(nx), normY(startY), normX(nx), normY(endY), 80);
    }

    private void handleKey(String key) {
        if ("Backspace".equals(key)) {
            AccessibilityNodeInfo node = findFocusedInput();
            if (node != null && node.getText() != null) {
                String text = node.getText().toString();
                if (text.length() > 0) setTextToNode(node, text.substring(0, text.length() - 1));
            }
        } else if ("Enter".equals(key)) {
            AccessibilityNodeInfo node = findFocusedInput();
            if (node != null) node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } else if ("Escape".equals(key)) {
            performGlobalAction(GLOBAL_ACTION_BACK);
        }
    }

    private void appendText(String newText) {
        AccessibilityNodeInfo node = findFocusedInput();
        if (node != null) {
            CharSequence currentText = node.getText();
            String base = currentText != null ? currentText.toString() : "";
            setTextToNode(node, base + newText);
        }
    }

    private AccessibilityNodeInfo findFocusedInput() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focus == null) focus = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        return focus;
    }

    private void setTextToNode(AccessibilityNodeInfo node, String text) {
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }
}