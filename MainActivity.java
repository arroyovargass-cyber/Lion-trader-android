package com.liontrader.android;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.Toast;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public final class MainActivity extends Activity {
    private WebView web;
    private LinearLayout container;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        container = new LinearLayout(this);
        container.setBackgroundColor(Color.rgb(11,16,25));
        container.setFitsSystemWindows(true);
        setContentView(container);
        openPanel();
    }

    private void openPanel() {
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(11,16,25));
        web.setSaveEnabled(false);
        web.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setSaveFormData(false);
        settings.setGeolocationEnabled(false);
        settings.setSafeBrowsingEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        CookieManager.getInstance().setAcceptCookie(false);
        WebView.setWebContentsDebuggingEnabled(false);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String value = request.getUrl().toString();
                if (NavigationPolicy.isCapital(value)) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(value))); }
                    catch (Exception e) { Toast.makeText(MainActivity.this,"No se pudo abrir Capital.com.",Toast.LENGTH_LONG).show(); }
                    return true;
                }
                return !NavigationPolicy.isDocument(value);
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String value = request.getUrl().toString();
                if (NavigationPolicy.isApi(value) || (request.isForMainFrame() && NavigationPolicy.isDocument(value))) return null;
                return new WebResourceResponse("text/plain","UTF-8",403,"Blocked",java.util.Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));
            }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                container.removeView(view);
                view.destroy();
                web=null;
                Toast.makeText(MainActivity.this,"El panel se reinició. Conecta otra vez con tu token.",Toast.LENGTH_LONG).show();
                openPanel();
                return true;
            }
        });
        container.addView(web,new LinearLayout.LayoutParams(-1,-1));
        try (InputStream input=getAssets().open("operations.html"); ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192];int count;
            while ((count=input.read(buffer))!=-1) output.write(buffer,0,count);
            web.loadDataWithBaseURL(NavigationPolicy.APP_URL,output.toString("UTF-8"),"text/html","UTF-8",null);
        } catch (Exception e) {
            web.loadDataWithBaseURL(NavigationPolicy.APP_URL,"<p>No se pudo abrir Lion Trader. Cierra y vuelve a abrir la aplicación.</p>","text/html","UTF-8",null);
        }
    }

    @Override public void onResume() {
        super.onResume();
        if (web!=null) { web.onResume();web.resumeTimers();web.evaluateJavascript("if(typeof refresh==='function')refresh();",null); }
    }
    @Override public void onPause() {
        if (web!=null) { web.onPause();web.pauseTimers(); }
        super.onPause();
    }
    @Override public void onBackPressed() {
        if(web==null){super.onBackPressed();return;}
        web.evaluateJavascript("(function(){var d=document.getElementById('review');if(d&&d.open){d.close();if(typeof pending!=='undefined')pending=null;return true;}return false;})()",result->{
            if(!"true".equals(result) && !isFinishing()){
                if(web!=null && web.canGoBack())web.goBack();
                else MainActivity.super.onBackPressed();
            }
        });
    }
    @Override public void onDestroy() {
        if(web!=null){container.removeView(web);web.destroy();web=null;}
        super.onDestroy();
    }
}
