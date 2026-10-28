"""Analytic callbacks exercise the full graph matrix with zero optimizer fits."""
from copy import deepcopy
import gc
import hashlib
import json
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import weakref

import numpy as np

from research_runtime import graph_explanation as ex, graph_training as train
from research_runtime.graph_native import GraphHazards, FEATURES
from research_runtime.training import synthetic_cohort, checked_design, input_fingerprint, prediction_hash
from research_runtime.evaluation import censor_survival, horizon_ipcw_weights

AUDIT = dict(optimized_fits=0,analytic_cif_callbacks=0,mock_fit_attempts=0,mock_fit_returns=0,
             mock_fits_accepted=0,old_fixture_loads=0,full_matrix_runs=0)
FULL_RESULT = None


class Network:
    def __init__(self,arm):
        self.arm=arm;self.edges=ex.graph.arm_edges(arm);self.training=False


class Model:
    def __init__(self,frame,arm,seed,*,perturbed=False):
        x=frame[list(FEATURES)].to_numpy(float)
        medians=np.nan_to_num(np.nanmedian(x,axis=0),nan=0)
        e=SimpleNamespace(arm=arm,seed=seed,network_=Network(arm),medians_=medians,
            mean_=medians.copy(),scale_=np.ones(10),classes_=np.arange(3),
            protocol_sha256_=ex.graph.protocol()[1],
            initial_state_sha256_=str(seed),batch_schedule_sha256_='fixed-batches',
            training_input_sha256_=input_fingerprint(frame,'multimodal_values'),
            perturbed=perturbed)
        # Opposite seed effects make abs(mean) differ from mean(abs).
        e.coefficients=np.arange(1.,11.)*np.array([.01,.01,.01,.01,.1,.1,.1,.01,.0001,.0001])
        if perturbed:
            sign={20260926:-1,20260927:1,20260928:2}[seed]
            e.coefficients *= 1+sign*.03
        self.named_steps={'hazards':e}


def identity(model):
    e=model.named_steps['hazards']
    return hashlib.sha256(repr((e.arm,e.seed,e.network_.training,e.network_.edges,e.perturbed,
        e.medians_.tolist(),e.coefficients.tolist())).encode()).hexdigest()


def analytic_cif(model,frame):
    AUDIT['analytic_cif_callbacks']+=1
    return analytic_values(model,frame)


def analytic_values(model,frame):
    e=model.named_steps['hazards'];values=frame[list(FEATURES)].to_numpy(float)
    x=np.where(np.isnan(values),e.medians_,values)
    z=(x*e.coefficients).sum(1)
    interaction=np.tanh(x[:,0]*x[:,1]*.0001)
    return np.column_stack((.12+.02*np.tanh(z*.01)+.003*interaction,
                            .04+.003*np.tanh(z*.02)-.0004*interaction))


def fixture():
    frame,splits,_=checked_design(synthetic_cohort(),'multimodal_values')
    spec,_=ex.graph.protocol();arms=spec['arms'];seeds=spec['future_initialization_seeds']
    candidates=[];models={};raw={(s,a):np.zeros((len(frame),2)) for s in seeds for a in arms}
    weights=np.zeros(len(frame));target=frame.event_type.eq(1).to_numpy(float)
    for fold,(a,b) in enumerate(splits,1):
        weights[b]=horizon_ipcw_weights(frame.iloc[b],censor_survival(frame.iloc[a]))
        for seed in seeds:
            for arm in arms:
                model=Model(frame.iloc[a],arm,seed);models[fold,seed,arm]=model
                raw[seed,arm][b]=analytic_cif(model,frame.iloc[b])
                candidates.append(dict(outer_fold=fold,seed=seed,arm=arm,role='GRAPH_OUTER_VALIDATION',
                    sha256='a'*64,scope_identity='b'*64,
                    training_input_sha256=model.named_steps['hazards'].training_input_sha256_))
    expected=[]
    for arm in arms:
        mean=np.stack([raw[s,arm] for s in seeds]).mean(0)
        row=dict(arm=arm,ad_oof_sha256=prediction_hash(mean[:,0]),other_oof_sha256=prediction_hash(mean[:,1]),
            pooled_oof_brier=float(np.mean(weights*(target-mean[:,0])**2)),per_seed=[])
        for seed in seeds:
            row['per_seed'].append(dict(seed=seed,ad_oof_sha256=prediction_hash(raw[seed,arm][:,0]),
                other_oof_sha256=prediction_hash(raw[seed,arm][:,1]),
                pooled_oof_brier=float(np.mean(weights*(target-raw[seed,arm][:,0])**2))))
        expected.append(row)
    def loader(arm,seed,fold):
        AUDIT['old_fixture_loads']+=1
        return models.pop((fold,seed,arm))
    return frame,splits,candidates,dict(models=expected),loader,models


