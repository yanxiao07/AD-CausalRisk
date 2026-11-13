"""Draft transport contract checks, independent of scientific dependencies."""
from copy import deepcopy
import json
from pathlib import Path
import unittest
import subprocess
import sys

from jsonschema import Draft202012Validator, FormatChecker
from openapi_spec_validator import validate
from research_runtime.research_table_protocol import validate_response, ResearchResponseError, ERRORS

ROOT=Path(__file__).resolve().parents[1]
SPEC=ROOT/'docs/contracts/research-table-agent.draft.openapi.json'
EXAMPLES=ROOT/'docs/contracts/examples/research-table-agent'


class ResearchTableContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.spec=json.loads(SPEC.read_text(encoding='utf-8'))

    def validator(self,name):
        return Draft202012Validator({'$ref':'#/components/schemas/'+name,
            'components':self.spec['components']},format_checker=FormatChecker())

    def read(self,name):
        return json.loads((EXAMPLES/name).read_text(encoding='utf-8'))

    def check_semantics(self, response, label='seven', **kwargs):
        expected=self.read('response-'+label+'.json')
        return validate_response(response,expected_request=self.read('request-'+label+'.json'),
            expected_model=expected['model'],expected_data_scope='CONSTRUCTED_VALIDATION',
            **kwargs)

    def test_valid_openapi_and_explicit_non_deployed_draft(self):
        validate(self.spec)
        self.assertEqual(self.spec['x-implementation-state'],'DRAFT_FOR_BACKEND_REVIEW')
        self.assertEqual(self.spec['servers'],[])
        for path in self.spec['paths'].values():
            self.assertEqual(path['post']['x-implementation-state'],'CONSTRUCTED_LOOPBACK_VALIDATION')

    def test_new_path_does_not_replace_either_existing_contract(self):
        for name in ['python-agent.openapi.json','research-runtime.openapi.json']:
            old=json.loads((ROOT/'contracts'/name).read_text(encoding='utf-8'))
            self.assertFalse(set(old['paths']) & set(self.spec['paths']))
        self.assertEqual(self.spec['security'],[{'LocalBearer':[]}])

    def test_all_constructed_request_response_examples_validate(self):
        for label in ['seven','ten','blocked']:
            with self.subTest(label=label):
                self.validator('AssessmentRequest').validate(self.read('request-'+label+'.json'))
                response=self.read('response-'+label+'.json')
                self.validator('AssessmentResponse').validate(response)
                self.assertEqual(response['dataScope'],'CONSTRUCTED_VALIDATION')
        self.validator('ErrorResponse').validate(self.read('response-error-403.json'))

    def test_injected_context_permission_path_or_legacy_field_rejected(self):
        body=self.read('request-seven.json')
        for name in ['trusted_context','authorization_granted','modelPath','model','riskScore','max_coalitions']:
            self.assertFalse(self.validator('AssessmentRequest').is_valid(dict(body,**{name:'FORGED'})))

    def test_feature_sets_units_metadata_and_non_24_window_are_closed(self):
        body=self.read('request-ten.json')
        for change in [lambda x:x.update(predictionWindowMonths=12),
                lambda x:x['features'].pop('cdr_global'),
                lambda x:x['features']['mmse'].pop('completion'),
                lambda x:x['features']['mri_left_hippocampus_volume'].update(unit='normalized'),
                lambda x:x['features']['apoe4_count'].update(value=1.5)]:
            altered=deepcopy(body);change(altered)
            self.assertFalse(self.validator('AssessmentRequest').is_valid(altered))

    def test_blocked_is_null_zero_call_and_cannot_be_relabelled_success(self):
        body=self.read('response-blocked.json')
        for change in [lambda x:x.update(prediction={'adCif24m':0,'otherDementiaCif24m':0}),
                lambda x:x['computation'].update(directCifCalls=1),
                lambda x:x.update(status='COMPLETED')]:
            altered=deepcopy(body);change(altered)
            self.assertFalse(self.validator('AssessmentResponse').is_valid(altered))

    def test_success_requires_explanation_binding_and_false_boundaries(self):
        body=self.read('response-seven.json')
        for change in [lambda x:x.update(explanation=None),lambda x:x.update(binding=None),
                lambda x:x.update(clinicalUse=True),lambda x:x.update(sourceAuthenticityCertified=True),
                lambda x:x.update(persistentRunStore=True),lambda x:x.update(riskScore=.5)]:
            altered=deepcopy(body);change(altered)
            self.assertFalse(self.validator('AssessmentResponse').is_valid(altered))

    def test_lexical_integer_and_cif_sum_require_semantics_beyond_schema(self):
        request=self.read('request-ten.json');request['features']['apoe4_count']['value']=1.0
        # JSON Schema treats 1.0 as an integer. The adapter's actual semantic
        # check rejects it; the draft explicitly records this extra invariant.
        self.assertTrue(self.validator('AssessmentRequest').is_valid(request))
        with self.assertRaises(ResearchResponseError):
            validate_response(self.read('response-ten.json'),expected_request=request,
                expected_model=self.read('response-ten.json')['model'],expected_data_scope='CONSTRUCTED_VALIDATION')
        response=self.read('response-seven.json')
        response['prediction']={'adCif24m':.8,'otherDementiaCif24m':.8}
        self.assertTrue(self.validator('AssessmentResponse').is_valid(response))
        with self.assertRaises(ResearchResponseError):
            self.check_semantics(response)
        self.assertTrue(any('双原因CIF和' in row for row in self.spec['x-semantic-validation']))
        self.assertTrue(any('严格JSON整数' in row for row in self.spec['x-semantic-validation']))

    def test_semantic_success_and_blocked_return_independent_copies(self):
        for label in ['seven','ten','blocked']:
            original=self.read('response-'+label+'.json')
            response=self.check_semantics(original,label)
            self.assertEqual(original,response)
            response['model']['features'].clear()
            self.assertTrue(original['model']['features'])

    def test_semantic_checks_reject_self_consistent_wrong_model_and_identity(self):
        original=self.read('response-seven.json')
        altered=deepcopy(original)
        for obj in [altered['model'],altered['admission']['model'],altered['binding']['model']]:
            obj['artifactSha256']='9'*64
        self.assertTrue(self.validator('AssessmentResponse').is_valid(altered))
        with self.assertRaises(ResearchResponseError):self.check_semantics(altered)
        for key,value in [('runId','run-other'),('subjectCode','OTHER'),('dataVersion','other'),
                          ('requestSha256','9'*64),('dataScope','CONTROLLED_RESEARCH')]:
            with self.assertRaises(ResearchResponseError):self.check_semantics(dict(original,**{key:value}))

    def test_semantic_checks_reject_reconstruction_context_and_false_count(self):
        original=self.read('response-seven.json')
        for mutate in [lambda x:x['explanation']['contributions'][0][0].__setitem__(0,.5),
                       lambda x:x['explanation']['groups'].reverse(),
                       lambda x:x['admission']['missingFeatureCounts'].update(mmse=1),
                       lambda x:x['computation'].update(directCifCalls=True),
                       lambda x:x['binding'].update(inputSha256='9'*64)]:
            altered=deepcopy(original);mutate(altered)
            with self.assertRaises(ResearchResponseError):self.check_semantics(altered)
        altered=deepcopy(original);altered['binding']['trustedContextSha256']='9'*64
        with self.assertRaises(ResearchResponseError):
            self.check_semantics(altered,expected_context_sha256=original['binding']['trustedContextSha256'])

    def test_error_status_map_matches_candidate_and_semantics_need_no_site_packages(self):
        self.assertEqual(self.spec['x-error-http-status'],ERRORS)
        code='from research_runtime.research_table_protocol import validate_response; import sys; assert not any(n in sys.modules for n in ("numpy","pandas","sklearn","joblib","torch")); print("stdlib-only")'
        run=subprocess.run([sys.executable,'-S','-B','-c',code],cwd=ROOT,capture_output=True,text=True)
        self.assertEqual(run.returncode,0,run.stderr)
        self.assertEqual(run.stdout.strip(),'stdlib-only')


if __name__=='__main__':unittest.main()
