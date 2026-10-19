package com.zcode.mobile;

import android.net.Uri;

/**
 * 校验并规范化「Web 远程控制」配对 URL。
 *
 * 二维码内容为官方手机远控页地址 + 配对参数（逆向自官方桌面版 buildWebRemoteControlExternalQrUrl）：
 *   https://zcode.z.ai/remote/v4?sid=<deviceSid>&hash=<passHash>&t=...&mid=...&name=...
 *
 * 出于防二维码劫持考虑，只接受官方域名 + 必须携带 sid 与 hash。
 */
public final class PairUrl {
    private static final String[] ALLOWED_HOSTS = {"zcode.z.ai", "zcode.chatglm.site", "www.zcode.z.ai"};

    /** 直连模式的设备 id 前缀：用户手动输入服务器地址（而非扫码配对）。 */
    public static final String DIRECT_PREFIX = "direct:";

    private PairUrl() {
    }

    public static boolean isAllowedHost(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.trim().toLowerCase();
        for (String allowed : ALLOWED_HOSTS) {
            if (normalized.equals(allowed)) {
                return true;
            }
        }
        return false;
    }

    /** 该设备 id 是否为「直连服务器」模式（跳过 relay 凭据预检，允许 http 与同站导航）。 */
    public static boolean isDirectDeviceId(String id) {
        return id != null && id.startsWith(DIRECT_PREFIX);
    }

    /**
     * 解析直连地址：用户在直连对话框手动输入，http/https 均可，任意主机，
     * 不要求 sid/hash（自建 dsh web 的认证在 nginx 基本认证层）。
     *
     * @return 规范化后的地址（补齐末尾 /），不合法返回 null。
     */
    public static String parseDirect(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "http://" + trimmed;
        }
        Uri uri;
        try {
            uri = Uri.parse(trimmed);
        } catch (Exception e) {
            return null;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || host.trim().isEmpty()) {
            return null;
        }
        boolean httpOrHttps = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        if (!httpOrHttps) {
            return null;
        }
        StringBuilder normalized = new StringBuilder();
        normalized.append(scheme.toLowerCase()).append("://").append(host.toLowerCase());
        if (uri.getPort() != -1) {
            normalized.append(':').append(uri.getPort());
        }
        String path = uri.getPath();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            normalized.append('/');
        } else {
            normalized.append(path);
        }
        return normalized.toString();
    }

    /** 直连设备的稳定唯一键：direct:host:port。同站重连复用同一条目并更新地址。 */
    public static String directDeviceId(String url) {
        Uri uri = Uri.parse(url);
        return DIRECT_PREFIX + uri.getHost() + ":" + uri.getPort();
    }

    /** 返回 null 表示不合法；合法时返回规范化后的连接 URL。 */
    public static String parse(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        Uri uri;
        try {
            uri = Uri.parse(trimmed);
        } catch (Exception e) {
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return null;
        }
        if (!"https".equalsIgnoreCase(scheme)) {
            return null;
        }
        if (!isAllowedHost(uri.getHost())) {
            return null;
        }
        String sid = uri.getQueryParameter("sid");
        String hash = uri.getQueryParameter("hash");
        if (sid == null || sid.trim().isEmpty() || hash == null || hash.trim().isEmpty()) {
            return null;
        }
        return trimmed;
    }

    /** 提取桌面名称（二维码 name 参数），用于绑定信息展示。 */
    public static String extractDeviceName(String raw) {
        try {
            Uri uri = Uri.parse(raw);
            String name = uri.getQueryParameter("name");
            return name == null || name.trim().isEmpty() ? "桌面端" : name.trim();
        } catch (Exception e) {
            return "桌面端";
        }
    }

    /** 提取桌面 deviceSid（sid 参数），作为绑定列表的唯一键。 */
    public static String extractDeviceSid(String raw) {
        try {
            String sid = Uri.parse(raw).getQueryParameter("sid");
            return sid == null || sid.trim().isEmpty() ? null : sid.trim();
        } catch (Exception e) {
            return null;
        }
    }

    /** 提取桌面机器标识（mid 参数）；重置配对后 sid/hash 轮换但 mid 不变，用于识别同一台桌面。 */
    public static String extractDeviceMid(String raw) {
        try {
            String mid = Uri.parse(raw).getQueryParameter("mid");
            return mid == null || mid.trim().isEmpty() ? null : mid.trim();
        } catch (Exception e) {
            return null;
        }
    }
}
