"""45 analytic OOF slots, no clinical rows, model files or optimization."""
from contextlib import ExitStack
from copy import deepcopy
import gc
import json
import unittest
from unittest.mock import Mock, patch
import weakref

import numpy as np
from sklearn.pipeline import Pipeline
from sklearn.impute import SimpleImputer
from sklearn.preprocessing import StandardScaler
from sklearn.linear_model import LogisticRegression
from sklearn.ensemble import RandomForestClassifier
from xgboost import XGBClassifier

from research_runtime import table_xai_study as study
from research_runtime.decision_curve import CurveProtocol
from research_runtime.training import synthetic_cohort, checked_design, input_fingerprint, prediction_hash
from research_runtime.tests.test_table_xai_study import bundle_for
from research_runtime.tests.test_table_explanation import constructed_model, frame_for


class ThresholdStudyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.template,cls.splits,_=checked_design(synthetic_cohort(),'multimodal_values')
        cls.fingerprint=input_fingerprint(cls.template,'multimodal_values')
        cls.candidates=[];cls.bundles={};cls.model_keys={};cls.probabilities={}
        cls.weights=np.empty(len(cls.template))
        for fold,(train,test) in enumerate(cls.splits,1):
            cls.weights[test]=study.horizon_ipcw_weights(cls.template.iloc[test],study.censor_survival(cls.template.iloc[train]))
            for si,spec in enumerate(study.THRESHOLD_SPECIFICATIONS):
                for fi,family in enumerate(study.FAMILIES):
                    row,bundle=bundle_for(spec,family,fold)
                    cls.candidates.append(row);cls.bundles[spec,family,fold]=bundle
                    cls.model_keys[id(bundle['model'])]=(spec,family,fold)
                    cls.probabilities[spec,family]=.12+si*.01+fi*.002+np.arange(90)*.00001

    def setUp(self):
        self.frame=self.template.copy(deep=True)
        self.expected=self.expectations(self.probabilities)
        self.protocol=CurveProtocol('synthetic-v1','synthetic-first-AD-24m',study.THRESHOLDS)
        self.private_folds=[dict(train_positions=a.tolist(),test_positions=b.tolist(),
            train_subjects=self.template.iloc[a].subject_id.tolist(),test_subjects=self.template.iloc[b].subject_id.tolist())
            for a,b in self.splits]

    def expectations(self,values):
        expected={s:{'historical_other_cause_OOF_hash':None} for s in study.THRESHOLD_SPECIFICATIONS}
        target=self.template.event_type.eq(1).to_numpy(float)
        for (spec,family),p in values.items():
            expected[spec][family]=dict(oof_prediction_sha256=prediction_hash(p),
                                      pooled_oof_brier=float(np.mean(self.weights*(target-p)**2)))
        return expected

    def analytic(self,model,part,spec):
        _,family,_=self.model_keys[id(model)]
        expected=study.information_arm(self.frame.loc[part.index],spec)
        self.assertTrue(part.equals(expected))
        return np.column_stack((self.probabilities[spec,family][part.index],np.full(len(part),.02)))

    def invoke(self,**changes):
        options=dict(protocol=self.protocol,expected_input_fingerprint=self.fingerprint,
                     original_folds=self.private_folds,expected_subjects=90)
        loader=changes.pop('loader',lambda s,f,k:self.bundles[s,f,k])
        candidates=changes.pop('candidates',self.candidates)
        expected=changes.pop('expected',self.expected)
        baseline=changes.pop('baseline',self.analytic)
        splits=changes.pop('splits',self.splits)
        options.update(changes)
        with patch.object(study,'checked_baseline',side_effect=baseline):
            return study.evaluate_thresholds(self.frame,splits,loader,candidates,expected,**options)

    def test_complete_45_matrix_nine_gates_before_three_family_reports_and_no_fits(self):
        events=[];loaded=[]
        def loader(s,f,k):loaded.append((s,f,k));return self.bundles[s,f,k]
        original=study.evaluate_decision_curves
        def evaluator(*args):
            self.assertEqual(events[-1]['phase'],'ALL_NINE_AD_OOF_VERIFIED')
            return original(*args)
        with ExitStack() as stack:
            for cls in (Pipeline,SimpleImputer,StandardScaler,LogisticRegression,RandomForestClassifier,XGBClassifier):
                stack.enter_context(patch.object(cls,'fit',side_effect=AssertionError('unexpected fit')))
            stack.enter_context(patch.object(study,'fit_base',side_effect=AssertionError('unexpected fit_base')))
            stack.enter_context(patch.object(study.table,'explain_shapley',side_effect=AssertionError('attribution')))
            stack.enter_context(patch.object(study.table,'explain_lime',side_effect=AssertionError('local fit')))
            callback=stack.enter_context(patch.object(study,'evaluate_decision_curves',side_effect=evaluator))
            result=self.invoke(loader=loader,progress=events.append)
        self.assertEqual((len(loaded),len(set(loaded)),callback.call_count),(45,45,3))
        self.assertEqual(result['operations'],dict(model_load_attempts=45,model_load_returns=45,model_loads_completed=45,
            cif_attempts=45,cif_returns=45,cif_completed=45,cif_state_rows_attempted=810,cif_state_rows_completed=810,
            training_KM_replays=5,inference_person_months=19440))
        self.assertEqual(result['privacyStatus'],'JOINT_CELLS_AT_LEAST_FIVE')
        self.assertEqual(result['historical_AD_OOF_and_Brier_gates'],9)
        self.assertEqual(events[45]['phase'],'ALL_NINE_AD_OOF_VERIFIED')
        self.assertFalse(result['historical_other_cause_OOF_hash_verified'])
        self.assertEqual(sum(len(v) for r in result['familyResults'].values() for v in r['curves'].values()),54)
        self.assertEqual(sum(len(p['curves']) for r in result['familyResults'].values() for p in r['pairedComparisons']),54)
        encoded=json.dumps(result,allow_nan=False)
        self.assertNotIn('SYNTHETIC-',encoded)
        self.assertNotIn('oof_prediction_sha256',encoded)
        self.assertEqual(result['fits'],0)
        self.assertTrue(self.frame.equals(self.template))

    def test_availability_explicit_opt_in_identity_and_zeroing_missingness(self):
        row,bundle=bundle_for('modality_availability','logistic',1)
        with self.assertRaisesRegex(ValueError,'OUTER_CANDIDATE'):study.checked_outer_bundle(bundle,row)
        self.assertIs(study.checked_outer_bundle(bundle,row,allow_availability=True),bundle['model'])
        for key,value in (('arm','multimodal_values'),('prediction_policy','calibrated'),('retrospective_only',False),
                          ('role','MULTIMODAL_FULL_COHORT_RESEARCH'),('outer_fold',True),('columns',[])):
            bad=dict(bundle);bad[key]=value
            with self.assertRaises(ValueError):study.checked_outer_bundle(bad,row,allow_availability=True)
        self.assertEqual(study.SPECIFICATIONS,('seven','multimodal_values'))
        transformed=study.information_arm(self.frame,'modality_availability')
        for name in study.SPECS['multimodal_values'][7:]:
            np.testing.assert_array_equal(transformed[name].isna(),self.frame[name].isna())
            self.assertTrue(transformed.loc[self.frame[name].notna(),name].eq(0).all())
        self.invoke()  # Analytic callback independently checks all 45 input batches.

    def test_missing_duplicate_wrong_role_and_malformed_expectation_fail_before_load(self):
        loader=Mock()
        bads=[self.candidates[:-1],self.candidates[:-1]+[self.candidates[0]]]
        wrong=deepcopy(self.candidates);wrong[0]['role']='FULL_MODEL';bads.append(wrong)
        for candidates in bads:
            with self.assertRaises(ValueError):self.invoke(loader=loader,candidates=candidates)
        for field,value in (('oof_prediction_sha256','not-a-hash'),('pooled_oof_brier',float('nan')),
                            ('pooled_oof_brier',10**1000)):
            expected=deepcopy(self.expected);expected['seven']['logistic'][field]=value
            with self.assertRaisesRegex(ValueError,'NINE_EXPECTATIONS'):self.invoke(loader=loader,expected=expected)
        loader.assert_not_called()

    def test_bad_oof_or_brier_cannot_invoke_metrics_or_publish_partial_table(self):
        for field,value in (('oof_prediction_sha256','0'*64),('pooled_oof_brier',.9)):
            expected=deepcopy(self.expected);expected['multimodal_values']['xgboost'][field]=value
            events=[]
            with patch.object(study,'evaluate_decision_curves') as report:
                with self.assertRaisesRegex(ValueError,'AD_OOF_CHANGED|AD_BRIER_CHANGED'):
                    self.invoke(expected=expected,progress=events.append)
            report.assert_not_called()
            self.assertEqual(events[-1]['phase'],'FAILED')
            self.assertEqual(events[-1]['cif_completed'],45)
            self.assertFalse(any(e['phase']=='ALL_NINE_AD_OOF_VERIFIED' for e in events))

    def test_original_fold_leak_repeat_missing_and_wrong_input_rejected_before_load(self):
        loader=Mock()
        bad=deepcopy(self.splits);bad[1]=bad[0]
        for split in (None,bad,self.splits[:-1]):
            with self.assertRaises(ValueError):self.invoke(loader=loader,splits=split)
        with self.assertRaisesRegex(ValueError,'INPUT_FINGERPRINT'):
            self.invoke(loader=loader,expected_input_fingerprint='0'*64)
        loader.assert_not_called()

    def test_valid_partition_cannot_replace_original_fold_order_or_identity(self):
        loader=Mock()
        with self.assertRaisesRegex(ValueError,'ORIGINAL_FOLD_SEQUENCE'):
            self.invoke(loader=loader,splits=list(reversed(self.splits)))
        wrong=deepcopy(self.private_folds);wrong[0]['test_subjects'][0]='WRONG-IDENTITY'
        with self.assertRaisesRegex(ValueError,'ORIGINAL_FOLD_SUBJECTS'):
            self.invoke(loader=loader,original_folds=wrong)
        loader.assert_not_called()

    def test_loading_failure_has_attempt_without_completion_and_sanitized_error(self):
        events=[];loader=Mock(side_effect=RuntimeError('SYNTHETIC-PRIVATE-DETAIL'))
        with self.assertRaisesRegex(ValueError,'^THRESHOLD_MODEL_LOAD_FAILED$'):
            self.invoke(loader=loader,progress=events.append)
        self.assertEqual(loader.call_count,1)
        self.assertEqual((events[-1]['model_load_attempts'],events[-1]['model_loads_completed'],events[-1]['cif_attempts']),(1,0,0))

    def test_prediction_failure_or_bad_probability_stops_once_without_metrics(self):
        for output in (RuntimeError('private detail'),np.zeros((1,2)),np.tile([np.nan,.02],(18,1)),
                       np.tile([.9,.2],(18,1)),np.tile([-.1,.1],(18,1))):
            events=[]
            def predictor(*args):
                if isinstance(output,Exception):raise output
                return output
            with patch.object(study,'evaluate_decision_curves') as report:
                with self.assertRaisesRegex(ValueError,'CIF_FAILED|TWO_CAUSE_CIF'):
                    self.invoke(baseline=predictor,progress=events.append)
            report.assert_not_called()
            self.assertEqual((events[-1]['cif_attempts'],events[-1]['cif_completed']),(1,0))

    def test_callback_input_and_parameter_side_effects_rejected(self):
        for target in ('part','model','caller'):
            bundles=deepcopy(self.bundles)
            def predictor(model,part,spec):
                if target=='part':part.iloc[0,0]=999
                elif target=='model':model.named_steps['hazards'].estimator_.marker_=99
                else:self.frame.iloc[0,0]=999
                return np.tile([.1,.02],(len(part),1))
            with self.assertRaisesRegex(ValueError,'SIDE_EFFECT|CALLER_INPUT_MUTATED'):
                self.invoke(loader=lambda s,f,k:bundles[s,f,k],baseline=predictor)
            self.frame=self.template.copy(deep=True)

    def test_model_or_preprocessor_reuse_rejected(self):
        bundles=deepcopy(self.bundles)
        bundles['seven','random_forest',1]['model'].steps[0]=('preprocess',bundles['seven','logistic',1]['model'].named_steps['preprocess'])
        with self.assertRaisesRegex(ValueError,'MODEL_OBJECT_REUSE'):
            self.invoke(loader=lambda s,f,k:bundles[s,f,k],baseline=lambda m,p,s:np.tile([.1,.02],(len(p),1)))

    def test_live_model_references_released_between_slots(self):
        refs=[]
        def loader(s,f,k):
            _,bundle=bundle_for(s,f,k);model=bundle['model']
            refs.append(weakref.ref(model))
            self.model_keys[id(model)]=(s,f,k)
            return bundle
        self.invoke(loader=loader)
        gc.collect()
        self.assertTrue(all(ref() is None for ref in refs))

    def test_resources_and_unplanned_thresholds_stop_before_load(self):
        loader=Mock()
        for change in (dict(max_memory_bytes=0),dict(max_elapsed_seconds=0),
                       dict(max_elapsed_seconds=10**1000),
                       dict(protocol=CurveProtocol('synthetic-v1','synthetic-first-AD-24m',tuple(np.float64(x) for x in study.THRESHOLDS))),
                       dict(protocol=CurveProtocol('synthetic-v1','synthetic-first-AD-24m',(.1,.2)))):
            with self.assertRaises(ValueError):self.invoke(loader=loader,**change)
        loader.assert_not_called()

    def test_elapsed_limit_rechecked_after_prediction(self):
        clock=[0.];events=[]
        def predictor(model,part,spec):
            clock[0]=1201.
            return self.analytic(model,part,spec)
        with patch.object(study.time,'perf_counter',side_effect=lambda:clock[0]):
            with self.assertRaisesRegex(ValueError,'TIME_OR_MEMORY_LIMIT'):
                self.invoke(baseline=predictor,progress=events.append)
        self.assertEqual((events[-1]['cif_attempts'],events[-1]['cif_completed']),(1,0))

    def test_metadata_rejection_distinguishes_loader_return_from_accepted_bundle(self):
        events=[]
        def loader(s,f,k):return dict(self.bundles[s,f,k],outer_fold=5)
        with self.assertRaisesRegex(ValueError,'OUTER_IDENTITY'):
            self.invoke(loader=loader,progress=events.append)
        self.assertEqual((events[-1]['model_load_attempts'],events[-1]['model_load_returns'],
                          events[-1]['model_loads_completed'],events[-1]['cif_attempts']),(1,1,0,0))

    def test_small_joint_cell_suppresses_entire_report_not_just_count(self):
        values={k:v.copy() for k,v in self.probabilities.items()}
        values['multimodal_values','logistic'][1]=.6
        self.expected=self.expectations(values)
        def predictor(model,part,spec):
            _,family,_=self.model_keys[id(model)]
            return np.column_stack((values[spec,family][part.index],np.full(len(part),.02)))
        with patch.object(study,'evaluate_decision_curves') as report:
            result=self.invoke(baseline=predictor)
        report.assert_not_called()
        self.assertEqual(result['privacyStatus'],'SUPPRESSED')
        self.assertIsNone(result['familyResults'])
        self.assertNotIn('knownADEvents',json.dumps(result))

    def test_cross_threshold_difference_detected_with_safe_marginal_counts(self):
        values={k:v.copy() for k,v in self.probabilities.items()};ad=np.flatnonzero(self.frame.event_type.eq(1))
        p=values['seven','logistic'];p[ad[:10]]=.07;p[ad[10:11]]=.15;p[ad[11:]]=.25
        for threshold in study.THRESHOLDS:
            n=int(np.sum(p[ad]>=threshold))
            self.assertTrue(n==0 or n>=5);self.assertTrue(len(ad)-n==0 or len(ad)-n>=5)
        self.expected=self.expectations(values)
        def predictor(model,part,spec):
            _,family,_=self.model_keys[id(model)]
            return np.column_stack((values[spec,family][part.index],np.full(len(part),.02)))
        self.assertEqual(self.invoke(baseline=predictor)['privacyStatus'],'SUPPRESSED')

    def test_cross_algorithm_intersection_detected_beyond_per_family_suppression(self):
        values={k:v.copy() for k,v in self.probabilities.items()};ad=np.flatnonzero(self.frame.event_type.eq(1))
        a=values['seven','logistic'];b=values['seven','random_forest']
        a[ad]=.01;b[ad]=.01;a[ad[:15]]=.6;b[np.r_[ad[:1],ad[16:]]]=.6
        self.assertEqual(int(np.sum((a[ad]>=.5)&(b[ad]>=.5))),1)
        self.assertEqual(int(np.sum(a[ad]>=.5)),15);self.assertEqual(int(np.sum(b[ad]>=.5)),15)
        self.expected=self.expectations(values)
        def predictor(model,part,spec):
            _,family,_=self.model_keys[id(model)]
            return np.column_stack((values[spec,family][part.index],np.full(len(part),.02)))
        self.assertIsNone(self.invoke(baseline=predictor)['familyResults'])

    def test_progress_failure_does_not_return_partial_results(self):
        callback=Mock(side_effect=RuntimeError('private progress'))
        with self.assertRaisesRegex(ValueError,'^THRESHOLD_PROGRESS_FAILED$'):
            self.invoke(progress=callback)

    def test_real_cif_primitive_on_assigned_artificial_availability_state(self):
        # Two original CIF arithmetic calls, manual parameters only; no fit/load.
        model=constructed_model('multimodal_values')
        frame=frame_for(model,'multimodal_values')
        transformed=study.information_arm(frame,'modality_availability')
        a=study.checked_baseline(model,transformed,'modality_availability')
        b=study.checked_baseline(model,transformed,'multimodal_values')
        np.testing.assert_array_equal(a,b)
        self.assertTrue(frame.equals(frame_for(model,'multimodal_values')))


if __name__=='__main__':unittest.main()
