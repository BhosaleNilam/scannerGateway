package com.cafeguard.scanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * CafeGuard Scanner Gateway — runs on the cafe mini-PC.
 *
 * Flow: USB barcode → SQLite queue → HTTPS CafeGuard backend.
 */
public final class GatewayApp {

    private static final Logger log =
            LoggerFactory.getLogger(GatewayApp.class);

    public static void main(String[] args) throws Exception {
        Path configPath = Path.of(
                args.length > 0 ? args[0] : "config/gateway.properties"
        ).toAbsolutePath().normalize();

        log.info("Loading config from {}", configPath);
        GatewayConfig config = GatewayConfig.load(configPath);

        ScanQueue queue = new ScanQueue(config.dbPath, config.dedupeWindowMs);
        CafeGuardClient client = new CafeGuardClient(config);
        SenderWorker sender = new SenderWorker(config, queue, client);
        BarcodeReader reader = new BarcodeReader(config);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down scanner gateway…");
            try {
                reader.close();
            } catch (Exception ignored) {
                // ignore
            }
            try {
                sender.close();
            } catch (Exception ignored) {
                // ignore
            }
            try {
                queue.close();
            } catch (Exception ignored) {
                // ignore
            }
        }));

        sender.start();
        reader.start(payload -> {
            try {
                queue.enqueue(payload);
            } catch (Exception ex) {
                log.error("Failed to queue scan: {}", ex.toString());
            }
        });

        log.info(
                "Scanner gateway running → {}",
                config.baseUrl
        );
        Thread.currentThread().join();
    }

    private GatewayApp() {
    }
}
