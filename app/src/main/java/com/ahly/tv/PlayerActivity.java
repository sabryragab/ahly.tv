package com.ahly.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Native full-screen player. Channels: Up/Down zap, Left/Right move over the buttons.
 * Movies and episodes: seek bar, play/pause, +-10 s, previous/next, audio and subtitle tracks, remembers where you stopped.
 */
public class PlayerActivity extends Activity {
    public static String channelsJson = "[]";   // handed over by MainActivity (can be large)
    public static int lastIndex = -1;
    public static PlayerActivity current;   // for the phone remote
    public static boolean liveDone = false, liveFailed = false;
    public static String liveKey = "";

    private static final int YEL = 0xFFFFC72C, DARK = 0xFF1A1300;
    private static final int T_PLAY = 0, T_PREV = 1, T_NEXT = 2, T_BACK = 3, T_FWD = 4, T_PILL = 5;
    private static final Pattern XTREAM = Pattern.compile("^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\\d+)(?:\\.(ts|m3u8))?$", Pattern.CASE_INSENSITIVE);
    private static final String[] MIME_NAMES = {"تلقائي", "HLS", "عادي"};
    private static final String[] ASPECT_NAMES = {"الحجم: أصلي", "الحجم: ملء الشاشة", "الحجم: تكبير"};

    /** where a movie / episode was left: "positionMs|durationMs|savedAt", or "" */
    public static String savedPos(Context c, String url) {
        try { return c.getSharedPreferences("pos", Context.MODE_PRIVATE).getString(url, ""); } catch (Exception e) { return ""; }
    }

    public static String allPos(Context c) {
        JSONObject o = new JSONObject();
        try {
            for (java.util.Map.Entry<String, ?> en : c.getSharedPreferences("pos", Context.MODE_PRIVATE).getAll().entrySet())
                o.put(en.getKey(), String.valueOf(en.getValue()));
        } catch (Exception ignored) { }
        return o.toString();
    }

    public static void clearPos(Context c, String url) {
        try { c.getSharedPreferences("pos", Context.MODE_PRIVATE).edit().remove(url).apply(); } catch (Exception ignored) { }
    }

    private boolean live = false;          // a scheduled live broadcast (no zapping, closes itself)
    private boolean vod = false;           // the current item has a known length and can be sought
    private boolean firstItem = true;
    private long startMs = 0;
    private String numBuf = "";
    private final Runnable numGo = () -> {
        try { int n = Integer.parseInt(numBuf); if (n >= 1 && n <= this.chans.size()) { this.idx = n - 1; switchBy(0); } } catch (Exception ignored) { }
        numBuf = "";
    };

