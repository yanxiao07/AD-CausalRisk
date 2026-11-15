"""Dynamic snapshot bundle tests; no model artifact or database is needed."""
import json
from contextlib import contextmanager
from pathlib import Path
import unittest

from research_runtime.dynamic_snapshot import (DynamicSnapshotError,
    SnapshotDirectory, admission_core, bundle_from_parts, load_bundle,
    verify_bundle, write_bundle)
from research_runtime.examples.input_admission_constructed import constructed_pair
from research_runtime.research_table_protocol import CONTRACT, digest


RUN_ID = "run-dynamic-0123456789abcdef"
WORKSPACE_TEMP = Path(".tmp") / "dynamic-snapshot-tests"


@contextmanager
def workspace_tempdir():
    WORKSPACE_TEMP.mkdir(parents=True, exist_ok=True)
    for entry in WORKSPACE_TEMP.iterdir():
        if entry.is_file():
            entry.unlink()
    try:
        yield WORKSPACE_TEMP
    finally:
        for entry in WORKSPACE_TEMP.iterdir():
            if entry.is_file():
                entry.unlink()


def parts():
    fixture, context = constructed_pair("seven", "logistic")
    record = fixture["records"][0]
    request = dict(contractVersion=CONTRACT, runId=RUN_ID,
                   modelKey="trained-seven-logistic", subjectCode=record["subjectCode"],
                   dataVersion=fixture["dataVersion"], indexDate=fixture["indexDate"],
                   availabilityPolicy=fixture["availabilityPolicy"],
                   predictionWindowMonths=24, features=record["features"])
    return request, context["model"], context


class BundleTests(unittest.TestCase):
    def test_digests_use_the_existing_contract_computation(self):
        request, model, context = parts()
        bundle = bundle_from_parts(run_id=RUN_ID, request=request, model=model,
                                   context=context, snapshot_id=3, subject_id=203)
        self.assertEqual(bundle["requestSha256"], digest(request))
        self.assertEqual(bundle["inputSha256"], digest(admission_core(request, model)))
        self.assertEqual(bundle["contextSha256"], digest(context))
        self.assertEqual(bundle["dataScope"], "CONSTRUCTED_VALIDATION")
        self.assertEqual(bundle["snapshotId"], 3)

    def test_unknown_run_is_absent_and_new_file_is_picked_up(self):
        with workspace_tempdir() as directory:
            snapshots = SnapshotDirectory(directory)
            self.assertIsNone(snapshots.resolve(RUN_ID))
            request, model, context = parts()
            bundle = bundle_from_parts(run_id=RUN_ID, request=request, model=model,
                                       context=context)
            write_bundle(Path(directory) / "one.json", bundle)
            found = snapshots.resolve(RUN_ID)
            self.assertIsNotNone(found)
            self.assertEqual(found.run_id, RUN_ID)
            self.assertEqual(found.request_sha256, bundle["requestSha256"])

    def test_tampered_request_or_context_is_rejected(self):
        request, model, context = parts()
        bundle = bundle_from_parts(run_id=RUN_ID, request=request, model=model,
                                   context=context)
        changed = json.loads(json.dumps(bundle))
        changed["request"]["features"]["mmse"]["value"] = 27
        with self.assertRaises(DynamicSnapshotError):
            verify_bundle(changed)
        changed = json.loads(json.dumps(bundle))
        changed["context"]["dataVersion"] = "different-version"
        with self.assertRaises(DynamicSnapshotError):
            verify_bundle(changed)

    def test_duplicate_source_ids_fail_context_validation(self):
        request, model, context = parts()
        context["sourceMappings"]["mmse"]["sourceId"] = context["sourceMappings"]["age_years"]["sourceId"]
        with self.assertRaises(DynamicSnapshotError):
            bundle_from_parts(run_id=RUN_ID, request=request, model=model, context=context)

    def test_duplicate_run_ids_in_one_directory_are_rejected(self):
        with workspace_tempdir() as directory:
            request, model, context = parts()
            bundle = bundle_from_parts(run_id=RUN_ID, request=request, model=model,
                                       context=context)
            write_bundle(Path(directory) / "one.json", bundle)
            write_bundle(Path(directory) / "two.json", bundle)
            with self.assertRaises(DynamicSnapshotError):
                SnapshotDirectory(directory)

    def test_invalid_bundle_is_not_written(self):
        with workspace_tempdir() as directory:
            request, model, context = parts()
            bundle = bundle_from_parts(run_id=RUN_ID, request=request, model=model,
                                       context=context)
            bundle["contextSha256"] = "0" * 64
            target = Path(directory) / "bad.json"
            with self.assertRaises(DynamicSnapshotError):
                write_bundle(target, bundle)
            self.assertFalse(target.exists())

    def test_load_round_trip_preserves_scope_and_identity(self):
        with workspace_tempdir() as directory:
            request, model, context = parts()
            bundle = bundle_from_parts(run_id=RUN_ID, request=request, model=model,
                                       context=context, data_scope="CONTROLLED_RESEARCH")
            target = Path(directory) / "one.json"
            write_bundle(target, bundle)
            loaded = load_bundle(target)
            self.assertEqual(loaded.data_scope, "CONTROLLED_RESEARCH")
            self.assertEqual(loaded.model, context["model"])
            self.assertEqual(loaded.context, context)


if __name__ == "__main__":
    unittest.main()
