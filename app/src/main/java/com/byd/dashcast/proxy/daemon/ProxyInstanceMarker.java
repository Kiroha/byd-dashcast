package com.byd.dashcast.proxy.daemon;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

/** Atomic ownership check for the recovery nonce published by one proxy daemon process. */
final class ProxyInstanceMarker {
    private ProxyInstanceMarker() {}

    static boolean ensureOwned(File marker, String token) throws IOException {
        byte[] expected = token.getBytes(StandardCharsets.US_ASCII);
        try {
            Files.write(marker.toPath(), expected,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return true;
        } catch (FileAlreadyExistsException existing) {
            return hasExactContents(marker, expected);
        }
    }

    private static boolean hasExactContents(File marker, byte[] expected) throws IOException {
        byte[] actual = new byte[expected.length + 1];
        int count = 0;
        try (FileInputStream input = new FileInputStream(marker)) {
            while (count < actual.length) {
                int read = input.read(actual, count, actual.length - count);
                if (read < 0) break;
                count += read;
            }
            if (count != expected.length || input.read() >= 0) return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (actual[index] != expected[index]) return false;
        }
        return true;
    }
}