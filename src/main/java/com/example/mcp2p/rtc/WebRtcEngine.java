package com.example.mcp2p.rtc;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.internal.NativeLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process wide WebRTC state.
 *
 * <p>Creating a {@link PeerConnectionFactory} starts WebRTC's native threads and allocates its own
 * resources, so it happens once and only when a session is actually wanted. {@link
 * #loadNativeLibrary()} can be called on its own to find out whether this platform's native library
 * can be extracted and loaded, which the mod logs during startup.
 *
 * <p>The native library is bundled inside the mod jar ({@code META-INF/jars}) and extracted to a
 * temporary file by {@link NativeLoader} — see {@code docs/dependency-bundling.md}.
 */
public final class WebRtcEngine implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcp2p/rtc");
    private static final String LIBRARY_NAME = "webrtc-java";

    private static volatile Boolean nativeLibraryAvailable;

    /**
     * Tries to load the native WebRTC library, remembering the outcome.
     *
     * @return {@code true} when a peer connection can be created on this platform
     */
    public static boolean loadNativeLibrary() {
        Boolean available = nativeLibraryAvailable;
        if (available == null) {
            synchronized (WebRtcEngine.class) {
                available = nativeLibraryAvailable;
                if (available == null) {
                    try {
                        NativeLoader.loadLibrary(LIBRARY_NAME);
                        available = Boolean.TRUE;
                        LOGGER.info("Native WebRTC library loaded");
                    } catch (Throwable t) {
                        // Missing native build for this platform, or the file could not be extracted.
                        available = Boolean.FALSE;
                        LOGGER.warn(
                                "Native WebRTC library is not available on this platform, P2P connections are disabled",
                                t);
                    }
                    nativeLibraryAvailable = available;
                }
            }
        }
        return available;
    }

    private PeerConnectionFactory factory;

    /** @return the shared factory, created on first use */
    public synchronized PeerConnectionFactory factory() {
        if (factory == null) {
            if (!loadNativeLibrary()) {
                throw new IllegalStateException("The native WebRTC library is not available");
            }
            factory = new PeerConnectionFactory();
            LOGGER.info("Created the WebRTC peer connection factory");
        }
        return factory;
    }

    /** @return {@code true} when the shared factory already exists */
    public synchronized boolean isStarted() {
        return factory != null;
    }

    @Override
    public synchronized void close() {
        if (factory != null) {
            factory.dispose();
            factory = null;
            LOGGER.info("Disposed the WebRTC peer connection factory");
        }
    }
}
