package com.zcode.mobile;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

/**
 * 入口页：扫码绑定桌面端 / 进入已绑定的会话。
 *
 * 桌面端入口：ZCode 桌面版 → 工作区 →「Web 远程控制」，用本 App 扫描其二维码。
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

    private TextView statusTitle;
    private TextView statusDetail;
    private Button primaryButton;
    private Button secondaryButton;
    private TextView unbindButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pairing);
        prefStore = new PrefStore(this);

        statusTitle = findViewById(R.id.status_title);
        statusDetail = findViewById(R.id.status_detail);
        primaryButton = findViewById(R.id.btn_primary);
        secondaryButton = findViewById(R.id.btn_secondary);
        unbindButton = findViewById(R.id.btn_unbind);

        primaryButton.setOnClickListener(v -> {
            if (prefStore.isBound()) {
                enterRemote();
            } else {
                startScanFlow();
            }
        });
        secondaryButton.setOnClickListener(v -> showPasteDialog());
        unbindButton.setOnClickListener(v -> confirmUnbind());
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderState();
    }

    private void renderState() {
        if (prefStore.isBound()) {
            statusTitle.setText(R.string.status_bound_title);
            String detail = getString(R.string.status_bound_detail, prefStore.getDeviceName());
            statusDetail.setText(detail);
            primaryButton.setText(R.string.btn_enter_session);
            secondaryButton.setText(R.string.btn_scan_new);
            unbindButton.setVisibility(View.VISIBLE);
        } else {
            statusTitle.setText(R.string.status_unbound_title);
            statusDetail.setText(R.string.status_unbound_detail);
            primaryButton.setText(R.string.btn_scan_connect);
            secondaryButton.setText(R.string.btn_paste_link);
            unbindButton.setVisibility(View.GONE);
        }
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
        prefStore.save(normalized, PairUrl.extractDeviceName(normalized));
        Toast.makeText(this, R.string.paired_toast, Toast.LENGTH_SHORT).show();
        enterRemote();
    }

    private void confirmUnbind() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.unbind_title)
                .setMessage(getString(R.string.unbind_message, prefStore.getDeviceName()))
                .setPositiveButton(R.string.dialog_unbind, (dialog, which) -> {
                    prefStore.clear();
                    renderState();
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void enterRemote() {
        startActivity(new Intent(this, RemoteActivity.class));
    }
}
