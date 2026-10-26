package com.ahly.tv;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Tiny HTTP server on the TV (port 8765) so a phone on the same Wi-Fi can add IPTV playlists:
 * GET / (form page), GET /list, POST /add (JSON), POST /delete?id=.
 */
public class PhoneServer {
    public interface Handler {
        /** returns {status, contentType, body} */
        Object[] handle(String method, String path, Map<String, String> query, byte[] body);
    }

    private static final int MAX_BODY = 45 * 1024 * 1024;
    private final int port;
    private final Handler handler;
    private ServerSocket server;
    private volatile boolean running;

    public PhoneServer(int port, Handler handler) { this.port = port; this.handler = handler; }

    public void start() {
        running = true;
        Thread t = new Thread(() -> {
            try {
                server = new ServerSocket(port);
                while (running) {
                    final Socket s = server.accept();
                    new Thread(() -> serve(s)).start();
                }
            } catch (IOException ignored) { }
        }, "phone-server");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
    }

    private void serve(Socket s) {
        try {
            s.setSoTimeout(60000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            String head = readHead(in);
            if (head == null) { s.close(); return; }
            String[] lines = head.split("\r\n");
            String[] req = lines[0].split(" ");
            if (req.length < 2) { s.close(); return; }
            String method = req[0].toUpperCase(Locale.ROOT);
            String target = req[1];
            int len = 0;
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c > 0 && lines[i].substring(0, c).trim().equalsIgnoreCase("content-length")) {
                    try { len = Integer.parseInt(lines[i].substring(c + 1).trim()); } catch (NumberFormatException ignored) { }
                }
            }
            if (len > MAX_BODY) { respond(s.getOutputStream(), 413, "application/json", "{\"ok\":false,\"error\":\"too big\"}".getBytes(StandardCharsets.UTF_8)); s.close(); return; }
            byte[] body = new byte[Math.max(0, len)];
            int off = 0;
            while (off < len) { int n = in.read(body, off, len - off); if (n < 0) break; off += n; }
            String path = target; Map<String, String> q = new HashMap<>();
            int qi = target.indexOf('?');
            if (qi >= 0) {
                path = target.substring(0, qi);
                for (String kv : target.substring(qi + 1).split("&")) {
                    int e = kv.indexOf('=');
                    if (e > 0) q.put(URLDecoder.decode(kv.substring(0, e), "UTF-8"), URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
                }
            }
            Object[] r = handler.handle(method, path, q, body);
            respond(s.getOutputStream(), (Integer) r[0], (String) r[1], (byte[]) r[2]);
            s.close();
        } catch (Exception e) {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0, c;
        while ((c = in.read()) >= 0) {
            b.write(c);
            if (b.size() > 16384) return null;
            if ((state == 0 || state == 2) && c == '\r') state++;
            else if ((state == 1 || state == 3) && c == '\n') { state++; if (state == 4) return b.toString("UTF-8"); }
            else state = 0;
        }
        return null;
    }

    private static void respond(OutputStream out, int status, String type, byte[] body) throws IOException {
        String reason = status == 200 ? "OK" : status == 404 ? "Not Found" : "Error";
        String h = "HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }
}
