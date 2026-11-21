import tempfile
import unittest
from pathlib import Path

from research_runtime.lock_digest import lock_sha256


class LockDigestTests(unittest.TestCase):
    def test_crlf_and_lf_checkouts_produce_the_same_digest(self):
        with tempfile.TemporaryDirectory() as directory:
            crlf = Path(directory) / "crlf.lock"
            lf = Path(directory) / "lf.lock"
            crlf.write_bytes(b"numpy==2.4.6\r\npandas==3.0.5\r\n")
            lf.write_bytes(b"numpy==2.4.6\npandas==3.0.5\n")

            self.assertEqual(lock_sha256(crlf), lock_sha256(lf))

    def test_current_lock_matches_the_model_identity_constant(self):
        self.assertEqual(
            lock_sha256(),
            "29236beb6079c25aee004819b5b6c2188517872ae06d49ca320b6cb2ae0db66c",
        )


if __name__ == "__main__":
    unittest.main()
