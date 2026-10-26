package com.ahly.tv;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import android.content.Context;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private WebView web;
    private long lastMove = 0;
    private long backDownAt = 0;
    private boolean backLongFired = false;
    private PhoneServer phoneServer;
    private TextToSpeech tts;
    private volatile boolean ttsAr = false;

    /** Lets the page list and open the TV's other apps, and open system settings. */
    public class Bridge {
        @JavascriptInterface
        public String apps() {
            PackageManager pm = getPackageManager();
            Map<String, ActivityInfo> found = new LinkedHashMap<>();
            for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
                Intent i = new Intent(Intent.ACTION_MAIN).addCategory(cat);
                List<ResolveInfo> list = pm.queryIntentActivities(i, 0);
                for (ResolveInfo r : list) {
                    String pkg = r.activityInfo.packageName;
                    if (pkg.equals(getPackageName()) || found.containsKey(pkg)) continue;
                    found.put(pkg, r.activityInfo);
                }
            }
            JSONArray out = new JSONArray();
            for (ActivityInfo a : found.values()) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("pkg", a.packageName);
                    o.put("label", String.valueOf(a.loadLabel(pm)));
                    Drawable d = a.loadBanner(pm);
                    boolean banner = d != null;
                    if (d == null) d = a.loadIcon(pm);
                    o.put("banner", banner);
                    o.put("img", toPng(d, banner ? 240 : 112, banner ? 135 : 112));
                    out.put(o);
                } catch (Exception ignored) { }
            }
            return out.toString();
        }

        @JavascriptInterface
        public void open(String pkg) {
            PackageManager pm = getPackageManager();
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(pkg);
            if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); }
        }

        @JavascriptInterface
        public void settings() { runOnUiThread(MainActivity.this::openSettings); }

        @JavascriptInterface
        public void wifi() {
            runOnUiThread(() -> {
                try { startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (Exception e) { openSettings(); }
            });
        }

        /** The TV's address on the home Wi-Fi, for the phone page. */
        @JavascriptInterface
        public String ip() { return localIp(); }

        @JavascriptInterface
        public String readFile(String id) {
            try {
                File f = playlistFile(id);
                byte[] b = new byte[(int) f.length()];
                try (FileInputStream in = new FileInputStream(f)) { int off = 0; while (off < b.length) { int n = in.read(b, off, b.length - off); if (n < 0) break; off += n; } }
                return new String(b, StandardCharsets.UTF_8);
            } catch (Exception e) { return ""; }
        }

        @JavascriptInterface
        public void deleteFile(String id) { playlistFile(id).delete(); }

        @JavascriptInterface
        public boolean ttsReady() { return ttsAr; }

        @JavascriptInterface
        public void speak(String text) {
            if (tts == null || !ttsAr) return;
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "zk");
        }

        @JavascriptInterface
        public void ttsStop() { if (tts != null) tts.stop(); }

        @JavascriptInterface
        public void homeSettings() {
            runOnUiThread(() -> {
                try { startActivity(new Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (Exception e) { openSettings(); }
            });
        }
    }

    private File playlistFile(String id) {
        return new File(getFilesDir(), "pl_" + id.replaceAll("[^A-Za-z0-9_-]", "") + ".m3u");
    }

    private static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return "";
    }

    /** Runs JS in the page and waits for its (string) result, for the phone server thread. */
    private String callPage(String js) {
        final String[] out = {""};
        final CountDownLatch done = new CountDownLatch(1);
        runOnUiThread(() -> web.evaluateJavascript(js, r -> {
            try { Object v = new JSONTokener(r).nextValue(); out[0] = v == null ? "" : String.valueOf(v); } catch (Exception e) { out[0] = ""; }
            done.countDown();
        }));
        try { done.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        return out[0];
    }

    private Object[] json(int status, String body) { return new Object[]{status, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8)}; }

    private Object[] handlePhone(String method, String path, Map<String, String> q, byte[] body) {
        try {
            if (path.equals("/") || path.equals("/index.html")) {
                try (InputStream in = getAssets().open("phone.html")) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
                    while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                    return new Object[]{200, "text/html; charset=utf-8", b.toByteArray()};
                }
            }
            if (path.equals("/list")) {
                String r = callPage("window.listPlaylists?window.listPlaylists():'[]'");
                return json(200, r.isEmpty() ? "[]" : r);
            }
            if (path.equals("/delete") && method.equals("POST")) {
                String id = q.get("id");
                if (id != null) callPage("window.deletePlaylist&&window.deletePlaylist(" + JSONObject.quote(id) + ")");
                return json(200, "{\"ok\":true}");
            }
            if (path.equals("/add") && method.equals("POST")) {
                JSONObject in = new JSONObject(new String(body, StandardCharsets.UTF_8));
                JSONObject p = new JSONObject();
                String name = in.optString("name", "").trim();
                p.put("name", name.isEmpty() ? "IPTV" : name);
                String text = in.optString("text", "");
                if (!text.isEmpty()) {
                    String id = "f" + System.currentTimeMillis();
                    try (FileOutputStream o = new FileOutputStream(playlistFile(id))) { o.write(text.getBytes(StandardCharsets.UTF_8)); }
                    p.put("id", id); p.put("file", true);
                } else {
                    String url = in.optString("url", "").trim();
                    if (!url.startsWith("http")) return json(200, "{\"ok\":false,\"error\":\"الرابط لازم يبدأ بـ http\"}");
                    p.put("id", "u" + System.currentTimeMillis()); p.put("url", url);
                }
                String r = callPage("window.onPhoneAdd?window.onPhoneAdd(" + p.toString() + "):''");
                return json(200, "ok".equals(r) ? "{\"ok\":true}" : "{\"ok\":false,\"error\":\"افتح تطبيق الأهلي TV على الشاشة وجرّب تاني\"}");
            }
            return json(404, "{\"ok\":false}");
        } catch (Exception e) {
            return json(500, "{\"ok\":false,\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}");
        }
    }

    private void openSettings() {
        try { startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception ignored) { }
    }

    private static String toPng(Drawable d, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 90, bo);
        return "data:image/png;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        web.setBackgroundColor(0xFF14040A);
        web.setLayerType(WebView.LAYER_TYPE_HARDWARE, null);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "AhlyTV");
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.requestFocus();
        setContentView(web);
        phoneServer = new PhoneServer(8765, this::handlePhone);
        phoneServer.start();
        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) return;
            int r = tts.setLanguage(new Locale("ar"));
            ttsAr = r >= TextToSpeech.LANG_AVAILABLE;
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onError(String id) { }
                @Override public void onDone(String id) { runOnUiThread(() -> web.evaluateJavascript("window.onTtsDone&&window.onTtsDone()", null)); }
            });
        });
        if (state != null) web.restoreState(state);
        else web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    private String keyName(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return "L";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "R";
            case KeyEvent.KEYCODE_DPAD_UP: return "U";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "D";
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return "C";
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP: return "CU";
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN: return "CD";
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE: return "PP";
            default: return null;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) {
            // short press: the page goes back. Hold 3 seconds: open the TV's settings (escape hatch).
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) { backDownAt = System.currentTimeMillis(); backLongFired = false; }
                else if (!backLongFired && System.currentTimeMillis() - backDownAt > 2500) { backLongFired = true; openSettings(); }
            } else if (e.getAction() == KeyEvent.ACTION_UP && !backLongFired) {
                web.evaluateJavascript("window.tvBack&&window.tvBack()", null);
            }
            return true;
        }
        String k = keyName(code);
        if (k == null) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            // when holding an arrow down, step at a steady pace instead of racing
            long now = System.currentTimeMillis();
            if (e.getRepeatCount() > 0 && now - lastMove < 140) return true;
            if (e.getRepeatCount() > 0 && k.equals("C")) return true;
            lastMove = now;
            web.evaluateJavascript("window.tvKey?window.tvKey('" + k + "'):'ok'", result -> {
                if (result != null && result.contains("ime")) {
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(web, InputMethodManager.SHOW_IMPLICIT);
                }
            });
        }
        return true;
    }

    @Override
    protected void onResume() { super.onResume(); web.onResume(); web.requestFocus(); }

    @Override
    protected void onDestroy() {
        if (phoneServer != null) phoneServer.stop();
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }
}
