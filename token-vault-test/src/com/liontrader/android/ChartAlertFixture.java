package com.liontrader.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Test-APK-only driver: exercises the shipped WebView and captures synthetic-data evidence. */
final class ChartAlertFixture {
    private final Instrumentation instrumentation;
    private Activity activity;
    private WebView web;
    String stage = "fixture-setup";

    ChartAlertFixture(Instrumentation instrumentation) { this.instrumentation = instrumentation; }

    void run() throws Exception {
        Context target = instrumentation.getTargetContext();
        TokenVault vault = new TokenVault(target);
        try {
            check(new JSONObject(vault.execute("{\"op\":\"clear\"}")).getBoolean("ok"));
            Intent intent = new Intent(target, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = instrumentation.startActivitySync(intent);
            instrumentation.runOnMainSync(() -> web = findWebView(activity.getWindow().getDecorView()));
            check(web != null);
            stage = "packaged-javascript-ready";
            awaitValue("typeof startSession==='function' && typeof accessBootstrap!=='undefined' && document.readyState==='complete' && !document.hidden", "true");
            String script;
            try (InputStream input = instrumentation.getContext().getAssets().open("chart-alert-fixture.js");
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];int read;
                while ((read = input.read(buffer)) != -1) bytes.write(buffer, 0, read);
                script = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            }
            stage = "install-offline-fixture";
            evaluate(script);
            exercise("prepare");
            screenshot("07_GRAFICO_PRUEBA.png");
            exercise("framesAndExpiry");
            exercise("alert");
            screenshot("08_ALERTA_PRUEBA.png");
            exercise("dismiss");
        } finally {
            // The test never saves credentials. Clear the real vault anyway before the next smoke.
            boolean cleared = new JSONObject(vault.execute("{\"op\":\"clear\"}")).getBoolean("ok");
            if (activity != null) instrumentation.runOnMainSync(() -> activity.finish());
            if (!cleared) { stage = "fixture-vault-cleanup";check(false); }
        }
    }

    private void exercise(String name) throws Exception {
        stage = "fixture-" + name;
        evaluate("window.__lionFixtureResult='PENDING'; window.__lionChartAlertFixture." + name
                + "().then(()=>window.__lionFixtureResult='PASS',()=>window.__lionFixtureResult='FAIL'); void 0;");
        awaitValue("window.__lionFixtureResult", "\"PASS\"");
    }

    private String evaluate(String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> web.evaluateJavascript(script, value -> { result.set(value);done.countDown(); }));
        check(done.await(10, TimeUnit.SECONDS));
        return result.get();
    }

    private void awaitValue(String script, String expected) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 12000;
        while (SystemClock.elapsedRealtime() < deadline) {
            String result = evaluate(script);
            if (expected.equals(result)) return;
            if ("\"FAIL\"".equals(result)) throw new AssertionError("fixture assertion failed");
            SystemClock.sleep(50);
        }
        throw new AssertionError("fixture timeout");
    }

    private void screenshot(String name) throws Exception {
        stage = "screenshot-" + name;
        CountDownLatch drawn = new CountDownLatch(1);
        instrumentation.runOnMainSync(() -> web.postVisualStateCallback(1, new WebView.VisualStateCallback() {
            @Override public void onComplete(long requestId) { drawn.countDown(); }
        }));
        check(drawn.await(10, TimeUnit.SECONDS));
        instrumentation.waitForIdleSync();
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        check(bitmap != null && bitmap.getWidth() > 0 && bitmap.getHeight() > 0);
        File directory = new File(instrumentation.getTargetContext().getFilesDir(), "lion-ui-fixture");
        check(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { bitmap.recycle(); }
    }

    private static WebView findWebView(View view) {
        if (view instanceof WebView) return (WebView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                WebView found = findWebView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
    private static void check(boolean value) { if (!value) throw new AssertionError("fixture check failed"); }
}
