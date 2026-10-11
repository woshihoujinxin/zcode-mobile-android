package com.zcode.mobile;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 局域网群控台服务：同一 Wi-Fi 内 PC 浏览器访问，网格化同时操作多台已绑定桌面的会话页。
 *
 * 形态：App 内置迷你 HTTP 服务（无第三方依赖）——
 *   GET /?token=…          群控台页面（assets/console.html）
 *   GET /api/devices?token=…  已绑定设备列表 JSON（含官方远控页 URL，凭据在内网+token 下发）
 *
 * 安全边界：token 随机生成保存在本机，URL 由 App 展示；token 错误一律 403。
 * 页面本体（console.html）不含任何凭据，凭据只在通过 token 校验后经 /api/devices 下发。
 */
public final class ControlServer {

    public interface Listener {
        void onServerStarted(String url);

        void onServerError(String message);
    }

    private static final int MAX_BODY = 16 * 1024;
    private static final String PREFS_TOKEN_KEY = "control_token";

    private final Context context;
    private final PrefStore prefStore;
    private final Listener listener;
    private ServerSocket serverSocket;
    private volatile boolean running;
    private ThreadPoolExecutor workers;
    private String token;

    private ControlServer(Context context, PrefStore prefStore, Listener listener) {
        this.context = context.getApplicationContext();
        this.prefStore = prefStore;
        this.listener = listener;
    }

    /** 启动并返回实例；失败返回 null。 */
    public static ControlServer start(Context context, PrefStore prefStore, Listener listener) {
        ControlServer server = new ControlServer(context, prefStore, listener);
        try {
            server.token = server.loadOrCreateToken();
            server.serverSocket = new ServerSocket(8790, 16);
            server.running = true;
            server.workers = new ThreadPoolExecutor(
                    2, 8, 20, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                    r -> {
                        Thread t = new Thread(r, "zcode-control-server");
                        t.setDaemon(true);
                        return t;
                    });
            Thread acceptor = new Thread(server::acceptLoop, "zcode-control-accept");
            acceptor.setDaemon(true);
            acceptor.start();
            String ip = resolveLanIp();
            server.listener.onServerStarted(ip == null
                    ? "http://127.0.0.1:8790/?token=" + server.token
                    : "http://" + ip + ":8790/?token=" + server.token);
            return server;
        } catch (IOException e) {
            server.listener.onServerError(e.getMessage() == null ? "bind failed" : e.getMessage());
            return null;
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        if (workers != null) {
            workers.shutdownNow();
        }
    }

    private String loadOrCreateToken() {
        android.content.SharedPreferences prefs =
                context.getSharedPreferences("zcode_control", Context.MODE_PRIVATE);
        String existing = prefs.getString(PREFS_TOKEN_KEY, null);
        if (existing != null && existing.length() >= 16) {
            return existing;
        }
        byte[] raw = new byte[18];
        new SecureRandom().nextBytes(raw);
        StringBuilder sb = new StringBuilder();
        for (byte b : raw) {
            sb.append(String.format("%02x", b));
        }
        String created = sb.substring(0, 24);
        prefs.edit().putString(PREFS_TOKEN_KEY, created).apply();
        return created;
    }

    /** 取 wlan 接口的 IPv4（无需定位权限）。 */
    public static String resolveLanIp() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface nif : interfaces) {
                if (!nif.isUp() || nif.isLoopback() || !nif.getName().startsWith("wlan")) {
                    continue;
                }
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                workers.execute(() -> handle(client));
            } catch (IOException e) {
                if (running) {
                    listener.onServerError(e.getMessage() == null ? "accept failed" : e.getMessage());
                }
                break;
            }
        }
    }

    private void handle(Socket client) {
        try {
            client.setSoTimeout(10_000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            String header = readHeader(in);
            if (header == null) {
                respond(out, 400, "text/plain", "bad request");
                return;
            }
            String[] requestLine = header.split("\r\n")[0].split(" ");
            if (requestLine.length < 2) {
                respond(out, 400, "text/plain", "bad request");
                return;
            }
            String method = requestLine[0];
            String full = requestLine[1];
            int queryStart = full.indexOf('?');
            String path = queryStart < 0 ? full : full.substring(0, queryStart);
            String query = queryStart < 0 ? "" : full.substring(queryStart + 1);

            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                respond(out, 405, "text/plain", "method not allowed");
                return;
            }
            String requestToken = param(query, "token");
            if (requestToken == null || !requestToken.equals(token)) {
                respond(out, 403, "text/plain", "forbidden");
                return;
            }
            if (path.equals("/") || path.equals("/index.html")) {
                byte[] page = readAsset("console.html");
                if (page == null) {
                    respond(out, 500, "text/plain", "console page missing");
                    return;
                }
                respondBytes(out, 200, "text/html; charset=utf-8", page);
                return;
            }
            if (path.equals("/api/devices")) {
                // 注意：这里绝不能触发 CredentialHealth.refresh——probe 以 terminal 角色连 relay，
                // 而同一桌面只允许一个活跃终端，周期性探测会把已配对的手机/PC 会话踢下线（KICKED）。
                // 状态只在打开设备（RemoteActivity 预检）时更新。
                StringBuilder versionSeed = new StringBuilder();
                JSONArray array = new JSONArray();
                List<PairedDevice> devices = prefStore.getDevices();
                for (PairedDevice device : devices) {
                    JSONObject item = new JSONObject();
                    try {
                        item.put("id", device.id);
                        item.put("name", device.name);
                        item.put("url", device.url);
                        item.put("boundAt", device.boundAt);
                        if (device.mid != null) {
                            item.put("mid", device.mid);
                        }
                        item.put("credsHash", credsHash(device.url));
                        item.put("credStatus", CredentialHealth.statusFor(prefStore, device.id));
                        item.put("credProbedAt", CredentialHealth.probedAtFor(prefStore, device.id));
                        array.put(item);
                    } catch (Exception ignored) {
                    }
                    versionSeed.append(credsHash(device.url)).append(',');
                }
                JSONObject result = new JSONObject();
                try {
                    result.put("devices", array);
                    result.put("version", Integer.toHexString(versionSeed.toString().hashCode()));
                } catch (Exception ignored) {
                }
                respond(out, 200, "application/json", result.toString());
                return;
            }
            respond(out, 404, "text/plain", "not found");
        } catch (Exception ignored) {
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 凭据变更指纹：url 一变（重扫更新 hash / 换发 sid）即视为新凭据。 */
    private static String credsHash(String url) {
        return Integer.toHexString(url == null ? 0 : url.hashCode()) + ":" + (url == null ? 0 : url.length());
    }

    private static String param(String query, String key) {
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }

    private static String readHeader(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int last4 = 0;
        while (buffer.size() < MAX_BODY) {
            int b = in.read();
            if (b == -1) {
                return null;
            }
            buffer.write(b);
            last4 = (last4 << 8) | b;
            if (last4 == 0x0D0A0D0A) {
                return buffer.toString(StandardCharsets.ISO_8859_1.name());
            }
        }
        return null;
    }

    private byte[] readAsset(String name) {
        try (InputStream is = context.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = is.read(chunk)) != -1) {
                out.write(chunk, 0, read);
            }
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private static void respond(OutputStream out, int code, String type, String body) throws IOException {
        respondBytes(out, code, type, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void respondBytes(OutputStream out, int code, String type, byte[] body) throws IOException {
        String status = code == 200 ? "OK" : code == 403 ? "Forbidden"
                : code == 404 ? "Not Found" : code == 405 ? "Method Not Allowed" : "Error";
        String head = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }
}
