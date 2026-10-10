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
}
