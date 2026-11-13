"""One authorized table prediction and saved-reference explanation in memory.

The host must independently verify artifacts, source evidence and execution
rights before supplying them. This module supplies none of those attestations.
No loaders, readers, fitting, HTTP endpoints or persistence are introduced.
"""
from copy import deepcopy
import hashlib
import json

import numpy as np
import pandas as pd

from . import table_explanation as table
from .group_shapley import METHOD
from .input_admission import _snapshot, _token, validate_snapshot
from .inmemory_inference import bind_existing_model, run_in_memory

SCHEMA = 'research-table-assessment-v1'


class TableAssessmentError(ValueError):
    """Stable code with no source values, identities or exception details."""


def _fail(code):
    raise TableAssessmentError(code) from None


def _digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, allow_nan=False,
        sort_keys=True, separators=(',', ':')).encode('utf-8')).hexdigest()


class TableAssessment:
    """Bind one already verified model instance for same-call assessment.

    The one-shot permit covers direct prediction. The trusted host must also
    explicitly authorize bounded explanation calls. Neither permission is read
    from request JSON. A consumed prediction permit stays consumed if later
    explanation fails; no partial result is returned to the caller.
    """
    def __init__(self, model, *, verified_identity, reference_kind='median'):
        identity = _snapshot(verified_identity, 'REQUEST_FORMAT')
        if identity.get('family') not in ('logistic', 'random_forest', 'xgboost'):
            _fail('TABLE_ASSESSMENT_FAMILY')
        if reference_kind not in ('median', 'mean'):
            _fail('TABLE_ASSESSMENT_REFERENCE')
        self._model = model
        self._identity = identity
        self._reference_kind = reference_kind
        self._predictor = bind_existing_model(model, verified_identity=identity,
            state_token=lambda: table.model_state_identity(model))

    @property
    def model_identity(self):
        """An independent copy for the host's transport binding."""
        return deepcopy(self._identity)

    def run(self, request, *, trusted_context, permit=None,
            explanation_authorization_ref=None, explanation_authorized=False,
            max_coalitions=512):
        if type(max_coalitions) is not int or not 1 <= max_coalitions <= 512:
            _fail('TABLE_ASSESSMENT_BUDGET')
        request = _snapshot(request, 'REQUEST_FORMAT')
        context = _snapshot(trusted_context, 'CONTEXT_FORMAT')
        admission = validate_snapshot(request, trusted_context=context)
        if len(request['records']) != 1:
            _fail('TABLE_ASSESSMENT_SINGLE_RECORD')
        if (admission['status'] == 'METADATA_CONSISTENT' and permit is not None
                and (explanation_authorized is not True or not _token(explanation_authorization_ref))):
            _fail('TABLE_EXPLANATION_PERMISSION_REQUIRED')

        # Keep the existing model lock across prediction, reference selection
        # and explanation. Other calls on this assessment cannot interleave.
        with self._predictor._lock:
            prediction = run_in_memory(request, trusted_context=context,
                predictor=self._predictor, permit=permit)
            result = dict(schemaVersion=SCHEMA, status=prediction['status'],
                prediction=prediction, explanation=None,
                explanationCifCalls=0, clinicalUse=False,
                individualCausalEffectIdentified=False, defaultModelChanged=False)
            if prediction['status'] != 'COMPLETED':
                return result
            try:
                names = list(self._identity['features'])
                row = request['records'][0]
                frame = pd.DataFrame([{'subject_id': row['subjectCode'],
                    **{name: row['features'][name]['value'] for name in names}}],
                    columns=['subject_id', *names]).astype({name: 'float64' for name in names})
                stats = table.training_reference_statistics(self._model, self._identity['specification'])
                reference = stats[self._reference_kind].tolist()
                reference_identity = dict(featureOrder=names,
                    policy='SAVED_TABLE_' + self._reference_kind.upper(), values=reference)
                explanation = table.explain_shapley(self._model, frame,
                    self._identity['specification'], reference_kind=self._reference_kind,
                    max_coalitions=max_coalitions)
                if self._predictor._state() != self._predictor._baseline:
                    _fail('TABLE_ASSESSMENT_MODEL_CHANGED')
                expected = np.array([[prediction['predictions'][0][key]
                    for key in ('ad_cif_24m', 'other_dementia_cif_24m')]])
                explained = np.asarray(explanation['prediction_cif'])
                baseline = np.asarray(explanation['reference_cif'])
                contributions = np.asarray(explanation['contributions'])
                groups = [name for name, _ in table.groups_for(self._identity['specification'])]
                if (explanation['method'] != METHOD or explanation['is_causal_effect'] is not False
                        or explanation['reference_policy'] != 'outer_training_person_month_' + self._reference_kind
                        or list(explanation['group_names']) != groups
                        or explained.shape != (1, 2) or baseline.shape != (1, 2)
                        or contributions.shape != (1, len(groups), 2)
                        or not all(np.isfinite(value).all() for value in (explained, baseline, contributions))
                        or (baseline < 0).any() or (baseline > 1).any()
                        or (baseline.sum(1) > 1 + 1e-12).any()
                        or type(explanation['cif_calls']) is not int
                        or not 1 <= explanation['cif_calls'] <= max_coalitions
                        or not np.allclose(explained, expected, atol=1e-12, rtol=0)
                        or not np.allclose(baseline + contributions.sum(1), expected, atol=1e-12, rtol=0)):
                    _fail('TABLE_ASSESSMENT_RECONSTRUCTION')
                if explanation['model_identity'] != self._predictor._baseline:
                    _fail('TABLE_ASSESSMENT_MODEL_CHANGED')
                result.update(status='COMPLETED',
                    basis=dict(inputSha256=_digest(request), trustedContextSha256=_digest(context),
                        model=deepcopy(self._identity), modelStateToken=self._predictor._baseline,
                        referenceSha256=_digest(reference_identity),
                        referencePolicy=reference_identity['policy'], featureOrder=names,
                        causeOrder=['AD', 'OTHER_DEMENTIA'], predictionWindowMonths=24,
                        predictionPolicy=self._identity['predictionPolicy']),
                    explanation=dict(method=METHOD, groups=groups,
                        referenceCif=baseline.tolist(), predictionCif=explained.tolist(),
                        contributions=contributions.tolist(),
                        reconstructionMaxError=float(np.max(np.abs(baseline + contributions.sum(1) - expected))),
                        missingnessEffectsExplained=False, isCausalEffect=False,
                        limeStatus='NOT_EVALUATED_NO_LOCAL_FIT'),
                    explanationCifCalls=explanation['cif_calls'])
                # Reject a malformed/non-finite result before anything escapes.
                json.dumps(result, allow_nan=False)
                return result
            except TableAssessmentError:
                raise
            except Exception:
                _fail('TABLE_ASSESSMENT_EXPLANATION_FAILED')
