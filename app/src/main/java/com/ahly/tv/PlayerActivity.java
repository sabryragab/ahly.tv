package com.ahly.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Native full-screen channel player (fast start on TV hardware). Up/Down change channel, Back returns. */
public class PlayerActivity extends Activity {
    public static String channelsJson = "[]";   // handed over by MainActivity (can be large)
    public static int lastIndex = -1;
    public static PlayerActivity current;   // for the phone remote
    public static boolean liveDone = false, liveFailed = false;
    public static String liveKey = "";
    private boolean live = false;
    private String numBuf = "";
    private final Runnable numGo = () -> {
        try { int n = Integer.parseInt(numBuf); if (n >= 1 && n <= this.chans.size()) { this.idx = n - 1; switchBy(0); } } catch (Exception ignored) { }
        numBuf = "";
    };

    private final List<String[]> chans = new ArrayList<>(); // {name, url}
    private int idx;
    private ExoPlayer player;
    private TextView title, number, error;
    private ProgressBar spinner;
    private LinearLayout osd;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable hideOsd = () -> osd.setVisibility(View.GONE);
    private final Runnable startPending = this::startCurrent;
    private List<String> tries = new ArrayList<>();
    private long lastSwitch = 0;
    private int mimeMode = 0;  // 0 auto, 1 force HLS, 2 force TS/progressive
    private static final String[] MIME_NAMES = {"تلقائي", "HLS", "عادي"};
    private long lastStall = 0;

