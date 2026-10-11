package com.zcode.mobile;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Base64;

/**
 * 直连模式的下载桥。
 *
 * 页面里的下载是 blob:/data: 链接上的 a[download]（预览工具栏、图片灯箱），
 * WebView 的 DownloadListener 拿到 blob: 无法交给 DownloadManager；注入脚本在
 * 捕获阶段拦截这类点击，fetch 出字节、base64 过桥，由这里落盘到公共下载目录
 * （API 29+ 走 MediaStore，26-28 写应用外部下载目录并提示路径）。
 * http(s) 附件下载仍走 DownloadListener + DownloadManager（带基本认证头）。
 */
public class DshDownloader {

    /** 注入脚本的桥对象名（window.DshDownloader）。 */
    public static final String JS_BRIDGE_NAME = "DshDownloader";

    private final Context context;
    private final String deviceId;

    public DshDownloader(Context context, String deviceId) {
        this.context = context;
        this.deviceId = deviceId;
    }

    /** 供注入脚本调用：保存 base64 编码的文件字节到下载目录。 */
    @JavascriptInterface
    public void save(String name, String mime, String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64 == null ? "" : base64);
            String safeName = sanitize(name);
            String saved = saveToDownloads(safeName, mime == null || mime.isEmpty()
                    ? "application/octet-stream" : mime, bytes);
            toast(context.getString(R.string.download_done, saved));
        } catch (Exception e) {
            toast(context.getString(R.string.download_failed, String.valueOf(e)));
        }
    }

    /** DownloadManager 下载 http(s) 附件时附带直连服务器的基本认证头。 */
    public void attachAuth(DownloadManager.Request request) {
        PrefStore store = new PrefStore(context);
        String user = store.getAuthUser(deviceId);
        String pass = store.getAuthPass(deviceId);
        if (user != null && pass != null) {
            String token = Base64.getEncoder().encodeToString((user + ":" + pass).getBytes());
            request.addRequestHeader("Authorization", "Basic " + token);
        }
    }

    private String saveToDownloads(String name, String mime, byte[] bytes) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(android.provider.MediaStore.Downloads.MIME_TYPE, mime);
            android.net.Uri uri = context.getContentResolver()
                    .insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IllegalStateException("MediaStore insert failed");
            }
            try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                out.write(bytes);
            }
            return name;
        }
        File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) {
            dir = context.getFilesDir();
        }
        File target = new File(dir, name);
        File targetDir = target.getParentFile();
        if (targetDir != null && !targetDir.exists() && !targetDir.mkdirs()) {
            throw new IllegalStateException("cannot mkdir " + targetDir);
        }
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(bytes);
        }
        return target.getAbsolutePath();
    }

    /** 下载通知用的上下文（主线程创建 Toast 由 WebView 线程切换处理）。 */
    private void toast(String message) {
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        handler.post(() -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
    }

    private static String sanitize(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "download-" + System.currentTimeMillis();
        }
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    /** 注入页面的脚本：捕获 a[download] 的 blob:/data: 点击并过桥。 */
    public static String clickInterceptorScript() {
        return "(function(){if(window.__dshDownloadPatched)return;window.__dshDownloadPatched=true;"
            + "document.addEventListener('click',function(e){"
            + "var t=e.target;while(t&&t.nodeType===1&&t.tagName!=='A')t=t.parentNode;"
            + "if(!t||t.nodeType!==1)return;var dl=t.getAttribute('download');if(dl===null)return;"
            + "var href=t.getAttribute('href')||'';"
            + "if(href.indexOf('blob:')!==0&&href.indexOf('data:')!==0)return;"
            + "e.preventDefault();e.stopPropagation();"
            + "fetch(href).then(function(r){return r.blob()}).then(function(b){"
            + "var fr=new FileReader();fr.onload=function(){"
            + "var s=String(fr.result);var comma=s.indexOf(',');"
            + "window." + JS_BRIDGE_NAME + ".save(dl,b.type||'application/octet-stream',comma<0?'':s.slice(comma+1));"
            + "};fr.readAsDataURL(b);}).catch(function(){});},true);})();";
    }

    /** 直连模式注入下载桥 + 拦截脚本的完整动作。 */
    public static void installInto(WebView webView, String deviceId) {
        webView.addJavascriptInterface(new DshDownloader(webView.getContext(), deviceId), JS_BRIDGE_NAME);
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                Uri uri = Uri.parse(url);
                if ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) {
                    DownloadManager.Request request = new DownloadManager.Request(uri);
                    String name = guessFileName(url, contentDisposition, mimeType);
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    if (mimeType != null && !mimeType.isEmpty()) {
                        request.setMimeType(mimeType);
                    }
                    new DshDownloader(webView.getContext(), deviceId).attachAuth(request);
                    DownloadManager manager = (DownloadManager) webView.getContext()
                            .getSystemService(Context.DOWNLOAD_SERVICE);
                    if (manager != null) {
                        manager.enqueue(request);
                        Toast.makeText(webView.getContext(),
                                webView.getContext().getString(R.string.download_started, name),
                                Toast.LENGTH_SHORT).show();
                    }
                }
            } catch (Exception ignored) {
                // 无法处理的下载（blob 等）已由注入脚本拦截，这里静默。
            }
        });
    }

    private static String guessFileName(String url, String contentDisposition, String mimeType) {
        if (contentDisposition != null) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("filename\\*?=\"?([^\";]+)\"?").matcher(contentDisposition);
            if (matcher.find()) {
                return sanitize(matcher.group(1));
            }
        }
        String path = Uri.parse(url).getLastPathSegment();
        if (path != null && !path.isEmpty() && !path.contains("blob")) {
            return sanitize(path);
        }
        return "download-" + System.currentTimeMillis();
    }
}
