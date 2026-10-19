package dev.shellmcp;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 极简 MCP 服务器（Streamable HTTP），零依赖，纯 ServerSocket 实现。
 * 端点：任意路径的 POST 都接受；默认绑定 127.0.0.1。
 */
public final class McpServer {

    private static final String TAG = "McpServer";
    private static final String PROTOCOL = "2025-06-18";
    private static final String SERVER_NAME = "android-shell";
    private static final String SERVER_VERSION = "1.0.0";

    private final int port;
    private final boolean lan;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private volatile boolean running = false;

    public McpServer(int port, boolean lan) {
        this.port = port;
        this.lan = lan;
    }

    public boolean isRunning() { return running; }
    public int getPort() { return port; }

    public synchronized void start() throws IOException {
        if (running) return;
        InetAddress bind = lan
                ? new InetSocketAddress(0).getAddress()               // 0.0.0.0
                : InetAddress.getByName("127.0.0.1");
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(bind, port));
        running = true;
        acceptThread = new Thread(this::acceptLoop, "mcp-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        Log.i(TAG, "listening on " + (lan ? "0.0.0.0" : "127.0.0.1") + ":" + port);
    }

    public synchronized void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignore) {}
        workers.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = serverSocket.accept();
                workers.submit(() -> handle(s));
            } catch (IOException e) {
                if (running) Log.w(TAG, "accept error", e);
            }
        }
    }

    // ---------------- HTTP ----------------

    private void handle(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(60_000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();

            // 读请求行 + 头
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "";

            int contentLength = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0) {
                    String k = line.substring(0, c).trim().toLowerCase();
                    String v = line.substring(c + 1).trim();
                    if (k.equals("content-length")) {
                        try { contentLength = Integer.parseInt(v); } catch (NumberFormatException ignore) {}
                    }
                }
            }

            if ("OPTIONS".equals(method)) { writeRaw(out, 204, "No Content", null, null); return; }
            if ("GET".equals(method))     { writeRaw(out, 405, "Method Not Allowed", null, null); return; }
            if ("DELETE".equals(method))  { writeRaw(out, 200, "OK", null, null); return; }
            if (!"POST".equals(method))   { writeRaw(out, 405, "Method Not Allowed", null, null); return; }

            byte[] body = readBody(in, contentLength);
            String text = new String(body, StandardCharsets.UTF_8).trim();

            Object parsed;
            try {
                parsed = text.isEmpty() ? new JSONObject() : new JSONTokener(text).nextValue();
            } catch (JSONException e) {
                writeJson(out, 400,
                        errorObj(null, -32700, "Parse error").toString(), false);
                return;
            }

            boolean batch = parsed instanceof JSONArray;
            JSONArray messages = batch ? (JSONArray) parsed : new JSONArray().put(parsed);
            JSONArray responses = new JSONArray();
            boolean newSession = false;

            for (int i = 0; i < messages.length(); i++) {
                JSONObject msg = messages.optJSONObject(i);
                boolean[] isInit = {false};
                JSONObject resp = dispatch(msg, isInit);
                if (isInit[0]) newSession = true;
                if (resp != null) responses.put(resp);
            }

            if (responses.length() == 0) {
                writeRaw(out, 202, "Accepted", null, null);  // 全是通知
                return;
            }
            String payload = batch ? responses.toString() : responses.get(0).toString();
            writeJson(out, 200, payload, newSession);

        } catch (Exception e) {
            Log.w(TAG, "handle error", e);
        }
    }

    // ---------------- MCP 分发 ----------------

    private JSONObject dispatch(JSONObject msg, boolean[] isInit) {
        if (msg == null) return errorObj(null, -32600, "Invalid Request");

        Object id = msg.has("id") ? msg.opt("id") : null;
        boolean notification = !msg.has("id");
        String methodName = msg.optString("method", "");
        JSONObject params = msg.optJSONObject("params");
        if (params == null) params = new JSONObject();

        try {
            switch (methodName) {
                case "initialize": {
                    isInit[0] = true;
                    JSONObject caps = new JSONObject()
                            .put("tools", new JSONObject().put("listChanged", false));
                    JSONObject info = new JSONObject()
                            .put("name", SERVER_NAME).put("version", SERVER_VERSION);
                    JSONObject r = new JSONObject()
                            .put("protocolVersion", params.optString("protocolVersion", PROTOCOL))
                            .put("capabilities", caps)
                            .put("serverInfo", info);
                    return resultObj(id, r);
                }
                case "notifications/initialized":
                case "notifications/cancelled":
                    return null;
                case "ping":
                    return resultObj(id, new JSONObject());
                case "tools/list":
                    return resultObj(id, new JSONObject().put("tools", Tools.list()));
                case "tools/call": {
                    String name = params.optString("name");
                    JSONObject args = params.optJSONObject("arguments");
                    if (args == null) args = new JSONObject();
                    Tools.Result tr = Tools.call(name, args);
                    JSONObject content = new JSONObject()
                            .put("type", "text").put("text", tr.text);
                    JSONObject r = new JSONObject()
                            .put("content", new JSONArray().put(content))
                            .put("isError", tr.isError);
                    return resultObj(id, r);
                }
                default:
                    if (notification) return null;
                    return errorObj(id, -32601, "Method not found: " + methodName);
            }
        } catch (Exception e) {
            return errorObj(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    private static JSONObject resultObj(Object id, JSONObject result) {
        try {
            return new JSONObject().put("jsonrpc", "2.0").put("id", id == null ? JSONObject.NULL : id)
                    .put("result", result);
        } catch (JSONException e) { throw new RuntimeException(e); }
    }

    private static JSONObject errorObj(Object id, int code, String message) {
        try {
            JSONObject err = new JSONObject().put("code", code).put("message", message);
            return new JSONObject().put("jsonrpc", "2.0").put("id", id == null ? JSONObject.NULL : id)
                    .put("error", err);
        } catch (JSONException e) { throw new RuntimeException(e); }
    }

    // ---------------- 低层 IO ----------------

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int prev = -1, b;
        while ((b = in.read()) != -1) {
            if (prev == '\r' && b == '\n') {
                byte[] arr = buf.toByteArray();
                return new String(arr, 0, arr.length - 1, StandardCharsets.UTF_8);
            }
            buf.write(b);
            prev = b;
        }
        if (buf.size() == 0) return null;
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static byte[] readBody(InputStream in, int len) throws IOException {
        if (len <= 0) return new byte[0];
        byte[] data = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(data, off, len - off);
            if (n < 0) break;
            off += n;
        }
        if (off == len) return data;
        byte[] trimmed = new byte[off];
        System.arraycopy(data, 0, trimmed, 0, off);
        return trimmed;
    }

    private static void writeJson(OutputStream out, int status, String json, boolean newSession)
            throws IOException {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(status).append(" OK\r\n");
        h.append("Content-Type: application/json\r\n");
        h.append("Content-Length: ").append(payload.length).append("\r\n");
        if (newSession) h.append("Mcp-Session-Id: ").append(UUID.randomUUID().toString().replace("-", "")).append("\r\n");
        corsHeaders(h);
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.UTF_8));
        out.write(payload);
        out.flush();
    }

    private static void writeRaw(OutputStream out, int status, String reason,
                                 String ctype, byte[] payload) throws IOException {
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
        if (ctype != null) h.append("Content-Type: ").append(ctype).append("\r\n");
        h.append("Content-Length: ").append(payload == null ? 0 : payload.length).append("\r\n");
        corsHeaders(h);
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.UTF_8));
        if (payload != null) out.write(payload);
        out.flush();
    }

    private static void corsHeaders(StringBuilder h) {
        h.append("Access-Control-Allow-Origin: *\r\n");
        h.append("Access-Control-Allow-Headers: Content-Type, Mcp-Session-Id, Accept, Authorization\r\n");
        h.append("Access-Control-Allow-Methods: POST, GET, DELETE, OPTIONS\r\n");
        h.append("Access-Control-Expose-Headers: Mcp-Session-Id\r\n");
    }
}
