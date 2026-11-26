package com.cassio.telashare;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

public class MainActivity extends Activity {

    private static final int REQ_CAPTURE = 1001;
    private WebView web;
    private String pendingHostId, pendingQuality;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        // Serve os arquivos de assets/ em https:// (contexto seguro, exigido pelo WebRTC)
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.grant(request.getResources());
            }
        });

        web.addJavascriptInterface(new Bridge(), "AndroidScreen");
        ScreenCaptureService.statusListener = json -> runOnUiThread(() ->
                web.evaluateJavascript("window.__nativeStatus && window.__nativeStatus(" + json + ")", null));

        String url = "https://appassets.androidplatform.net/assets/index.html";
        Intent i = getIntent();
        if (i != null && i.getData() != null && i.getData().getQueryParameter("sala") != null) {
            url += "?sala=" + i.getData().getQueryParameter("sala");
        }
        web.loadUrl(url);
    }

    /** Ponte JavaScript ⇄ Android, usada pelo app.js */
    class Bridge {
        /** Pede permissão de captura; depois o serviço transmite com o id e a qualidade escolhidos. */
        @JavascriptInterface
        public void start(String hostId, String quality) {
            pendingHostId = hostId;
            pendingQuality = quality;
            runOnUiThread(() -> {
                MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
            });
        }

        @JavascriptInterface
        public void stop() {
            stopService(new Intent(MainActivity.this, ScreenCaptureService.class));
        }

        @JavascriptInterface
        public void shareText(String text) {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, text);
            startActivity(Intent.createChooser(send, "Enviar código"));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        boolean ok = resultCode == RESULT_OK && data != null;
        if (ok) {
            Intent svc = new Intent(this, ScreenCaptureService.class);
            svc.putExtra("data", data);
            svc.putExtra("hostId", pendingHostId);
            svc.putExtra("quality", pendingQuality);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc); else startService(svc);
        }
        web.evaluateJavascript("window.__onAndroidCapture && window.__onAndroidCapture(" + ok + ")", null);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else moveTaskToBack(true); // não fecha durante a transmissão
    }

    @Override
    protected void onDestroy() {
        ScreenCaptureService.statusListener = null;
        stopService(new Intent(this, ScreenCaptureService.class));
        super.onDestroy();
    }
}
