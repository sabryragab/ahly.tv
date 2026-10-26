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
import android.net.Uri;
import android.os.Build;
import androidx.core.content.FileProvider;
import java.net.HttpURLConnection;
import java.net.URL;
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
    private boolean okDown = false, okLong = false;
    private long lastUpdateCheck = 0;
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

        /** Opens the native full-screen player with the visible channel list. */
        @JavascriptInterface
        public void playChannels(String json, int index) {
            PlayerActivity.channelsJson = json;
            PlayerActivity.lastIndex = -1;
            runOnUiThread(() -> startActivity(new Intent(MainActivity.this, PlayerActivity.class).putExtra("index", index)));
        }

        /** Live broadcast (the Haram at adhan time); closes itself after autoMin minutes. */
        @JavascriptInterface
        public void playLive(String json, int autoMin) {
            PlayerActivity.channelsJson = json;
            PlayerActivity.lastIndex = -1;
            runOnUiThread(() -> startActivity(new Intent(MainActivity.this, PlayerActivity.class).putExtra("index", 0).putExtra("live", true).putExtra("auto", autoMin)));
        }

        @JavascriptInterface
        public void closePlayer() { PlayerActivity pa = PlayerActivity.current; if (pa != null) runOnUiThread(pa::finish); }

        /** A short message that also shows over the video player. */
        @JavascriptInterface
        public void notify(String text) { runOnUiThread(() -> android.widget.Toast.makeText(MainActivity.this, text, android.widget.Toast.LENGTH_LONG).show()); }

        /** Lowest backlight while the sleep timer's black screen is up. */
        @JavascriptInterface
        public void dim(boolean on) {
            runOnUiThread(() -> {
                android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.screenBrightness = on ? 0.01f : android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                getWindow().setAttributes(lp);
            });
        }

        @JavascriptInterface
        public int versionCode() { return currentVersion(); }

        @JavascriptInterface
        public void checkUpdate() { new Thread(MainActivity.this::checkForUpdate).start(); }

        @JavascriptInterface
        public void update() { new Thread(MainActivity.this::downloadAndInstall).start(); }

        @JavascriptInterface
        public void homeSettings() {
            runOnUiThread(() -> {
                try { startActivity(new Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (Exception e) { openSettings(); }
            });
        }
    }

    private static final String REL = "https://github.com/sabryragab/ahly.tv/releases/latest/download/";

    private int currentVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode; } catch (Exception e) { return 0; }
    }

    private void js(String code) { runOnUiThread(() -> web.evaluateJavascript(code, null)); }

    private HttpURLConnection open(String u) throws Exception {
        // follow GitHub's redirect to its file host by hand (HttpURLConnection won't across hosts)
        for (int i = 0; i < 5; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000); c.setReadTimeout(30000);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) { u = c.getHeaderField("Location"); c.disconnect(); continue; }
            return c;
        }
        throw new Exception("too many redirects");
    }

    private void checkForUpdate() {
        try {
            HttpURLConnection c = open(REL + "version.json");
            InputStream in = c.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            JSONObject v = new JSONObject(b.toString("UTF-8"));
            if (v.optInt("code") > currentVersion()) js("window.onUpdateAvailable&&window.onUpdateAvailable(" + JSONObject.quote(v.optString("name")) + ")");
        } catch (Exception ignored) { }
    }

    private void downloadAndInstall() {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                js("window.onUpdateProgress&&window.onUpdateProgress(-2)");
                runOnUiThread(() -> {
                    try { startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) { }
                });
                return;
            }
            File dir = new File(getCacheDir(), "updates"); dir.mkdirs();
            File apk = new File(dir, "AhlyTV.apk");
            HttpURLConnection c = open(REL + "AhlyTV.apk");
            int total = c.getContentLength(), done = 0, last = -1;
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(apk)) {
                byte[] buf = new byte[65536]; int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n); done += n;
                    int pct = total > 0 ? (int) (100L * done / total) : 0;
                    if (pct != last && pct % 5 == 0) { last = pct; js("window.onUpdateProgress&&window.onUpdateProgress(" + pct + ")"); }
                }
            }
            Intent i = new Intent(Intent.ACTION_VIEW);
            Uri uri;
            if (Build.VERSION.SDK_INT >= 24) { uri = FileProvider.getUriForFile(this, "com.ahly.tv.files", apk); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            else { apk.setReadable(true, false); uri = Uri.fromFile(apk); }
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            runOnUiThread(() -> startActivity(i));
        } catch (Exception e) {
            js("window.onUpdateProgress&&window.onUpdateProgress(-1)");
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
            if (path.equals("/key")) {
                String k = q.get("k");
                if (k == null || !k.matches("[A-Z0-9]{1,5}")) return json(200, "{\"ok\":false}");
                if (k.equals("VUP") || k.equals("VDN") || k.equals("MUTE")) {
                    android.media.AudioManager am = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
                    int dir = k.equals("VUP") ? android.media.AudioManager.ADJUST_RAISE : k.equals("VDN") ? android.media.AudioManager.ADJUST_LOWER : android.media.AudioManager.ADJUST_TOGGLE_MUTE;
                    am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, dir, android.media.AudioManager.FLAG_SHOW_UI);
                    return json(200, "{\"ok\":true}");
                }
                PlayerActivity pa = PlayerActivity.current;
                if (pa != null) { final String kk = k; runOnUiThread(() -> pa.remote(kk)); return json(200, "{\"ok\":true}"); }
                callPage("window.remoteKey?window.remoteKey('" + k + "'):''");
                return json(200, "{\"ok\":true}");
            }
            if (path.equals("/text") && method.equals("POST")) {
                String t = new String(body, StandardCharsets.UTF_8);
                String r = callPage("window.typeText?window.typeText(" + JSONObject.quote(t) + "):''");
                return json(200, "ok".equals(r) ? "{\"ok\":true}" : "{\"ok\":false,\"error\":\"اختار خانة الكتابة على الشاشة الأول\"}");
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
            case KeyEvent.KEYCODE_PROG_RED: return "RED";
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
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) sendKey("N" + (code - KeyEvent.KEYCODE_0));
            return true;
        }
        String k = keyName(code);
        if (k == null) return super.dispatchKeyEvent(e);
        if (k.equals("C")) {
            // OK: short press = select, hold = favourite / pin
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) { okDown = true; okLong = false; }
                else if (!okLong && e.getRepeatCount() >= 6) { okLong = true; sendKey("CL"); }
            } else if (e.getAction() == KeyEvent.ACTION_UP && okDown) {
                okDown = false;
                if (!okLong) sendKey("C");
            }
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            // when holding an arrow down, step at a steady pace instead of racing
            long now = System.currentTimeMillis();
            if (e.getRepeatCount() > 0 && now - lastMove < 110) return true;
            if (e.getRepeatCount() > 0 && k.equals("C")) return true;
            lastMove = now;
            sendKey(k);
        }
        return true;
    }

    private void sendKey(String k) {
        web.evaluateJavascript("window.tvKey?window.tvKey('" + k + "'):'ok'", result -> {
            if (result != null && result.contains("ime")) {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(web, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume(); web.onResume(); web.requestFocus();
        // look for a new version every time the app comes to the front (at most every 10 minutes)
        if (System.currentTimeMillis() - lastUpdateCheck > 10 * 60 * 1000) { lastUpdateCheck = System.currentTimeMillis(); new Thread(this::checkForUpdate).start(); }
        if (PlayerActivity.liveDone) {
            PlayerActivity.liveDone = false;
            web.evaluateJavascript("window.onLiveClosed&&window.onLiveClosed(" + PlayerActivity.liveFailed + "," + JSONObject.quote(PlayerActivity.liveKey) + ")", null);
        } else if (PlayerActivity.lastIndex >= 0) {
            int i = PlayerActivity.lastIndex; PlayerActivity.lastIndex = -1;
            web.evaluateJavascript("window.onPlayerClosed&&window.onPlayerClosed(" + i + ")", null);
        }
    }

    @Override
    protected void onDestroy() {
        if (phoneServer != null) phoneServer.stop();
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }
}
