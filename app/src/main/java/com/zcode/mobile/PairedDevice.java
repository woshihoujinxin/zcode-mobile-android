package com.zcode.mobile;

/** 一台已绑定的桌面端：凭据来自其「Web 远程控制」二维码（sid + hash）。 */
public final class PairedDevice {
    /** 桌面在官方云的 deviceSid，作为唯一键；桌面重置配对后 sid 变化，视为新设备。 */
    public final String id;
    /** 完整远控页地址（含 sid/hash 查询参数）。 */
    public final String url;
    /** 二维码 name 参数（桌面名称），缺省「桌面端」。 */
    public final String name;
    public final long boundAt;

    public PairedDevice(String id, String url, String name, long boundAt) {
        this.id = id;
        this.url = url;
        this.name = name;
        this.boundAt = boundAt;
    }
}
