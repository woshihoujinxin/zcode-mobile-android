package com.zcode.mobile;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 多桌面绑定存储。列表以 JSON 数组保存在应用私有 SharedPreferences；
 * 单设备时代的旧键（remote_url 等）在首次读取时自动迁移，无缝升级。
 */
public final class PrefStore {
    private static final String PREFS = "zcode_mobile_prefs";
    private static final String KEY_DEVICES = "paired_devices";
    private static final String KEY_LAST_DEVICE = "last_device_id";
    private static final String PREFIX_CRED_STATUS = "cred_status_";
    private static final String PREFIX_CRED_PROBED = "cred_probed_";
    private static final String PREFIX_AUTH_USER = "auth_user_";
    private static final String PREFIX_AUTH_PASS = "auth_pass_";
    // 旧版单设备键，仅用于迁移。
    private static final String LEGACY_KEY_URL = "remote_url";
    private static final String LEGACY_KEY_NAME = "device_name";

    private final SharedPreferences prefs;

    public PrefStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        migrateLegacyIfNeeded();
    }

    private void migrateLegacyIfNeeded() {
        if (prefs.contains(KEY_DEVICES)) {
            return;
        }
        String legacyUrl = prefs.getString(LEGACY_KEY_URL, null);
        if (legacyUrl == null || legacyUrl.trim().isEmpty()) {
            return;
        }
        String sid = PairUrl.extractDeviceSid(legacyUrl);
        if (sid == null) {
            return;
        }
        String name = prefs.getString(LEGACY_KEY_NAME, "桌面端");
        List<PairedDevice> devices = new ArrayList<>();
        devices.add(new PairedDevice(sid, legacyUrl, name, System.currentTimeMillis()));
        saveDevices(devices);
        prefs.edit()
                .remove(LEGACY_KEY_URL)
                .remove(LEGACY_KEY_NAME)
                .remove("bound_at")
                .putString(KEY_LAST_DEVICE, sid)
                .apply();
    }

    public List<PairedDevice> getDevices() {
        List<PairedDevice> devices = new ArrayList<>();
        String raw = prefs.getString(KEY_DEVICES, null);
        if (raw == null) {
            return devices;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                devices.add(new PairedDevice(
                        item.getString("id"),
                        item.getString("url"),
                        item.getString("name"),
                        item.getLong("boundAt"),
                        item.has("mid") ? item.getString("mid") : null));
            }
        } catch (Exception ignored) {
            // 损坏的存储按空列表处理，重扫即可恢复。
        }
        return devices;
    }

    /**
     * 按 id 去重：同 sid 视为同一桌面，重扫更新凭据（桌面重置配对后 hash 会变）。
     * 另外：桌面「重置配对」会换发新 sid 但 mid 不变——按 mid 命中的旧条目一并替换，
     * 避免失效条目残留列表。旧版本条目没有 mid，退化用「二维码携带的桌面名」匹配。
     */
    public void addOrReplace(PairedDevice device) {
        List<PairedDevice> devices = getDevices();
        List<PairedDevice> next = new ArrayList<>();
        boolean hasRealName = device.name != null && !device.name.isEmpty()
                && !"桌面端".equals(device.name);
        for (PairedDevice d : devices) {
            boolean sameSid = d.id.equals(device.id);
            boolean sameMachine = device.mid != null && device.mid.equals(d.mid);
            boolean legacySameName = d.mid == null && hasRealName && device.name.equals(d.name);
            if (!sameSid && !sameMachine && !legacySameName) {
                next.add(d);
                continue;
            }
            // 被替换条目的凭据状态随之作废。
            prefs.edit()
                    .remove(PREFIX_CRED_STATUS + d.id)
                    .remove(PREFIX_CRED_PROBED + d.id)
                    .apply();
        }
        next.add(device);
        saveDevices(next);
        setLastDeviceId(device.id);
    }

    public void remove(String id) {
        List<PairedDevice> next = new ArrayList<>();
        for (PairedDevice d : getDevices()) {
            if (!d.id.equals(id)) {
                next.add(d);
            } else {
                prefs.edit()
                        .remove(PREFIX_CRED_STATUS + d.id)
                        .remove(PREFIX_CRED_PROBED + d.id)
                        .remove(PREFIX_AUTH_USER + d.id)
                        .remove(PREFIX_AUTH_PASS + d.id)
                        .apply();
            }
        }
        saveDevices(next);
        if (id.equals(getLastDeviceId())) {
            prefs.edit().remove(KEY_LAST_DEVICE).apply();
        }
    }

    public PairedDevice getDevice(String id) {
        if (id == null) {
            return null;
        }
        for (PairedDevice d : getDevices()) {
            if (d.id.equals(id)) {
                return d;
            }
        }
        return null;
    }

    /** 按机器标识（mid）查找：桌面重置配对换发新 sid 后仍能认出同一台桌面。 */
    public PairedDevice getDeviceByMid(String mid) {
        if (mid == null) {
            return null;
        }
        for (PairedDevice d : getDevices()) {
            if (mid.equals(d.mid)) {
                return d;
            }
        }
        return null;
    }

    public String getLastDeviceId() {
        return prefs.getString(KEY_LAST_DEVICE, null);
    }

    public void setLastDeviceId(String id) {
        prefs.edit().putString(KEY_LAST_DEVICE, id).apply();
    }

    /** 凭据健康度（CredentialHealth 持久化）。无记录返回 STATUS_UNKNOWN。 */
    public String getCredStatus(String id) {
        if (id == null) {
            return CredentialHealth.STATUS_UNKNOWN;
        }
        return prefs.getString(PREFIX_CRED_STATUS + id, CredentialHealth.STATUS_UNKNOWN);
    }

    public long getCredProbedAt(String id) {
        if (id == null) {
            return 0;
        }
        return prefs.getLong(PREFIX_CRED_PROBED + id, 0);
    }

    public void setCredStatus(String id, String status) {
        if (id == null) {
            return;
        }
        prefs.edit()
                .putString(PREFIX_CRED_STATUS + id, status)
                .putLong(PREFIX_CRED_PROBED + id, System.currentTimeMillis())
                .apply();
    }

    private void saveDevices(List<PairedDevice> devices) {
        JSONArray array = new JSONArray();
        for (PairedDevice d : devices) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", d.id);
                item.put("url", d.url);
                item.put("name", d.name);
                item.put("boundAt", d.boundAt);
                if (d.mid != null) {
                    item.put("mid", d.mid);
                }
                array.put(item);
            } catch (Exception ignored) {
            }
        }
        prefs.edit().putString(KEY_DEVICES, array.toString()).apply();
    }

    /** 直连服务器的基本认证账号（nginx auth_basic）；null 表示尚未保存。 */
    public String getAuthUser(String id) {
        return id == null ? null : prefs.getString(PREFIX_AUTH_USER + id, null);
    }

    /** 直连服务器的基本认证密码；null 表示尚未保存。 */
    public String getAuthPass(String id) {
        return id == null ? null : prefs.getString(PREFIX_AUTH_PASS + id, null);
    }

    public void setAuth(String id, String user, String pass) {
        if (id == null) {
            return;
        }
        prefs.edit()
                .putString(PREFIX_AUTH_USER + id, user)
                .putString(PREFIX_AUTH_PASS + id, pass)
                .apply();
    }

    public void clearAuth(String id) {
        if (id == null) {
            return;
        }
        prefs.edit()
                .remove(PREFIX_AUTH_USER + id)
                .remove(PREFIX_AUTH_PASS + id)
                .apply();
    }
}
