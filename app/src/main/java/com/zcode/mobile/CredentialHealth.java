package com.zcode.mobile;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 凭据健康度：按设备调度 RelayProbe，并把结果持久化到 SharedPreferences 供
 * 设备列表、远控页预检与群控台 /api/devices 共同消费。
 *
 * 节流：单线程执行器串行探测；同一设备 30s 内不重复探测；同 sid 的排队请求去重。
 */
public final class CredentialHealth {

    public static final String STATUS_UNKNOWN = "unknown";
    public static final String STATUS_VALID = "valid";
    public static final String STATUS_STALE = "stale";

    private static final long THROTTLE_MS = 30_000;
    private static final int PROBE_TIMEOUT_MS = 8_000;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "zcode-cred-probe");
        t.setDaemon(true);
        return t;
    });
    private static final Set<String> QUEUED = new HashSet<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private CredentialHealth() {
    }

    public interface Listener {
        void onCredentialStatusChanged();
    }

    /**
     * 为过期设备异步补探测；返回 true 表示有新探测排队。
     * 非阻塞；结果经 Listener 回调（主线程）。listener 可为 null。
     */
    public static boolean refresh(Context context, List<PairedDevice> devices, Listener listener) {
        PrefStore store = new PrefStore(context.getApplicationContext());
        long now = System.currentTimeMillis();
        boolean queuedAny = false;
        for (PairedDevice device : devices) {
            long probedAt = store.getCredProbedAt(device.id);
            if (now - probedAt < THROTTLE_MS) {
                continue;
            }
            final String sid = device.id;
            final String url = device.url;
            synchronized (QUEUED) {
                if (!QUEUED.add(sid)) {
                    continue;
                }
            }
            queuedAny = true;
            EXECUTOR.execute(() -> {
                try {
                    RelayProbe.Result result = RelayProbe.probe(url, PROBE_TIMEOUT_MS);
                    persist(store, sid, result);
                } catch (Throwable ignored) {
                    // 探测异常保持原状态；下次节流窗口后会再试。
                } finally {
                    synchronized (QUEUED) {
                        QUEUED.remove(sid);
                    }
                }
                if (listener != null) {
                    MAIN.post(listener::onCredentialStatusChanged);
                }
            });
        }
        return queuedAny;
    }

    private static void persist(PrefStore store, String sid, RelayProbe.Result result) {
        switch (result) {
            case VALID:
                store.setCredStatus(sid, STATUS_VALID);
                break;
            case STALE:
                store.setCredStatus(sid, STATUS_STALE);
                break;
            default:
                store.setCredStatus(sid, STATUS_UNKNOWN);
                break;
        }
    }

    /** 主线程/工作线程均可读；无记录时返回 STATUS_UNKNOWN。 */
    public static String statusFor(PrefStore store, String sid) {
        return store.getCredStatus(sid);
    }

    public static long probedAtFor(PrefStore store, String sid) {
        return store.getCredProbedAt(sid);
    }
}