    private final List<String[]> chans = new ArrayList<>(); // {name, url, startMs}
    private int idx;
    private ExoPlayer player;
    private PlayerView pv;
    private TextView title, sub, msg, error, tCur, tDur;
    private ProgressBar spinner;
    private FrameLayout ctrl;
    private LinearLayout barRow, btnRow;
    private TextView liveBadge;
    private Bar bar;
    private Btn playBtn;
    private final List<Btn> btns = new ArrayList<>();
    private int fi = 0;
    private boolean barFocus = false;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable hideOsd = () -> ctrl.setVisibility(View.GONE);
    private final Runnable hideMsg = () -> msg.setVisibility(View.GONE);
    private final Runnable startPending = this::startCurrent;
    private List<String> tries = new ArrayList<>();
    private long lastSwitch = 0, lastStall = 0;
    private int mimeMode = 0;  // 0 auto, 1 force HLS, 2 force TS/progressive
    private int aspect = 0, prevIdx = -1, audioIdx = 0, textIdx = -1, saveCnt = 0, videoH = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            JSONArray a = new JSONArray(channelsJson);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                chans.add(new String[]{o.optString("n"), o.optString("u"), String.valueOf(o.optLong("s", 0))});
                if (i == 0) liveKey = o.optString("k", "");
            }
        } catch (Exception ignored) { }
        idx = getIntent().getIntExtra("index", 0);
        live = getIntent().getBooleanExtra("live", false);
        if (live) {
            liveFailed = false;
            int auto = getIntent().getIntExtra("auto", 0);
            if (auto > 0) h.postDelayed(this::finish, auto * 60000L);
        }

        FrameLayout root = new FrameLayout(this);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        root.setBackgroundColor(Color.BLACK);
        pv = new PlayerView(this);
        pv.setUseController(false);
        pv.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        pv.setKeepContentOnPlayerReset(false);
        root.addView(pv, new FrameLayout.LayoutParams(-1, -1));

        spinner = new ProgressBar(this);
        root.addView(spinner, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER));

        error = new TextView(this);
        error.setTextColor(Color.WHITE); error.setTextSize(20); error.setGravity(Gravity.CENTER);
        error.setPadding(dp(28), dp(18), dp(28), dp(18));
        GradientDrawable eb = new GradientDrawable(); eb.setColor(0xDD000000); eb.setCornerRadius(dp(14)); eb.setStroke(dp(2), YEL); error.setBackground(eb);
        error.setText("مش قادر أشغّل ده دلوقتي.\nجرّب زر «مشغّل» أو حاجة تانية");
        error.setVisibility(View.GONE);
        root.addView(error, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        msg = new TextView(this);
        msg.setTextColor(DARK); msg.setTextSize(18); msg.setTypeface(Typeface.DEFAULT_BOLD);
        msg.setPadding(dp(22), dp(10), dp(22), dp(10));
        GradientDrawable mb = new GradientDrawable(); mb.setColor(YEL); mb.setCornerRadius(dp(30)); msg.setBackground(mb);
        msg.setVisibility(View.GONE);
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        mp.topMargin = dp(36);
        root.addView(msg, mp);

        // controls: a dark fade at the top (title) and at the bottom (bar and buttons)
        ctrl = new FrameLayout(this);
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(48), dp(28), dp(48), dp(40));
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xDD000000, 0x00000000}));
        title = new TextView(this); title.setTextColor(Color.WHITE); title.setTextSize(30); title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        sub = new TextView(this); sub.setTextColor(YEL); sub.setTextSize(16);
        top.addView(title); top.addView(sub);
        ctrl.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(48), dp(60), dp(48), dp(26));
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xF0000000, 0x00000000}));
        barRow = new LinearLayout(this);
        barRow.setOrientation(LinearLayout.HORIZONTAL);
        barRow.setGravity(Gravity.CENTER_VERTICAL);
        tCur = new TextView(this); tCur.setTextColor(Color.WHITE); tCur.setTextSize(16); tCur.setMinWidth(dp(70));
        tDur = new TextView(this); tDur.setTextColor(0xFFCFC6A6); tDur.setTextSize(16); tDur.setMinWidth(dp(70)); tDur.setGravity(Gravity.END);
        bar = new Bar(this);
        barRow.addView(tCur);
        barRow.addView(bar, new LinearLayout.LayoutParams(0, dp(26), 1f));
        barRow.addView(tDur);
        liveBadge = new TextView(this); liveBadge.setText("●  مباشر"); liveBadge.setTextColor(YEL); liveBadge.setTextSize(16); liveBadge.setTypeface(Typeface.DEFAULT_BOLD);
        btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = dp(10);
        bottom.addView(liveBadge);
        bottom.addView(barRow);
        bottom.addView(btnRow, bp);
        ctrl.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        root.addView(ctrl, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        // start quickly: small buffer before playback begins
        DefaultLoadControl lc = new DefaultLoadControl.Builder().setBufferDurationsMs(8000, 30000, 700, 1500).build();
        // IPTV servers: follow http<->https redirects, identify like a common player, tolerate rough TS streams,
        // and fall back to another decoder when the TV's first choice fails
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true).setUserAgent("VLC/3.0.20 LibVLC/3.0.20")
                .setConnectTimeoutMs(12000).setReadTimeoutMs(15000);
        DefaultExtractorsFactory ex = new DefaultExtractorsFactory()
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES | DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS);
        player = new ExoPlayer.Builder(this, new DefaultRenderersFactory(this).setEnableDecoderFallback(true))
                .setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(this, http), ex))
                .setLoadControl(lc).build();
        pv.setPlayer(player);
        player.setPlayWhenReady(true);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int s) {
                spinner.setVisibility(s == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                if (s == Player.STATE_READY) { error.setVisibility(View.GONE); detectMode(); }
                if (s == Player.STATE_ENDED && vod) { savePos(); if (chans.size() > 1) switchBy(1); else { showOsd(); } }
                h.removeCallbacks(stallWatch);
                if (s == Player.STATE_BUFFERING) h.postDelayed(stallWatch, 18000);  // frozen mid-stream -> reload once
            }
            @Override public void onPlayWhenReadyChanged(boolean pw, int reason) { if (playBtn != null) playBtn.invalidate(); showOsd(); }
            @Override public void onPlayerError(PlaybackException e) { tryNext(); }
            @Override public void onVideoSizeChanged(VideoSize v) { videoH = v.height; refreshSub(); }
        });
        buildControls();
        startCurrent();
        h.post(tick);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private final Runnable stallWatch = () -> {
        if (player.getPlaybackState() == Player.STATE_BUFFERING && System.currentTimeMillis() - lastStall > 20000) {
            lastStall = System.currentTimeMillis(); player.seekToDefaultPosition(); player.prepare();
        }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refreshBar();
            if (++saveCnt >= 10) { saveCnt = 0; savePos(); }
            h.postDelayed(this, 500);
        }
    };

    // ---------------------------------------------------------------- controls

    private void detectMode() {
        long d = player.getDuration();
        boolean v = d > 0 && d != C.TIME_UNSET && !player.isCurrentMediaItemDynamic();
        if (v != vod) { vod = v; buildControls(); }
        refreshSub();
    }

    private void buildControls() {
        btns.clear();
        btnRow.removeAllViews();
        barRow.setVisibility(vod ? View.VISIBLE : View.GONE);
        liveBadge.setVisibility(vod ? View.GONE : View.VISIBLE);
        if (!vod) barFocus = false;
        boolean many = chans.size() > 1;
        if (many) addBtn(new Btn(T_PREV, null, () -> switchBy(-1)));
        if (vod) addBtn(new Btn(T_BACK, null, () -> seekBy(-10000)));
        playBtn = new Btn(T_PLAY, null, this::togglePlay);
        addBtn(playBtn);
        if (vod) addBtn(new Btn(T_FWD, null, () -> seekBy(10000)));
        if (many) addBtn(new Btn(T_NEXT, null, () -> switchBy(1)));
        fi = btns.indexOf(playBtn);
        View sp = new View(this);
        btnRow.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        addBtn(new Btn(T_PILL, "الصوت", this::nextAudio));
        addBtn(new Btn(T_PILL, "الترجمة", this::nextText));
        addBtn(new Btn(T_PILL, "الحجم", this::cycleAspect));
        addBtn(new Btn(T_PILL, "مشغّل", this::cycleMime));
        addBtn(new Btn(T_PILL, "خارجي", this::openExternal));
        updateSel();
    }

    private void addBtn(Btn b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(10);
        btnRow.addView(b, lp);
        btns.add(b);
    }

    private void updateSel() {
        for (int i = 0; i < btns.size(); i++) { btns.get(i).sel = !barFocus && i == fi; btns.get(i).invalidate(); }
        bar.sel = barFocus; bar.invalidate();
    }

    private void showOsd() {
        ctrl.setVisibility(View.VISIBLE);
        h.removeCallbacks(hideOsd);
        if (player != null && player.getPlayWhenReady()) h.postDelayed(hideOsd, 5000);
    }

    private void toast(String t) {
        msg.setText(t); msg.setVisibility(View.VISIBLE);
        h.removeCallbacks(hideMsg); h.postDelayed(hideMsg, 2200);
    }

    private void refreshSub() {
        String s = chans.size() > 1 ? (idx + 1) + " / " + chans.size() : "";
        if (videoH > 0) s += (s.isEmpty() ? "" : "   ·   ") + videoH + "p";
        sub.setText(s);
        sub.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void refreshBar() {
        if (!vod || ctrl.getVisibility() != View.VISIBLE) return;
        long d = player.getDuration(), p = player.getCurrentPosition();
        if (d <= 0) return;
        bar.set(p, player.getBufferedPosition(), d);
        tCur.setText(fmt(p)); tDur.setText(fmt(d));
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms) / 1000, hh = s / 3600, mm = (s % 3600) / 60, ss = s % 60;
        return hh > 0 ? String.format("%d:%02d:%02d", hh, mm, ss) : String.format("%02d:%02d", mm, ss);
    }

    private void togglePlay() {
        if (player.getPlaybackState() == Player.STATE_ENDED) player.seekTo(0);
        player.setPlayWhenReady(!player.getPlayWhenReady());
        playBtn.invalidate();
        showOsd();
    }

    private void seekBy(long d) {
        if (!vod) return;
        long dur = player.getDuration();
        long t = Math.max(0, Math.min(dur - 1000, player.getCurrentPosition() + d));
        player.seekTo(t);
        bar.set(t, player.getBufferedPosition(), dur);
        tCur.setText(fmt(t));
        showOsd();
    }

    // ---------------------------------------------------------------- saved position

    private void savePos() {
        if (!vod || player == null || chans.isEmpty()) return;
        long d = player.getDuration(), p = player.getCurrentPosition();
        if (d <= 0 || d == C.TIME_UNSET) return;
        String k = chans.get((idx % chans.size() + chans.size()) % chans.size())[1];
        SharedPreferences.Editor e = getSharedPreferences("pos", MODE_PRIVATE).edit();
        if (p > d * 0.95) e.remove(k);
        else if (p >= 30000) e.putString(k, p + "|" + d + "|" + System.currentTimeMillis());
        e.apply();
    }

    private long savedStart(String url) {
        try { String s = savedPos(this, url); if (s.isEmpty()) return 0; long p = Long.parseLong(s.split("\\|")[0]); return p >= 30000 ? p : 0; } catch (Exception e) { return 0; }
    }

    // ---------------------------------------------------------------- playback

    /** cycle the internal decoder hint for the current channel */
    private void cycleMime() {
        mimeMode = (mimeMode + 1) % 3;
        toast("المشغّل: " + MIME_NAMES[mimeMode]);
        tries = new ArrayList<>();
        tries.add(chans.get(idx)[1]);
        player.stop(); spinner.setVisibility(View.VISIBLE); error.setVisibility(View.GONE); tryNext();
    }

    /** hand the stream to an external player (VLC, MX Player, ...) if one is installed */
    private void openExternal() {
        String u = chans.get(idx)[1];
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.parse(u), "video/*");
        i.putExtra("title", chans.get(idx)[0]);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (i.resolveActivity(getPackageManager()) != null) startActivity(Intent.createChooser(i, "تشغيل بـ"));
        else toast("مفيش مشغّل خارجي متثبّت (VLC أو MX Player)");
    }

    private void startCurrent() {
        if (chans.isEmpty()) { finish(); return; }
        idx = (idx % chans.size() + chans.size()) % chans.size();
        lastIndex = live ? -1 : idx;
        String url = chans.get(idx)[1];
        title.setText(chans.get(idx)[0]);
        videoH = 0;
        refreshSub();
        if (firstItem) { firstItem = false; try { startMs = Long.parseLong(chans.get(idx)[2]); } catch (Exception e) { startMs = 0; } }
        else startMs = savedStart(url);
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
        if (startMs > 0) player.setMediaItem(mb.build(), startMs); else player.setMediaItem(mb.build());
        player.prepare();
    }

    private void cycleAspect() {
        aspect = (aspect + 1) % 3;
        pv.setResizeMode(aspect == 0 ? AspectRatioFrameLayout.RESIZE_MODE_FIT : aspect == 1 ? AspectRatioFrameLayout.RESIZE_MODE_FILL : AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
        toast(ASPECT_NAMES[aspect]);
    }

    private void nextAudio() {
        List<Tracks.Group> a = new ArrayList<>();
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) if (g.getType() == C.TRACK_TYPE_AUDIO) a.add(g);
        if (a.size() < 2) { toast("فيه لغة صوت واحدة بس"); return; }
        audioIdx = (audioIdx + 1) % a.size();
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setOverrideForType(new TrackSelectionOverride(a.get(audioIdx).getMediaTrackGroup(), 0)).build());
        String lang = a.get(audioIdx).getMediaTrackGroup().getFormat(0).language;
        toast("الصوت " + (audioIdx + 1) + " من " + a.size() + (lang != null ? " (" + lang + ")" : ""));
    }

    private void nextText() {
        List<Tracks.Group> t = new ArrayList<>();
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) if (g.getType() == C.TRACK_TYPE_TEXT) t.add(g);
        if (t.isEmpty()) { toast("مفيش ترجمة في الفيديو ده"); return; }
        textIdx++;
        if (textIdx >= t.size()) textIdx = -1;
        TrackSelectionParameters.Builder tb = player.getTrackSelectionParameters().buildUpon();
        if (textIdx < 0) { tb.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true); toast("الترجمة: مقفولة"); }
        else {
            tb.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(new TrackSelectionOverride(t.get(textIdx).getMediaTrackGroup(), 0));
            String lang = t.get(textIdx).getMediaTrackGroup().getFormat(0).language;
            toast("الترجمة " + (textIdx + 1) + " من " + t.size() + (lang != null ? " (" + lang + ")" : ""));
        }
        player.setTrackSelectionParameters(tb.build());
    }

    private void recall() {
        if (live || prevIdx < 0 || prevIdx == idx) return;
        int go = prevIdx; switchBy(go - idx);
    }

    private void switchBy(int d) {
        if (live) return;
        savePos();
        mimeMode = 0; audioIdx = 0; textIdx = -1; vod = false;
        if (d != 0) prevIdx = idx;
        idx += d;
        idx = (idx % chans.size() + chans.size()) % chans.size();
        title.setText(chans.get(idx)[0]);
        refreshSub();
        buildControls();
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
        if (!chans.isEmpty()) title.setText(chans.get((idx % chans.size() + chans.size()) % chans.size())[0]);
        refreshSub();
        showOsd();
    }

    // ---------------------------------------------------------------- keys

    /** keys sent from the phone remote */
    public void remote(String k) {
        switch (k) {
            case "U": case "CU": if (vod) { showOsd(); } else switchBy(-1); break;
            case "D": case "CD": if (vod) { showOsd(); } else switchBy(1); break;
            case "B": case "HOME": finish(); break;
            case "PP": case "C": togglePlay(); break;
            case "YELLOW": cycleMime(); break;
            case "L": if (vod) seekBy(-10000); else recall(); break;
            case "R": if (vod) seekBy(10000); else cycleAspect(); break;
            case "BLUE": nextAudio(); break;
            case "GREEN": openExternal(); break;
            default:
                if (k.startsWith("N") && k.length() == 2) digit(k.charAt(1)); else showOsd();
        }
    }

    private void digit(char d) {
        numBuf = (numBuf + d);
        if (numBuf.length() > 4) numBuf = numBuf.substring(numBuf.length() - 4);
        toast("قناة رقم " + numBuf);
        h.removeCallbacks(numGo);
        h.postDelayed(numGo, 1500);
    }

    @Override
    protected void onResume() { super.onResume(); current = this; }

    @Override
    protected void onPause() { if (current == this) current = null; savePos(); super.onPause(); }

    private boolean horiz(int d, KeyEvent e) {
        boolean on = ctrl.getVisibility() == View.VISIBLE;
        long step = e.getRepeatCount() > 3 ? 30000 : 10000;
        if (!on) { if (vod) seekBy(d * step); else showOsd(); return true; }
        if (vod && barFocus) { seekBy(d * step); return true; }
        fi = Math.max(0, Math.min(btns.size() - 1, fi + d));
        updateSel(); showOsd();
        return true;
    }

    private boolean vert(int d) {
        boolean on = ctrl.getVisibility() == View.VISIBLE;
        if (!vod) { if (live) { showOsd(); return true; } switchBy(d); return true; }
        if (!on) { showOsd(); return true; }
        if (barFocus && d > 0) barFocus = false; else if (!barFocus && d < 0) barFocus = true;
        updateSel(); showOsd();
        return true;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e);
        int code = e.getKeyCode();
        boolean on = ctrl.getVisibility() == View.VISIBLE;
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) { digit((char) ('0' + code - KeyEvent.KEYCODE_0)); return true; }
        switch (code) {
            case KeyEvent.KEYCODE_BACK:
                if (on && !live && vod && player.getPlayWhenReady()) { h.removeCallbacks(hideOsd); ctrl.setVisibility(View.GONE); return true; }
                return super.dispatchKeyEvent(e);
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (!on) { togglePlay(); return true; }
                if (barFocus) togglePlay(); else if (fi >= 0 && fi < btns.size()) btns.get(fi).run();
                showOsd();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: return horiz(-1, e);
            case KeyEvent.KEYCODE_DPAD_RIGHT: return horiz(1, e);
            case KeyEvent.KEYCODE_DPAD_UP: return vert(-1);
            case KeyEvent.KEYCODE_DPAD_DOWN: return vert(1);
            case KeyEvent.KEYCODE_CHANNEL_UP: case KeyEvent.KEYCODE_PAGE_UP: case KeyEvent.KEYCODE_MEDIA_NEXT: switchBy(chans.size() > 1 ? -1 : 0); return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN: case KeyEvent.KEYCODE_PAGE_DOWN: case KeyEvent.KEYCODE_MEDIA_PREVIOUS: switchBy(chans.size() > 1 ? 1 : 0); return true;
            case KeyEvent.KEYCODE_LAST_CHANNEL: recall(); return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: seekBy(30000); return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND: seekBy(-30000); return true;
            case KeyEvent.KEYCODE_TV_ZOOM_MODE: cycleAspect(); return true;
            case KeyEvent.KEYCODE_PROG_BLUE: case KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK: nextAudio(); return true;
            case KeyEvent.KEYCODE_INFO: case KeyEvent.KEYCODE_MENU: showOsd(); return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY: player.setPlayWhenReady(true); return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE: case KeyEvent.KEYCODE_MEDIA_STOP: player.setPlayWhenReady(false); showOsd(); return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: togglePlay(); return true;
            case KeyEvent.KEYCODE_PROG_YELLOW: cycleMime(); return true;
            case KeyEvent.KEYCODE_PROG_GREEN: openExternal(); return true;
            default: return super.dispatchKeyEvent(e);
        }
    }

    @Override
    protected void onStop() { super.onStop(); savePos(); player.setPlayWhenReady(false); }

    @Override
    protected void onRestart() { super.onRestart(); player.setPlayWhenReady(true); }

    @Override
    public void finish() {
        savePos();
        // mark before MainActivity resumes, so it knows a live broadcast just ended
        if (live) { liveDone = true; lastIndex = -1; }
        super.finish();
    }

    @Override
    protected void onDestroy() {
        if (live) { liveDone = true; lastIndex = -1; }
        h.removeCallbacksAndMessages(null); player.release(); super.onDestroy();
    }

    // ---------------------------------------------------------------- views

    /** a round (or pill) button drawn by hand: no icon fonts needed */
    private class Btn extends View {
        final int type; final String label; final Runnable act; boolean sel;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Btn(int type, String label, Runnable act) {
            super(PlayerActivity.this);
            this.type = type; this.label = label; this.act = act;
            p.setTextAlign(Paint.Align.CENTER);
        }

        void run() { if (act != null) act.run(); }

        @Override protected void onMeasure(int w, int hh) {
            if (type == T_PILL) { p.setTextSize(dp(15)); setMeasuredDimension((int) p.measureText(label) + dp(40), dp(42)); }
            else { int s = type == T_PLAY ? dp(68) : dp(52); setMeasuredDimension(s, s); }
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2;
            p.setStyle(Paint.Style.FILL);
            p.setColor(sel ? YEL : (type == T_PLAY ? 0xCC3A3012 : 0x99000000));
            if (type == T_PILL) c.drawRoundRect(new RectF(0, 0, w, h), h / 2, h / 2, p); else c.drawCircle(cx, cy, Math.min(w, h) / 2, p);
            if (!sel) {
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(2)); p.setColor(type == T_PLAY ? YEL : 0x55FFFFFF);
                if (type == T_PILL) c.drawRoundRect(new RectF(dp(1), dp(1), w - dp(1), h - dp(1)), h / 2, h / 2, p); else c.drawCircle(cx, cy, Math.min(w, h) / 2 - dp(1), p);
            }
            int ic = sel ? DARK : (type == T_PLAY ? YEL : Color.WHITE);
            p.setStyle(Paint.Style.FILL); p.setColor(ic);
            float s = Math.min(w, h) * 0.22f;
            switch (type) {
                case T_PLAY:
                    if (player != null && player.getPlayWhenReady() && player.getPlaybackState() != Player.STATE_ENDED) {
                        c.drawRect(cx - s * 0.95f, cy - s, cx - s * 0.25f, cy + s, p);
                        c.drawRect(cx + s * 0.25f, cy - s, cx + s * 0.95f, cy + s, p);
                    } else {
                        path.reset(); path.moveTo(cx - s * 0.7f, cy - s * 1.1f); path.lineTo(cx + s * 1.1f, cy); path.lineTo(cx - s * 0.7f, cy + s * 1.1f); path.close(); c.drawPath(path, p);
                    }
                    break;
                case T_PREV:
                    c.drawRect(cx - s * 1.1f, cy - s, cx - s * 0.7f, cy + s, p);
                    path.reset(); path.moveTo(cx + s * 1.1f, cy - s); path.lineTo(cx - s * 0.5f, cy); path.lineTo(cx + s * 1.1f, cy + s); path.close(); c.drawPath(path, p);
                    break;
                case T_NEXT:
                    c.drawRect(cx + s * 0.7f, cy - s, cx + s * 1.1f, cy + s, p);
                    path.reset(); path.moveTo(cx - s * 1.1f, cy - s); path.lineTo(cx + s * 0.5f, cy); path.lineTo(cx - s * 1.1f, cy + s); path.close(); c.drawPath(path, p);
                    break;
                case T_BACK: case T_FWD:
                    c.save();
                    if (type == T_FWD) c.scale(-1, 1, cx, cy);
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(2.5f > 0 ? 3 : 3)); p.setStrokeCap(Paint.Cap.ROUND);
                    c.drawArc(new RectF(cx - s * 1.5f, cy - s * 1.5f, cx + s * 1.5f, cy + s * 1.5f), -60, 300, false, p);
                    p.setStyle(Paint.Style.FILL);
                    path.reset(); path.moveTo(cx + s * 0.55f, cy - s * 2.1f); path.lineTo(cx + s * 1.75f, cy - s * 0.95f); path.lineTo(cx + s * 0.1f, cy - s * 0.85f); path.close();
                    c.drawPath(path, p);
                    c.restore();
                    p.setTextSize(s * 1.25f); p.setTypeface(Typeface.DEFAULT_BOLD);
                    c.drawText("10", cx, cy + s * 0.45f, p);
                    p.setTypeface(Typeface.DEFAULT);
                    break;
                default:
                    p.setTextSize(dp(15)); p.setTypeface(Typeface.DEFAULT_BOLD);
                    c.drawText(label, cx, cy + dp(5), p);
                    p.setTypeface(Typeface.DEFAULT);
            }
        }
    }

    /** the progress bar: played (yellow), loaded (grey) and a thumb when it has the focus */
    private class Bar extends View {
        long cur, buf, dur; boolean sel;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Bar(Context c) { super(c); }

        void set(long c, long b, long d) { cur = c; buf = b; dur = d; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), cy = getHeight() / 2f, th = dp(sel ? 9 : 6);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x44FFFFFF); c.drawRoundRect(new RectF(0, cy - th / 2, w, cy + th / 2), th / 2, th / 2, p);
            if (dur > 0) {
                p.setColor(0x88FFFFFF); c.drawRoundRect(new RectF(0, cy - th / 2, w * Math.min(1f, (float) buf / dur), cy + th / 2), th / 2, th / 2, p);
                float x = w * Math.min(1f, (float) cur / dur);
                p.setColor(YEL); c.drawRoundRect(new RectF(0, cy - th / 2, x, cy + th / 2), th / 2, th / 2, p);
                if (sel) { p.setColor(Color.WHITE); c.drawCircle(x, cy, dp(11), p); p.setColor(YEL); c.drawCircle(x, cy, dp(7), p); }
            }
        }
    }
}
