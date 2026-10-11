package com.zcode.mobile;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.widget.ImageView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.util.List;

/**
 * 设备列表页（入口）：展示已绑定的多台桌面端，点击进入会话；
 * 支持扫码/粘贴添加新桌面，删除已绑定桌面（对应桌面端重置配对后的重新绑定）。
 */
public class PairingActivity extends AppCompatActivity {

    private final ActivityResultLauncher<ScanOptions> barcodeLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                String content = result.getContents();
                if (content != null) {
                    handlePairInput(content);
                }
            });

    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted != null && granted) {
                    launchScanner();
                } else {
                    Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_LONG).show();
                }
            });

    private PrefStore prefStore;
    private LinearLayout deviceList;
    private ControlServer controlServer;
    private String controlUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pairing);
        prefStore = new PrefStore(this);

        deviceList = findViewById(R.id.device_list);
        Button scanButton = findViewById(R.id.btn_scan_add);
        Button pasteButton = findViewById(R.id.btn_paste_add);

        scanButton.setOnClickListener(v -> startScanFlow());
        pasteButton.setOnClickListener(v -> showPasteDialog());
        findViewById(R.id.btn_control).setOnClickListener(v -> showControlDialog());

        handleDeepLink(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleDeepLink(intent);
    }

    /** 深链接绑定：浏览器/聊天工具点开「https://官方域/remote/v4?sid=…」直接进入绑定流程。 */
    private void handleDeepLink(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) {
            return;
        }
        Uri data = intent.getData();
        if (data == null) {
            return;
        }
        String normalized = PairUrl.parse(data.toString());
        if (normalized == null) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.invalid_qr_title)
                    .setMessage(R.string.invalid_qr_message)
                    .setPositiveButton(R.string.dialog_retry, (d, w) -> startScanFlow())
                    .setNegativeButton(R.string.dialog_cancel, null)
                    .show();
            return;
        }
        String sid = PairUrl.extractDeviceSid(normalized);
        String mid = PairUrl.extractDeviceMid(normalized);
        if (sid == null) {
            Toast.makeText(this, R.string.invalid_qr_message, Toast.LENGTH_LONG).show();
            return;
        }
        PairedDevice existing = prefStore.getDevice(sid);
        if (existing == null && mid != null) {
            existing = prefStore.getDeviceByMid(mid);
        }
        String name = PairUrl.extractDeviceName(normalized);
        prefStore.addOrReplace(new PairedDevice(sid, normalized, name, System.currentTimeMillis(), mid));
        Toast.makeText(this,
                existing == null
                        ? getString(R.string.paired_toast, name)
                        : getString(R.string.repaired_toast, name),
                Toast.LENGTH_SHORT).show();
        enterRemote(sid);
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderDeviceList();
        CredentialHealth.refresh(this, prefStore.getDevices(), this::renderDeviceStatuses);
    }

    /** 探测完成后只刷新状态点与 meta 行，不重建列表（避免打断点击）。 */
    private void renderDeviceStatuses() {
        for (int i = 0; i < deviceList.getChildCount(); i++) {
            View item = deviceList.getChildAt(i);
            String sid = (String) item.getTag(R.id.device_status_dot);
            if (sid == null) {
                continue;
            }
            applyStatus(item, prefStore.getDevice(sid));
        }
    }

    private void renderDeviceList() {
        deviceList.removeAllViews();
        List<PairedDevice> devices = prefStore.getDevices();
        for (PairedDevice device : devices) {
            View item = LayoutInflater.from(this).inflate(R.layout.item_device, deviceList, false);
            TextView nameView = item.findViewById(R.id.device_name);
            nameView.setText(device.name);
            item.setTag(R.id.device_status_dot, device.id);
            applyStatus(item, device);
            item.setOnClickListener(v -> onDeviceClicked(device));
            View removeButton = item.findViewById(R.id.device_remove);
            removeButton.setOnClickListener(v -> confirmRemove(device));
            deviceList.addView(item);
        }
    }

    private void applyStatus(View item, PairedDevice device) {
        if (device == null) {
            return;
        }
        View dot = item.findViewById(R.id.device_status_dot);
        TextView metaView = item.findViewById(R.id.device_meta);
        String status = CredentialHealth.statusFor(prefStore, device.id);
        int dotColor;
        String statusText;
        switch (status) {
            case CredentialHealth.STATUS_VALID:
                dotColor = 0xFF22C55E;
                statusText = getString(R.string.cred_status_valid);
                break;
            case CredentialHealth.STATUS_STALE:
                dotColor = 0xFFEF4444;
                statusText = getString(R.string.cred_status_stale);
                break;
            default:
                dotColor = 0xFF6B6B72;
                statusText = getString(R.string.cred_status_unknown);
                break;
        }
        dot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(dotColor));
        CharSequence when = android.text.format.DateFormat.format("MM-dd HH:mm", device.boundAt);
        metaView.setText(getString(R.string.device_meta_with_status, when, statusText));
    }

    private void onDeviceClicked(PairedDevice device) {
        if (CredentialHealth.STATUS_STALE.equals(CredentialHealth.statusFor(prefStore, device.id))) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.stale_title)
                    .setMessage(getString(R.string.stale_message, device.name))
                    .setPositiveButton(R.string.btn_rescan, (d, w) -> startScanFlow())
                    .setNegativeButton(R.string.dialog_cancel, null)
                    .setNeutralButton(R.string.btn_open_anyway, (d, w) -> enterRemote(device.id))
                    .show();
            return;
        }
        enterRemote(device.id);
    }


    /** PC 群控台：局域网 Web 服务开关与地址展示（URL + 二维码）。 */
    private void showControlDialog() {
        View form = getLayoutInflater().inflate(R.layout.dialog_control, null);
        TextView status = form.findViewById(R.id.control_status);
        TextView urlView = form.findViewById(R.id.control_url);
        ImageView qrView = form.findViewById(R.id.control_qr);
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.control_title)
                .setView(form)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.control_copy, null)
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
        // 中立按钮不关弹窗：点击复制当前地址。
        dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(b -> {
            CharSequence url = urlView.getText();
            if (url.length() > 0) {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("zcode-control", url));
                Toast.makeText(this, R.string.control_copied, Toast.LENGTH_SHORT).show();
            }
        });
        if (controlServer != null) {
            renderControlRunning(form, status, urlView, qrView, controlUrl);
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setText(R.string.control_stop);
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener(b -> {
                stopControlServer();
                dialog.dismiss();
            });
            return;
        }
        status.setText(R.string.control_off_status);
        urlView.setVisibility(View.GONE);
        qrView.setVisibility(View.GONE);
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setText(R.string.control_start);
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener(b -> {
            controlServer = ControlServer.start(this, prefStore, new ControlServer.Listener() {
                @Override
                public void onServerStarted(String url) {
                    controlUrl = url;
                    runOnUiThread(() -> {
                        if (dialog.isShowing()) {
                            renderControlRunning(form, status, urlView, qrView, url);
                        }
                    });
                }

                @Override
                public void onServerError(String message) {
                    runOnUiThread(() -> {
                        controlServer = null;
                        Toast.makeText(PairingActivity.this,
                                getString(R.string.control_error_toast, message),
                                Toast.LENGTH_SHORT).show();
                    });
                }
            });
            if (controlServer == null) {
                Toast.makeText(this, R.string.control_no_wifi, Toast.LENGTH_LONG).show();
            } else {
                dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setText(R.string.control_stop);
                dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener(nb -> {
                    stopControlServer();
                    dialog.dismiss();
                });
            }
        });
    }

    private void renderControlRunning(View form, TextView status, TextView urlView,
                                       ImageView qrView, String url) {
        if (url == null) {
            status.setText(R.string.control_no_wifi);
            return;
        }
        status.setText(R.string.control_on_status);
        urlView.setText(url);
        urlView.setVisibility(View.VISIBLE);
        qrView.setImageBitmap(generateQr(url));
        qrView.setVisibility(View.VISIBLE);
    }


    private void stopControlServer() {
        if (controlServer != null) {
            controlServer.stop();
            controlServer = null;
        }
        controlUrl = null;
    }

    private static Bitmap generateQr(String content) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 640, 640);
            Bitmap bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.RGB_565);
            for (int x = 0; x < 640; x++) {
                for (int y = 0; y < 640; y++) {
                    bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }

    private void confirmRemove(PairedDevice device) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.unbind_title)
                .setMessage(getString(R.string.unbind_message, device.name))
                .setPositiveButton(R.string.dialog_unbind, (dialog, which) -> {
                    prefStore.remove(device.id);
                    renderDeviceList();
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void startScanFlow() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            launchScanner();
        } else {
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA);
        }
    }

    private void launchScanner() {
        ScanOptions options = new ScanOptions();
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        options.setPrompt(getString(R.string.scan_prompt));
        options.setBeepEnabled(false);
        options.setOrientationLocked(true);
        barcodeLauncher.launch(options);
    }

    private void showPasteDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.paste_hint);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout container = new android.widget.FrameLayout(this);
        container.setPadding(padding, padding / 2, padding, 0);
        container.addView(input);
        new AlertDialog.Builder(this)
                .setTitle(R.string.paste_title)
                .setView(container)
                .setPositiveButton(R.string.dialog_connect, (dialog, which) ->
                        handlePairInput(input.getText().toString()))
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void handlePairInput(String raw) {
        String normalized = PairUrl.parse(raw);
        if (normalized == null) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.invalid_qr_title)
                    .setMessage(R.string.invalid_qr_message)
                    .setPositiveButton(R.string.dialog_retry, (d, w) -> startScanFlow())
                    .setNegativeButton(R.string.dialog_cancel, null)
                    .show();
            return;
        }
        String sid = PairUrl.extractDeviceSid(normalized);
        String mid = PairUrl.extractDeviceMid(normalized);
        if (sid == null) {
            Toast.makeText(this, R.string.invalid_qr_message, Toast.LENGTH_LONG).show();
            return;
        }
        // 同 sid 视为重绑（更新 hash）；桌面重置配对后 sid 会换发，此时按 mid（机器标识）识别同一台桌面。
        PairedDevice existing = prefStore.getDevice(sid);
        if (existing == null && mid != null) {
            existing = prefStore.getDeviceByMid(mid);
        }
        String name = PairUrl.extractDeviceName(normalized);
        prefStore.addOrReplace(new PairedDevice(sid, normalized, name, System.currentTimeMillis(), mid));
        Toast.makeText(this,
                existing == null
                        ? getString(R.string.paired_toast, name)
                        : getString(R.string.repaired_toast, name),
                Toast.LENGTH_SHORT).show();
        enterRemote(sid);
    }

    private void enterRemote(String deviceId) {
        Intent intent = new Intent(this, RemoteActivity.class);
        intent.putExtra(RemoteActivity.EXTRA_DEVICE_ID, deviceId);
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        stopControlServer();
        super.onDestroy();
    }
}
