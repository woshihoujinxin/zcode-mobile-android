package com.zcode.mobile;

import android.net.Uri;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 官方 relay 凭据探针：用最小 WebSocket 客户端（RFC6455 over TLS，无第三方依赖）
 * 执行一次与官方手机页完全一致的终端鉴权握手，判断本地保存的 sid/hash 是否仍被 relay 接受。
 *
 * 背景（逆向自官方 zcode-remote-hub 协议，参考 ZCode 源码 packages/web/src/webRemoteControlTransport.ts）：
 *   auth_init{role:"terminal",device_sid} → auth_challenge{nonce}
 *   → auth_response{proof=HMAC-SHA256(passHash, `${nonce}|terminal|${sid}`) base64url}
 *   → auth_ack（凭据有效）| error{code:"AUTH_FAILED"}（凭据已失效）
 *
 * relay 端设备注册是会话态：桌面端「重置配对」会换发新 sid+hash，桌面长时间离线后注册也会过期，
 * 此时旧凭据会得到 AUTH_FAILED——这正是「绑定过的设备打不开」的直接原因。
 * 探测结果是客户端凭据健康度的唯一可靠信号，驱动设备列表状态与群控台自愈。
 */
public final class RelayProbe {

    /** VALID=凭据被 relay 接受；STALE=relay 明确拒绝（AUTH_FAILED/WRONG_PARAM）；UNKNOWN=网络等原因无法判定。 */
    public enum Result { VALID, STALE, UNKNOWN }

    private static final int WS_OPCODE_TEXT = 0x1;
    private static final int WS_OPCODE_CLOSE = 0x8;
    private static final int WS_OPCODE_PONG = 0xA;

    private RelayProbe() {
    }

