package com.example.elink.config;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.PointerByReference;
import java.util.Arrays;
import java.util.List;

/**
 * Wraps a byte array with the Windows data protection API (DPAPI), which ties the wrapped data to the
 * current Windows account: another user or another machine cannot unwrap it.
 *
 * <p>The JNA interfaces live in nested interfaces on purpose. Loading {@code crypt32} fails on other
 * platforms, and a failed class initialisation is remembered by the JVM, so the failure has to stay
 * contained. {@link #isAvailable()} reports the outcome once and the caller falls back to storing the
 * master key unwrapped.
 */
final class WindowsKeyProtector {
    private static final String DESCRIPTION = "elink account tokens";

    private static Boolean available;

    private WindowsKeyProtector() {}

    static synchronized boolean isAvailable() {
        if (available == null) {
            boolean usable;
            try {
                usable =
                        System.getProperty("os.name", "").toLowerCase().contains("win")
                                && Crypt32.INSTANCE != null;
            } catch (Throwable t) {
                usable = false;
            }
            available = usable;
        }
        return available;
    }

    static byte[] protect(final byte[] data) {
        final DataBlob input = new DataBlob(data);
        final DataBlob output = new DataBlob();
        input.write();
        output.write();

        if (!Crypt32.INSTANCE.CryptProtectData(input, new WString(DESCRIPTION), null, null, null, 0, output)) {
            throw new IllegalStateException("CryptProtectData failed with error " + Native.getLastError());
        }
        return readAndFree(output);
    }

    static byte[] unprotect(final byte[] data) {
        final DataBlob input = new DataBlob(data);
        final DataBlob output = new DataBlob();
        input.write();
        output.write();

        if (!Crypt32.INSTANCE.CryptUnprotectData(input, null, null, null, null, 0, output)) {
            throw new IllegalStateException("CryptUnprotectData failed with error " + Native.getLastError());
        }
        return readAndFree(output);
    }

    private static byte[] readAndFree(final DataBlob output) {
        output.read();
        try {
            return output.pbData.getByteArray(0, output.cbData);
        } finally {
            Kernel32.INSTANCE.LocalFree(output.pbData);
        }
    }

    /** Mirrors the Win32 {@code DATA_BLOB} structure. */
    public static class DataBlob extends Structure {
        public int cbData;
        public Pointer pbData;

        public DataBlob() {
            super();
        }

        public DataBlob(final byte[] data) {
            cbData = data.length;
            pbData = new Memory(Math.max(1, data.length));
            pbData.write(0, data, 0, data.length);
        }

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("cbData", "pbData");
        }
    }

    private interface Crypt32 extends Library {
        Crypt32 INSTANCE = Native.load("crypt32", Crypt32.class);

        boolean CryptProtectData(
                DataBlob dataIn,
                WString description,
                DataBlob entropy,
                Pointer reserved,
                Pointer prompt,
                int flags,
                DataBlob dataOut);

        boolean CryptUnprotectData(
                DataBlob dataIn,
                PointerByReference description,
                DataBlob entropy,
                Pointer reserved,
                Pointer prompt,
                int flags,
                DataBlob dataOut);
    }

    private interface Kernel32 extends Library {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);

        Pointer LocalFree(Pointer handle);
    }
}
