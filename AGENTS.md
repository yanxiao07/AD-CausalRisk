# Project rules for AI coding assistants

## Read before implementing

Read README.md, docs/Integration_Contract.md, docs/Research_Core_Principles.md,
the applicable contracts/*.openapi.json and the README in the assigned module.
Historical context is in docs/Course_SRS_V3_2.md; it is not the current product baseline.
Use docs/Design_Guide.md for role, persistence and page requirements.
Inspect the current branch and existing code before creating or replacing files.

## Research direction and completion

- Follow [Research_Core_Principles](docs/Research_Core_Principles.md):
  multi-source fusion -> individualized causal graph -> causal-guided warning
  model -> individualized explanation. These are research goals, not completed claims.
- Map every new task to one or more of those four links, or identify its bounded
  supporting dependency; state the actual gap, reusable artifacts and acceptance endpoint.
- A graph must affect actual model inputs or computation to count as graph-guided
  prediction. Source links, prior arrows, prediction attribution, GAT, or the four
  software stage names alone do not establish individual causal effects.
- Keep completed fixed experiments closed, including negative findings. New unmet
  research may proceed under a distinct question and existing authorization;
  do not retrain merely to publish source or search for a positive graph result.
- Keep research validation, software checks, model delivery, Java integration and
  clinical evidence separate. Close each stage with evidence and one next task.

## Scope and ownership

- backend owns Java business APIs, database migrations, authorization, tasks,
  AgentClient, persistence, reviews, follow-ups, reports and audit.
- frontend owns routes, sessions, typed API clients, role views, pages and UI tests.
- python_service owns the four-stage workflow and HTTP contract implementation.
- Work within the requested module. Explain cross-module or shared-contract
  changes and obtain agreement before implementing them.
- Do not start additional agents unless the developer explicitly requests them.
- Never overwrite uncommitted work or use a hard reset/force push to resolve it.

## Contract invariants

- Browser clients call Java; Java calls Python on its configured local address.
- Python execution is synchronous and its run store is volatile.
  Java persists tasks and results; do not fabricate live stage progress.
- The default scoring window is 24 months. Other valid windows can return BLOCKED.
- BLOCKED means riskScore=null and riskLevel=UNAVAILABLE; never substitute a score.
- Preserve DEMO-* subject codes, demo-* versions and SYNTHETIC_DEMO result labels.
- Keep Java task-creation idempotency distinct from Python runId replay.
- Validate runId, versions and returned structure. Surface timeouts/conflicts/errors.
- Enforce role and object access in Java. Patient summaries exclude raw scores,
  graph weights and internal traces; hiding a UI element is insufficient.
- Keep source dates, units, missing values and data/model/workflow versions.
- Do not change rules or weaken tests merely to make an integration appear successful.

## Validation and documentation

Python checks from the repository root:

Windows: `.venv/Scripts/python.exe -m unittest discover -s tests -v`

Linux/macOS: `.venv/bin/python -m unittest discover -s tests -v`

Install test dependencies with the same interpreter and requirements-dev.lock.
Each Java/frontend implementation must document and run its own build/test commands.
Update request/response examples and affected tests when a contract changes.
Run relevant checks and fix actual failures. Report commands, results and limits;
never claim a test ran if it did not.

Document team-facing setup and behavior. Exclude chat history, internal handoff
notes, machine-specific audit snapshots and private project background.
Keep credentials, personal records, virtual environments and build caches out of Git.

## Completion

Summarize behavior changes, files, actual tests and remaining work. Prepare a PR
description when asked. Commit, push, create or merge a PR only when the developer
has explicitly requested that action. Do not modify repository membership/settings
or deploy services as part of an ordinary coding task.