    /** 同步执行一次探测，阻塞至多 timeoutMs；必须在子线程调用。 */
    public static Result probe(String remoteUrl, int timeoutMs) {
        Uri uri;
        try {
            uri = Uri.parse(remoteUrl);
        } catch (Exception e) {
            return Result.UNKNOWN;
        }
        String host = uri.getHost();
        if (host == null || !PairUrl.isAllowedHost(host) || !"https".equalsIgnoreCase(uri.getScheme())) {
            return Result.UNKNOWN;
        }
        String sid = uri.getQueryParameter("sid");
        String hash = uri.getQueryParameter("hash");
        if (sid == null || sid.isEmpty() || hash == null || hash.isEmpty()) {
            return Result.UNKNOWN;
        }
        String mid = uri.getQueryParameter("mid");

        SSLSocket socket = null;
        try {
            socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket(host, 443);
            socket.setSoTimeout(timeoutMs);
            socket.setTcpNoDelay(true);
            return runHandshake(socket, host, mid, sid, hash, timeoutMs);
        } catch (Exception e) {
            return Result.UNKNOWN;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static Result runHandshake(SSLSocket socket, String host, String mid,
                                       String sid, String hash, int timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        byte[] keyBytes = new byte[16];
        new SecureRandom().nextBytes(keyBytes);
        String wsKey = Base64.encodeToString(keyBytes, Base64.NO_WRAP);
        String path = "/ws" + (mid == null || mid.isEmpty() ? "" : "?mid=" + Uri.encode(mid));
        String handshake = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + wsKey + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "User-Agent: ZCodeMobile/0.3\r\n\r\n";
        out.write(handshake.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();

        String response = readUntilHeaders(in, deadline);
        if (response == null || !response.startsWith("HTTP/1.1 101")) {
            return Result.UNKNOWN;
        }

        try {
            JSONObject init = new JSONObject();
            init.put("type", "auth_init");
            init.put("role", "terminal");
            init.put("device_sid", sid);
            JSONObject meta = new JSONObject();
            meta.put("platform", "web");
            meta.put("version", "web");
            meta.put("name", "mobile-browser");
            init.put("meta", meta);
            init.put("client_ts", System.currentTimeMillis());
            writeFrame(out, WS_OPCODE_TEXT, init.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return Result.UNKNOWN;
        }

        ByteArrayOutputStream pending = new ByteArrayOutputStream();
        while (System.currentTimeMillis() < deadline) {
            int[] opcode = new int[1];
            byte[] payload = readFrame(in, out, opcode, pending);
            if (payload == null) {
                continue; // ping/pong/control 帧已在 readFrame 内处理
            }
            if (opcode[0] == WS_OPCODE_CLOSE) {
                return Result.UNKNOWN;
            }
            JSONObject msg;
            try {
                msg = new JSONObject(new String(payload, StandardCharsets.UTF_8));
            } catch (Exception e) {
                continue;
            }
            String type = msg.optString("type", "");
            if ("auth_challenge".equals(type)) {
                String nonce = msg.optString("nonce", "");
                JSONObject resp = new JSONObject();
                try {
                    resp.put("type", "auth_response");
                    resp.put("device_sid", sid);
                    resp.put("proof", calculateProof(hash, nonce, sid));
                    resp.put("client_ts", System.currentTimeMillis());
                    writeFrame(out, WS_OPCODE_TEXT, resp.toString().getBytes(StandardCharsets.UTF_8));
                } catch (Exception e) {
                    return Result.UNKNOWN;
                }
            } else if ("auth_ack".equals(type) || "pair_status_ack".equals(type)) {
                return Result.VALID;
            } else if ("error".equals(type)) {
                String code = msg.optString("code", "");
                // 与官方手机页 handleRelayError 一致：AUTH_FAILED/WRONG_PARAM = 凭据不再有效。
                return ("AUTH_FAILED".equals(code) || "WRONG_PARAM".equals(code))
                        ? Result.STALE : Result.UNKNOWN;
            }
        }
        return Result.UNKNOWN;
    }

    /** proof = HMAC-SHA256(passHash, `${nonce}|terminal|${sid}`) 的 base64url（无填充）。 */
    private static String calculateProof(String passHash, String nonce, String sid) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(passHash.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal((nonce + "|terminal|" + sid).getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(digest, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static String readUntilHeaders(InputStream in, long deadline) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int last4 = 0;
        while (buffer.size() < 16 * 1024) {
            if (System.currentTimeMillis() > deadline) {
                throw new IOException("handshake timeout");
            }
            int b = in.read();
            if (b < 0) {
                throw new EOFException();
            }
            buffer.write(b);
            last4 = (last4 << 8) | b;
            if (last4 == 0x0D0A0D0A) {
                return buffer.toString(StandardCharsets.ISO_8859_1.name()).split("\r\n")[0];
            }
        }
        return null;
    }

    /**
     * 读取一个完整 WebSocket 消息；服务端帧不掩码。
     * ping 帧就地回 pong；返回 null 表示该帧无需上层处理。CLOSE 帧原样上报由调用方决定。
     */
    private static byte[] readFrame(InputStream in, OutputStream out, int[] opcodeOut,
                                    ByteArrayOutputStream fragmented) throws IOException {
        int b1 = in.read();
        int b2 = in.read();
        if (b1 < 0 || b2 < 0) {
            throw new EOFException();
        }
        boolean fin = (b1 & 0x80) != 0;
        int opcode = b1 & 0x0F;
        boolean masked = (b2 & 0x80) != 0;
        long len = b2 & 0x7F;
        if (len == 126) {
            int hi = in.read();
            int lo = in.read();
            if (hi < 0 || lo < 0) throw new EOFException();
            len = ((hi & 0xFF) << 8) | (lo & 0xFF);
        } else if (len == 127) {
            for (int i = 0; i < 8; i++) {
                if (in.read() < 0) throw new EOFException();
            }
            len = 1 << 20; // 探针场景不会有大帧，封顶 1MB
        }
        byte[] mask = new byte[4];
        if (masked && in.read(mask) != 4) {
            throw new EOFException();
        }
        byte[] payload = new byte[(int) len];
        int off = 0;
        while (off < payload.length) {
            int n = in.read(payload, off, payload.length - off);
            if (n < 0) throw new EOFException();
            off += n;
        }
        if (masked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= mask[i & 3];
            }
        }

        if (opcode == 0x9) { // ping → pong（控制帧不携带业务语义）
            try {
                writeFrame(out, WS_OPCODE_PONG, payload);
            } catch (IOException ignored) {
            }
            opcodeOut[0] = -1;
            return null;
        }
        if (opcode == WS_OPCODE_CLOSE || opcode == WS_OPCODE_PONG) {
            opcodeOut[0] = opcode == WS_OPCODE_CLOSE ? WS_OPCODE_CLOSE : -1;
            return opcode == WS_OPCODE_CLOSE ? payload : null;
        }
        if (opcode == WS_OPCODE_TEXT && fin) {
            opcodeOut[0] = WS_OPCODE_TEXT;
            return payload;
        }
        if (opcode == 0x0 || (opcode == WS_OPCODE_TEXT && !fin)) { // 分片
            fragmented.write(payload);
            if (fin) {
                byte[] full = fragmented.toByteArray();
                fragmented.reset();
                opcodeOut[0] = WS_OPCODE_TEXT;
                return full;
            }
            opcodeOut[0] = -1;
            return null;
        }
        opcodeOut[0] = -1;
        return null;
    }

    /** 客户端帧必须掩码。消息都很小，仅实现 126 以内的原生长度与 2 字节扩展长度。 */
    private static void writeFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
        int len = payload.length;
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        head.write(0x80 | opcode);
        if (len < 126) {
            head.write(0x80 | len);
        } else if (len < 65536) {
            head.write(0x80 | 126);
            head.write((len >> 8) & 0xFF);
            head.write(len & 0xFF);
        } else {
            throw new IOException("frame too large");
        }
        byte[] mask = new byte[4];
        new SecureRandom().nextBytes(mask);
        head.write(mask);
        out.write(head.toByteArray());
        byte[] masked = Arrays.copyOf(payload, len);
        for (int i = 0; i < len; i++) {
            masked[i] ^= mask[i & 3];
        }
        out.write(masked);
        out.flush();
    }
}
