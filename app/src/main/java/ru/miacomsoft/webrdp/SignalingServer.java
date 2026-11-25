package ru.miacomsoft.webrdp;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.IceCandidate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;

public class SignalingServer {

    private static final String TAG = "SignalingServer";
    private final Context ctx;
    private final SettingsManager settings;
    private HttpServer server;

    public SignalingServer(Context ctx, SettingsManager s) {
        this.ctx = ctx;
        this.settings = s;
    }

    public void start() throws IOException {
        int port = settings.getHttpPort();
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newCachedThreadPool());

        server.createContext("/", new UiHandler());
        server.createContext("/viewer.js", new FileHandler("web/viewer.js", "application/javascript"));
        server.createContext("/api/offer", new OfferHandler());
        server.createContext("/api/ice", new IceHandler());
        server.createContext("/api/reset", new ResetHandler());
        server.start();
    }

    public void stop() {
        if (server != null) { server.stop(0); server = null; }
    }

    // ================= helpers =================

    private boolean checkAuth(HttpExchange ex) {
        Headers h = ex.getRequestHeaders();
        String auth = h.getFirst("Authorization");
        if (auth == null || !auth.startsWith("Basic ")) return false;
        try {
            String dec = new String(Base64.decode(auth.substring(6), Base64.DEFAULT), StandardCharsets.UTF_8);
            int idx = dec.indexOf(':');
            if (idx < 0) return false;
            String u = dec.substring(0, idx), p = dec.substring(idx + 1);
            return u.equals(settings.getUsername()) && p.equals(settings.getPassword());
        } catch (Exception e) { return false; }
    }

    private void cors(Headers h) {
        h.set("Access-Control-Allow-Origin", "*");
        h.set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        h.set("Access-Control-Allow-Headers", "Authorization,Content-Type");
        h.set("Access-Control-Max-Age", "86400");
    }

    private static void sendBytes(HttpExchange ex, int code, byte[] body, String mime) throws IOException {
        Headers h = ex.getResponseHeaders();
        if (mime != null) h.set("Content-Type", mime);
        ex.sendResponseHeaders(code, body == null ? -1 : body.length);
        if (body != null && body.length > 0) {
            OutputStream os = ex.getResponseBody();
            os.write(body);
        }
        ex.close();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static String readString(HttpExchange ex) throws IOException {
        return new String(readAll(ex.getRequestBody()), StandardCharsets.UTF_8);
    }

    private ScreenStreamService svc() {
        // Сервис — синглтон-подобный доступ через Application?
        // В реальном проекте удобно хранить статическую ссылку в самом сервисе.
        return ScreenStreamServiceHolder.get();
    }

    // ================= handlers =================

    private class UiHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            cors(ex.getResponseHeaders());
            String method = ex.getRequestMethod();
            if ("OPTIONS".equalsIgnoreCase(method)) { sendBytes(ex, 204, null, null); return; }
            if (!checkAuth(ex)) {
                ex.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"WebRDP\"");
                sendBytes(ex, 401, "Unauthorized".getBytes(StandardCharsets.UTF_8), "text/plain");
                return;
            }
            try {
                byte[] html = readAsset("web/index.html");
                sendBytes(ex, 200, html, "text/html; charset=utf-8");
            } catch (Exception e) {
                sendBytes(ex, 500, ("error: "+e.getMessage()).getBytes(StandardCharsets.UTF_8), "text/plain");
            }
        }
    }

    private class FileHandler implements HttpHandler {
        private final String path, mime;
        public FileHandler(String p, String m) { this.path = p; this.mime = m; }
        @Override public void handle(HttpExchange ex) throws IOException {
            cors(ex.getResponseHeaders());
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { sendBytes(ex, 204, null, null); return; }
            if (!checkAuth(ex)) { sendBytes(ex, 401, "Unauthorized".getBytes(StandardCharsets.UTF_8), "text/plain"); return; }
            try { sendBytes(ex, 200, readAsset(path), mime + "; charset=utf-8"); }
            catch (Exception e) { sendBytes(ex, 404, "Not found".getBytes(StandardCharsets.UTF_8), "text/plain"); }
        }
    }

    private class OfferHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            cors(ex.getResponseHeaders());
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { sendBytes(ex, 204, null, null); return; }
            if (!checkAuth(ex)) { sendBytes(ex, 401, "Unauthorized".getBytes(StandardCharsets.UTF_8), "text/plain"); return; }
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { sendBytes(ex, 405, null, null); return; }
            try {
                String offerSdp = readString(ex);
                ScreenStreamService s = svc();
                if (s == null) { sendBytes(ex, 503, "Service not running".getBytes(StandardCharsets.UTF_8), "text/plain"); return; }
                String answer = s.handleOffer(offerSdp);
                sendBytes(ex, 200, answer.getBytes(StandardCharsets.UTF_8), "application/sdp");
            } catch (Exception e) {
                Log.e(TAG, "offer", e);
                sendBytes(ex, 500, ("error: "+e.getMessage()).getBytes(StandardCharsets.UTF_8), "text/plain");
            }
        }
    }

    private class IceHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            cors(ex.getResponseHeaders());
            String method = ex.getRequestMethod();
            if ("OPTIONS".equalsIgnoreCase(method)) { sendBytes(ex, 204, null, null); return; }
            if (!checkAuth(ex)) { sendBytes(ex, 401, "Unauthorized".getBytes(StandardCharsets.UTF_8), "text/plain"); return; }
            ScreenStreamService s = svc();
            if (s == null) { sendBytes(ex, 503, "Service not running".getBytes(StandardCharsets.UTF_8), "text/plain"); return; }
            try {
                if ("POST".equalsIgnoreCase(method)) {
                    JSONObject o = new JSONObject(readString(ex));
                    s.addClientIce(o.optString("sdpMid"), o.optInt("sdpMLineIndex"),
                            o.optString("candidate"));
                    sendBytes(ex, 204, null, null);
                } else {
                    List<IceCandidate> list = s.drainServerIce();
                    JSONArray arr = new JSONArray();
                    for (IceCandidate c : list) {
                        JSONObject o = new JSONObject();
                        o.put("sdpMid", c.sdpMid);
                        o.put("sdpMLineIndex", c.sdpMLineIndex);
                        o.put("candidate", c.sdp);
                        arr.put(o);
                    }
                    sendBytes(ex, 200, arr.toString().getBytes(StandardCharsets.UTF_8), "application/json");
                }
            } catch (Exception e) {
                Log.e(TAG, "ice", e);
                sendBytes(ex, 500, ("error: "+e.getMessage()).getBytes(StandardCharsets.UTF_8), "text/plain");
            }
        }
    }

    private class ResetHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            cors(ex.getResponseHeaders());
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { sendBytes(ex, 204, null, null); return; }
            if (!checkAuth(ex)) { sendBytes(ex, 401, "Unauthorized".getBytes(StandardCharsets.UTF_8), "text/plain"); return; }
            ScreenStreamService s = svc();
            if (s != null) s.resetPeerConnection();
            sendBytes(ex, 204, null, null);
        }
    }

    private byte[] readAsset(String name) throws IOException {
        try (InputStream in = ctx.getAssets().open(name);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) bos.write(b, 0, n);
            return bos.toByteArray();
        }
    }
}