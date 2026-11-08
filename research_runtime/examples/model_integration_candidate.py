"""Constructed examples of existing APIs, not an HTTP adapter or model runner.

The optional --full output is an example catalogue, not a new request envelope.
All records, evidence, hashes and callback values are artificial. No archive,
clinical source, model, original predict_cif, fit or network is used.
"""
from copy import deepcopy
import argparse
import hashlib
import json

from research_runtime.examples.input_admission_constructed import constructed_pair
from research_runtime.input_admission import (
    AdmissionError, FEATURES, validate_json, validate_snapshot,
)
from research_runtime.inmemory_inference import (
    BoundPredictor, InferenceError, PREDICTOR_SCHEMA,
    authorize_once, run_in_memory,
)


def build_examples():
    """Exercise a fixed catalogue using only constructed caller-owned memory."""
    checks = []

    def check(name, condition):
        if not condition:
            raise AssertionError(name)
        checks.append(name)

    pairs = {name: constructed_pair(spec, 'logistic') for name, spec in
             (('seven', 'seven'), ('ten', 'multimodal_values'))}
    combinations = []
    for specification in ('seven', 'multimodal_values'):
        for family in ('logistic', 'random_forest', 'xgboost'):
            request, context = constructed_pair(specification, family)
            raw = json.dumps(request, allow_nan=False).encode('utf-8')
            report = validate_json(raw, trusted_context=context)
            check(specification + '/' + family + '/metadata',
                  report['status'] == 'METADATA_CONSISTENT'
                  and report['predictionAllowed'] is False)
            combinations.append(dict(specification=specification, family=family,
                status=report['status'], actualModelEvaluated=False))

    request, context = deepcopy(pairs['ten'])
    metadata = validate_snapshot(request, trusted_context=context)
    waiting = run_in_memory(request, trusted_context=context)
    check('metadata_is_not_execution_permission', waiting['status'] == 'PERMISSION_REQUIRED'
          and waiting['predictionCalls'] == 0 and waiting['predictions'] is None)

    calls = 0
    identity = deepcopy(context['model'])
    constant_values = (.20, .05)
    token = hashlib.sha256(b'CONSTRUCTED-CONSTANT-CALLBACK:0.20:0.05').hexdigest()

    def constant_callback(rows):
        nonlocal calls
        calls += 1
        return dict(schemaVersion=PREDICTOR_SCHEMA, model=deepcopy(identity),
            predictionWindowMonths=24, predictionPolicy=identity['predictionPolicy'],
            records=[dict(subject_id=row['subject_id'], ad_cif_24m=constant_values[0],
                          other_dementia_cif_24m=constant_values[1]) for row in rows])

    predictor = BoundPredictor(identity, predict=constant_callback, state_token=lambda: token)
    permit = authorize_once(request, trusted_context=context,
        authorization_ref='CONSTRUCTED-CANDIDATE-ONLY', authorization_granted=True)
    completed = run_in_memory(request, trusted_context=context, predictor=predictor, permit=permit)
    check('constructed_callback_completed_once', completed['status'] == 'COMPLETED'
          and completed['predictionCalls'] == calls == 1 and permit.state == 'COMPLETED'
          and completed['predictions'] == [dict(subject_id='CONSTRUCTED-001',
              ad_cif_24m=.20, other_dementia_cif_24m=.05)])

    # Construct two artificial scenarios. Updating the fixture's evidence here
    # is NOT a production recipe for deriving trusted_context from a request.
    as_of_request, as_of_context = deepcopy(pairs['ten'])
    as_of_request['availabilityPolicy'] = as_of_context['availabilityPolicy'] = 'AS_OF_INDEX'
    observation = as_of_request['records'][0]['features']['mmse']
    observation['availableDate'] = None
    as_of_context['evidence'][observation['evidenceRef']]['observation'] = deepcopy(observation)
    blocked = run_in_memory(as_of_request, trusted_context=as_of_context)
    check('as_of_unknown_availability_blocks_before_callback', blocked['status'] == 'BLOCKED'
          and blocked['predictions'] is None and blocked['predictionCalls'] == 0
          and any(row['code'] == 'AVAILABILITY_UNKNOWN' and row['severity'] == 'BLOCK'
                  for row in blocked['admission']['issues']))
    retrospective_request, retrospective_context = deepcopy((as_of_request, as_of_context))
    retrospective_request['availabilityPolicy'] = retrospective_context['availabilityPolicy'] = 'RETROSPECTIVE_SNAPSHOT'
    retrospective = run_in_memory(retrospective_request, trusted_context=retrospective_context)
    check('retrospective_warning_still_requires_permission',
          retrospective['status'] == 'PERMISSION_REQUIRED'
          and retrospective['predictionCalls'] == 0
          and any(row['code'] == 'AVAILABILITY_UNKNOWN' and row['severity'] == 'WARN'
                  for row in retrospective['admission']['issues']))

    mismatched = deepcopy(request)
    mismatched['model']['artifactSha256'] = '0' * 64
    mismatch = run_in_memory(mismatched, trusted_context=context)
    check('model_binding_mismatch_blocks', mismatch['status'] == 'BLOCKED'
          and mismatch['predictions'] is None and mismatch['predictionCalls'] == 0
          and mismatch['admission']['blockedRecordCount'] == 1)

    missing_request, missing_context = deepcopy(pairs['ten'])
    for name in FEATURES[8:]:
        item = missing_request['records'][0]['features'][name]
        item.update(value=None, measuredDate=None, availableDate=None, dateBasis='UNKNOWN',
                    qc='UNKNOWN', completion='NOT_APPLICABLE', missingReason='NOT_COLLECTED', scanId=None)
        missing_context['evidence'][item['evidenceRef']]['observation'] = deepcopy(item)
    missing = run_in_memory(missing_request, trusted_context=missing_context)
    check('bilateral_missing_mri_preserves_null_and_denominators',
          missing['status'] == 'PERMISSION_REQUIRED' and missing['predictionCalls'] == 0
          and missing['predictions'] is None
          and all(missing['admission']['missingFeatureCounts'][name] == 1 for name in FEATURES[8:])
          and all(missing_request['records'][0]['features'][name]['value'] is None for name in FEATURES[8:]))
    no_core_request, no_core_context = deepcopy(pairs['seven'])
    for name in FEATURES[:7]:
        item = no_core_request['records'][0]['features'][name]
        item.update(value=None, measuredDate=None, availableDate=None, dateBasis='UNKNOWN',
                    qc='UNKNOWN', completion='UNKNOWN', missingReason='NOT_COLLECTED', scanId=None)
        no_core_context['evidence'][item['evidenceRef']]['observation'] = deepcopy(item)
    no_core = run_in_memory(no_core_request, trusted_context=no_core_context)
    check('all_core_inputs_missing_blocks', no_core['status'] == 'BLOCKED'
          and no_core['predictions'] is None and no_core['predictionCalls'] == 0
          and any(row['code'] == 'NO_CORE_INPUT' for row in no_core['admission']['issues']))

    errors = []

    def expect_error(name, operation, expected_type, expected_code):
        try:
            operation()
        except (AdmissionError, InferenceError) as error:
            check(name, type(error) is expected_type and str(error) == expected_code)
            # These fixed exception codes are examples, not an HTTP error mapping.
            errors.append(dict(case=name, exception=type(error).__name__, code=str(error)))
        else:
            raise AssertionError(name + ': expected rejection')

    wrong_unit = deepcopy(request)
    wrong_unit['records'][0]['features'][FEATURES[8]]['unit'] = 'normalized'
    expect_error('normalized_is_not_mm3',
        lambda: validate_snapshot(wrong_unit, trusted_context=context), AdmissionError, 'FEATURE_UNIT')
    decimal_apoe = deepcopy(request)
    decimal_apoe['records'][0]['features']['apoe4_count']['value'] = 1.0
    expect_error('apoe_float_is_not_integer_count',
        lambda: validate_snapshot(decimal_apoe, trusted_context=context), AdmissionError, 'APOE_INTEGER_COUNT')
    other_window = deepcopy(request)
    other_window['predictionWindowMonths'] = 12
    expect_error('non_24_months_rejected',
        lambda: validate_snapshot(other_window, trusted_context=context), AdmissionError, 'ONLY_24_MONTH_WINDOW')
    availability_arm = deepcopy(request)
    availability_arm['model']['specification'] = 'modality_availability'
    expect_error('availability_control_not_supported_by_admission',
        lambda: validate_snapshot(availability_arm, trusted_context=context), AdmissionError, 'MODEL_SPECIFICATION')
    expect_error('duplicate_json_keys_rejected',
        lambda: validate_json(b'{"schemaVersion":"x","schemaVersion":"y"}', trusted_context=context),
        AdmissionError, 'DUPLICATE_JSON_KEY')
    expect_error('host_permission_required',
        lambda: authorize_once(request, trusted_context=context,
            authorization_ref='CONSTRUCTED-NO-PERMISSION', authorization_granted=False),
        InferenceError, 'INDEPENDENT_EXECUTION_PERMISSION_REQUIRED')
    expect_error('same_in_memory_permit_cannot_be_reused',
        lambda: run_in_memory(request, trusted_context=context, predictor=predictor, permit=permit),
        InferenceError, 'PERMIT_ALREADY_USED')
    check('only_one_constructed_callback_total', calls == 1)
    responses = dict(metadataConsistent=metadata, permissionRequired=waiting,
        completedConstructedCallback=completed, blockedAsOfUnknownAvailability=blocked,
        retrospectiveUnknownAvailability=retrospective, blockedModelMismatch=mismatch,
        bilateralMissingMRI=missing, allCoreInputsMissing=no_core)
    check('all_responses_keep_uncertified_flags', all(value['clinicalUse'] is False
        and value['sourceAuthenticityCertified'] is False
        and value['historicalAvailabilityCertified'] is False
        and value['individualCausalEffectIdentified'] is False for value in responses.values()))
    return dict(scope='CONSTRUCTED_PYTHON_API_EXAMPLES_ONLY', isHTTPContract=False,
        fixtureNote='All identities, evidence and values are artificial; context is not caller authorization.',
        fixtures={name: dict(request=pair[0], constructedHostContext=pair[1]) for name, pair in pairs.items()},
        metadataCombinations=combinations, responses=responses, errorExamples=errors,
        accounting=dict(checksPassed=len(checks), constructedCallbackCalls=calls,
                        actualModelCalls=0, originalCIFCalls=0, fits=0), checks=checks)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--full', action='store_true', help='Print the constructed example catalogue.')
    args = parser.parse_args()
    examples = build_examples()
    output = examples if args.full else dict(scope=examples['scope'],
        accounting=examples['accounting'],
        responseStatuses={key: value['status'] for key, value in examples['responses'].items()},
        errorCodes=[row['code'] for row in examples['errorExamples']])
    print(json.dumps(output, ensure_ascii=False, allow_nan=False, indent=2))


if __name__ == '__main__':
    main()
