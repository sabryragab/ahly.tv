package com.ahly.tv;

import android.app.Activity;
import android.net.Uri;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A small live preview that sits on top of the web page inside a rectangle the page asks for. */
final class Preview {
    interface Cb { void state(String s); }

    private static final Pattern XTREAM = Pattern.compile("^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\\d+)(?:\\.(ts|m3u8))?$", Pattern.CASE_INSENSITIVE);
    private final Activity act;
    private final FrameLayout host;
    private final Cb cb;
    private ExoPlayer player;
    private TextureView tv;
    private final List<String> tries = new ArrayList<>();
    private int bx, by, bw = 16, bh = 9, vw, vh;
    private boolean active;

    Preview(Activity a, FrameLayout host, Cb cb) { this.act = a; this.host = host; this.cb = cb; }

    void play(String url, int x, int y, int w, int h) {
        bx = x; by = y; bw = Math.max(16, w); bh = Math.max(9, h); vw = 0; vh = 0;
        ensure();
        tries.clear();
        tries.add(url);
        Matcher m = XTREAM.matcher(url);
        if (m.find() && !url.matches("(?i).*\\.(mp4|mkv|avi)$")) {
            tries.add(m.group(1) + "/live/" + m.group(2) + "/" + m.group(3) + "/" + m.group(4) + ".m3u8");
            tries.add(m.group(1) + "/live/" + m.group(2) + "/" + m.group(3) + "/" + m.group(4) + ".ts");
        }
        tries.add("hls:" + url);
        active = true;
        tv.setAlpha(0f);
        tv.setVisibility(View.VISIBLE);
        place();
        cb.state("loading");
        next();
    }

    void layout(int x, int y, int w, int h) {
        bx = x; by = y; bw = Math.max(16, w); bh = Math.max(9, h);
        if (active) place();
    }

    /** Stops and frees the decoder and the connection (some IPTV accounts allow a single connection). */
    void stop() {
        active = false;
        tries.clear();
        if (player != null) { player.release(); player = null; }
        if (tv != null) tv.setVisibility(View.GONE);
    }

    private void ensure() {
        if (tv == null) {
            tv = new TextureView(act);
            tv.setVisibility(View.GONE);
            host.addView(tv, new FrameLayout.LayoutParams(bw, bh));
        }
        if (player != null) return;
        DefaultLoadControl lc = new DefaultLoadControl.Builder().setBufferDurationsMs(4000, 15000, 600, 1200).build();
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true).setUserAgent("VLC/3.0.20 LibVLC/3.0.20")
                .setConnectTimeoutMs(10000).setReadTimeoutMs(12000);
        DefaultExtractorsFactory ex = new DefaultExtractorsFactory()
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES | DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS);
        player = new ExoPlayer.Builder(act, new DefaultRenderersFactory(act).setEnableDecoderFallback(true))
                .setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(act, http), ex))
                .setLoadControl(lc).build();
        player.setVideoTextureView(tv);
        player.setPlayWhenReady(true);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int s) { if (s == Player.STATE_READY && active) cb.state("playing"); }
            @Override public void onRenderedFirstFrame() { if (tv != null && active) tv.setAlpha(1f); }
            @Override public void onVideoSizeChanged(VideoSize v) { vw = v.width; vh = v.height; if (active) place(); }
            @Override public void onPlayerError(PlaybackException e) { next(); }
        });
    }

    private void next() {
        if (player == null) return;
        if (tries.isEmpty()) { cb.state("error"); return; }
        String u = tries.remove(0);
        boolean forceHls = u.startsWith("hls:");
        if (forceHls) u = u.substring(4);
        MediaItem.Builder mb = new MediaItem.Builder().setUri(Uri.parse(u));
        if (forceHls || u.toLowerCase().contains("m3u8") || u.contains("type=m3u")) mb.setMimeType(MimeTypes.APPLICATION_M3U8);
        player.setMediaItem(mb.build());
        player.prepare();
    }

    /** fit the picture inside the rectangle, keeping its shape */
    private void place() {
        if (tv == null) return;
        int w = bw, h = bh;
        if (vw > 0 && vh > 0) {
            float ar = (float) vw / vh;
            h = (int) (bw / ar);
            if (h > bh) { h = bh; w = (int) (bh * ar); }
        }
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, Gravity.TOP | Gravity.LEFT);
        p.leftMargin = bx + (bw - w) / 2;
        p.topMargin = by + (bh - h) / 2;
        tv.setLayoutParams(p);
    }
}
