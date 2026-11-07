#!/usr/bin/env python3
"""
CafeGuard fiscal-scan gateway (no Java).

Reads HID keyboard-wedge barcode lines from stdin and POSTs each
to CafeGuard:

  POST /api/auth/login
  POST /api/receipts/fiscal-scans

Config file (default: ../config/gateway.properties next to this repo):
  cafeguard.baseUrl=https://cafeguard.techbrewery.ca
  cafeguard.email=cafe@example.com
  cafeguard.password=secret

Run:
  python3 scripts/fiscal_scan_gateway.py config/gateway.properties

Keep the Terminal window focused, then scan.
"""

from __future__ import annotations

import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path


def load_properties(path: Path) -> dict[str, str]:
    props: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        props[key.strip()] = value.strip()
    return props


class CafeGuard:
    def __init__(self, base_url: str, email: str, password: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.email = email
        self.password = password
        self.token: str | None = None
        self.token_expires_at = 0.0

    def _request(
        self,
        method: str,
        path: str,
        body: dict | None = None,
        auth: bool = False,
    ) -> dict:
        if auth:
            self._ensure_token()
        data = None if body is None else json.dumps(body).encode("utf-8")
        headers = {
            "Content-Type": "application/json",
            "Accept": "application/json",
            # Cloudflare blocks the default Python urllib user-agent.
            "User-Agent": "CafeGuard-ScannerGateway/1.0",
        }
        if auth and self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        req = urllib.request.Request(
            self.base_url + path,
            data=data,
            headers=headers,
            method=method,
        )
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                raw = resp.read().decode("utf-8") or "{}"
                return json.loads(raw)
        except urllib.error.HTTPError as ex:
            detail = ex.read().decode("utf-8", errors="replace")
            if ex.code == 401 and auth:
                self.token = None
                self.token_expires_at = 0.0
                self._ensure_token()
                headers["Authorization"] = f"Bearer {self.token}"
                req = urllib.request.Request(
                    self.base_url + path,
                    data=data,
                    headers=headers,
                    method=method,
                )
                with urllib.request.urlopen(req, timeout=60) as resp:
                    raw = resp.read().decode("utf-8") or "{}"
                    return json.loads(raw)
            raise RuntimeError(f"HTTP {ex.code} {path}: {detail}") from ex

    def _ensure_token(self) -> None:
        if self.token and time.time() < self.token_expires_at:
            return
        data = self._request(
            "POST",
            "/api/auth/login",
            {"email": self.email, "password": self.password},
            auth=False,
        )
        token = data.get("token")
        if not token:
            raise RuntimeError("Login OK but no token returned")
        self.token = token
        self.token_expires_at = time.time() + 35 * 60
        print(f"Signed in as {self.email}", flush=True)

    def enqueue_fiscal_scan(self, text: str) -> dict:
        return self._request(
            "POST",
            "/api/receipts/fiscal-scans",
            {"text": text},
            auth=True,
        )


def main() -> int:
    config_path = Path(
        sys.argv[1] if len(sys.argv) > 1 else "config/gateway.properties"
    )
    if not config_path.is_file():
        print(f"Missing config: {config_path}", file=sys.stderr)
        return 1

    props = load_properties(config_path)
    base = props.get("cafeguard.baseUrl", "").strip()
    email = props.get("cafeguard.email", "").strip()
    password = props.get("cafeguard.password", "").strip()
    if not base or not email or not password:
        print(
            "config needs cafeguard.baseUrl, email, password",
            file=sys.stderr,
        )
        return 1

    client = CafeGuard(base, email, password)
    print(f"Listening for HID scans → {base}", flush=True)
    print("Keep this Terminal focused, then scan a fiscal QR.", flush=True)

    for line in sys.stdin:
        payload = line.strip()
        if not payload:
            continue
        print(f"Scan detected ({len(payload)} chars)", flush=True)
        try:
            result = client.enqueue_fiscal_scan(payload)
            print(
                f"Queued id={result.get('id')} status={result.get('status')}",
                flush=True,
            )
        except Exception as ex:  # noqa: BLE001 — keep listening
            print(f"Send failed: {ex}", file=sys.stderr, flush=True)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
