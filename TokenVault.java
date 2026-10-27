package com.liontrader.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Stores credentials only as Keystore-authenticated ciphertext in app-private preferences. */
public final class TokenVault {
    private static final Object LOCK = new Object();
    private static final int FORMAT = 1;
    private static boolean storageFailed;
    private final SharedPreferences preferences;
    private final String alias;
    private final byte[] aad;

    public TokenVault(Context context) {
        Context app = context.getApplicationContext();
        preferences = app.getSharedPreferences("lion_access_v1", Context.MODE_PRIVATE);
        alias = app.getPackageName() + ".access.v1";
        // Storage format is stable across APK updates. Never include versionName/versionCode.
        aad = (app.getPackageName() + "\n" + NavigationPolicy.ORIGIN + "\naccess-v1")
                .getBytes(StandardCharsets.UTF_8);
    }

    public String execute(String request) {
        synchronized (LOCK) {
            boolean validatedMutation = false;
            try {
                if (request == null || request.length() > 32768) return error("invalid_request");
                JSONObject value = new JSONObject(request);
                Object operation = value.opt("op");
                if (!(operation instanceof String) || !AccessPromptPolicy.isOperation((String) operation)) return error("invalid_request");
                String op = (String) operation;
                if ("save".equals(op)) {
                    Object token = value.opt("token");
                    if (value.length() != 2 || !(token instanceof String) || !AccessPromptPolicy.isToken((String) token)) return error("invalid_request");
                    validatedMutation = true;
                    return write((String) token, true);
                }
                if (value.length() != 1) return error("invalid_request");
                if ("clear".equals(op)) return clear();
                validatedMutation = "pause".equals(op);
                JSONObject stored = read();
                if ("pause".equals(op)) {
                    if (stored == null) return success(false).toString();
                    return write(stored.getString("token"), false);
                }
                if (stored == null) return success(false).toString();
                return success(stored.getBoolean("autoConnect")).put("token", stored.getString("token")).toString();
            } catch (Exception unavailable) {
                // Exception text, request JSON and credentials must never reach logs or callers.
                if (validatedMutation) {
                    // Pause can fail during decryption, and either rewrite can fail before
                    // commit. Never leave a previous auto-connecting credential usable.
                    invalidateAccess();
                    return error("storage_unavailable");
                }
                return error("access_unavailable");
            }
        }
    }

    private KeyStore keyStore() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        return store;
    }

    private SecretKey existingKey() throws Exception {
        return (SecretKey) keyStore().getKey(alias, null);
    }

    private SecretKey encryptionKey() throws Exception {
        SecretKey key = existingKey();
        if (key != null) return key;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }

    private JSONObject read() throws Exception {
        if (storageFailed) throw new IllegalStateException();
        if (preferences.getAll().isEmpty()) return null;
        if (preferences.getInt("format", 0) != FORMAT || preferences.getAll().size() != 3) throw new IllegalStateException();
        String encodedIv = preferences.getString("iv", null);
        String encodedCiphertext = preferences.getString("ciphertext", null);
        if (encodedIv == null || encodedIv.length() > 24 || encodedCiphertext == null || encodedCiphertext.length() > 65536) throw new IllegalStateException();
        byte[] iv = Base64.decode(encodedIv, Base64.NO_WRAP);
        byte[] ciphertext = Base64.decode(encodedCiphertext, Base64.NO_WRAP);
        if (iv.length != 12 || ciphertext.length < 16 || ciphertext.length > 32768) throw new IllegalStateException();
        SecretKey key = existingKey();
        if (key == null) throw new IllegalStateException();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        cipher.updateAAD(aad);
        JSONObject plaintext = new JSONObject(new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8));
        if (plaintext.length() != 2 || !(plaintext.opt("token") instanceof String)
                || !AccessPromptPolicy.isToken((String) plaintext.opt("token"))
                || !(plaintext.opt("autoConnect") instanceof Boolean)) throw new IllegalStateException();
        return plaintext;
    }

    private String write(String token, boolean autoConnect) throws Exception {
        JSONObject plaintext = new JSONObject().put("token", token).put("autoConnect", autoConnect);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        // The Android Keystore provider generates a fresh unpredictable IV for every encryption.
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey());
        cipher.updateAAD(aad);
        byte[] ciphertext = cipher.doFinal(plaintext.toString().getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != 12) throw new IllegalStateException();
        boolean committed;
        try {
            committed = preferences.edit().clear().putInt("format", FORMAT)
                    .putString("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
                    .putString("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP)).commit();
        } catch (Exception unavailable) {
            committed = false;
        }
        if (!committed) {
            // SharedPreferences may already expose the new value in memory while retaining
            // the old value on disk. Destroy the key so neither image can restore access.
            invalidateAccess();
            return error("storage_unavailable");
        }
        storageFailed = false;
        return success(autoConnect).toString();
    }

    private String clear() throws Exception {
        if (!invalidateAccess()) return error("storage_unavailable");
        storageFailed = false;
        return success(false).toString();
    }

    private boolean invalidateAccess() {
        // Suppress every instance immediately, even if either cleanup operation also fails.
        storageFailed = true;
        boolean removed = true;
        try { keyStore().deleteEntry(alias); } catch (Exception unavailable) { removed = false; }
        boolean committed;
        try { committed = preferences.edit().clear().commit(); }
        catch (Exception unavailable) { committed = false; }
        return removed && committed;
    }

    private static JSONObject success(boolean autoConnect) throws Exception {
        return new JSONObject().put("ok", true).put("autoConnect", autoConnect);
    }

    private static String error(String code) {
        return "{\"ok\":false,\"autoConnect\":false,\"error\":\"" + code + "\"}";
    }
}
