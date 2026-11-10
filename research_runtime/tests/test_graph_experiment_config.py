"""Fixed graph configuration policy, legacy-byte and no-runtime challenges."""
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import unittest

from research_runtime.experiment_config import (
    GRAPH_SCHEMA, GRAPH_ARMS, GRAPH_SEEDS, GRAPH_PROTOCOL_SHA256,
    export_experiment_config, load_experiment_config, ExperimentConfigError)
from research_runtime.examples.graph_experiment_config_constructed import constructed_selection
from research_runtime.tests.test_experiment_config import selection as table_selection

TABLE_V1_DIGESTS = {
    'seven/logistic': '42f3ad88466a0fd741be96b682bff23b12e64624c78b65a8e1a6372c34b7b2a9',
    'seven/random_forest': '3def3427cef772f502788d719b9c5c3cf7331eb817115a18e4964cb51073b95b',
    'seven/xgboost': 'f401a2f6dc8e731ac27bcd4c8e1af52fe60c0d037bef0adb34c70fe1b23c6aaa',
    'multimodal_values/logistic': '788658eab9452d39e349e90eb56cd3b71b1082a4f6174019cf941c2f2bdd251d',
    'multimodal_values/random_forest': '23c17df9910af324cb8c4776464a15a60a3eb206b829d8a9684333ff810c5f54',
    'multimodal_values/xgboost': '22f81fe27d93b18cb89acf3e27456ba59d876838d08f5b20cef6fb60153c749b',
}

def restore(raw):
    return load_experiment_config(raw, expected_sha256=hashlib.sha256(raw).hexdigest())

