package com.zcode.mobile;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.webkit.HttpAuthHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

/**
 * 远控容器：加载官方手机远控页（remote/v4?sid=...&hash=...）。
 * 会话列表、任务接管、权限审批均由官方页面提供，本容器负责：
 *  - 打开前凭据预检（RelayProbe）：凭据被 relay 拒绝时给中文引导 + 重新扫码，而非官方英文 AUTH_FAILED 终态页
 *  - 域名白名单导航拦截（页面内跳转仅允许官方域，外链交给系统浏览器）
 *  - 附件选择（onShowFileChooser）
 *  - 网络错误重试与渲染进程崩溃自愈
 */
public class RemoteActivity extends AppCompatActivity {

    public static final String EXTRA_DEVICE_ID = "device_id";

    /** 当前活跃实例（同进程）。群控台启动时释放手机端会话，避免与 PC 端争夺唯一终端槽位。 */
    private static RemoteActivity activeInstance;

    /** 关闭正在使用的远控页（若在），释放手机端终端连接。 */
    public static void releaseIfActive() {
        RemoteActivity activity = activeInstance;
        if (activity != null && !activity.isFinishing()) {
            activity.finish();
        }
    }

    private WebView webView;
    private View errorOverlay;
    private PrefStore prefStore;
    private String deviceId;
    private String remoteUrl;
    /** 直连模式：用户手动输入的自建 dsh 服务器（无 relay 凭据、可能为 http + 基本认证）。 */
    private boolean directMode;
    /** 预检完成前不加载官方页，避免凭据已失效时闪过一屏英文错误。 */
    private boolean pendingPrecheck = true;

