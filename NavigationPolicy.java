package com.liontrader.android;

import java.net.URI;

public final class NavigationPolicy {
    public static final String ORIGIN = "https://lion-trade-demo.arroyovargass.workers.dev";
    public static final String APP_URL = ORIGIN + "/operate/";
    private static URI parse(String value) {
        try { return new URI(value); } catch (Exception e) { return null; }
    }
    private static boolean secure(URI uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme()) && uri.getRawUserInfo() == null
                && (uri.getPort() == -1 || uri.getPort() == 443);
    }
    public static boolean isWorker(String value) {
        URI uri = parse(value);
        return secure(uri) && "lion-trade-demo.arroyovargass.workers.dev".equalsIgnoreCase(uri.getHost());
    }
    public static boolean isApi(String value) {
        URI uri = parse(value);
        return isWorker(value) && uri.getPath() != null && uri.getPath().startsWith("/api/v1/");
    }
    public static boolean isDocument(String value) {
        URI uri = parse(value);
        return isWorker(value) && ("/operate/".equals(uri.getPath()) || "/operate".equals(uri.getPath()));
    }
    public static boolean isCapital(String value) {
        URI uri = parse(value);
        return secure(uri) && ("capital.com".equalsIgnoreCase(uri.getHost()) || "www.capital.com".equalsIgnoreCase(uri.getHost()));
    }
}
