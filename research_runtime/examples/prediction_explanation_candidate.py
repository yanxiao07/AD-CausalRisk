"""One constructed prediction/explanation join, NOT a production report API.

Only the existing NumPy Shapley kernel and in-memory dispatcher are used. The
formula, identities, permissions and reference are invented, not fitted models
or verified host evidence. No readers, original CIF, LIME regression or writes.
"""
from copy import deepcopy
import hashlib
import json

import numpy as np

from research_runtime.examples.input_admission_constructed import constructed_pair
from research_runtime.group_shapley import exact_values
from research_runtime.inmemory_inference import (
    BoundPredictor, PREDICTOR_SCHEMA, authorize_once, run_in_memory,
)


def _digest(value):
    return hashlib.sha256(json.dumps(
        value, sort_keys=True, separators=(',', ':'), ensure_ascii=False,
        allow_nan=False).encode('utf-8')).hexdigest()


class _ConstructedFormula:
    """Analytic probabilities on this example's replacement states only."""

    def __init__(self):
        self.parameters = dict(recipe='CONSTRUCTED-age-mmse-bilateral-v1',
                               ad_base=.10, age=.02, mmse=.04,
                               bilateral=.03, interaction=.01,
                               other_base=.04, other_age=.01)
        self.calls = 0

    def token(self):
        # Only a toy parameter/recipe token; no attestation of executing code.
        return _digest(self.parameters)

    def predict(self, values):
        self.calls += 1
        age = (values[:, 0] - 60) / 20
        mmse = (30 - values[:, 2]) / 10
        observed = np.isfinite(values[:, 8:10]).all(axis=1)
        bilateral = np.where(observed, (7000 - values[:, 8:10].sum(1)) / 2000, 0)
        p = self.parameters
        ad = (p['ad_base'] + p['age']*age + p['mmse']*mmse
              + p['bilateral']*bilateral + p['interaction']*age*mmse)
        other = p['other_base'] + p['other_age']*age
        return np.column_stack((ad, other))


def _join_constructed(expected, prediction, explanation):
    """Reject mismatched evidence in this fixed example; not an untrusted API.

    expected is fixed before either computation, outside either returned object.
    Digests check agreement, not authenticity. Real host evidence remains unmet.
    """
    if prediction['basis'] != expected or explanation['basis'] != expected:
        raise ValueError('CONSTRUCTED_BINDING_MISMATCH')
    if (_digest(explanation['referenceValues']) != expected['referenceSha256']
            or _digest(explanation['observedValues']) != expected['observedValuesSha256']):
        raise ValueError('CONSTRUCTED_EXPLANATION_INPUT_REFERENCE_MISMATCH')
    output = prediction['output']
    if (output['status'] != 'COMPLETED' or not output['executionPermissionUsed']
            or output['model'] != expected['model']
            or output['dataVersion'] != expected['dataVersion']
            or output['indexDate'] != expected['indexDate']
            or output['availabilityPolicy'] != expected['availabilityPolicy']
            or output['predictionWindowMonths'] != expected['predictionWindowMonths']):
        raise ValueError('CONSTRUCTED_PREDICTION_CONTEXT_MISMATCH')
    records = output['predictions']
    if (len(records) != 1 or records[0]['subject_id'] != expected['subjectCode']):
        raise ValueError('CONSTRUCTED_SUBJECT_MISMATCH')
    predicted = np.asarray([[records[0]['ad_cif_24m'],
                             records[0]['other_dementia_cif_24m']]], dtype=float)
    values = np.asarray(explanation['prediction_cif'], dtype=float)
    reference = np.asarray(explanation['reference_cif'], dtype=float)
    contributions = np.asarray(explanation['contributions'], dtype=float)
    if (predicted.shape != (1, 2) or values.shape != (1, 2)
            or reference.shape != (1, 2) or contributions.shape != (1, 9, 2)
            or not all(np.isfinite(a).all() for a in
                       (predicted, values, reference, contributions))):
        raise ValueError('CONSTRUCTED_EXPLANATION_VALUES')
    if (not np.allclose(predicted, values, atol=1e-12, rtol=0)
            or (values < 0).any() or (values > 1).any()
            or (reference < 0).any() or (reference > 1).any()
            or (values.sum(1) > 1+1e-12).any()
            or (reference.sum(1) > 1+1e-12).any()):
        raise ValueError('CONSTRUCTED_PREDICTION_EXPLANATION_MISMATCH')
    if (explanation['method'] != 'exact-single-reference-group-shapley-v1'
            or explanation['is_causal_effect'] is not False
            or explanation['reference_policy'] != 'caller_supplied_same_missingness'
            or list(explanation['group_names']) != [g[0] for g in expected['groups']]
            or not np.allclose(reference + contributions.sum(1), values,
                               atol=1e-12, rtol=0)):
        raise ValueError('CONSTRUCTED_EXPLANATION_SEMANTICS')
    return dict(
        schemaVersion='constructed-prediction-explanation-example-v1',
        status='CONSTRUCTED_BINDING_CHECKED', basis=deepcopy(expected),
        prediction=deepcopy(records),
        explanation=dict(method=explanation['method'],
                         referencePolicy=expected['referencePolicy'],
                         referenceCif=reference.tolist(),
                         groups=list(explanation['group_names']),
                         contributions=contributions.tolist(),
                         reconstructionMaxError=float(np.max(np.abs(
                             reference+contributions.sum(1)-values))),
                         lime=None, limeStatus='NOT_EVALUATED_NO_LOCAL_FIT'),
        actualResearchModelEvaluated=False, realHostEvidenceEvaluated=False,
        sourceAuthenticityCertified=False, historicalAvailabilityCertified=False,
        individualCausalEffectIdentified=False, clinicalUse=False,
        defaultModelChanged=False)


