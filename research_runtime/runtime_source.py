"""Digest of the exact runtime source files that define research assessment.

The host identity must change whenever any of these files changes.  The list is
deliberately explicit: importing a new module does not silently widen the
attested runtime, and a missing file fails closed.
"""
from hashlib import sha256
from pathlib import Path

from .research_table_protocol import digest


SOURCE_FILES = (
    "inference.py",
    "discrete.py",
    "input_admission.py",
    "inmemory_inference.py",
    "table_assessment.py",
    "table_explanation.py",
    "group_shapley.py",
    "research_table_agent.py",
    "research_table_protocol.py",
    "dynamic_snapshot.py",
    "dynamic_host.py",
    "runtime_source.py",
    "examples/research_table_http.py",
    "examples/input_admission_constructed.py",
)


def runtime_source_sha256(root=None):
    """Return a canonical digest of the host source files under ``root``."""
    base = Path(root).resolve() if root is not None else Path(__file__).resolve().parent
    return digest({name: sha256((base / name).read_bytes()).hexdigest()
                   for name in SOURCE_FILES})
