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
}
