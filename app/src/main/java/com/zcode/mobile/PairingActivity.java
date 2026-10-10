package com.zcode.mobile;

import android.content.Intent;
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
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderDeviceList();
    }

    private void renderDeviceList() {
        deviceList.removeAllViews();
        List<PairedDevice> devices = prefStore.getDevices();
        for (PairedDevice device : devices) {
            View item = LayoutInflater.from(this).inflate(R.layout.item_device, deviceList, false);
            TextView nameView = item.findViewById(R.id.device_name);
            TextView metaView = item.findViewById(R.id.device_meta);
            nameView.setText(device.name);
            metaView.setText(getString(R.string.device_meta,
                    android.text.format.DateFormat.format("MM-dd HH:mm", device.boundAt)));
            item.setOnClickListener(v -> enterRemote(device.id));
            View removeButton = item.findViewById(R.id.device_remove);
            removeButton.setOnClickListener(v -> confirmRemove(device));
            deviceList.addView(item);
        }
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
        if (sid == null) {
            Toast.makeText(this, R.string.invalid_qr_message, Toast.LENGTH_LONG).show();
            return;
        }
        // 同 sid 视为重绑（更新 hash），否则新增设备。
        PairedDevice existing = prefStore.getDevice(sid);
        String name = PairUrl.extractDeviceName(normalized);
        prefStore.addOrReplace(new PairedDevice(sid, normalized, name, System.currentTimeMillis()));
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