def _case(*, missing_mri=False):
    request, context = constructed_pair('multimodal_values', 'logistic')
    names = request['model']['features']
    if missing_mri:
        for name in names[8:]:
            item = request['records'][0]['features'][name]
            item.update(value=None, missingReason='NOT_COLLECTED',
                        measuredDate=None, availableDate=None, dateBasis='UNKNOWN',
                        qc='NOT_APPLICABLE', scanId=None)
            context['evidence'][item['evidenceRef']]['observation'] = deepcopy(item)
    before = _digest(dict(request=request, context=context))
    values = [[request['records'][0]['features'][n]['value'] for n in names]]
    # A deliberately invented reference, NOT saved training statistics.
    reference = deepcopy(values)
    reference[0][0], reference[0][2] = 60, 30
    if not missing_mri:
        reference[0][8:10] = [3500, 3500]
    groups = [[n, [i]] for i, n in enumerate(names[:8])]
    groups.append(['bilateral_hippocampus', [8, 9]])
    formula = _ConstructedFormula()
    expected = dict(
        inputSha256=_digest(request), trustedContextSha256=_digest(context),
        observedValuesSha256=_digest(values),
        model=deepcopy(request['model']), formulaStateToken=formula.token(),
        subjectCode=request['records'][0]['subjectCode'],
        dataVersion=request['dataVersion'], indexDate=request['indexDate'],
        availabilityPolicy=request['availabilityPolicy'],
        predictionWindowMonths=24, predictionPolicy=request['model']['predictionPolicy'],
        causeOrder=['AD', 'OTHER_DEMENTIA'], featureOrder=list(names),
        units=[request['records'][0]['features'][n]['unit'] for n in names],
        referencePolicy='CONSTRUCTED_RAW_SCALE_REFERENCE_SAME_MISSINGNESS',
        referenceValues=reference, referenceSha256=_digest(reference), groups=groups)

    def predict_rows(rows):
        result = formula.predict(np.asarray([[r[n] for n in names] for r in rows], dtype=float))
        return dict(schemaVersion=PREDICTOR_SCHEMA, model=deepcopy(request['model']),
                    predictionWindowMonths=24,
                    predictionPolicy=request['model']['predictionPolicy'],
                    records=[dict(subject_id=r['subject_id'], ad_cif_24m=float(p[0]),
                                  other_dementia_cif_24m=float(p[1]))
                             for r, p in zip(rows, result)])

    predictor = BoundPredictor(request['model'], predict=predict_rows, state_token=formula.token)
    permit = authorize_once(request, trusted_context=context,
                            authorization_ref='CONSTRUCTED-P3-ONLY', authorization_granted=True)
    prediction = dict(basis=deepcopy(expected), output=run_in_memory(
        request, trusted_context=context, predictor=predictor, permit=permit))
    explanation = exact_values(formula.predict, np.asarray(values, dtype=float),
                               np.asarray(reference, dtype=float),
                               [(n, tuple(cols)) for n, cols in groups], max_coalitions=8)
    if formula.token() != expected['formulaStateToken'] or before != _digest(
            dict(request=request, context=context)):
        raise ValueError('CONSTRUCTED_STATE_OR_INPUT_CHANGED')
    explanation['basis'] = deepcopy(expected)
    explanation['observedValues'] = deepcopy(values)
    explanation['referenceValues'] = deepcopy(reference)
    counts = dict(dispatchCallbacks=permit.prediction_calls,
                  shapleyCoalitions=explanation['coalition_evaluations'],
                  analyticCalls=formula.calls, actualModelLoads=0,
                  originalCifCalls=0, riskFits=0, calibrationFits=0, localFits=0)
    return expected, prediction, explanation, counts


def build_example(*, missing_mri=False):
    expected, prediction, explanation, counts = _case(missing_mri=missing_mri)
    result = _join_constructed(expected, prediction, explanation)
    result['executionCounts'] = counts
    result['limitations'] = [
        'All identities, sources, permission and reference are constructed.',
        'The logistic identity is a metadata fixture; this formula is not logistic regression.',
        'Digests bind this example only; P2 real host evidence is NOT_EVALUATED.',
        'Replacement states are not interventions or a conditional population distribution.',
        'Missingness is fixed; zero dummy attribution does not mean no clinical effect.',
        'No HTTP, Java persistence, patient report or real model binding is established.',
    ]
    return result


if __name__ == '__main__':
    print(json.dumps(build_example(), ensure_ascii=False, indent=2, allow_nan=False))
