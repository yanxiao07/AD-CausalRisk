"""Candidate wire adapter with constructed memory only; no HTTP listener."""
from copy import deepcopy
import json
import unittest
from unittest.mock import patch

import numpy as np

from research_runtime.research_table_agent import CONTRACT, ResearchTableAgent, ResearchAgentError
from research_runtime.table_assessment import TableAssessment
from research_runtime.tests.test_table_assessment import case


def configured(spec='seven', *, unknown_qc=False, missing_mri=False):
    model, request, context = case(spec)
    if unknown_qc:
        item = request['records'][0]['features']['mmse']
        item['qc'] = 'UNKNOWN'
        context['evidence'][item['evidenceRef']]['observation'] = deepcopy(item)
    if missing_mri:
        for name in request['model']['features'][8:]:
            item = request['records'][0]['features'][name]
            item.update(value=None, measuredDate=None, availableDate=None, dateBasis='UNKNOWN',
                qc='NOT_APPLICABLE', completion='NOT_APPLICABLE', missingReason='NOT_COLLECTED', scanId=None)
            context['evidence'][item['evidenceRef']]['observation'] = deepcopy(item)
    assessment = TableAssessment(model, verified_identity=request['model'])
    body = dict(contractVersion=CONTRACT, runId='run-constructed-research-wire',
        modelKey='constructed-' + spec + '-logistic', subjectCode=request['records'][0]['subjectCode'],
        dataVersion=request['dataVersion'], indexDate=request['indexDate'],
        availabilityPolicy=request['availabilityPolicy'], predictionWindowMonths=24,
        features=deepcopy(request['records'][0]['features']))
    agent = ResearchTableAgent(model_key=body['modelKey'], subject_code=body['subjectCode'],
        assessment=assessment, trusted_context=context, data_scope='CONSTRUCTED_VALIDATION')
    return agent, body, assessment, context


def post(agent, body, **kwargs):
    return agent.handle_json(json.dumps(body, ensure_ascii=False, allow_nan=False).encode('utf-8'),
        **(dict(authorization_granted=True, prediction_authorization_ref='CONSTRUCTED-PREDICT',
                explanation_authorization_ref='CONSTRUCTED-EXPLAIN') | kwargs))


