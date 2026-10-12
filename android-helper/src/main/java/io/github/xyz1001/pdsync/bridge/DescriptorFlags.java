package io.github.xyz1001.pdsync.bridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Strict, bounded parser for Linux /proc/self/fdinfo flags on API 26 through 29. */
final class DescriptorFlags {
    private DescriptorFlags() { }

    static int read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[512];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count > 4096 - output.size()) throw invalid();
            output.write(buffer, 0, count);
        }
        String value = null;
        for (String line : new String(output.toByteArray(), StandardCharsets.US_ASCII).split("\n", -1)) {
            if (!line.startsWith("flags:")) continue;
            if (value != null) throw invalid();
            value = line.substring("flags:".length());
        }
        if (value == null || !value.matches("[ \t]*[0-7]{1,11}[ \t]*")) throw invalid();
        long flags = Long.parseLong(value.trim(), 8);
        if (flags > 0xffffffffL) throw invalid();
        return (int) flags;
    }

    private static IOException invalid() {
        return new IOException("Cannot verify archive descriptor access mode");
    }
}
