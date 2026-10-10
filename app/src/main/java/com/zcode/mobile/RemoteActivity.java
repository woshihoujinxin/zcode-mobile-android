package com.zcode.mobile;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 远控容器：加载官方手机远控页（remote/v4?sid=...&hash=...）。
 * 会话列表、任务接管、权限审批均由官方页面提供，本容器负责：
 *  - 域名白名单导航拦截（页面内跳转仅允许官方域，外链交给系统浏览器）
 *  - 附件选择（onShowFileChooser）
 *  - 网络错误重试与渲染进程崩溃自愈
 */
public class RemoteActivity extends AppCompatActivity {

    private WebView webView;
    private View errorOverlay;
    private String remoteUrl;

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
        getOnBackPressedDispatcher().addCallback(this, backCallback);

        PrefStore prefStore = new PrefStore(this);
        remoteUrl = prefStore.getRemoteUrl();
        if (remoteUrl == null) {
            finish();
            return;
        }

        webView = findViewById(R.id.web_view);
        errorOverlay = findViewById(R.id.error_overlay);
        Button retryButton = findViewById(R.id.btn_retry);
        retryButton.setOnClickListener(v -> {
            errorOverlay.setVisibility(View.GONE);
            webView.reload();
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
                if (host != null && PairUrl.isAllowedHost(host)
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
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                // 只拦截主文档加载失败；子资源失败由页面自行降级。
                if (request.isForMainFrame()) {
                    showError(error == null ? null : String.valueOf(error.getDescription()));
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                // 渲染进程崩溃后 WebView 已不可用，重建整个容器页恢复现场。
                recreate();
                return true;
            }
        });

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
            webView.restoreState(savedInstanceState);
            if (webView.getUrl() == null) {
                webView.loadUrl(remoteUrl);
            }
        } else {
            webView.loadUrl(remoteUrl);
        }
    }

    private void showError(String description) {
        errorOverlay.setVisibility(View.VISIBLE);
        TextView message = findViewById(R.id.error_message);
        message.setText(getString(R.string.error_load_failed, description == null ? "" : description));
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
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
