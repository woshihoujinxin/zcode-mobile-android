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
                        item.getLong("boundAt")));
            }
        } catch (Exception ignored) {
            // 损坏的存储按空列表处理，重扫即可恢复。
        }
        return devices;
    }

    /** 按 id 去重：同 sid 视为同一桌面，重扫更新凭据（桌面重置配对后 hash 会变）。 */
    public void addOrReplace(PairedDevice device) {
        List<PairedDevice> devices = getDevices();
        List<PairedDevice> next = new ArrayList<>();
        for (PairedDevice d : devices) {
            if (!d.id.equals(device.id)) {
                next.add(d);
            }
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

    public String getLastDeviceId() {
        return prefs.getString(KEY_LAST_DEVICE, null);
    }

    public void setLastDeviceId(String id) {
        prefs.edit().putString(KEY_LAST_DEVICE, id).apply();
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
                array.put(item);
            } catch (Exception ignored) {
            }
        }
        prefs.edit().putString(KEY_DEVICES, array.toString()).apply();
    }
}
