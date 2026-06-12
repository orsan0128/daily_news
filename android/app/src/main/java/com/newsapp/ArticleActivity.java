package com.newsapp;

import android.app.Activity;
import android.graphics.Bitmap;
import android.net.http.SslError;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.util.HashSet;
import java.util.Set;

public class ArticleActivity extends Activity {
    private WebView webView;
    private boolean isOriginalUrlMSN = false;

    // Ad domains to block
    private static final Set<String> AD_DOMAINS = new HashSet<>();
    static {
        AD_DOMAINS.add("googlesyndication.com");
        AD_DOMAINS.add("googleadservices.com");
        AD_DOMAINS.add("doubleclick.net");
        AD_DOMAINS.add("pagead2.googlesyndication.com");
        AD_DOMAINS.add("adservice.google.com");
        AD_DOMAINS.add("ad.ettoday.net");
        AD_DOMAINS.add("ad2.ettoday.net");
        AD_DOMAINS.add("taboola.com");
        AD_DOMAINS.add("outbrain.com");
        AD_DOMAINS.add("adnxs.com");
        AD_DOMAINS.add("criteo.com");
        AD_DOMAINS.add("criteo.net");
        AD_DOMAINS.add("amazon-adsystem.com");
        AD_DOMAINS.add("media.net");
        AD_DOMAINS.add("openx.net");
        AD_DOMAINS.add("pubmatic.com");
        AD_DOMAINS.add("rubiconproject.com");
        AD_DOMAINS.add("casalemedia.com");
        AD_DOMAINS.add("smartadserver.com");
        AD_DOMAINS.add("indexexchange.com");
        AD_DOMAINS.add("yieldmo.com");
        AD_DOMAINS.add("hotjar.com");
        AD_DOMAINS.add("crazyegg.com");
        // 快科技 (MyDrivers) ad domains
        AD_DOMAINS.add("mydrivers.com/ads");
        AD_DOMAINS.add("mydrivers.com/ad/");
        AD_DOMAINS.add("gg.mydrivers.com");
        AD_DOMAINS.add("mdav.mydrivers.com");
        AD_DOMAINS.add("a.mydrivers.com");
    }

    // Ad script patterns in URLs
    private static final String[] AD_SCRIPT_PATTERNS = {
        "googlesyndication", "gpt.js", "prebid", "amazon-adsystem",
        "adnxs.com", "criteo", "taboola", "outbrain",
        "adsbygoogle", "adsense"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.setWebChromeClient(new WebChromeClient());

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // Block navigation away from MSN for MSN articles
                if (isOriginalUrlMSN) {
                    if (url != null && !url.contains("msn.com")) {
                        return true; // Block the redirect
                    }
                }
                return false;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString().toLowerCase();

                // Block ad domains
                for (String domain : AD_DOMAINS) {
                    if (url.contains(domain)) {
                        return new WebResourceResponse("text/plain", "UTF-8",
                                new ByteArrayInputStream(new byte[0]));
                    }
                }

                // Block ad script patterns
                for (String pattern : AD_SCRIPT_PATTERNS) {
                    if (url.contains(pattern)) {
                        return new WebResourceResponse("text/plain", "UTF-8",
                                new ByteArrayInputStream(new byte[0]));
                    }
                }

                return null;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);

                // MSN redirect fix: if page navigated away from MSN, go back immediately
                if (isOriginalUrlMSN && url != null && !url.contains("msn.com")) {
                    view.stopLoading();
                    view.goBack();
                    return;
                }

                // Inject redirect blocker JS for MSN pages
                if (url != null && url.contains("msn.com")) {
                    view.evaluateJavascript(
                        "(function(){" +
                        // Override location.assign and location.replace
                        "var origAssign=window.location.assign.bind(window.location);" +
                        "var origReplace=window.location.replace.bind(window.location);" +
                        "window.location.assign=function(u){" +
                        "  if(u&&u.indexOf('msn.com')===-1){return;}" +
                        "  origAssign(u);" +
                        "};" +
                        "window.location.replace=function(u){" +
                        "  if(u&&u.indexOf('msn.com')===-1){return;}" +
                        "  origReplace(u);" +
                        "};" +
                        "})()", null);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Re-check: if we ended up on a non-MSN page, go back
                if (isOriginalUrlMSN && url != null && !url.contains("msn.com")) {
                    view.goBack();
                    return;
                }
                // Inject ad blocking JS (safe version - no broad CSS selectors)
                view.evaluateJavascript(getSafeAdBlockJS(), null);
                // Clean up again after dynamic ads load
                view.postDelayed(() -> {
                    if (isOriginalUrlMSN && !webView.getUrl().contains("msn.com")) {
                        webView.goBack();
                        return;
                    }
                    view.evaluateJavascript(getSafeAdBlockJS(), null);
                }, 2000);
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }
        });

        String url = getIntent().getStringExtra("url");
        if (url != null) {
            isOriginalUrlMSN = url.contains("msn.com");
            webView.loadUrl(url);
        }
    }

    private String getSafeAdBlockJS() {
        return "(function(){" +
            // 1. Hide known ad-specific elements (safe, specific selectors only)
            "var s=document.createElement('style');" +
            "s.textContent='" +
            ".adsbygoogle," +
            ".ad_frame," +
            ".adsbox," +
            "#google_ads_frame," +
            ".google-ad," +
            ".taboola," +
            ".outbrain," +
            ".ezoic-ad," +
            "[data-google-query-id]," +
            "[id*='google_ads']," +
            ".ETtodatAdBox," +
            ".et_ad," +
            ".ad-slot," +
            ".ad-container," +
            ".ad-wrapper," +
            ".advertisement," +
            ".advertising," +
            ".sponsored-content," +
            ".promoted-content," +
            ".full-page-ad," +
            ".interstitial," +
            ".popup-overlay," +
            ".modal-overlay" +
            "{display:none!important;max-height:0!important;overflow:hidden!important;padding:0!important;margin:0!important}" +
            "';document.head.appendChild(s);" +

            // 2. Remove iframes (most ads)
            "document.querySelectorAll('iframe').forEach(function(f){" +
            "var s=f.src||'';" +
            // Keep YouTube iframes
            "if(s.indexOf('youtube')===-1&&s.indexOf('youtu.be')===-1){" +
            "f.style.display='none';" +
            "}" +
            "});" +

            // 3. Remove fixed/sticky overlays that are likely ad popups
            "document.querySelectorAll('*').forEach(function(el){" +
            "var cs=window.getComputedStyle(el);" +
            "if((cs.position==='fixed'||cs.position==='sticky')&&" +
            "parseInt(cs.height)>100&&parseInt(cs.width)>200){" +
            "el.style.display='none';" +
            "}" +
            "});" +

            // 4. Remove ad data-attributes
            "document.querySelectorAll('[data-ad-unit],[data-ad-slot],[data-google-query-id]').forEach(function(el){" +
            "el.style.display='none';" +
            "});" +

            "})()";
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
