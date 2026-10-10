package com.zcode.mobile;

import android.content.Context;
import android.content.SharedPreferences;

/** 配对凭据存储。sid/hash 等价于「打开桌面会话的钥匙」，仅保存在本机私有存储。 */
public final class PrefStore {
    private static final String PREFS = "zcode_mobile_prefs";
    private static final String KEY_URL = "remote_url";
    private static final String KEY_DEVICE_NAME = "device_name";
    private static final String KEY_BOUND_AT = "bound_at";

    private final SharedPreferences prefs;

    public PrefStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isBound() {
        String url = prefs.getString(KEY_URL, null);
        return url != null && !url.trim().isEmpty();
    }

    public String getRemoteUrl() {
        return prefs.getString(KEY_URL, null);
    }

    public String getDeviceName() {
        return prefs.getString(KEY_DEVICE_NAME, "桌面端");
    }

    public void save(String url, String deviceName) {
        prefs.edit()
                .putString(KEY_URL, url)
                .putString(KEY_DEVICE_NAME, deviceName)
                .putLong(KEY_BOUND_AT, System.currentTimeMillis())
                .apply();
    }

    public void clear() {
        prefs.edit().clear().apply();
    }
}
