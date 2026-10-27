package com.liontrader.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProtection;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

/** Runs in the target process; fixtures are compiled only into the instrumentation APK. */
public final class TokenVaultInstrumentation extends Instrumentation {
    private static final String TOKEN = "native-test-fixture-token-abcdefghijklmnopqrstuvwxyz";
    private Context target;
    private String phase;
    private ChartAlertFixture chartFixture;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        phase = arguments == null ? null : arguments.getString("phase");
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            target = getTargetContext();
            if ("chart-alert".equals(phase)) {
                chartFixture = new ChartAlertFixture(this);
                chartFixture.run();
                result.putString("stream", "LION native chart alert tests: passed\n");
                finish(Activity.RESULT_OK, result);
                return;
            } else if ("persistence-write".equals(phase)) {
                check(ok(vault().execute("{\"op\":\"clear\"}")), "seed clear");
                check(ok(save(vault(), TOKEN)), "seed save");
            } else if ("persistence-read".equals(phase)) {
                JSONObject restored = json(vault().execute("{\"op\":\"load\"}"));
                check(restored.getBoolean("ok") && TOKEN.equals(restored.getString("token")) && restored.getBoolean("autoConnect"), "process persistence");
                check(ok(vault().execute("{\"op\":\"clear\"}")), "persistence cleanup");
            } else {
                testVault();
            }
            result.putString("stream", "LION native vault tests: passed (" + (phase == null ? "full" : phase) + ")\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            // Do not echo exception messages: a failed assertion must never disclose a credential.
            result.putString("stream", chartFixture == null ? "LION native vault tests: failed; stage=" + stage + "\n"
                    : "LION native chart alert tests: failed; stage=" + chartFixture.stage + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private String stage = "setup";
    private void check(boolean condition, String label) {
        stage = label;
        if (!condition) throw new AssertionError(label);
    }
    private TokenVault vault() { return new TokenVault(target); }
    private SharedPreferences prefs() { return target.getSharedPreferences("lion_access_v1", Context.MODE_PRIVATE); }
    private KeyStore keys() throws Exception { KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null); return ks; }
    private String alias() { return target.getPackageName() + ".access.v1"; }
    private static JSONObject json(String value) throws Exception { return new JSONObject(value); }
    private static boolean ok(String value) throws Exception { return json(value).getBoolean("ok"); }
    private static String save(TokenVault vault, String token) throws Exception { return vault.execute(new JSONObject().put("op", "save").put("token", token).toString()); }
    private void rejects(String request, String label) throws Exception {
        String value = vault().execute(request);
        check(!ok(value) && !value.contains(TOKEN), label);
    }

    private void testVault() throws Exception {
        check(ok(vault().execute("{\"op\":\"clear\"}")), "initial clear");
        JSONObject empty = json(vault().execute("{\"op\":\"load\"}"));
        check(empty.getBoolean("ok") && !empty.has("token") && !empty.getBoolean("autoConnect"), "empty vault");
        rejects("not-json", "malformed request");
        rejects("{\"op\":\"export\"}", "closed operations");
        rejects("{\"op\":\"save\",\"token\":42}", "typed token");
        rejects("{\"op\":\"save\",\"token\":\"short\"}", "short token");
        rejects("{\"op\":\"clear\",\"token\":\"unexpected\"}", "unexpected argument");
        check(ok(save(vault(), TOKEN)), "save");
        Map<String, ?> first = prefs().getAll();
        check(first.size() == 3 && !first.toString().contains(TOKEN), "ciphertext-only preferences");
        KeyStore ks = keys();
        check(ks.getKey(alias(), null).getEncoded() == null, "nonexportable key");
        SecretKey key = (SecretKey) ks.getKey(alias(), null);
        KeyInfo info = (KeyInfo) SecretKeyFactory.getInstance("AES", "AndroidKeyStore").getKeySpec(key, KeyInfo.class);
        check(info.getKeySize() == 256, "AES 256 key");
        byte[] firstIv = Base64.decode((String) first.get("iv"), Base64.NO_WRAP);
        check(firstIv.length == 12, "GCM IV length");
        JSONObject loaded = json(vault().execute("{\"op\":\"load\"}"));
        check(loaded.getBoolean("ok") && TOKEN.equals(loaded.getString("token")) && loaded.getBoolean("autoConnect"), "reload new vault instance");
        check(ok(save(vault(), TOKEN)), "second save");
        check(!first.get("iv").equals(prefs().getString("iv", "")) && !first.get("ciphertext").equals(prefs().getString("ciphertext", "")), "random IV and ciphertext");
        check(ok(vault().execute("{\"op\":\"pause\"}")), "pause");
        JSONObject paused = json(vault().execute("{\"op\":\"load\"}"));
        check(paused.getBoolean("ok") && TOKEN.equals(paused.getString("token")) && !paused.getBoolean("autoConnect"), "paused access reused");
        check(ok(save(vault(), TOKEN)), "resume save");
        String cipher = prefs().getString("ciphertext", "");
        byte[] damaged = Base64.decode(cipher, Base64.NO_WRAP); damaged[0] ^= 1;
        check(prefs().edit().putString("ciphertext", Base64.encodeToString(damaged, Base64.NO_WRAP)).commit(), "corrupt fixture");
        rejects("{\"op\":\"load\"}", "GCM corruption fails closed");
        check(ok(save(vault(), TOKEN)), "recover after corruption");
        String iv = prefs().getString("iv", "");
        damaged = Base64.decode(iv, Base64.NO_WRAP); damaged[0] ^= 1;
        check(prefs().edit().putString("iv", Base64.encodeToString(damaged, Base64.NO_WRAP)).commit(), "IV fixture");
        rejects("{\"op\":\"load\"}", "IV tamper fails closed");
        check(ok(save(vault(), TOKEN)), "save before format mutation");
        check(prefs().edit().putInt("format", 2).commit(), "format fixture");
        rejects("{\"op\":\"load\"}", "unknown format fails closed");
        check(ok(save(vault(), TOKEN)), "save before AAD mutation");
        Cipher foreign = Cipher.getInstance("AES/GCM/NoPadding");
        foreign.init(Cipher.ENCRYPT_MODE, key);
        foreign.updateAAD("other-package\nhttps://evil.test\naccess-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] foreignCiphertext = foreign.doFinal(new JSONObject().put("token", TOKEN).put("autoConnect", true).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        check(prefs().edit().putString("iv", Base64.encodeToString(foreign.getIV(), Base64.NO_WRAP))
                .putString("ciphertext", Base64.encodeToString(foreignCiphertext, Base64.NO_WRAP)).commit(), "AAD fixture");
        rejects("{\"op\":\"load\"}", "different package or origin fails closed");
        check(ok(save(vault(), TOKEN)), "save before key removal");
        ks.deleteEntry(alias());
        rejects("{\"op\":\"load\"}", "missing key fails closed");
        check(!ks.containsAlias(alias()), "load must not recreate missing key");
        check(ok(save(vault(), TOKEN)), "explicit replacement after missing key");
        check(ok(vault().execute("{\"op\":\"clear\"}")), "clear");
        check(prefs().getAll().isEmpty() && !ks.containsAlias(alias()), "clear deletes preferences and key");
        check(!json(vault().execute("{\"op\":\"load\"}")).has("token"), "clear removes token");
        testFailedCommit("save");
        testFailedCommit("pause");
        testFailedEncryption("save");
        testFailedEncryption("pause");
        testFailedPauseRead();
    }

    private void testFailedCommit(String operation) throws Exception {
        check(ok(save(vault(), TOKEN)), "seed before failed " + operation);
        // Save the old durable image so we can replay disk state left behind by a failed commit.
        Map<String, ?> durableBefore = prefs().getAll();
        TokenVault failing = new TokenVault(new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                SharedPreferences actual = super.getSharedPreferences(name, mode);
                return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (!"edit".equals(method.getName())) return invoke(actual, method, args);
                    SharedPreferences.Editor editor = actual.edit();
                    return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class}, (editorProxy, editMethod, editArgs) -> {
                        Object value = invoke(editor, editMethod, editArgs);
                        // Real preferences perform memory/disk side effects. Report a failed write
                        // after those effects, including failed best-effort cleanup commits.
                        if ("commit".equals(editMethod.getName())) return false;
                        return value instanceof SharedPreferences.Editor ? editorProxy : value;
                    });
                });
            }
        });
        String response = "save".equals(operation) ? save(failing, TOKEN + "replacement") : failing.execute("{\"op\":\"pause\"}");
        assertFailedMutation(response, durableBefore, operation + " commit");
    }

    private void assertFailedMutation(String response, Map<String, ?> durableBefore, String operation) throws Exception {
        JSONObject failed = json(response);
        check(!failed.getBoolean("ok") && !failed.getBoolean("autoConnect") && !failed.has("token")
                && "storage_unavailable".equals(failed.getString("error")) && !response.contains(TOKEN), "failed " + operation + " response");
        JSONObject current = json(vault().execute("{\"op\":\"load\"}"));
        check(!current.has("token") && !current.optBoolean("autoConnect"), "failed " + operation + " suppresses memory access");
        check(!keys().containsAlias(alias()), "failed " + operation + " deletes key");
        check(prefs().edit().clear().putInt("format", (Integer) durableBefore.get("format"))
                .putString("iv", (String) durableBefore.get("iv"))
                .putString("ciphertext", (String) durableBefore.get("ciphertext")).commit(), "replay old durable " + operation);
        JSONObject replayed = json(vault().execute("{\"op\":\"load\"}"));
        check(!replayed.has("token") && !replayed.optBoolean("autoConnect"), "failed " + operation + " rejects old disk access");
        check(ok(vault().execute("{\"op\":\"clear\"}")), "cleanup failed " + operation);
    }

    private void testFailedEncryption(String operation) throws Exception {
        check(ok(vault().execute("{\"op\":\"clear\"}")), "clear before failed encryption");
        // Import a real DECRYPT-only key and persist a compatible ciphertext. Reading the
        // old token succeeds, but Android Keystore rejects either attempted rewrite.
        byte[] material = new byte[32];
        java.util.Arrays.fill(material, (byte) 0x53);
        SecretKeySpec softwareKey = new SecretKeySpec(material, "AES");
        keys().setEntry(alias(), new KeyStore.SecretKeyEntry(softwareKey),
                new KeyProtection.Builder(KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, softwareKey);
        cipher.updateAAD((target.getPackageName() + "\nhttps://lion-trade-demo.arroyovargass.workers.dev\naccess-v1")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] ciphertext = cipher.doFinal(new JSONObject().put("token", TOKEN).put("autoConnect", true)
                .toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        check(prefs().edit().clear().putInt("format", 1)
                .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .putString("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP)).commit(), "seed decrypt-only access");
        JSONObject before = json(vault().execute("{\"op\":\"load\"}"));
        check(before.getBoolean("ok") && TOKEN.equals(before.getString("token")) && before.getBoolean("autoConnect"), "decrypt-only fixture loads");
        Map<String, ?> durableBefore = prefs().getAll();
        String response = "save".equals(operation) ? save(vault(), TOKEN + "replacement") : vault().execute("{\"op\":\"pause\"}");
        assertFailedMutation(response, durableBefore, operation + " encryption");
    }

    private void testFailedPauseRead() throws Exception {
        check(ok(save(vault(), TOKEN)), "seed before read failure");
        Map<String, ?> durableBefore = prefs().getAll();
        TokenVault failing = new TokenVault(new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                SharedPreferences actual = super.getSharedPreferences(name, mode);
                return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if ("getAll".equals(method.getName())) throw new IllegalStateException("test-only read failure");
                    return invoke(actual, method, args);
                });
            }
        });
        rejects("{\"op\":\"save\",\"token\":\"short\"}", "invalid save preserves access");
        rejects("{\"op\":\"pause\",\"unexpected\":true}", "invalid pause preserves access");
        rejects("not-json", "malformed input preserves access");
        JSONObject loadFailure = json(failing.execute("{\"op\":\"load\"}"));
        check(!loadFailure.getBoolean("ok") && !loadFailure.has("token"), "read-only failure response");
        JSONObject preserved = json(vault().execute("{\"op\":\"load\"}"));
        check(preserved.getBoolean("ok") && TOKEN.equals(preserved.getString("token"))
                && preserved.getBoolean("autoConnect") && keys().containsAlias(alias()), "validation and load-only failures preserve access");
        assertFailedMutation(failing.execute("{\"op\":\"pause\"}"), durableBefore, "pause read");
    }

    private static Object invoke(Object receiver, java.lang.reflect.Method method, Object[] arguments) throws Throwable {
        try { return method.invoke(receiver, arguments); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
