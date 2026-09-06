"""One-time payment gate for the secure-mesh-navigation pipeline.

Implements a single-purchase license model with replay protection:

- A payment receipt is a signed token that can be redeemed exactly ONCE.
- Redemption records the transaction id (and its nonce) in a local JSON
  ledger. Replaying the same token, re-entering the same transaction id, or
  reusing the same nonce is rejected, so a purchase cannot be applied twice.
- A product/dev license can be granted programmatically for development and
  integration testing, and a "forever" license is bound to the product name.

The module is dependency-free (stdlib only) so the pipeline can always load
the gate even when optional audio/LLM dependencies are missing.
"""

import base64
import hashlib
import hmac
import json
import os
import time
import uuid
from pathlib import Path

PAYMENT_SIGNING_KEY_ENV = "PAYMENT_SIGNING_KEY"
PAYMENT_LEDGER_ENV = "PAYMENT_LEDGER_PATH"
DEFAULT_PRODUCT = "secure-mesh-navigation"
TOKEN_PREFIX = "smn1."
LEDGER_VERSION = 1
DEFAULT_LEDGER_PATH = Path(__file__).resolve().parent / ".payment_ledger.json"

MIN_SIGNING_KEY_LEN = 16


def _b64url(data):
    return base64.urlsafe_b64encode(data).decode("ascii").rstrip("=")


def _b64url_decode(text):
    padding = "=" * (-len(text) % 4)
    return base64.urlsafe_b64decode(text + padding)


def _signing_key_from_env():
    key = os.environ.get(PAYMENT_SIGNING_KEY_ENV, "").strip()
    if not key:
        return None
    return key.encode("utf-8")


class PaymentError(Exception):
    """Raised when a payment action is invalid or cannot be performed."""


