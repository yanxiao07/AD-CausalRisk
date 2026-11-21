# Research Dynamic Host Design (T1.1)

## Current deployment contract

The dynamic serving path reports its loaded model catalog at the bearer-protected
`GET /api/research-table/v1/models`. The verified manifest and artifact digests
are returned with each specification and family. Java compares these receipts
against its repository records. Configuration at host startup determines which
models are loaded.

Java now constructs source mappings and evidence for constructed snapshots and
the host validates their existing digests. Controlled snapshots use host-owned
source material. A controlled run requires the separate authorization bound to
the run, archive and digests. The T1.1 account below is retained as historical
design context. See the [current research contract](Research_Table_Agent_Contract.md).

## Decision

Use **plan A: host-owned snapshot bundles and a per-request agent**. The HTTP
host no longer owns a fixed `agents` table. A request's `runId` is resolved
against a read-only directory of frozen bundles; each bundle contains the
request, model identity and trusted admission context that the host itself
verified. Java keeps its existing `snapshots/register` API and does not need a
new public write endpoint.

## RunId lifecycle

1. A controlled snapshot is registered in Java and receives a database id.
2. The operator (or the controlled provisioning tool) builds a bundle with
   `bundle_from_parts`; the bundle's `runId` is carried in the frozen request.
3. The bundle is written atomically (`.tmp` + replace) into the host's
   `--snapshot-dir`.
4. Java submits a request with that same `runId`; the host resolves the bundle
   by exact string match, compares the incoming canonical request digest with
   the frozen `requestSha256`, then rebuilds `ResearchTableAgent` from the
   bundle.

`runId` is a binding label, not authorization. The host never accepts a model
path, feature values, trusted context, source mappings or digest from the
client. A bundle is trusted because it is on the host's filesystem and passed
`verify_bundle`; it is not trusted because the client named its `runId`.

For the retained H2/trained fixture mode the host derives
`run-trained-<digest prefix>` from the frozen request. The literal
`run-trained-demo-seven` appears nowhere in Python source or tests.

## Trusted context and digests

`research_runtime/dynamic_snapshot.py` reuses the existing contract code:

- `requestSha256 = digest(request)` using
  `research_table_protocol.digest` (sorted keys, compact JSON, UTF-8).
- `inputSha256 = digest(admission_core(request, model))`, where
  `admission_core` is field-for-field the Java `admissionCore`.
- `contextSha256 = digest(context)`, where `context` is the full
  `research-input-context-v1` object (`sourceMappings`, `evidence`, model).

`verify_bundle` runs the existing `_context` and `validate_snapshot` checks and
then recomputes all three digests. No new context algorithm is introduced.

**Known Java registration defect (not changed in this task).** Java
`ResearchTableProtocol.registrationContext` stores an empty `sourceMappings`
and `evidence`, while the Python admission context requires one unique source
per feature and self-referencing evidence. Consequently a snapshot created by
`POST /snapshots/register` has a `context_sha256` that no valid Python context
can reproduce, and a Python response binding would fail Java's
`validateResponse`. The host does **not** fake that digest. The controlled
provisioning path registers the snapshot through Java (so audit, subject and
request are checked by Java), then repairs only the stored
`research_table_snapshot.context_sha256` to the digest of the host-verified
context. This is the same class of controlled fixture insert used by
`ResearchTableDemo`/`ResearchTableAssessmentTest`, and is recorded as a
follow-up for Java registration. Snapshot #2 (`run-manual-...`) is structurally
invalid for admission: duplicate `MANUAL-ENTRY` source ids and `PASS/COMPLETE`
on age/education. It is reported, not silently accepted.

## Failure behavior

| Condition | Error / HTTP |
|---|---|
| Malformed or missing `runId` | `RUN_ID_INVALID` / 400 |
| `runId` not in the directory | `RUN_ID_INVALID` / 400 |
| Body digest differs from bundle request | `SNAPSHOT_BINDING_MISMATCH` / 409 |
| Bundle directory unreadable/invalid | `HOST_BINDING_INVALID` / 503 |
| Model artifact/plan/runtime/software mismatch | `HOST_BINDING_INVALID` / 503 |
| Request fields/modelKey/subject/context mismatch | existing agent errors (`MODEL_NOT_BOUND`, `SUBJECT_NOT_BOUND`, `SNAPSHOT_BINDING_MISMATCH`) |

The host verifies model identity against `load_final_model`: manifest SHA,
artifact SHA, training-plan SHA, current runtime-source hash and
`requirements.lock` hash. A bundle whose model identity does not match the
loaded pinned model is rejected before inference.

## Anti-forgery properties

- Bundles are read only from `--snapshot-dir`; no request field can select a
  model file, context file or subject context.
- Duplicate JSON keys, NaN/Infinity, oversized bundles and duplicate `runId`s
  are rejected.
- The incoming request must match the frozen request digest, not merely the
  `runId`.
- Agents are rebuilt per request; the cached `TableAssessment` is keyed by the
  model identity digest and is immutable per identity.
- `validate_response` still receives `expected_context_sha256` from the
  bundle context, so the host checks its own output binding.

## Operation

```text
.venv-research/Scripts/python.exe research_runtime/examples/research_table_http.py \
  --port 16300 --manifest <manifest.json> \
  --trained-manifest C:\controlled-model\Model_Manifest.json \
  --trusted-manifest-sha256 <sha> \
  --snapshot-dir <bundle-dir>
```

Environment: `RESEARCH_TABLE_TEST_TOKEN` (bearer token shared with Java's
research host setting). Java keeps `researchHost.base-url` at
`http://127.0.0.1:16300`. The host writes a startup manifest containing
`mode=dynamic` and the resolved snapshot descriptors; it does not contain model
paths or contexts.

## W1 verification result

The controlled snapshot used for the live check was registered through Java and
received `snapshotId=4`; its run is `run-manual-w1-snapshot3` (subject 203,
`DEMO-SMOKE-252019`, index date `2026-11-15`, retrospective snapshot, seven
features 72/14/26/1.5/0.5/18/4). The stored context digest was repaired to the
host-verified `a287cbdf82736501429be46eefb15a85d2a17ff4c5d0bc27979aa1afb622ad20`;
request and input digests are
`8cf9023627b55ff6ad8a01b65a01f42099fe3a03eb1fe8d3f79f55759871a1f6` and
`9273402bbdc6df8de3eff9a35cb86110636e448be98901046e81622cc1b4aec1`.

Java task 1 returned `COMPLETED` with `adCif24m=0.1933752820629015`,
`otherDementiaCif24m=0.000795670798321653`, one direct CIF call and 64
explanation calls. The `research_table_task` row and a fresh GET both contain
the same values. The full Python suite (`770` tests) passes.

Snapshot #2 remains reported as structurally inadmissible; it is not the
snapshot used for the acceptance result.
