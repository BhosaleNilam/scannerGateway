package com.cafeguard.scanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background worker: drain SQLite queue → CafeGuard HTTPS.
 */
public final class SenderWorker implements AutoCloseable {

    private static final Logger log =
            LoggerFactory.getLogger(SenderWorker.class);

    private final GatewayConfig config;
    private final ScanQueue queue;
    private final CafeGuardClient client;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;

    public SenderWorker(
            GatewayConfig config,
            ScanQueue queue,
            CafeGuardClient client
    ) {
        this.config = config;
        this.queue = queue;
        this.client = client;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        worker = Thread.ofVirtual().name("scan-sender").start(() -> {
            log.info("Sender worker started");
            while (running.get()) {
                try {
                    drainOnce();
                    Thread.sleep(config.pollIntervalMs);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception ex) {
                    log.warn("Sender loop error: {}", ex.toString());
                    try {
                        Thread.sleep(config.retryDelayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            log.info("Sender worker stopped");
        });
    }

    private void drainOnce() throws Exception {
        List<ScanQueue.QueuedScan> batch =
                queue.claimPending(5, config.maxAttempts);
        for (ScanQueue.QueuedScan scan : batch) {
            if (!running.get()) {
                return;
            }
            if (scan.attempts() > 0) {
                Thread.sleep(config.retryDelayMs);
            }
            try {
                client.submitBarcode(scan.payload());
                queue.markSent(scan.id());
            } catch (Exception ex) {
                log.warn(
                        "Scan #{} send failed (attempt {}): {}",
                        scan.id(),
                        scan.attempts() + 1,
                        ex.toString()
                );
                queue.markFailed(
                        scan.id(),
                        ex.getMessage(),
                        config.maxAttempts
                );
            }
        }
    }

    @Override
    public void close() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
        }
    }
}
