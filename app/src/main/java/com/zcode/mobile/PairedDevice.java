package com.zcode.mobile;

import android.net.Uri;

/** 一台已绑定的桌面端：凭据来自其「Web 远程控制」二维码（sid + hash）。 */
public final class PairedDevice {
    /** 桌面在官方云的 deviceSid，作为唯一键；桌面重置配对后 sid 变化。 */
    public final String id;
    /** 完整远控页地址（含 sid/hash 查询参数）。 */
    public final String url;
    /** 二维码 name 参数（桌面名称），缺省「桌面端」。 */
    public final String name;
    /** 二维码 mid 参数（桌面机器标识）。重置配对后 sid/hash 都会轮换但 mid 不变，
     * 用于识别「同一台桌面重新配对」，替换掉旧 sid 的失效条目。旧版数据可能为 null。 */
    public final String mid;
    public final long boundAt;

    public PairedDevice(String id, String url, String name, long boundAt) {
        this(id, url, name, boundAt, extractMid(url));
    }

    public PairedDevice(String id, String url, String name, long boundAt, String mid) {
        this.id = id;
        this.url = url;
        this.name = name;
        this.mid = mid;
        this.boundAt = boundAt;
    }

    private static String extractMid(String url) {
        try {
            String mid = Uri.parse(url).getQueryParameter("mid");
            return mid == null || mid.trim().isEmpty() ? null : mid.trim();
        } catch (Exception e) {
            return null;
        }
    }
}