class ResearchTableAgentTests(unittest.TestCase):
    def test_seven_and_ten_complete_without_legacy_score_or_real_origin_claim(self):
        for spec in ('seven', 'multimodal_values'):
            agent, body, _, _ = configured(spec)
            before = deepcopy(body)
            result = post(agent, body)
            self.assertEqual(result['status'], 'COMPLETED')
            self.assertEqual(result['subjectCode'], body['subjectCode'])
            self.assertEqual(result['dataScope'], 'CONSTRUCTED_VALIDATION')
            self.assertEqual(set(result['prediction']), {'adCif24m', 'otherDementiaCif24m'})
            self.assertNotIn('riskScore', result)
            self.assertFalse(result['clinicalUse'])
            self.assertFalse(result['sourceAuthenticityCertified'])
            self.assertEqual(result['computation']['directCifCalls'], 1)
            self.assertEqual(body, before)
            json.dumps(result, allow_nan=False)

    def test_consistent_unknown_qc_blocks_before_any_model_call(self):
        agent, body, assessment, _ = configured(unknown_qc=True)
        with patch.object(assessment._model, 'predict_proba', side_effect=AssertionError('CALLED')):
            result = post(agent, body)
        self.assertEqual(result['status'], 'BLOCKED')
        self.assertIsNone(result['prediction'])
        self.assertIsNone(result['explanation'])
        self.assertIsNone(result['binding'])
        self.assertEqual(result['computation'], dict(directCifCalls=0, explanationCifCalls=0))

    def test_missing_mri_remains_missing_and_its_group_has_zero_contribution(self):
        agent, body, _, _ = configured('multimodal_values', missing_mri=True)
        result = post(agent, body)
        self.assertEqual(result['status'], 'COMPLETED')
        self.assertIsNone(body['features']['mri_left_hippocampus_volume']['value'])
        self.assertEqual(result['explanation']['groups'][-1], 'bilateral_hippocampus')
        np.testing.assert_array_equal(result['explanation']['contributions'][0][-1], [0, 0])

    def test_json_cannot_supply_context_permission_paths_or_model_identity(self):
        agent, body, assessment, _ = configured()
        for field in ('trusted_context', 'authorization_granted', 'modelPath', 'model', 'dataScope', 'max_coalitions'):
            with patch.object(assessment, 'run', side_effect=AssertionError('CALLED')):
                with self.assertRaises(ResearchAgentError) as error:
                    post(agent, dict(body, **{field: 'FORGED'}))
            self.assertEqual(error.exception.code, 'REQUEST_FIELDS_INVALID')

    def test_legacy_agent_request_is_not_reinterpreted(self):
        agent, _, _, _ = configured()
        with self.assertRaises(ResearchAgentError) as error:
            post(agent, dict(contractVersion='deepagent-v1', visits=[], features=[]))
        self.assertEqual(error.exception.code, 'REQUEST_FIELDS_INVALID')

    def test_host_permission_checked_before_model_or_subject_lookup(self):
        agent, body, assessment, _ = configured()
        with patch.object(assessment, 'run', side_effect=AssertionError('CALLED')):
            for options in ({'authorization_granted': False}, {'authorization_granted': 1},
                            {'prediction_authorization_ref': None}, {'explanation_authorization_ref': None}):
                with self.assertRaises(ResearchAgentError) as error:
                    post(agent, dict(body, modelKey='unbound'), **options)
                self.assertEqual(error.exception.http_status, 403)
                self.assertEqual(error.exception.code, 'INDEPENDENT_EXECUTION_PERMISSION_REQUIRED')

    def test_wrong_model_and_subject_refused_without_calculation(self):
        agent, body, assessment, _ = configured()
        with patch.object(assessment, 'run', side_effect=AssertionError('CALLED')):
            for key, value, code, status in [('modelKey', 'different-model', 'MODEL_NOT_BOUND', 409),
                    ('subjectCode', 'PRIVATE-SUBJECT', 'SUBJECT_NOT_BOUND', 403)]:
                with self.assertRaises(ResearchAgentError) as error:
                    post(agent, dict(body, **{key: value}))
                self.assertEqual((error.exception.code, error.exception.http_status), (code, status))
                self.assertNotIn(value, json.dumps(error.exception.response()))

    def test_snapshot_version_index_and_policy_cannot_be_switched(self):
        agent, body, assessment, _ = configured()
        with patch.object(assessment, 'run', side_effect=AssertionError('CALLED')):
            for key, value in [('dataVersion', 'different'), ('indexDate', '2019-01-01'), ('availabilityPolicy', 'AS_OF_INDEX')]:
                with self.assertRaises(ResearchAgentError) as error:
                    post(agent, dict(body, **{key: value}))
                self.assertEqual(error.exception.code, 'SNAPSHOT_BINDING_MISMATCH')

    def test_only_strict_24_month_integer_is_accepted(self):
        agent, body, _, _ = configured()
        for window in (12, 24.0, True):
            with self.assertRaises(ResearchAgentError) as error:
                post(agent, dict(body, predictionWindowMonths=window))
            self.assertEqual(error.exception.code, 'INPUT_DECLARATION_INVALID')

    def test_apoe_integer_token_and_mri_mm3_cannot_be_silently_converted(self):
        agent, body, _, _ = configured('multimodal_values')
        for feature, key, value in [('apoe4_count', 'value', 1.0), ('mri_left_hippocampus_volume', 'unit', 'normalized')]:
            changed = deepcopy(body)
            changed['features'][feature][key] = value
            with self.assertRaises(ResearchAgentError) as error:
                post(agent, changed)
            self.assertEqual(error.exception.code, 'INPUT_DECLARATION_INVALID')

    def test_run_id_is_correlation_only_not_a_fabricated_replay_store(self):
        agent, body, assessment, _ = configured()
        original = assessment._predictor._predict
        with patch.object(assessment._predictor, '_predict', wraps=original) as callback:
            first = post(agent, body)
            second = post(agent, body)
        self.assertEqual(callback.call_count, 2)
        self.assertEqual(first, second)
        self.assertFalse(first['persistentRunStore'])

    def test_bad_json_duplicate_keys_nonfinite_utf16_and_size_are_rejected(self):
        agent, _, _, _ = configured()
        for raw in (b'{"a":1,"a":2}', b'{"a":NaN}', b'{"a":1e999}', '{"a":1}'.encode('utf-16'), b'[]', {}):
            with self.assertRaises(ResearchAgentError) as error:
                agent.handle_json(raw)
            self.assertIn(error.exception.code, ('REQUEST_JSON_INVALID', 'REQUEST_FIELDS_INVALID'))
        with self.assertRaises(ResearchAgentError) as error:
            agent.handle_json(b' ' * 131073)
        self.assertEqual(error.exception.http_status, 413)

    def test_exception_details_and_partial_prediction_do_not_escape(self):
        agent, body, assessment, _ = configured()
        with patch.object(assessment, 'run', side_effect=RuntimeError('PRIVATE_INTERNAL_URL')):
            with self.assertRaises(ResearchAgentError) as error:
                post(agent, body)
        self.assertEqual(error.exception.http_status, 500)
        self.assertNotIn('PRIVATE_INTERNAL_URL', str(error.exception))
        self.assertNotIn('prediction', error.exception.response())

    def test_host_context_and_public_identity_are_copied_and_not_self_attested(self):
        agent, body, assessment, context = configured()
        context['evidence'].clear()
        identity = assessment.model_identity
        identity['features'].clear()
        self.assertEqual(post(agent, body)['status'], 'COMPLETED')
        unverified = ResearchTableAgent(model_key=body['modelKey'], subject_code=body['subjectCode'],
            assessment=assessment, trusted_context=context, data_scope='CONSTRUCTED_VALIDATION')
        result = post(unverified, body)
        self.assertEqual(result['status'], 'BLOCKED')
        self.assertIsNone(result['prediction'])

    def test_invalid_host_binding_and_wrong_wire_version_or_run_id(self):
        agent, body, assessment, context = configured()
        for malformed in ({}, dict(context, model=dict(context['model'], artifactSha256='9' * 64))):
            with self.assertRaises(ResearchAgentError) as error:
                ResearchTableAgent(model_key=body['modelKey'], subject_code=body['subjectCode'],
                    assessment=assessment, trusted_context=malformed, data_scope='CONSTRUCTED_VALIDATION')
            self.assertEqual(error.exception.code, 'HOST_BINDING_INVALID')
        for key, value, code in [('contractVersion', 'deepagent-v1', 'RESEARCH_CONTRACT_MISMATCH'),
                                 ('runId', 'PRIVATE BAD ID', 'RUN_ID_INVALID')]:
            with self.assertRaises(ResearchAgentError) as error:
                post(agent, dict(body, **{key: value}))
            self.assertEqual(error.exception.code, code)


if __name__ == '__main__':
    unittest.main()
