package com.byd.dashcast.util;

import java.nio.charset.StandardCharsets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Creates stable per-installation package markers without exposing a global dictionary hash. */
public final class PackagePseudonymizer {

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final int MARKER_BYTES = 8;

    private PackagePseudonymizer() {}

    /** Returns the first 64 bits of HMAC-SHA-256 as lower-case hexadecimal. */
    public static String marker(byte[] installationKey, String packageName) {
        if (installationKey == null || installationKey.length < 16) {
            throw new IllegalArgumentException("installationKey must contain at least 128 bits");
        }
        if (packageName == null) throw new IllegalArgumentException("packageName required");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(installationKey, "HmacSHA256"));
            byte[] digest = mac.doFinal(packageName.getBytes(StandardCharsets.UTF_8));
            char[] marker = new char[MARKER_BYTES * 2];
            for (int i = 0; i < MARKER_BYTES; i++) {
                int value = digest[i] & 0xff;
                marker[i * 2] = HEX[value >>> 4];
                marker[i * 2 + 1] = HEX[value & 0x0f];
            }
            return new String(marker);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
