package com.chk.binancebybit;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * CHK Crypto internal UI only. Requires explicit Android accessibility grant and
 * a separate opt-in switch in the application. Does not control other apps.
 * Remote trades, orders, confirmations, API keys and password entry are blocked.
 */
public final class CryptoVisualAccessibilityService extends AccessibilityService {
    private static volatile CryptoVisualAccessibilityService instance;
    private static final String PREFS = "chk_crypto_visual_mcp";
    private static final long MAX_WAIT_MS = 6000L;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override protected void onServiceConnected() { super.onServiceConnected(); instance = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}
    @Override public void onDestroy() { if (instance == this) instance = null; super.onDestroy(); }

    public static boolean optedIn(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false);
    }
    public static void setOptedIn(Context context, boolean on) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", on).apply();
    }
    public static boolean granted(Context context) {
        String entries = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return entries != null && entries.contains(context.getPackageName() + "/" + CryptoVisualAccessibilityService.class.getName());
    }
    public static JSONObject execute(Context context, String action, JSONObject args) throws Exception {
        JSONObject output = new JSONObject().put("action", action).put("timestamp", System.currentTimeMillis());
        CryptoVisualAccessibilityService service = instance;
        if ("ui_status".equals(action)) {
            return output.put("ok", true).put("consent", optedIn(context))
                    .put("accessibilityGranted", granted(context)).put("connected", service != null)
                    .put("screenCaptureSupported", Build.VERSION.SDK_INT >= 30)
                    .put("scope", "CHK Crypto only");
        }
        if (!optedIn(context) || !CryptoUniversalAgent.isEnabled(context))
            return output.put("ok", false).put("error", "visual_control_disabled_in_settings");
        if (service == null) return output.put("ok", false).put("error", "accessibility_service_not_connected");
        if ("ui_capture".equals(action)) return service.captureOnWorker();
        if (args == null) args = new JSONObject();
        JSONObject safeArgs = args;
        final AtomicReference<JSONObject> ref = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        service.main.post(() -> {
            try { ref.set(service.dispatchOnMain(action, safeArgs)); }
            catch (Exception e) {
                try { ref.set(new JSONObject().put("ok", false)
                        .put("error", "visual_control_error").put("detail", String.valueOf(e.getMessage()).substring(0,
                        Math.min(90, String.valueOf(e.getMessage()).length())))); }
                catch (Exception ignored) {}
            } finally { done.countDown(); }
        });
        if (!done.await(MAX_WAIT_MS, TimeUnit.MILLISECONDS))
            return output.put("ok", false).put("error", "visual_control_timeout");
        return ref.get() == null ? output.put("ok", false).put("error", "no_result") : ref.get();
    }

    private JSONObject dispatchOnMain(String action, JSONObject args) throws Exception {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        JSONObject out = new JSONObject().put("action", action).put("at", System.currentTimeMillis());
        if (root == null || !getPackageName().contentEquals(String.valueOf(root.getPackageName())))
            return out.put("ok", false).put("error", "open_chk_crypto_on_phone_first");
        try {
            if ("ui_inspect".equals(action)) return inspect(root);
            if ("ui_back".equals(action))
                return out.put("ok", performGlobalAction(GLOBAL_ACTION_BACK));
            if ("ui_click".equals(action)) {
                int index = args.optInt("index", -1);
                AccessibilityNodeInfo target = nodeAt(root, index);
                if (target == null) return out.put("ok", false).put("error", "element_not_found");
                if (isForbidden(target) || isProtectedScreen(root))
                    return out.put("ok", false).put("error", "sensitive_action_not_allowed");
                boolean clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (!clicked) {
                    Rect b = new Rect(); target.getBoundsInScreen(b);
                    clicked = gesture(b.centerX(), b.centerY(), b.centerX(), b.centerY(), 100);
                }
                return out.put("ok", clicked).put("index", index);
            }
            if ("ui_type".equals(action)) {
                String value = args.optString("text", "");
                if (value.length() > 500 || isProtectedScreen(root))
                    return out.put("ok", false).put("error", "restricted_input");
                AccessibilityNodeInfo node = args.has("index") ? nodeAt(root, args.optInt("index", -1))
                        : root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (node == null || !node.isEditable() || isForbidden(node))
                    return out.put("ok", false).put("error", "safe_edit_field_required");
                android.os.Bundle bundle = new android.os.Bundle();
                bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
                return out.put("ok", node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle))
                        .put("characters", value.length());
            }
            if ("ui_tap".equals(action) || "ui_long_press".equals(action) || "ui_swipe".equals(action)) {
                if (isProtectedScreen(root)) return out.put("ok", false).put("error", "protected_trading_or_keys_screen");
                int x = args.optInt("x", -1), y = args.optInt("y", -1);
                if (x < 0 || y < 0 || x > getResources().getDisplayMetrics().widthPixels
                        || y > getResources().getDisplayMetrics().heightPixels)
                    return out.put("ok", false).put("error", "coordinates_out_of_bounds");
                if (isForbidden(hit(root, x, y))) return out.put("ok", false).put("error", "sensitive_target");
                int endX = "ui_swipe".equals(action) ? args.optInt("endX", x) : x;
                int endY = "ui_swipe".equals(action) ? args.optInt("endY", y) : y;
                if (endX < 0 || endY < 0 || endX > getResources().getDisplayMetrics().widthPixels
                        || endY > getResources().getDisplayMetrics().heightPixels)
                    return out.put("ok", false).put("error", "end_coordinates_out_of_bounds");
                long duration = "ui_swipe".equals(action) ? Math.min(1100, Math.max(140, args.optLong("durationMs", 350)))
                        : "ui_long_press".equals(action) ? 650 : 110;
                return out.put("ok", gesture(x, y, endX, endY, duration))
                        .put("x", x).put("y", y);
            }
            return out.put("ok", false).put("error", "unsupported_visual_action");
        } finally { root.recycle(); }
    }

    private boolean gesture(int x, int y, int x2, int y2, long duration) {
        Path p = new Path(); p.moveTo(x, y); p.lineTo(x2, y2);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(p, 0, duration);
        return dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
    }

    private JSONObject inspect(AccessibilityNodeInfo root) throws Exception {
        JSONArray items = new JSONArray();
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>(); queue.add(root);
        int index = 0;
        boolean protectedScreen = isProtectedScreen(root);
        while (!queue.isEmpty() && index < 150) {
            AccessibilityNodeInfo node = queue.removeFirst();
            if (node == null) continue;
            Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
            String label = node.isPassword() || isForbidden(node) || protectedScreen ? "[masqué]"
                    : trunc(String.valueOf(node.getText() != null ? node.getText() :
                      node.getContentDescription() != null ? node.getContentDescription() : ""), 90);
            if (node.isVisibleToUser())
                items.put(new JSONObject().put("index", index).put("label", label)
                        .put("class", String.valueOf(node.getClassName()))
                        .put("clickable", node.isClickable() && !isForbidden(node) && !protectedScreen)
                        .put("editable", node.isEditable() && !isForbidden(node) && !protectedScreen)
                        .put("bounds", new JSONArray().put(bounds.left).put(bounds.top).put(bounds.right).put(bounds.bottom)));
            index++;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        return new JSONObject().put("ok", true).put("screen", getPackageName())
                .put("restricted", protectedScreen).put("elements", items);
    }

    private AccessibilityNodeInfo nodeAt(AccessibilityNodeInfo root, int target) {
        if (target < 0 || target >= 150) return null;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>(); queue.add(root);
        int index = 0;
        while (!queue.isEmpty() && index < 150) {
            AccessibilityNodeInfo n = queue.removeFirst();
            if (index++ == target) return n;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo child = n.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        return null;
    }

    private AccessibilityNodeInfo hit(AccessibilityNodeInfo root, int x, int y) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>(); queue.add(root);
        AccessibilityNodeInfo match = root;
        int count = 0;
        while (!queue.isEmpty() && count++ < 150) {
            AccessibilityNodeInfo n = queue.removeFirst();
            Rect bounds = new Rect(); n.getBoundsInScreen(bounds);
            if (n.isVisibleToUser() && bounds.contains(x, y)) {
                match = n;
                for (int i = 0; i < n.getChildCount(); i++) {
                    AccessibilityNodeInfo child = n.getChild(i);
                    if (child != null) queue.addLast(child);
                }
            }
        }
        return match;
    }

    private boolean isProtectedScreen(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>(); queue.add(root);
        int count = 0;
        while (!queue.isEmpty() && count++ < 120) {
            AccessibilityNodeInfo n = queue.removeFirst();
            if (n.isPassword()) return true;
            String label = String.valueOf(n.getText() != null ? n.getText() : "");
            if (label.contains("API Key") || label.contains("Secret Key")
                    || label.contains("CONFIRMER L'ORDRE") || label.contains("Confirmer l'ordre")
                    || label.contains("Passer un ordre") || label.contains("Auto-Trade CHK")) return true;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) queue.add(c);
            }
        }
        return false;
    }

    private boolean isForbidden(AccessibilityNodeInfo node) {
        if (node == null || node.isPassword()) return true;
        String s = String.valueOf(node.getText()) + " " + String.valueOf(node.getContentDescription())
                + " " + String.valueOf(node.getHintText());
        s = s.toLowerCase(Locale.ROOT);
        return s.contains("confirmer") || s.contains("acheter") || s.contains("vendre")
                || s.contains("achat ") || s.contains("vente ") || s.contains("retrait")
                || s.contains("api key") || s.contains("secret") || s.contains("mot de passe")
                || s.contains("supprimer") || s.contains("annuler l'ordre") || s.contains("transfert");
    }
    private String trunc(String s, int len) {
        if ("null".equals(s)) return "";
        return s.length() > len ? s.substring(0, len) : s;
    }

    /** Capture stays asynchronous on the main thread and waits on the MCP worker.
     * A successful result is returned only AFTER the JPEG has been generated.
     */
    private JSONObject captureOnWorker() throws Exception {
        JSONObject out = new JSONObject().put("action", "ui_capture")
                .put("observedAt", System.currentTimeMillis());
        if (Build.VERSION.SDK_INT < 30)
            return out.put("ok", false).put("error", "android_11_required");
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<JSONObject> response = new AtomicReference<>();
        main.post(() -> {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            try {
                if (root == null || !getPackageName().contentEquals(String.valueOf(root.getPackageName())))
                    throw new IllegalStateException("open_chk_crypto_first");
                if (isProtectedScreen(root)) throw new SecurityException("sensitive_screen_capture_blocked");
                takeScreenshot(Display.DEFAULT_DISPLAY, main::post, new TakeScreenshotCallback() {
                    @Override public void onSuccess(ScreenshotResult screenshot) {
                        try (HardwareBuffer buffer = screenshot.getHardwareBuffer()) {
                            Bitmap hw = Bitmap.wrapHardwareBuffer(buffer, screenshot.getColorSpace());
                            if (hw == null) throw new IllegalStateException("hardware_bitmap_unavailable");
                            int w = hw.getWidth(), h = hw.getHeight();
                            double ratio = Math.min(1.0, Math.min(680.0 / w, 1100.0 / h));
                            Bitmap scaled = Bitmap.createScaledBitmap(hw, Math.max(1, (int)(w * ratio)),
                                    Math.max(1, (int)(h * ratio)), true);
                            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                            scaled.compress(Bitmap.CompressFormat.JPEG, 32, bytes);
                            if (scaled != hw) scaled.recycle();
                            if (bytes.size() > 180000) throw new IllegalStateException("capture_too_large");
                            response.set(new JSONObject().put("ok", true).put("type", "image/jpeg")
                                    .put("width", scaled.getWidth()).put("height", scaled.getHeight())
                                    .put("jpegBase64", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)));
                        } catch (Exception e) {
                            response.set(error("capture_failed"));
                        } finally { latch.countDown(); }
                    }
                    @Override public void onFailure(int code) {
                        response.set(error("android_screenshot_error_" + code));
                        latch.countDown();
                    }
                });
            } catch (Exception e) {
                response.set(error(String.valueOf(e.getMessage())));
                latch.countDown();
            } finally { if (root != null) root.recycle(); }
        });
        if (!latch.await(8, TimeUnit.SECONDS))
            return out.put("ok", false).put("error", "capture_timeout");
        return response.get() == null ? out.put("ok", false).put("error", "no_capture") : response.get();
    }

    private static JSONObject error(String message) {
        try { return new JSONObject().put("ok", false).put("error", message); }
        catch (Exception ignored) { return new JSONObject(); }
    }
}
