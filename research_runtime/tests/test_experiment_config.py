"""Artificial configuration privacy, binding, parser and determinism challenges."""
from copy import deepcopy
import hashlib
import json
import unittest

from research_runtime.experiment_config import (
    export_experiment_config, load_experiment_config, ExperimentConfigError)

def selection():
    return {'specification': 'seven', 'family': 'logistic',
            'predictionPolicy': 'raw_uncalibrated_24month_CIF',
            'predictionWindowMonths': 24, 'referencePolicy': 'SAVED_PIPELINE_MEDIAN',
            'availabilityPolicy': 'RETROSPECTIVE_SNAPSHOT',
            'splitPolicy': 'FINAL_MODEL_NO_SPLIT', 'randomSeed': 20260926,
            'hashes': {name: str(i)*64 for i,name in enumerate([
                'manifestSha256', 'artifactSha256', 'trainingPlanSha256',
                'runtimeSourceSha256', 'softwareLockSha256'], 1)}}

def restore(raw):
    return load_experiment_config(raw, expected_sha256=hashlib.sha256(raw).hexdigest())

class ExperimentConfigTests(unittest.TestCase):
    def test_all_six_selections_roundtrip_without_model_or_data(self):
        for spec, n in [('seven',7), ('multimodal_values',10)]:
            for family in ('logistic','random_forest','xgboost'):
                s=selection();s.update(specification=spec,family=family)
                out=restore(export_experiment_config(s))
                self.assertEqual(out['selection'],s)
                self.assertEqual(len(out['features']),n)
                self.assertIs(out['executionAuthorized'],False)
                self.assertIs(out['clinicalUse'],False)
                self.assertEqual(out['purpose'],'REPRODUCIBILITY_METADATA_ONLY')

    def test_nested_key_order_does_not_change_export(self):
        s=selection();reversed_selection=dict(reversed(list(s.items())))
        reversed_selection['hashes']=dict(reversed(list(s['hashes'].items())))
        self.assertEqual(export_experiment_config(s),export_experiment_config(reversed_selection))

    def test_changes_to_each_release_digest_change_export(self):
        s=selection();old=export_experiment_config(s)
        for key in s['hashes']:
            changed=deepcopy(s);changed['hashes'][key]='f'*64
            self.assertNotEqual(export_experiment_config(changed),old)

    def test_policy_and_seed_changes_change_export(self):
        s=selection();old=export_experiment_config(s)
        for key,value in [('randomSeed',1),('referencePolicy','SAVED_PIPELINE_MEAN'),
                          ('availabilityPolicy','AS_OF_INDEX'),('splitPolicy','EXTERNAL_FROZEN_SUBJECT_SPLIT')]:
            changed=deepcopy(s);changed[key]=value
            self.assertNotEqual(export_experiment_config(changed),old)

    def test_individual_fields_paths_weights_and_permits_rejected(self):
        for key in ('subject_id','records','folds','privatePath','parameters','referenceValues',
                    'authorizationRef','permit','accessToken','event_type','individualPrediction'):
            s=selection();s[key]='PRIVATE-CANARY-DO-NOT-EXPORT'
            with self.assertRaises(ExperimentConfigError) as error:export_experiment_config(s)
            self.assertNotIn('PRIVATE-CANARY',str(error.exception))

    def test_unknown_hash_fields_rejected(self):
        s=selection();s['hashes']['subjectHash']='a'*64
        with self.assertRaises(ExperimentConfigError):export_experiment_config(s)

    def test_export_does_not_modify_caller_or_alias_restored_metadata(self):
        s=selection();before=deepcopy(s);raw=export_experiment_config(s)
        self.assertEqual(s,before)
        restored=restore(raw);restored['selection']['hashes']['artifactSha256']='f'*64
        self.assertEqual(restore(raw)['selection'],before)

    def test_wrong_or_tampered_digest_rejected(self):
        raw=export_experiment_config(selection())
        with self.assertRaisesRegex(ExperimentConfigError,'CONFIG_HASH_MISMATCH'):
            load_experiment_config(raw,expected_sha256='0'*64)
        with self.assertRaisesRegex(ExperimentConfigError,'CONFIG_HASH_MISMATCH'):
            load_experiment_config(raw+b' ',expected_sha256=hashlib.sha256(raw).hexdigest())

    def test_no_implicit_fields_or_defaults(self):
        s=selection()
        for key in s:
            changed=deepcopy(s);del changed[key]
            with self.assertRaises(ExperimentConfigError):export_experiment_config(changed)

    def test_unsupported_model_information_and_calibration_rejected(self):
        for key,value in [('specification','modality_availability'),('family','gat'),
                          ('predictionPolicy','calibrated'),('referencePolicy','caller_default'),
                          ('availabilityPolicy','CERTIFIED'),('splitPolicy','random_rows')]:
            s=selection();s[key]=value
            with self.assertRaises(ExperimentConfigError):export_experiment_config(s)

    def test_integer_fields_do_not_accept_bool_float_or_unbounded(self):
        for key,values in [('randomSeed',[True,1.,-1,2**32]),
                           ('predictionWindowMonths',[True,24.,12,0])]:
            for value in values:
                s=selection();s[key]=value
                with self.assertRaises(ExperimentConfigError):export_experiment_config(s)

    def test_invalid_hashes_and_nonstring_policy_rejected(self):
        for value in ['A'*64,'g'*64,'a'*63,{},None]:
            s=selection();s['hashes']['artifactSha256']=value
            with self.assertRaises(ExperimentConfigError):export_experiment_config(s)
        s=selection();s['specification']=[]
        with self.assertRaises(ExperimentConfigError):export_experiment_config(s)

    def test_raw_size_type_and_expected_digest_validation(self):
        for raw in [b'',b' '*8193,'{}',None]:
            with self.assertRaises(ExperimentConfigError):restore(raw) if type(raw) is bytes else load_experiment_config(raw,expected_sha256='a'*64)
        for expected in [None,'','A'*64]:
            with self.assertRaises(ExperimentConfigError):load_experiment_config(b'{}',expected_sha256=expected)

    def test_duplicate_keys_including_nested_rejected(self):
        for raw in [b'{"schemaVersion":1,"schemaVersion":2}',
                    b'{"selection":{"family":"logistic","family":"xgboost"}}']:
            with self.assertRaisesRegex(ExperimentConfigError,'CONFIG_DUPLICATE_KEY'):restore(raw)

    def test_malformed_nonfinite_utf8_and_deep_json_rejected(self):
        for raw in [b'{',b'\xff',b'{"a":NaN}',b'{"a":Infinity}',b'['*2000+b']'*2000]:
            with self.assertRaises(ExperimentConfigError):restore(raw)

    def test_privilege_upgrade_extra_fields_and_feature_mismatch_rejected(self):
        original=restore(export_experiment_config(selection()))
        changes=[('executionAuthorized',True),('executionAuthorized',0),('clinicalUse',True),
                 ('schemaVersion','other'),('purpose','CLINICAL'),('features',list(reversed(original['features']))),
                 ('units',dict(original['units'],mmse='years')),('records',[])]
        for key,value in changes:
            changed=deepcopy(original);changed[key]=value
            with self.assertRaises(ExperimentConfigError):restore(json.dumps(changed).encode())

    def test_whitespace_does_not_hide_content_changes(self):
        payload=restore(export_experiment_config(selection()))
        self.assertEqual(restore(json.dumps(payload,indent=2).encode()),payload)
        payload['selection']['family']='gat'
        with self.assertRaises(ExperimentConfigError):restore(json.dumps(payload,indent=2).encode())

if __name__ == '__main__':unittest.main()
