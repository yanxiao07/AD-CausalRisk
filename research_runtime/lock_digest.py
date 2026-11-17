"""Canonical dependency-lock digest shared by research hosts.

Git checkouts on Windows may materialise ``requirements.lock`` with CRLF while
Linux checkouts and the runtime image keep LF. Those bytes describe the same
dependency set, so the attestation digest must not depend on the checkout
platform. Normalise line endings before hashing.
"""

from __future__ import annotations

from hashlib import sha256
from pathlib import Path

LOCK_PATH = Path(__file__).resolve().parent / "requirements.lock"


def lock_sha256(path: Path | None = None) -> str:
    data = (path or LOCK_PATH).read_bytes().replace(b"\r\n", b"\n")
    return sha256(data).hexdigest()
