package com.liontrader.android;

/** Android-free checks so the privileged prompt boundary can be tested on the JVM. */
public final class AccessPromptPolicy {
    public static final String MESSAGE = "LION_ACCESS_V1";
    private AccessPromptPolicy() {}

    public static boolean allows(String senderUrl, String currentUrl, boolean currentInstance, String message) {
        return currentInstance && MESSAGE.equals(message)
                && NavigationPolicy.isAccessDocument(senderUrl) && NavigationPolicy.isAccessDocument(currentUrl);
    }

    public static boolean isOperation(String operation) {
        return "load".equals(operation) || "save".equals(operation)
                || "pause".equals(operation) || "clear".equals(operation);
    }

    public static boolean isToken(String token) {
        if (token == null || token.length() < 24 || token.length() > 4096) return false;
        for (int index = 0; index < token.length(); index++) {
            char value = token.charAt(index);
            if (value < 0x20 || value > 0x7e) return false;
        }
        return true;
    }
}
