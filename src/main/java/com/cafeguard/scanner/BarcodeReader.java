package com.cafeguard.scanner;

import com.fazecast.jSerialComm.SerialPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Reads barcode payloads from:
 * <ul>
 *   <li>{@code stdin} — USB HID keyboard-wedge (types + Enter)</li>
 *   <li>{@code serial} — USB-COM / RS-232 scanner via jSerialComm</li>
 * </ul>
 */
public final class BarcodeReader implements AutoCloseable {

    private static final Logger log =
            LoggerFactory.getLogger(BarcodeReader.class);

    private final GatewayConfig config;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;
    private SerialPort serialPort;

    public BarcodeReader(GatewayConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    public void start(Consumer<String> onScan) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        worker = Thread.ofVirtual().name("barcode-reader").start(() -> {
            try {
                if ("serial".equals(config.scannerMode)) {
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
        log.info(
                "Barcode reader started (mode={})",
                config.scannerMode
        );
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
        if (serialPort != null && serialPort.isOpen()) {
            serialPort.closePort();
        }
        if (worker != null) {
            worker.interrupt();
        }
    }
}
