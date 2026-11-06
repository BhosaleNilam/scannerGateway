package com.cafeguard.scanner;

import com.fazecast.jSerialComm.SerialPort;
import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Reads barcode payloads from:
 * <ul>
 *   <li>{@code keyboard} — global HID hook (best for Mac Mini auto-start)</li>
 *   <li>{@code stdin} — USB HID into the focused Terminal window</li>
 *   <li>{@code serial} — USB-COM / RS-232 via jSerialComm</li>
 * </ul>
 */
public final class BarcodeReader implements AutoCloseable {

    private static final Logger log =
            LoggerFactory.getLogger(BarcodeReader.class);

    private final GatewayConfig config;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;
    private SerialPort serialPort;
    private NativeKeyListener keyListener;
    private final StringBuilder keyBuffer = new StringBuilder();

    public BarcodeReader(GatewayConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    public void start(Consumer<String> onScan) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        String mode = config.scannerMode;
        if ("keyboard".equals(mode)) {
            startKeyboardHook(onScan);
            return;
        }
        worker = Thread.ofVirtual().name("barcode-reader").start(() -> {
            try {
                if ("serial".equals(mode)) {
                    readSerial(onScan);
                } else {
                    readStream(System.in, "stdin/HID", onScan);
                }
            } catch (Exception ex) {
                if (running.get()) {
                    log.error("Barcode reader stopped: {}", ex.toString());
                }
            } finally {
                running.set(false);
            }
        });
        log.info("Barcode reader started (mode={})", mode);
    }

    private void startKeyboardHook(Consumer<String> onScan) {
        // Quiet jnativehook's own logger.
        java.util.logging.Logger hookLog =
                java.util.logging.Logger.getLogger(
                        GlobalScreen.class.getPackage().getName()
                );
        hookLog.setLevel(Level.WARNING);
        hookLog.setUseParentHandlers(false);

        keyListener = new NativeKeyListener() {
            @Override
            public void nativeKeyPressed(NativeKeyEvent event) {
                if (!running.get()) {
                    return;
                }
                int code = event.getKeyCode();
                if (code == NativeKeyEvent.VC_ENTER) {
                    flushBuffer(onScan);
                    return;
                }
                char ch = event.getKeyChar();
                if (ch != NativeKeyEvent.CHAR_UNDEFINED
                        && !Character.isISOControl(ch)) {
                    keyBuffer.append(ch);
                }
            }

            @Override
            public void nativeKeyReleased(NativeKeyEvent event) {
                // unused
            }

            @Override
            public void nativeKeyTyped(NativeKeyEvent event) {
                // unused — we build from pressed + keyChar
            }
        };

        try {
            GlobalScreen.registerNativeHook();
            GlobalScreen.addNativeKeyListener(keyListener);
            log.info(
                    "Barcode reader started (mode=keyboard). "
                            + "On macOS allow Accessibility for Java/Terminal "
                            + "if scans are not detected."
            );
        } catch (NativeHookException ex) {
            running.set(false);
            throw new IllegalStateException(
                    "Could not register global keyboard hook. "
                            + "On macOS: System Settings → Privacy & Security "
                            + "→ Accessibility → enable Java / Terminal. "
                            + "Cause: " + ex.getMessage(),
                    ex
            );
        }
    }

    private void flushBuffer(Consumer<String> onScan) {
        String payload;
        synchronized (keyBuffer) {
            payload = keyBuffer.toString().trim();
            keyBuffer.setLength(0);
        }
        if (payload.isEmpty()) {
            return;
        }
        // Ignore short accidental keystrokes; barcodes/URLs are longer.
        if (payload.length() < 4) {
            return;
        }
        log.info("Scan detected ({} chars)", payload.length());
        onScan.accept(payload);
    }

    private void readSerial(Consumer<String> onScan) throws Exception {
        if (config.serialPort == null || config.serialPort.isBlank()) {
            throw new IllegalStateException(
                    "scanner.serialPort is required when scanner.mode=serial"
            );
        }
        serialPort = SerialPort.getCommPort(config.serialPort);
        serialPort.setComPortParameters(
                config.baudRate,
                8,
                SerialPort.ONE_STOP_BIT,
                SerialPort.NO_PARITY
        );
        serialPort.setComPortTimeouts(
                SerialPort.TIMEOUT_READ_SEMI_BLOCKING,
                250,
                0
        );
        if (!serialPort.openPort()) {
            throw new IllegalStateException(
                    "Could not open serial port: " + config.serialPort
            );
        }
        log.info(
                "Listening on serial {} @ {} baud",
                config.serialPort,
                config.baudRate
        );
        try (InputStream in = serialPort.getInputStream()) {
            readStream(in, config.serialPort, onScan);
        }
    }

    private void readStream(
            InputStream in,
            String label,
            Consumer<String> onScan
    ) throws Exception {
        log.info("Listening for scans on {}", label);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8)
        )) {
            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                String payload = line.trim();
                if (payload.isEmpty()) {
                    continue;
                }
                log.info("Scan detected ({} chars)", payload.length());
                onScan.accept(payload);
            }
        }
    }

    @Override
    public void close() {
        running.set(false);
        if (keyListener != null) {
            try {
                GlobalScreen.removeNativeKeyListener(keyListener);
                GlobalScreen.unregisterNativeHook();
            } catch (Exception ex) {
                log.warn("Keyboard hook shutdown: {}", ex.toString());
            }
            keyListener = null;
        }
        if (serialPort != null && serialPort.isOpen()) {
            serialPort.closePort();
        }
        if (worker != null) {
            worker.interrupt();
        }
    }
}
