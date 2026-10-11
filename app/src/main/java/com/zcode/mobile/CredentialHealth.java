package com.zcode.mobile;

/**
 * 凭据健康度状态常量与读写门面。
 *
 * 状态由 RemoteActivity 打开设备前的一次性预检（RelayProbe）写入 PrefStore；
 * 刻意不提供任何后台/周期探测：probe 以 terminal 角色连 relay，而同一桌面
 * 只允许一个活跃终端，任何自动探测都会把正在使用的手机/PC 会话踢下线。
 */
public final class CredentialHealth {

    public static final String STATUS_UNKNOWN = "unknown";
    public static final String STATUS_VALID = "valid";
    public static final String STATUS_STALE = "stale";

    private CredentialHealth() {
    }

    /** 主线程/工作线程均可读；无记录时返回 STATUS_UNKNOWN。 */
    public static String statusFor(PrefStore store, String sid) {
        return store.getCredStatus(sid);
    }

    public static long probedAtFor(PrefStore store, String sid) {
        return store.getCredProbedAt(sid);
    }
}