    private ValueCallback<Uri[]> filePathCallback;
    private final ActivityResultLauncher<Intent> fileChooserLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (filePathCallback == null) {
                    return;
                }
                Uri[] results = null;
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    results = WebChromeClient.FileChooserParams.parseResult(
                            result.getResultCode(), result.getData());
                }
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            });

    private final ActivityResultLauncher<ScanOptions> rescanLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                String content = result.getContents();
                if (content == null) {
                    return;
                }
                String normalized = PairUrl.parse(content);
                if (normalized == null) {
                    Toast.makeText(this, R.string.invalid_qr_message, Toast.LENGTH_LONG).show();
                    return;
                }
                String sid = PairUrl.extractDeviceSid(normalized);
                String mid = PairUrl.extractDeviceMid(normalized);
                if (sid == null) {
                    Toast.makeText(this, R.string.invalid_qr_message, Toast.LENGTH_LONG).show();
                    return;
                }
                // 重新扫码即重新绑定（同 sid 或同 mid 视为同一桌面），随后用新凭据重连。
                prefStore.addOrReplace(new PairedDevice(sid, normalized,
                        PairUrl.extractDeviceName(normalized), System.currentTimeMillis(), mid));
                deviceId = sid;
                remoteUrl = normalized;
                Toast.makeText(this, R.string.rescanned_reload, Toast.LENGTH_SHORT).show();
                hideOverlay();
                webView.loadUrl(remoteUrl);
            });

    private final androidx.activity.OnBackPressedCallback backCallback =
            new androidx.activity.OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    if (webView != null && webView.canGoBack()) {
                        webView.goBack();
                    } else {
                        setEnabled(false);
                        getOnBackPressedDispatcher().onBackPressed();
                        setEnabled(true);
                    }
                }
            };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_remote);
        activeInstance = this;
        getOnBackPressedDispatcher().addCallback(this, backCallback);

        prefStore = new PrefStore(this);
        deviceId = getIntent().getStringExtra(EXTRA_DEVICE_ID);
        if (deviceId == null) {
            deviceId = prefStore.getLastDeviceId();
        }
        PairedDevice device = prefStore.getDevice(deviceId);
        if (device == null) {
            finish();
            return;
        }
        prefStore.setLastDeviceId(device.id);
        remoteUrl = device.url;
        directMode = PairUrl.isDirectDeviceId(device.id);

        webView = findViewById(R.id.web_view);
        errorOverlay = findViewById(R.id.error_overlay);
        Button retryButton = findViewById(R.id.btn_retry);
        Button rescanButton = findViewById(R.id.btn_rescan);
        Button openAnywayButton = findViewById(R.id.btn_open_anyway);

        retryButton.setOnClickListener(v -> {
            hideOverlay();
            if (pendingPrecheck && !directMode) {
                startPrecheck();
            } else {
                pendingPrecheck = false;
                webView.reload();
            }
        });
        rescanButton.setOnClickListener(v -> launchScanner());
        openAnywayButton.setOnClickListener(v -> {
            hideOverlay();
            pendingPrecheck = false;
            webView.loadUrl(remoteUrl);
        });

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportMultipleWindows(false);
        // 官方页为纯 https 资源，禁止降级到 http 混合内容。
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webView.setBackgroundColor(0xFF0D0D0D);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String host = uri.getHost();
                if (directMode) {
                    // 直连模式：同站（含子域/端口不一致的静态资源由页面决定）留在 WebView，
                    // 外链交给系统浏览器。
                    String pageHost = Uri.parse(remoteUrl).getHost();
                    if (host != null && (host.equalsIgnoreCase(pageHost)
                            || (pageHost != null && host.endsWith("." + pageHost)))) {
                        return false;
                    }
                } else if (host != null && PairUrl.isAllowedHost(host)
                        && "https".equalsIgnoreCase(uri.getScheme())) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {
                    // 无可处理的应用时静默忽略，停留当前页。
                }
                return true;
            }

            @Override
            public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler,
                                                  String host, String realm) {
                if (!directMode) {
                    handler.cancel();
                    return;
                }
                String savedUser = prefStore.getAuthUser(deviceId);
                String savedPass = prefStore.getAuthPass(deviceId);
                if (savedUser != null && savedPass != null) {
                    handler.proceed(savedUser, savedPass);
                    return;
                }
                promptForAuth(handler, host, savedUser, savedPass);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (directMode) {
                    // 直连模式没有 relay 凭据可探测：主文档加载完成即视为连通（状态灯变绿）。
                    prefStore.setCredStatus(deviceId, CredentialHealth.STATUS_VALID);
                    // 每页就绪后重挂下载拦截（SPA 路由会换文档的情况也已覆盖）。
                    view.evaluateJavascript(DshDownloader.clickInterceptorScript(), null);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                // 只拦截主文档加载失败；子资源失败由页面自行降级。
                if (request.isForMainFrame()) {
                    if (directMode) {
                        // 直连设备无凭据语义：加载失败按「未检测」处理（灰灯），而非凭据失效。
                        prefStore.setCredStatus(deviceId, CredentialHealth.STATUS_UNKNOWN);
                    }
                    if (!pendingPrecheck) {
                        showError(null, getString(R.string.error_load_failed,
                                error == null ? "" : String.valueOf(error.getDescription())));
                    }
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                // 渲染进程崩溃后 WebView 已不可用，重建整个容器页恢复现场。
                recreate();
                return true;
            }
        });

        if (directMode) {
            DshDownloader.installInto(webView, deviceId);
        }

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    fileChooserLauncher.launch(params.createIntent());
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        if (savedInstanceState != null) {
            String savedUrl = prefStore.getDevice(deviceId) == null ? null : prefStore.getDevice(deviceId).url;
            if (savedUrl != null) {
                remoteUrl = savedUrl;
            }
            pendingPrecheck = false;
            webView.restoreState(savedInstanceState);
            if (webView.getUrl() == null) {
                webView.loadUrl(remoteUrl);
            }
        } else if (directMode) {
            // 直连模式没有 relay 凭据可预检，直接加载（认证失败由 onReceivedHttpAuthRequest 接住）。
            pendingPrecheck = false;
            hideOverlay();
            webView.loadUrl(remoteUrl);
        } else {
            startPrecheck();
        }
    }

    /** 直连服务器的 nginx 基本认证：已有保存则直接过，否则弹一次输入框并记住。 */
    private void promptForAuth(HttpAuthHandler handler, String host, String savedUser, String savedPass) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        form.setPadding(pad, pad / 2, pad, 0);
        EditText userInput = new EditText(this);
        userInput.setHint(R.string.auth_username_hint);
        userInput.setInputType(InputType.TYPE_CLASS_TEXT);
        if (!TextUtils.isEmpty(savedUser)) {
            userInput.setText(savedUser);
        }
        EditText passInput = new EditText(this);
        passInput.setHint(R.string.auth_password_hint);
        passInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(userInput);
        form.addView(passInput);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.auth_title, host == null ? "" : host))
                .setMessage(R.string.auth_message)
                .setView(form)
                .setCancelable(false)
                .setPositiveButton(R.string.dialog_connect, (d, w) -> {
                    String user = userInput.getText().toString().trim();
                    String pass = passInput.getText().toString();
                    prefStore.setAuth(deviceId, user, pass);
                    handler.proceed(user, pass);
                })
                .setNegativeButton(R.string.dialog_cancel, (d, w) -> handler.cancel())
                .show();
    }

    /** 打开官方页之前先验证凭据：VALID/UNKNOWN 直接进入；STALE 停下给重新扫码引导。 */
    private void startPrecheck() {
        pendingPrecheck = true;
        final String url = remoteUrl;
        final String sid = PairUrl.extractDeviceSid(url);
        showError(null, getString(R.string.cred_checking));
        final java.util.concurrent.ExecutorService exec = java.util.concurrent.Executors.newSingleThreadExecutor();
        exec.execute(() -> {
            RelayProbe.Result result = CredentialHealth.STATUS_STALE.equals(CredentialHealth.statusFor(prefStore, sid))
                    ? RelayProbe.Result.STALE
                    : RelayProbe.probe(url, 8_000);
            if (result != RelayProbe.Result.STALE) {
                prefStore.setCredStatus(sid, result == RelayProbe.Result.VALID
                        ? CredentialHealth.STATUS_VALID : CredentialHealth.STATUS_UNKNOWN);
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || !pendingPrecheck || !url.equals(remoteUrl)) {
                    return;
                }
                if (result == RelayProbe.Result.STALE) {
                    prefStore.setCredStatus(sid, CredentialHealth.STATUS_STALE);
                    showStale();
                    return;
                }
                pendingPrecheck = false;
                hideOverlay();
                webView.loadUrl(remoteUrl);
            });
            exec.shutdown();
        });
    }

    private void launchScanner() {
        ScanOptions options = new ScanOptions();
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        options.setPrompt(getString(R.string.scan_prompt));
        options.setBeepEnabled(false);
        options.setOrientationLocked(true);
        rescanLauncher.launch(options);
    }

    private void showStale() {
        errorOverlay.setVisibility(View.VISIBLE);
        TextView errorTitle = findViewById(R.id.error_title);
        TextView errorMessage = findViewById(R.id.error_message);
        PairedDevice device = prefStore.getDevice(deviceId);
        errorTitle.setVisibility(View.VISIBLE);
        errorTitle.setText(R.string.stale_title);
        errorMessage.setText(getString(R.string.stale_message,
                device == null ? getString(R.string.hero_title) : device.name));
        findViewById(R.id.btn_retry).setVisibility(View.GONE);
        findViewById(R.id.btn_rescan).setVisibility(View.VISIBLE);
        findViewById(R.id.btn_open_anyway).setVisibility(View.VISIBLE);
    }

    private void showError(String title, String message) {
        errorOverlay.setVisibility(View.VISIBLE);
        TextView errorTitle = findViewById(R.id.error_title);
        TextView errorMessage = findViewById(R.id.error_message);
        if (title == null) {
            errorTitle.setVisibility(View.GONE);
        } else {
            errorTitle.setVisibility(View.VISIBLE);
            errorTitle.setText(title);
        }
        errorMessage.setText(message);
        findViewById(R.id.btn_retry).setVisibility(View.VISIBLE);
        findViewById(R.id.btn_rescan).setVisibility(View.GONE);
        findViewById(R.id.btn_open_anyway).setVisibility(View.GONE);
    }

    private void hideOverlay() {
        errorOverlay.setVisibility(View.GONE);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) {
            webView.saveState(outState);
        }
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        if (activeInstance == this) {
            activeInstance = null;
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