class OneTimePayment:
    """One-time payment / license gate persisted to a JSON ledger."""

    def __init__(self, ledger_path=None, signing_key=None, product=DEFAULT_PRODUCT):
        self.product = product
        self.ledger_path = Path(ledger_path or DEFAULT_LEDGER_PATH)
        if signing_key is None:
            signing_key = _signing_key_from_env() or self._default_signing_key(self.ledger_path)
        if len(signing_key) < MIN_SIGNING_KEY_LEN:
            raise PaymentError("payment signing key is too short")
        self.signing_key = signing_key
        self._lock = {}
        self._ensure_ledger()

    @classmethod
    def from_env(cls):
        """Build the gate from PAYMENT_* environment variables."""
        return cls(
            ledger_path=os.environ.get(PAYMENT_LEDGER_ENV) or None,
            signing_key=os.environ.get(PAYMENT_SIGNING_KEY_ENV) or None,
        )

    @staticmethod
    def _default_signing_key(ledger_path):
        seed = str(ledger_path).encode("utf-8")
        digest = hashlib.sha256(seed).hexdigest().encode("ascii")
        return digest  # 64 bytes; stable per ledger path, rotated by setting PAYMENT_SIGNING_KEY

    def _ensure_ledger(self):
        if not self.ledger_path.exists():
            self.ledger_path.parent.mkdir(parents=True, exist_ok=True)
            self._write_ledger(self._new_ledger())

    def _new_ledger(self):
        return {
            "version": LEDGER_VERSION,
            "product": self.product,
            "redeemed_tokens": [],
            "redeemed_transactions": [],
            "redeemed_nonces": [],
            "license": None,
        }

    def _read_ledger(self):
        if not self.ledger_path.exists():
            return self._new_ledger()
        try:
            with self.ledger_path.open("r", encoding="utf-8") as fh:
                ledger = json.load(fh)
        except (OSError, json.JSONDecodeError):
            ledger = self._new_ledger()
        ledger.setdefault("version", LEDGER_VERSION)
        ledger.setdefault("product", self.product)
        ledger.setdefault("redeemed_tokens", [])
        ledger.setdefault("redeemed_transactions", [])
        ledger.setdefault("redeemed_nonces", [])
        ledger.setdefault("license", None)
        return ledger

    def _write_ledger(self, ledger):
        tmp = self.ledger_path.with_suffix(".tmp")
        with tmp.open("w", encoding="utf-8") as fh:
            json.dump(ledger, fh, indent=2, sort_keys=True)
        try:
            os.chmod(tmp, 0o600)
        except OSError:
            pass
        tmp.replace(self.ledger_path)

    def _sign(self, claims):
        payload = _b64url(json.dumps(claims, sort_keys=True, separators=(",", ":")).encode("utf-8"))
        mac = hmac.new(self.signing_key, payload.encode("ascii"), hashlib.sha256).hexdigest()
        return TOKEN_PREFIX + payload + "." + mac

    def _verify(self, token):
        if not token.startswith(TOKEN_PREFIX):
            raise PaymentError("invalid token format")
        body = token[len(TOKEN_PREFIX):]
        if "." not in body:
            raise PaymentError("invalid token structure")
        payload_b64, mac = body.split(".", 1)
        expected = hmac.new(self.signing_key, payload_b64.encode("ascii"), hashlib.sha256).hexdigest()
        if not hmac.compare_digest(expected, mac):
            raise PaymentError("invalid token signature")
        try:
            claims = json.loads(_b64url_decode(payload_b64).decode("utf-8"))
        except Exception as exc:
            raise PaymentError(f"unparseable token claims: {exc}")
        return claims

    # ---- Receipts and tokens ----

    def issue_receipt(self, transaction_id=None, amount=None, currency="USD", tier="pro"):
        """Create a signed one-time payment receipt.

        Normally a merchant backend issues this token after a successful
        payment. In this module it is a self-issue helper for testing and
        offline purchases; redemption marks the license active exactly once.
        """
        receipt = {
            "product": self.product,
            "tx": transaction_id or ("tx-" + uuid.uuid4().hex[:12]),
            "nonce": uuid.uuid4().hex,
            "tier": tier,
            "amount": amount,
            "currency": currency,
            "issued": round(time.time(), 3),
        }
        return self._sign(receipt)

    def redeem(self, token):
        """Redeem a one-time payment token. Returns a status dict.

        Raises PaymentError when the token is already spent, belongs to
        another product, or fails validation - enforcing the one-time rule.
        """
        claims = self._verify(token)
        if claims.get("product") != self.product:
            raise PaymentError(
                f"token product '{claims.get('product')}' does not match '{self.product}'"
            )
        ledger = self._read_ledger()
        tx = str(claims.get("tx", ""))
        nonce = str(claims.get("nonce", ""))
        if token in ledger["redeemed_tokens"]:
            raise PaymentError("token already redeemed (one-time payment)")
        if tx and tx in ledger["redeemed_transactions"]:
            raise PaymentError("transaction already applied (one-time payment)")
        if nonce and nonce in ledger["redeemed_nonces"]:
            raise PaymentError("nonce already redeemed (one-time payment)")
        ledger["redeemed_tokens"].append(token)
        if tx:
            ledger["redeemed_transactions"].append(tx)
        if nonce:
            ledger["redeemed_nonces"].append(nonce)
        ledger["license"] = {
            "product": self.product,
            "tx": tx,
            "tier": claims.get("tier", "pro"),
            "amount": claims.get("amount"),
            "currency": claims.get("currency", "USD"),
            "redeemed_at": round(time.time(), 3),
            "issued": claims.get("issued"),
            "forever": True,
            "source": "one_time_payment",
        }
        self._write_ledger(ledger)
        return self.status()

    def grant_dev_license(self, label="dev"):
        """One-time dev/product license grant (idempotent)."""
        if self.is_licensed():
            return self.status()
        ledger = self._read_ledger()
        ledger["license"] = {
            "product": self.product,
            "tx": "dev-" + uuid.uuid4().hex[:12],
            "tier": "pro",
            "amount": 0,
            "currency": "USD",
            "redeemed_at": round(time.time(), 3),
            "issued": round(time.time(), 3),
            "forever": True,
            "source": "dev_license",
            "label": label,
        }
        self._write_ledger(ledger)
        return self.status()

    def clear_ledger(self):
        """Reset the local ledger (tests / re-issue after refund)."""
        self._write_ledger(self._new_ledger())

    # ---- Queries ----

    def is_licensed(self):
        ledger = self._read_ledger()
        license = ledger.get("license")
        return bool(license) and license.get("product") == self.product

    def status(self):
        ledger = self._read_ledger()
        return {
            "product": self.product,
            "licensed": self.is_licensed(),
            "license": ledger.get("license"),
            "ledger_path": str(self.ledger_path),
            "tokens_redeemed": len(ledger.get("redeemed_tokens", [])),
            "tx_redeemed": len(ledger.get("redeemed_transactions", [])),
            "one_time_enforced": True,
        }

    def require_license(self, enforce=True, grace=True):
        """Startup gate. Returns True when the pipeline may run.

        With enforce=True an unlicensed run raises PaymentError. With grace=True
        (and enforce off) unlicensed runs are allowed but flagged as unlicensed.
        """
        if self.is_licensed():
            return True
        if enforce:
            raise PaymentError(
                f"license required for '{self.product}': redeem a one-time payment token "
                "(--payment-token) or grant a dev license in tests"
            )
        return not grace and False


def payment_status_from_env():
    """JSON-able status for dev_server / health checks (never raises)."""
    try:
        gate = OneTimePayment.from_env()
        return {"payment": gate.status()}
    except Exception as exc:
        return {"payment": {"error": repr(exc)}}


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="one-time payment gate CLI")
    parser.add_argument("command", choices=["status", "issue", "redeem", "grant", "reset"])
    parser.add_argument("token", nargs="?", default="", help="Token to redeem")
    parser.add_argument("--tx", default=None, help="Transaction id for issued receipt")
    args = parser.parse_args()
    gate = OneTimePayment.from_env()
    if args.command == "status":
        print(json.dumps(gate.status(), indent=2))
    elif args.command == "issue":
        print(gate.issue_receipt(transaction_id=args.tx))
    elif args.command == "redeem":
        try:
            print(json.dumps(gate.redeem(args.token), indent=2))
        except PaymentError as exc:
            print(json.dumps({"error": str(exc)}, indent=2))
            raise SystemExit(1) from exc
    elif args.command == "grant":
        print(json.dumps(gate.grant_dev_license(), indent=2))
    else:
        gate.clear_ledger()
        print(json.dumps(gate.status(), indent=2))