def mock_fit(frame,arm,seed,*,budget):
    budget.reserve();budget.attempts+=1;AUDIT['mock_fit_attempts']+=1;budget.event('FIT_ATTEMPT',arm,seed)
    model=Model(frame,arm,seed,perturbed=True)
    budget.fit_returns+=1;AUDIT['mock_fit_returns']+=1;budget.event('FIT_RETURN',arm,seed)
    budget.completed+=1;AUDIT['mock_fits_accepted']+=1;budget.event('FIT_COMPLETE',arm,seed)
    return model


class StreamingTests(unittest.TestCase):
    def invoke(self,fixture_values=None,**kwargs):
        frame,splits,candidates,expected,loader,_=fixture_values or fixture()
        opts=dict(expected_input_fingerprint=input_fingerprint(frame,'multimodal_values'),
            expected_subjects=90,constructed=True,fitter=mock_fit)
        opts.update(kwargs)
        with patch.object(ex,'model_identity',new=identity),patch.object(ex,'checked_cif',new=analytic_cif),\
                patch.object(GraphHazards,'fit',side_effect=AssertionError('OPTIMIZER_FORBIDDEN')):
            return ex.evaluate_training_perturbations(frame,splits,loader,candidates,expected,**opts)

    def test_full_225_slot_matrix_once(self):
        global FULL_RESULT
        values=fixture();frame,splits,candidates,expected,loader,remaining=values
        frame_before=frame.copy(deep=True);events=[];refs=[];loads=[];new_values={}
        def load(arm,seed,fold):
            model=loader(arm,seed,fold);refs.append(weakref.ref(model));loads.append((fold,seed,arm));return model
        def fit(part,arm,seed,*,budget):
            self.assertEqual(len(loads),45)
            self.assertTrue(any(e['phase']=='ALL_24_HASH_12_BRIER_GATES_PASSED' for e in events))
            gc.collect()
            self.assertTrue(all(ref() is None for ref in refs[45:]))
            slot=len(refs)-45;fold=slot//45+1;replicate=(slot%45)//9+1
            model=mock_fit(part,arm,seed,budget=budget);refs.append(weakref.ref(model))
            new_values[fold,replicate,arm,seed]=analytic_values(model,frame.iloc[splits[fold-1][1]])
            return model
        def progress(event):
            if event['phase'] in ('ALL_24_HASH_12_BRIER_GATES_PASSED','FOLD_COMPLETE','COMPLETE'):events.append(event)
        before=AUDIT.copy()
        FULL_RESULT=self.invoke((frame,splits,candidates,expected,load,remaining),fitter=fit,progress=progress)
        AUDIT['full_matrix_runs']+=1;result=FULL_RESULT
        self.assertEqual(result['status'],'CONSTRUCTED_GRAPH_PERTURBATION_MATRIX_COMPLETE')
        self.assertEqual((result['fit_attempts'],result['fit_returns'],result['fits_completed']),(225,225,225))
        self.assertIsNone(result['predictive_fits']);self.assertEqual(result['calibration_fits'],0)
        self.assertEqual((result['single_model_games'],result['ensemble_games']),(720,240))
        self.assertEqual((result['cif_attempts'],result['cif_completed']),(12780,12780))
        self.assertEqual(result['cif_state_rows_completed'],230040)
        self.assertEqual(result['internal_month_rows'],5520960)
        self.assertEqual((result['baseline_hashes_verified'],result['baseline_briers_verified']),(24,12))
        self.assertEqual(AUDIT['analytic_cif_callbacks']-before['analytic_cif_callbacks'],12780)
        self.assertEqual(AUDIT['mock_fit_attempts']-before['mock_fit_attempts'],225)
        self.assertTrue(frame.equals(frame_before));self.assertFalse(remaining)
        gc.collect();self.assertTrue(all(ref() is None for ref in refs))
        self.assertLessEqual(result['signed_decomposition_max_error'],1e-12)
        self.assertLessEqual(result['reconstruction_max_error'],1e-12)
        rows={tuple(row['key']):row['distribution'] for row in result['distributions']}
        for key,value in rows.items():
            if key[0] in ('risk_drift','signed_risk_change','L1','paired_risk_drift','paired_model_L1'):
                self.assertEqual(value['n'],90,key)
            if key[0]=='rank':
                _,comp,cause,metric,r,arm,order=key
                eligibility=rows['rank_common_eligibility',comp,cause,metric]
                self.assertEqual(value['n'],round(eligibility['mean']*90))
        spec=ex.graph.protocol()[0]
        weights=np.zeros(90)
        for a,b in splits:weights[b]=horizon_ipcw_weights(frame.iloc[b],censor_survival(frame.iloc[a]))
        target=frame.event_type.eq(1).to_numpy(float)
        for r in range(1,6):
            for arm in spec['arms']:
                cif=np.zeros((90,2));wrong=np.zeros(90)
                for fold,(_,b) in enumerate(splits,1):
                    seed_cifs=np.stack([new_values[fold,r,arm,s] for s in spec['future_initialization_seeds']])
                    cif[b]=seed_cifs.mean(0)
                    wrong[b]=np.mean((target[b]-seed_cifs[:,:,0])**2,axis=0)
                correct=float(np.mean(weights*(target-cif[:,0])**2))
                self.assertAlmostEqual(rows['AD_Brier',arm,r]['mean'],correct,places=15)
                self.assertGreater(float(np.mean(weights*wrong))-correct,0)
        self.assertEqual([e['phase'] for e in events].count('FOLD_COMPLETE'),5)
        # Analytic fixture arms coincide: stability cannot claim a strict benefit.
        self.assertFalse(result['empirical_AD_prediction_drift_lower_in_all_fixed_comparisons'])
        self.assertFalse(result['empirical_AD_model_contribution_L1_lower_in_all_fixed_comparisons_and_orders'])
        encoded=json.dumps(result,allow_nan=False)
        for subject in frame.subject_id:self.assertNotIn(subject,encoded)
        self.assertNotIn('model_identity',encoded)

    def test_signed_seed_mean_precedes_absolute_value(self):
        x=np.zeros((3,4,2,2,9,2));x[:,1]=np.array([-1,1,0])[:,None,None,None,None]
        _,parts,_=ex._ensemble_games(x)
        self.assertEqual(np.abs(parts['model']).sum(),0)
        self.assertGreater(np.abs(x[:,1]-x[:,0]).mean(0).sum(),0)

    def test_four_game_interaction_exact_identity(self):
        x=np.random.default_rng(9).normal(size=(3,4,2,4,9,2))
        games,parts,error=ex._ensemble_games(x)
        np.testing.assert_allclose(parts['interaction'],games[3]-games[1]-games[2]+games[0],rtol=0,atol=0)
        self.assertLessEqual(error,1e-12)
        for shape in ((2,4,2,4,9,2),(3,4,2,4,8,2)):
            with self.assertRaises(ValueError):ex._ensemble_games(np.zeros(shape))

    def test_rank_common_denominators_include_all_repeats_and_orders(self):
        rows=[dict(spearman=np.ones(4),top1=np.ones(4),top2=np.ones(4)) for _ in range(30)]
        rows[-1]['spearman'][0]=np.nan;rows[-2]['top1'][1]=np.nan;rows[-3]['top2'][2]=np.nan
        masks=ex._fixed_rank_masks(rows)
        np.testing.assert_array_equal(masks['spearman'],[0,1,1,1])
        np.testing.assert_array_equal(masks['top1'],[1,0,1,1])
        np.testing.assert_array_equal(masks['top2'],[1,1,0,1])

    def test_other_cause_hash_failure_before_first_fit(self):
        values=fixture();values[3]['models'][0]['per_seed'][0]['other_oof_sha256']='0'*64
        before=AUDIT['mock_fit_attempts'];events=[]
        with self.assertRaisesRegex(ValueError,'TWO_CAUSE'):self.invoke(values,progress=events.append)
        self.assertEqual(AUDIT['mock_fit_attempts'],before)
        self.assertEqual(events[-1]['phase'],'FAILED_NO_RETRY')

    def test_seed_brier_failure_before_first_fit(self):
        values=fixture();values[3]['models'][0]['per_seed'][0]['pooled_oof_brier']+=.01
        with self.assertRaisesRegex(ValueError,'SEED_BRIER'):self.invoke(values,fitter=unittest.mock.Mock())

    def test_nan_brier_rejected(self):
        values=fixture();values[3]['models'][0]['pooled_oof_brier']=float('nan')
        with self.assertRaisesRegex(ValueError,'ENSEMBLE_BRIER'):self.invoke(values)

    def test_wrong_role_rejected_without_loads(self):
        values=fixture();values[2][0]['role']='GRAPH_FINAL';before=AUDIT['old_fixture_loads']
        with self.assertRaisesRegex(ValueError,'CANDIDATE'):self.invoke(values)
        self.assertEqual(before,AUDIT['old_fixture_loads'])

    def test_wrong_binding_rejected_before_fit(self):
        values=fixture();values[2][0]['training_input_sha256']='0'*64
        with self.assertRaisesRegex(ValueError,'TRAINING_BINDING'):self.invoke(values)

    def test_input_identity_and_fold_leak_rejected(self):
        values=fixture()
        with self.assertRaisesRegex(ValueError,'INPUT_BINDING'):self.invoke(values,expected_input_fingerprint='0'*64)
        a,b=values[1][0];values[1][0]=(np.r_[a,b[0]],b)
        with self.assertRaisesRegex(ValueError,'LEAKING'):self.invoke(values)

    def test_old_model_reuse_rejected_before_fit(self):
        values=list(fixture());model=next(iter(values[-1].values()));values[4]=lambda *_:model
        with self.assertRaisesRegex(ValueError,'MODEL_REUSED|ROLE_CHANGED'):self.invoke(values)

    def test_CIF_budget_stops_before_next_callback(self):
        values=fixture();events=[];before=AUDIT['analytic_cif_callbacks']
        with self.assertRaisesRegex(ValueError,'CIF_BUDGET'):self.invoke(values,max_cif_calls=2,progress=events.append)
        self.assertEqual(AUDIT['analytic_cif_callbacks']-before,2)
        self.assertEqual(events[-1]['cif_attempts'],2);self.assertEqual(events[-1]['fit_attempts'],0)

    def test_state_row_budget_stops_before_callback(self):
        values=fixture();before=AUDIT['analytic_cif_callbacks']
        with self.assertRaisesRegex(ValueError,'CIF_BUDGET'):self.invoke(values,max_state_rows=17)
        self.assertEqual(AUDIT['analytic_cif_callbacks'],before)

    def test_CIF_failure_counts_attempt_without_completion_and_no_retry(self):
        values=fixture();events=[]
        with patch.object(ex,'model_identity',side_effect=identity),patch.object(ex,'checked_cif',side_effect=RuntimeError('injected')):
            frame,splits,candidates,expected,loader,_=values
            with self.assertRaisesRegex(RuntimeError,'injected'):
                ex.evaluate_training_perturbations(frame,splits,loader,candidates,expected,
                    expected_input_fingerprint=input_fingerprint(frame,'multimodal_values'),expected_subjects=90,
                    constructed=True,fitter=mock_fit,progress=events.append)
        self.assertEqual((events[-1]['cif_attempts'],events[-1]['cif_completed']),(1,0))

    def test_fit_failure_preserves_attempt_and_never_retries(self):
        values=fixture();events=[];attempts=[]
        def fail(frame,arm,seed,*,budget):
            budget.reserve();budget.attempts+=1;attempts.append(1);raise RuntimeError('fit-injected')
        with self.assertRaisesRegex(RuntimeError,'fit-injected'):self.invoke(values,fitter=fail,progress=events.append)
        self.assertEqual(len(attempts),1)
        self.assertEqual((events[-1]['fit_attempts'],events[-1]['fit_returns'],events[-1]['fits_completed']),(1,0,0))

    def test_fit_callback_cannot_bypass_budget(self):
        values=fixture()
        with self.assertRaisesRegex(ValueError,'ACCOUNTING'):self.invoke(values,fitter=lambda part,arm,seed,**_:Model(part,arm,seed))

    def test_wrong_new_seed_and_subset_mutation_rejected(self):
        def wrong(part,arm,seed,*,budget):
            result=mock_fit(part,arm,seed,budget=budget);result.named_steps['hazards'].seed+=1;return result
        with self.assertRaisesRegex(ValueError,'ROLE_CHANGED'):self.invoke(fitter=wrong)
        def mutate(part,arm,seed,*,budget):
            result=mock_fit(part,arm,seed,budget=budget);part.iloc[0,part.columns.get_loc(FEATURES[0])]+=1;return result
        with self.assertRaisesRegex(ValueError,'SUBSET_MUTATED'):self.invoke(fitter=mutate)

    def test_resource_guard_and_invalid_limits(self):
        values=fixture()
        with patch.object(ex,'peak_memory_bytes',return_value=2147483649):
            with self.assertRaisesRegex(ValueError,'RESOURCE_LIMIT'):self.invoke(values)
        for kwargs in (dict(max_cif_calls=12781),dict(max_state_rows=2964961),dict(max_elapsed_seconds=21601),
                       dict(max_memory_bytes=2147483649),dict(constructed=False,fitter=mock_fit)):
            with self.assertRaisesRegex(ValueError,'SCOPE_OR_LIMITS'):self.invoke(values,**kwargs)

    def test_nine_subset_preprocessing_and_input_must_match(self):
        def wrong(part,arm,seed,*,budget):
            model=mock_fit(part,arm,seed,budget=budget)
            if seed==20260927:model.named_steps['hazards'].mean_[0]+=.1
            return model
        with self.assertRaisesRegex(ValueError,'PREPROCESSING_OR_INPUT_NOT_MATCHED'):self.invoke(fitter=wrong)

    def test_arm_initialization_and_batch_signature_must_match(self):
        def wrong(part,arm,seed,*,budget):
            model=mock_fit(part,arm,seed,budget=budget)
            if arm=='prior':model.named_steps['hazards'].batch_schedule_sha256_='different'
            return model
        with self.assertRaisesRegex(ValueError,'ARMS_NOT_MATCHED'):self.invoke(fitter=wrong)

    def test_progress_failure_stops_before_first_CIF(self):
        values=fixture();before=AUDIT['analytic_cif_callbacks']
        def fail(_event):raise RuntimeError('event-injected')
        with self.assertRaisesRegex(RuntimeError,'event-injected'):self.invoke(values,progress=fail)
        self.assertEqual(AUDIT['analytic_cif_callbacks'],before)


if __name__=='__main__':unittest.main()