class GraphExperimentConfigTests(unittest.TestCase):
    def test_nine_single_model_and_three_ensemble_configs_roundtrip(self):
        for arm in GRAPH_ARMS:
            for seed in [*GRAPH_SEEDS,None]:
                selected = constructed_selection(arm,seed)
                result = restore(export_experiment_config(selected))
                self.assertEqual(result['selection'],selected)
                self.assertEqual(result['schemaVersion'],GRAPH_SCHEMA)
                self.assertEqual(len(result['features']),10)
                self.assertEqual(result['units']['mri_left_hippocampus_volume'],'mm3')
                self.assertIs(result['executionAuthorized'],False)
                self.assertIs(result['clinicalUse'],False)

    def test_old_six_table_v1_outputs_remain_byte_identical(self):
        for key,digest in TABLE_V1_DIGESTS.items():
            spec,family=key.split('/');s=table_selection();s.update(specification=spec,family=family)
            raw=export_experiment_config(s)
            self.assertEqual(hashlib.sha256(raw).hexdigest(),digest)
            self.assertEqual(restore(raw)['schemaVersion'],'research-experiment-config-v1')

    def test_graph_protocol_bytes_arms_and_seed_order_match_fixed_source(self):
        raw=(Path(__file__).resolve().parents[1]/'graph_protocol.json').read_bytes()
        protocol=json.loads(raw)
        self.assertEqual(hashlib.sha256(raw).hexdigest(),GRAPH_PROTOCOL_SHA256)
        self.assertEqual(protocol['arms'],list(GRAPH_ARMS))
        self.assertEqual(protocol['future_initialization_seeds'],list(GRAPH_SEEDS))
        self.assertEqual(protocol['features'],restore(export_experiment_config(constructed_selection()))['features'])
        self.assertIs(protocol['clinical_use'],False)

    def test_graph_requires_ten_variables_and_its_own_reference_policy(self):
        for key,value in [('specification','seven'),('referencePolicy','SAVED_PIPELINE_MEDIAN'),
                          ('predictionPolicy','calibrated'),('predictionWindowMonths',12)]:
            s=constructed_selection();s[key]=value
            with self.assertRaises(ExperimentConfigError):export_experiment_config(s)
        s=constructed_selection();s['referencePolicy']='SAVED_GRAPH_MEAN'
        self.assertEqual(restore(export_experiment_config(s))['selection']['referencePolicy'],'SAVED_GRAPH_MEAN')

    def test_unknown_or_nonstring_arm_is_rejected(self):
        for arm in ['four_edge','prior-best',None,[],1]:
            s=constructed_selection();s['graph']['arm']=arm
            with self.assertRaisesRegex(ExperimentConfigError,'CONFIG_GRAPH_ARM'):export_experiment_config(s)

    def test_single_seed_cannot_masquerade_as_ensemble_or_other_seed(self):
        for seeds in [[],list(GRAPH_SEEDS),[20260929],[True],[20260926.0],['20260926']]:
            s=constructed_selection(single_seed=GRAPH_SEEDS[0]);s['graph']['initializationSeeds']=seeds
            with self.assertRaises(ExperimentConfigError):export_experiment_config(s)

    def test_ensemble_requires_all_fixed_seeds_in_fixed_order(self):
        for seeds in [[GRAPH_SEEDS[0]],list(reversed(GRAPH_SEEDS)),[GRAPH_SEEDS[0]]*3,
                      [20260926,20260927,20260929],list(GRAPH_SEEDS)+[20260929],True,None]:
            s=constructed_selection();s['graph']['initializationSeeds']=seeds
            with self.assertRaises(ExperimentConfigError):export_experiment_config(s)

    def test_mean_logits_hazards_and_best_seed_policies_rejected(self):
        for policy in ['MEAN_LOGITS','MEAN_HAZARDS','BEST_SEED','SINGLE_MODEL','',[],None]:
            s=constructed_selection();s['graph']['aggregationPolicy']=policy
            with self.assertRaises(ExperimentConfigError):export_experiment_config(s)

    def test_artifact_map_must_exactly_match_the_selected_seeds(self):
        for mode in ['missing','extra','integer_key','wrong_hash','duplicate_artifact']:
            s=constructed_selection();m=s['graph']['artifactSha256BySeed']
            if mode=='missing':del m[str(GRAPH_SEEDS[0])]
            elif mode=='extra':m['20260929']='e'*64
            elif mode=='integer_key':m[GRAPH_SEEDS[0]]=m.pop(str(GRAPH_SEEDS[0]))
            elif mode=='wrong_hash':m[str(GRAPH_SEEDS[0])]='model.npz'
            else:m[str(GRAPH_SEEDS[1])]=m[str(GRAPH_SEEDS[0])]
            with self.assertRaises(ExperimentConfigError):export_experiment_config(s)

    def test_graph_protocol_must_be_explicit_and_match_fixed_version(self):
        for mode in ['missing','changed','table_artifact_alias']:
            s=constructed_selection()
            if mode=='missing':del s['hashes']['graphProtocolSha256']
            elif mode=='changed':s['hashes']['graphProtocolSha256']='a'*64
            else:s['hashes']['artifactSha256']=s['hashes'].pop('graphProtocolSha256')
            with self.assertRaises(ExperimentConfigError):export_experiment_config(s)

    def test_private_graph_values_and_permits_are_rejected_without_echo(self):
        for key in ['subject_id','edgeWeights','parameters','referenceValues','privatePath','permit','records']:
            s=constructed_selection();s['graph'][key]='PRIVATE-CANARY'
            with self.assertRaises(ExperimentConfigError) as error:export_experiment_config(s)
            self.assertNotIn('PRIVATE-CANARY',str(error.exception))
        s=constructed_selection();s['randomSeed']=GRAPH_SEEDS[0]
        with self.assertRaises(ExperimentConfigError):export_experiment_config(s)

    def test_graph_has_no_implicit_defaults(self):
        base=constructed_selection()
        for key in base:
            s=deepcopy(base);del s[key]
            with self.assertRaises(ExperimentConfigError):export_experiment_config(s)
        for key in base['graph']:
            s=deepcopy(base);del s['graph'][key]
            with self.assertRaises(ExperimentConfigError):export_experiment_config(s)

    def test_graph_and_table_schema_cannot_be_swapped(self):
        table=restore(export_experiment_config(table_selection()))
        graph=restore(export_experiment_config(constructed_selection()))
        for value,schema in [(table,GRAPH_SCHEMA),(graph,'research-experiment-config-v1')]:
            value['schemaVersion']=schema
            with self.assertRaisesRegex(ExperimentConfigError,'CONFIG_CONTENT_MISMATCH'):restore(json.dumps(value).encode())

    def test_seed_artifact_and_arm_changes_are_visible_in_export(self):
        s=constructed_selection();baseline=export_experiment_config(s)
        changed=deepcopy(s);changed['graph']['artifactSha256BySeed'][str(GRAPH_SEEDS[0])]='f'*64
        self.assertNotEqual(export_experiment_config(changed),baseline)
        changed=deepcopy(s);changed['graph']['arm']='self_only'
        self.assertNotEqual(export_experiment_config(changed),baseline)
        self.assertNotEqual(export_experiment_config(constructed_selection(single_seed=GRAPH_SEEDS[0])),baseline)

    def test_nested_order_is_deterministic_and_input_is_not_mutated(self):
        s=constructed_selection();before=deepcopy(s);other=dict(reversed(list(s.items())))
        other['graph']=dict(reversed(list(s['graph'].items())))
        other['graph']['artifactSha256BySeed']=dict(reversed(list(s['graph']['artifactSha256BySeed'].items())))
        raw=export_experiment_config(other)
        self.assertEqual(raw,export_experiment_config(s));self.assertEqual(s,before)
        output=restore(raw);output['selection']['graph']['initializationSeeds'].clear()
        self.assertEqual(restore(raw)['selection'],before)

    def test_graph_roundtrip_does_not_grant_privileges_or_accept_extra_payload(self):
        original=restore(export_experiment_config(constructed_selection()))
        for key,value in [('executionAuthorized',True),('clinicalUse',True),('records',[])]:
            changed=deepcopy(original);changed[key]=value
            with self.assertRaises(ExperimentConfigError):restore(json.dumps(changed).encode())
        raw=export_experiment_config(constructed_selection())
        with self.assertRaisesRegex(ExperimentConfigError,'CONFIG_HASH_MISMATCH'):
            load_experiment_config(raw+b' ',expected_sha256=hashlib.sha256(raw).hexdigest())

    def test_isolated_standard_library_interpreter_needs_no_model_packages(self):
        repo=Path(__file__).resolve().parents[2]
        code=('import sys;from research_runtime.examples.graph_experiment_config_constructed import constructed_selection;'
              'from research_runtime.experiment_config import export_experiment_config;'
              'assert export_experiment_config(constructed_selection());'
              'assert not set(sys.modules).intersection({"torch","numpy","pandas","sklearn","joblib","xgboost"});'
              'print("STDLIB_ONLY_PASS")')
        result=subprocess.run([sys.executable,'-B','-S','-c',code],cwd=repo,capture_output=True,text=True,timeout=15)
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual(result.stdout.strip(),'STDLIB_ONLY_PASS')

if __name__=='__main__':unittest.main()
