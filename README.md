# CafeGuard Scanner Gateway

Runs on the **cafe mini-PC** (same network as the USB barcode scanner).  
CafeGuard itself can stay on a **different network** — this agent only makes **outbound HTTPS** calls.

```
USB QR/barcode scanner
        │  USB HID or USB-Serial
        ▼
Java Scanner Gateway  (this project)
  · listen for scans
  · SQLite local queue
  · background HTTPS sender + retries
        │  outbound Internet HTTPS
        ▼
CafeGuard backend  (/api/receipts/parse + /orders)
```

## What it does

1. Detects each barcode/QR scan from the physical scanner
2. Stores the payload in a local SQLite queue (survives offline / restarts)
3. Logs into CafeGuard with the cafe account
4. Calls `POST /api/receipts/parse` then `POST /api/receipts/orders`
5. Retries failed sends automatically

## Build (on any machine with Java 21 + Maven)

```bash
cd scanner-gateway
mvn -q -DskipTests package
```

Fat jar: `target/scanner-gateway-1.0.0.jar`

## Configure on the mini-PC

```bash
mkdir -p /opt/cafeguard-scanner/config /opt/cafeguard-scanner/data
cp target/scanner-gateway-1.0.0.jar /opt/cafeguard-scanner/scanner-gateway.jar
cp config/gateway.properties.example /opt/cafeguard-scanner/config/gateway.properties
```

Edit `gateway.properties`:

```properties
cafeguard.baseUrl=https://cafeguard.techbrewery.ca
cafeguard.email=your-cafe@email.com
cafeguard.password=your-password

# Prefer serial for headless mini-PCs:
scanner.mode=serial
scanner.serialPort=/dev/ttyUSB0
# Windows example: scanner.serialPort=COM3

# Or HID keyboard-wedge (scanner types + Enter into this process):
# scanner.mode=stdin
```

## Run manually

```bash
java -jar scanner-gateway.jar config/gateway.properties
```

Scan a receipt QR — you should see log lines: `Scan detected` → `Queued` → `CafeGuard accepted scan`.

## Linux auto-start (systemd)

```bash
sudo cp deploy/cafeguard-scanner.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now cafeguard-scanner
sudo journalctl -u cafeguard-scanner -f
```

For **HID keyboard-wedge** scanners under systemd, either:

- switch the scanner to **USB serial** mode (recommended), or
- uncomment `StandardInput=tty` / `TTYPath=` in the unit so stdin receives keystrokes

Add the service user to the `dialout` group for serial ports:

```bash
sudo usermod -aG dialout cafeguard
```

## Windows auto-start

1. Install Java 21
2. Copy the jar + `config/gateway.properties`
3. Put a shortcut to `deploy/run-windows.bat` in the Startup folder (`shell:startup`)
4. For serial mode set `scanner.serialPort=COM3` (Device Manager → Ports)

## Notes

- Duplicate scans of the same barcode within `scanner.dedupeWindowMs` are ignored locally
- CafeGuard `409` (already scanned) is treated as success so the queue clears
- Queue file defaults to `./data/scans.db`
