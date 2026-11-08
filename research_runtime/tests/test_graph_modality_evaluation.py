"""Analytic fixed-weight path and gate failures; no optimization or data files."""
from copy import deepcopy
from contextlib import ExitStack
import hashlib
import json
import unittest
from unittest.mock import patch
import numpy as np
import pandas as pd

from research_runtime import graph_explanation as ex
from research_runtime.tests.test_graph_perturbation_streaming import Model, identity, analytic_values
from research_runtime.training import synthetic_cohort, checked_design, input_fingerprint, prediction_hash
from research_runtime.evaluation import censor_survival, horizon_ipcw_weights
from research_runtime.graph_native import FEATURES

AUDIT=dict(full_paths=0,full_path_calls=0,full_path_rows=0,analytic_callbacks=0)

def fixture(n=90):
    base=synthetic_cohort()
    frame=base.iloc[np.arange(n)%len(base)].reset_index(drop=True).copy()
    frame['subject_id']=['SYNTHETIC-MODALITY-'+str(i) for i in range(n)]
    if n==1160:
        frame.loc[:,list(FEATURES[7:])]=np.nan
        frame.loc[:427,FEATURES[7]]=1.
        frame.loc[:275,list(FEATURES[8:])]=[3000.,3200.]
    work,splits,_=checked_design(frame,'multimodal_values')
    arms=ex.graph.protocol()[0]['arms'];seeds=ex.graph.protocol()[0]['future_initialization_seeds']
    models={};candidates=[];raw={(s,a):np.zeros((n,2)) for s in seeds for a in arms}
    contribution={(s,a,o):np.zeros((n,9,2)) for s,a in raw for o in ex.ORDERS}
    weights=np.zeros(n);target=work.event_type.eq(1).to_numpy(float)
    for fold,(a,b) in enumerate(splits,1):
        weights[b]=horizon_ipcw_weights(work.iloc[b],censor_survival(work.iloc[a]))
        for s in seeds:
            for arm in arms:
                m=Model(work.iloc[a],arm,s);models[fold,s,arm]=m
                candidates.append(dict(outer_fold=fold,seed=s,arm=arm,role='GRAPH_OUTER_VALIDATION',
                    sha256='a'*64,scope_identity='b'*64,training_input_sha256=m.named_steps['hazards'].training_input_sha256_))
                raw[s,arm][b]=analytic_values(m,work.iloc[b])
                # Independent ordered state arithmetic creates the prior receipt.
                ref=work.iloc[b].astype({f:'float64' for f in FEATURES},copy=True)
                for j,f in enumerate(FEATURES):ref.loc[ref[f].notna(),f]=m.named_steps['hazards'].medians_[j]
                for order in ex.ORDERS:
                    state=ref.copy();previous=analytic_values(m,state)
                    for j in (range(9) if order=='forward' else reversed(range(9))):
                        cols=list(ex.GROUPS[j][1]);state.loc[:,cols]=work.iloc[b][cols].to_numpy()
                        current=analytic_values(m,state);contribution[s,arm,order][b,j]=current-previous;previous=current
    expected=[]
    for arm in arms:
        mean=np.stack([raw[s,arm] for s in seeds]).mean(0);err=weights*(target-mean[:,0])**2
        row=dict(arm=arm,ad_oof_sha256=prediction_hash(mean[:,0]),other_oof_sha256=prediction_hash(mean[:,1]),
            pooled_oof_brier=float(err.mean()),per_seed=[],missing_subgroups=[])
        for s in seeds:
            value=raw[s,arm]
            row['per_seed'].append(dict(seed=s,ad_oof_sha256=prediction_hash(value[:,0]),other_oof_sha256=prediction_hash(value[:,1]),
                pooled_oof_brier=float(np.mean(weights*(target-value[:,0])**2))))
        row['missing_subgroups']=[dict(name=name,subjects=int(mask.sum()),brier=float(err[mask].mean()) if mask.any() else None,
            mean_ad_cif=float(mean[mask,0].mean()) if mask.any() else None) for name,mask in ex.subgroup_masks(work).items()]
        expected.append(row)
    empty={(s,a):np.zeros((n,0,2)) for s,a in raw}
    edges={(s,a):np.zeros((n,len(ex.graph.arm_edges(a)),2)) for s,a in raw}
    att={(s,a):np.zeros((n,len(ex.graph.arm_edges(a)))) for s,a in raw}
    active={a:np.zeros((n,len(ex.graph.arm_edges(a))),bool) for a in arms}
    old=ex.aggregate(work,splits,raw,raw,contribution,edges,{k:np.zeros((n,2)) for k in raw},att,active)
    return work,splits,candidates,dict(models=expected),{k:old[k] for k in ('importance','order_sensitivity')},models

class EvaluationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):cls.small=fixture()
    def invoke(self,data=None,alter_cif=None,**options):
        frame,splits,candidates,expected,prior,models=deepcopy(data or self.small)
        def cif(model,current):
            AUDIT['analytic_callbacks']+=1
            return analytic_values(model,current) if alter_cif is None else alter_cif(model,current)
        opts=dict(expected_input_fingerprint=input_fingerprint(frame,'multimodal_values'),
            expected_counts={k:int(m.sum()) for k,m in ex.subgroup_masks(frame).items()},constructed=True)
        opts.update(options)
        loader=opts.pop('loader',lambda a,s,f:models[f,s,a])
        with patch.object(ex,'model_identity',identity),patch.object(ex,'checked_cif',cif):
            return ex.evaluate_modality_strata(frame,splits,loader,candidates,expected,prior,**opts)
    def test_full_855_path_once(self):
        events=[];before=AUDIT['analytic_callbacks'];result=self.invoke(fixture(1160),progress=events.append)
        AUDIT.update(full_paths=1,full_path_calls=AUDIT['analytic_callbacks']-before,full_path_rows=result['cif_state_rows_completed'])
        self.assertEqual(result['status'],'CONSTRUCTED_MODALITY_PATH_COMPLETE')
        self.assertEqual((result['model_load_attempts'],result['model_loads_completed'],result['single_model_games']),(45,45,45))
        self.assertEqual((result['cif_attempts'],result['cif_completed']),(855,855))
        self.assertEqual((result['cif_state_rows_attempted'],result['cif_state_rows_completed']),(198360,198360))
        self.assertEqual(result['derived_person_month_rows'],4760640)
        self.assertEqual((result['baseline_hashes_verified'],result['baseline_briers_verified'],result['stratum_metrics_verified']),(24,12,24))
        gate=next(e for e in events if e['phase']=='ALL_BASELINE_AND_STRATUM_GATES_PASSED')
        self.assertEqual((gate['cif_completed'],gate['single_model_games']),(45,0))
        self.assertEqual([s['subjects'] for s in result['modality_strata']['strata']],[276,152,0,732])
        self.assertEqual(result['fits'],0);self.assertLessEqual(result['reconstruction_max_error'],1e-12)
        self.assertLessEqual(result['signed_seed_reconstruction_max_error'],1e-12)
        self.assertTrue(result['original_pooled_explanation_verified'])
        text=json.dumps(result,allow_nan=False)
        self.assertNotIn('SYNTHETIC-MODALITY-',text);self.assertNotIn('train_positions',text)
    def failure(self,change,message,**opts):
        data=deepcopy(self.small);change(data);events=[]
        with self.assertRaisesRegex(ValueError,message):self.invoke(data,progress=events.append,**opts)
        return events
    def test_other_cause_hash_stops_before_explanation(self):
        e=self.failure(lambda d:d[3]['models'][0]['per_seed'][0].update(other_oof_sha256='0'*64),'TWO_CAUSE')
        self.assertEqual(e[-1]['single_model_games'],0);self.assertEqual(e[-1]['cif_completed'],45)
    def test_seed_brier_failure(self):self.failure(lambda d:d[3]['models'][0]['per_seed'][0].update(pooled_oof_brier=.8),'BRIER')
    def test_ensemble_hash_failure(self):self.failure(lambda d:d[3]['models'][0].update(ad_oof_sha256='0'*64),'TWO_CAUSE')
    def test_ensemble_brier_failure(self):self.failure(lambda d:d[3]['models'][0].update(pooled_oof_brier=.8),'BRIER')
    def test_subgroup_brier_failure(self):self.failure(lambda d:d[3]['models'][0]['missing_subgroups'][0].update(brier=.8),'SUBGROUP')
    def test_subgroup_mean_failure(self):self.failure(lambda d:d[3]['models'][0]['missing_subgroups'][0].update(mean_ad_cif=.8),'SUBGROUP')
    def test_subgroup_empty_null_failure(self):self.failure(lambda d:d[3]['models'][0]['missing_subgroups'][2].update(brier=0),'SUBGROUP')
    def test_missing_model_matrix(self):
        with self.assertRaisesRegex(ValueError,'MATRIX'):self.invoke((*self.small[:2],self.small[2][:-1],*self.small[3:]))
    def test_final_model_rejected(self):self.failure(lambda d:d[2][0].update(role='GRAPH_FINAL'),'OUTER_BINDING')
    def test_training_identity_failure(self):self.failure(lambda d:d[2][0].update(training_input_sha256='0'*64),'TRAINING_BINDING')
    def test_model_role_failure(self):self.failure(lambda d:setattr(next(iter(d[5].values())).named_steps['hazards'],'seed',0),'MODEL_ROLE')
    def test_paired_preprocessing_failure(self):self.failure(lambda d:next(iter(d[5].values())).named_steps['hazards'].scale_.__setitem__(0,2),'PREPROCESSING')
    def test_pooled_importance_failure(self):
        self.failure(lambda d:d[4]['importance'][0]['groups'][0].update(mean_signed_other=1),'POOLED_EXPLANATION')
    def test_pooled_order_failure(self):
        self.failure(lambda d:d[4]['order_sensitivity'][0]['local_order_l1'].update(mean=1),'POOLED_EXPLANATION')
    def test_resource_time_before_load(self):
        with patch.object(ex.time,'perf_counter',side_effect=[0.,1801.]):
            with self.assertRaisesRegex(ValueError,'RESOURCE'):self.invoke()
    def test_resource_memory_before_load(self):
        with patch.object(ex,'peak_memory_bytes',return_value=2147483649):
            with self.assertRaisesRegex(ValueError,'RESOURCE'):self.invoke()
    def test_call_budget_stops_before_callback(self):
        before=AUDIT['analytic_callbacks']
        with self.assertRaisesRegex(ValueError,'BUDGET'):self.invoke(max_cif_calls=1)
        self.assertEqual(AUDIT['analytic_callbacks']-before,1)
    def test_state_row_budget(self):
        before=AUDIT['analytic_callbacks']
        with self.assertRaisesRegex(ValueError,'BUDGET'):self.invoke(max_state_rows=1)
        self.assertEqual(AUDIT['analytic_callbacks'],before)
    def test_fingerprint_failure(self):
        with self.assertRaisesRegex(ValueError,'FROZEN'):self.invoke(expected_input_fingerprint='0'*64)
    def test_counts_failure(self):
        with self.assertRaisesRegex(ValueError,'FROZEN'):self.invoke(expected_counts=dict(both=1))
    def test_partial_bilateral_rejected(self):
        d=deepcopy(self.small);d[0].loc[0,FEATURES[8]]=np.nan;d[0].loc[0,FEATURES[9]]=3200.
        with self.assertRaisesRegex(ValueError,'PARTIAL'):self.invoke(d)
    def test_callback_failure_has_no_retry(self):
        calls=[]
        def fail(m,f):calls.append(1);raise RuntimeError('INJECTED')
        with self.assertRaisesRegex(RuntimeError,'INJECTED'):self.invoke(alter_cif=fail)
        self.assertEqual(len(calls),1)
    def test_final_replay_must_match_original_CIF(self):
        count=[0]
        def changed(m,f):
            count[0]+=1;result=analytic_values(m,f)
            if count[0]==63:result[:,0]+=.001  # 45 baselines + 17 game calls + replay.
            return result
        with self.assertRaisesRegex(ValueError,'ORIGINAL_MODEL_OR_CIF'):self.invoke(alter_cif=changed)
        self.assertEqual(count[0],63)
    def test_model_mutation_in_callback_is_rejected(self):
        def mutate(m,f):
            result=analytic_values(m,f);m.named_steps['hazards'].medians_[0]+=1;return result
        with self.assertRaisesRegex(ValueError,'MODEL_OR_INPUT_MUTATED'):self.invoke(alter_cif=mutate)
    def test_original_training_flag_is_preserved_without_requiring_eval_mode(self):
        d=deepcopy(self.small)
        for model in d[5].values():model.named_steps['hazards'].network_.training=True
        result=self.invoke(d)
        self.assertEqual(result['status'],'CONSTRUCTED_MODALITY_PATH_COMPLETE')
        self.assertEqual(result['cif_completed'],855)
    def test_limits_cannot_be_increased(self):
        for kw in (dict(max_cif_calls=856),dict(max_state_rows=198361),dict(max_elapsed_seconds=1801),dict(max_memory_bytes=2147483649)):
            with self.assertRaisesRegex(ValueError,'SCOPE'):self.invoke(**kw)

if __name__=='__main__':unittest.main()
