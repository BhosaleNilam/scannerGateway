package com.cafeguard.scanner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

public final class GatewayConfig {

    public final String baseUrl;
    public final String email;
    public final String password;
    public final String scannerMode;
    public final String serialPort;
    public final int baudRate;
    public final Path dbPath;
    public final long pollIntervalMs;
    public final int maxAttempts;
    public final long retryDelayMs;
    public final long dedupeWindowMs;

    private GatewayConfig(Properties props) {
        this.baseUrl = trimTrailingSlash(
                require(props, "cafeguard.baseUrl")
        );
        this.email = require(props, "cafeguard.email");
        this.password = require(props, "cafeguard.password");
        this.scannerMode = props
                .getProperty("scanner.mode", "stdin")
                .trim()
                .toLowerCase();
        this.serialPort = props.getProperty("scanner.serialPort", "").trim();
        this.baudRate = Integer.parseInt(
                props.getProperty("scanner.baudRate", "9600").trim()
        );
        this.dbPath = Path.of(
                props.getProperty("queue.dbPath", "./data/scans.db").trim()
        ).toAbsolutePath().normalize();
        this.pollIntervalMs = Long.parseLong(
                props.getProperty("sender.pollIntervalMs", "2000").trim()
        );
        this.maxAttempts = Integer.parseInt(
                props.getProperty("sender.maxAttempts", "12").trim()
        );
        this.retryDelayMs = Long.parseLong(
                props.getProperty("sender.retryDelayMs", "5000").trim()
        );
        this.dedupeWindowMs = Long.parseLong(
                props.getProperty("scanner.dedupeWindowMs", "15000").trim()
        );
    }

    public static GatewayConfig load(Path path) throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        }
        return new GatewayConfig(props);
    }

    private static String require(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing required config: " + key
            );
        }
        return value.trim();
    }

    private static String trimTrailingSlash(String url) {
        Objects.requireNonNull(url);
        return url.endsWith("/")
                ? url.substring(0, url.length() - 1)
                : url;
    }
}