    private static final Pattern XTREAM = Pattern.compile("^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\\d+)(?:\\.(ts|m3u8))?$", Pattern.CASE_INSENSITIVE);

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            JSONArray a = new JSONArray(channelsJson);
            for (int i = 0; i < a.length(); i++) { JSONObject o = a.getJSONObject(i); chans.add(new String[]{o.optString("n"), o.optString("u")}); if (i == 0) liveKey = o.optString("k", ""); }
        } catch (Exception ignored) { }
        idx = getIntent().getIntExtra("index", 0);
        live = getIntent().getBooleanExtra("live", false);
        if (live) {
            liveFailed = false;
            int auto = getIntent().getIntExtra("auto", 0);
            if (auto > 0) h.postDelayed(this::finish, auto * 60000L);
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        PlayerView pv = new PlayerView(this);
        pv.setUseController(false);
        pv.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        pv.setKeepContentOnPlayerReset(false);
        root.addView(pv, new FrameLayout.LayoutParams(-1, -1));

        spinner = new ProgressBar(this);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER);
        root.addView(spinner, sp);

        error = new TextView(this);
        error.setTextColor(Color.WHITE); error.setTextSize(20); error.setGravity(Gravity.CENTER);
        error.setPadding(dp(24), dp(16), dp(24), dp(16));
        GradientDrawable eb = new GradientDrawable(); eb.setColor(0xCC000000); eb.setCornerRadius(dp(12)); error.setBackground(eb);
        error.setText("القناة دي مش شغالة دلوقتي.\nدوس ▲ أو ▼ لقناة تانية");
        error.setVisibility(View.GONE);
        root.addView(error, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        osd = new LinearLayout(this);
        osd.setOrientation(LinearLayout.VERTICAL);
        osd.setPadding(dp(48), dp(24), dp(48), dp(28));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xE6000000, 0x00000000});
        osd.setBackground(g);
        title = new TextView(this); title.setTextColor(Color.WHITE); title.setTextSize(30); title.setTypeface(Typeface.DEFAULT_BOLD);
        number = new TextView(this); number.setTextColor(0xFFE3B94A); number.setTextSize(16);
        TextView hint = new TextView(this); hint.setTextColor(0xFFC9A9AE); hint.setTextSize(14);
        hint.setText("▲ ▼ تغيير القناة   ·   🟡 جرّب مشغّل تاني   ·   🟢 مشغّل خارجي   ·   رجوع للخروج");
        osd.addView(number); osd.addView(title); osd.addView(hint);
        root.addView(osd, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root);

        // start quickly: small buffer before playback begins
        DefaultLoadControl lc = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(8000, 30000, 700, 1500).build();
        player = new ExoPlayer.Builder(this).setLoadControl(lc).build();
        pv.setPlayer(player);
        player.setPlayWhenReady(true);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int s) {
                spinner.setVisibility(s == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                if (s == Player.STATE_READY) error.setVisibility(View.GONE);
                h.removeCallbacks(stallWatch);
                if (s == Player.STATE_BUFFERING) h.postDelayed(stallWatch, 18000);  // frozen mid-stream -> reload once
            }
            @Override public void onPlayerError(PlaybackException e) { tryNext(); }
        });
        startCurrent();
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private final Runnable stallWatch = () -> {
        if (player.getPlaybackState() == Player.STATE_BUFFERING && System.currentTimeMillis() - lastStall > 20000) {
            lastStall = System.currentTimeMillis(); player.seekToDefaultPosition(); player.prepare();
        }
    };

    /** cycle the internal decoder hint for the current channel */
    private void cycleMime() {
        mimeMode = (mimeMode + 1) % 3;
        number.setText("مشغّل: " + MIME_NAMES[mimeMode]);
        showOsd();
        tries = new ArrayList<>();
        tries.add(chans.get(idx)[1]);
        player.stop(); spinner.setVisibility(View.VISIBLE); error.setVisibility(View.GONE); tryNext();
    }

    /** hand the stream to an external player (VLC, MX Player, …) if one is installed */
    private void openExternal() {
        String u = chans.get(idx)[1];
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.parse(u), "video/*");
        i.putExtra("title", chans.get(idx)[0]);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (i.resolveActivity(getPackageManager()) != null) {
            startActivity(Intent.createChooser(i, "تشغيل بـ"));
        } else {
            number.setText("مفيش مشغّل خارجي متثبّت (VLC أو MX Player)");
            showOsd();
        }
    }

    private void showOsd() {
        osd.setVisibility(View.VISIBLE);
        h.removeCallbacks(hideOsd);
        h.postDelayed(hideOsd, 4000);
    }

    private void startCurrent() {
        if (chans.isEmpty()) { finish(); return; }
        idx = (idx % chans.size() + chans.size()) % chans.size();
        lastIndex = live ? -1 : idx;
        String url = chans.get(idx)[1];
        tries = new ArrayList<>();
        Matcher m = XTREAM.matcher(url);
        if (m.find() && !url.matches("(?i).*\\.(mp4|mkv|avi)$")) {
            tries.add(m.group(1) + "/live/" + m.group(2) + "/" + m.group(3) + "/" + m.group(4) + ".m3u8");
            tries.add(m.group(1) + "/live/" + m.group(2) + "/" + m.group(3) + "/" + m.group(4) + ".ts");
        }
        if (!tries.contains(url)) tries.add(url);
        tries.add("hls:" + url);   // last resort: force HLS for links without an extension
        error.setVisibility(View.GONE);
        tryNext();
    }

    private void tryNext() {
        if (tries.isEmpty()) {
            if (live) { liveFailed = true; finish(); return; }
            spinner.setVisibility(View.GONE); error.setVisibility(View.VISIBLE); showOsd(); return;
        }
        String u = tries.remove(0);
        boolean forceHls = u.startsWith("hls:");
        if (forceHls) u = u.substring(4);
        MediaItem.Builder mb = new MediaItem.Builder().setUri(Uri.parse(u));
        if (mimeMode == 1 || forceHls || u.toLowerCase().contains("m3u8") || u.contains("type=m3u")) mb.setMimeType(MimeTypes.APPLICATION_M3U8);
        else if (mimeMode == 2) mb.setMimeType(MimeTypes.VIDEO_MP2T);
        player.setMediaItem(mb.build());
        player.prepare();
    }

    private void switchBy(int d) {
        if (live) { showOsd(); return; }
        mimeMode = 0;
        idx += d;
        idx = (idx % chans.size() + chans.size()) % chans.size();
        title.setText(chans.get(idx)[0]);
        number.setText((idx + 1) + " / " + chans.size());
        showOsd();
        player.stop();
        spinner.setVisibility(View.VISIBLE);
        // when zapping fast, only open the channel the viewer stops on
        h.removeCallbacks(startPending);
        h.postDelayed(startPending, System.currentTimeMillis() - lastSwitch < 600 ? 450 : 120);
        lastSwitch = System.currentTimeMillis();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!chans.isEmpty()) { title.setText(chans.get((idx % chans.size() + chans.size()) % chans.size())[0]); number.setText((idx + 1) + " / " + chans.size()); }
        showOsd();
    }

    /** keys sent from the phone remote */
    public void remote(String k) {
        switch (k) {
            case "U": case "CU": switchBy(-1); break;
            case "D": case "CD": switchBy(1); break;
            case "B": case "HOME": finish(); break;
            case "PP": player.setPlayWhenReady(!player.getPlayWhenReady()); break;
            case "YELLOW": cycleMime(); break;
            case "GREEN": openExternal(); break;
            default:
                if (k.startsWith("N") && k.length() == 2) digit(k.charAt(1)); else showOsd();
        }
    }

    private void digit(char d) {
        numBuf = (numBuf + d);
        if (numBuf.length() > 4) numBuf = numBuf.substring(numBuf.length() - 4);
        number.setText("قناة رقم " + numBuf);
        showOsd();
        h.removeCallbacks(numGo);
        h.postDelayed(numGo, 1500);
    }

    @Override
    protected void onResume() { super.onResume(); current = this; }

    @Override
    protected void onPause() { if (current == this) current = null; super.onPause(); }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e);
        int code = e.getKeyCode();
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) { digit((char) ('0' + code - KeyEvent.KEYCODE_0)); return true; }
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_CHANNEL_UP: case KeyEvent.KEYCODE_PAGE_UP:
                switchBy(-1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_CHANNEL_DOWN: case KeyEvent.KEYCODE_PAGE_DOWN:
                switchBy(1); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_INFO:
                showOsd(); return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                player.setPlayWhenReady(!player.getPlayWhenReady()); return true;
            case KeyEvent.KEYCODE_PROG_YELLOW: cycleMime(); return true;
            case KeyEvent.KEYCODE_PROG_GREEN: openExternal(); return true;
            default: return super.dispatchKeyEvent(e);
        }
    }

    @Override
    protected void onStop() { super.onStop(); player.setPlayWhenReady(false); }

    @Override
    protected void onRestart() { super.onRestart(); player.setPlayWhenReady(true); }

    @Override
    public void finish() {
        // mark before MainActivity resumes, so it knows a live broadcast just ended
        if (live) { liveDone = true; lastIndex = -1; }
        super.finish();
    }

    @Override
    protected void onDestroy() {
        if (live) { liveDone = true; lastIndex = -1; }
        h.removeCallbacksAndMessages(null); player.release(); super.onDestroy();
    }
}
