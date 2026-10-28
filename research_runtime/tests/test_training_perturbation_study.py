"""Full artificial callback matrix and adversarial failures; zero risk fits."""
from collections import Counter
from copy import deepcopy
import gc
import json
import unittest
import weakref
from unittest.mock import Mock,patch

import numpy as np
from sklearn.ensemble import RandomForestClassifier
from xgboost import XGBClassifier

from research_runtime import table_xai_study as study,table_explanation as table
from research_runtime.discrete import person_period
from research_runtime.tests import test_table_xai_study as fixtures
from research_runtime.tests.test_table_explanation import constructed_model,frame_for

MATRIX_ACCEPTANCE=None


class TrainingPerturbationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        fixtures.TableStudyTests.setUpClass();cls.fixture=fixtures.TableStudyTests

    def setUp(self):
        self.fit_calls=[];self.news=[];self.phases=Counter();self.last={};self.samples={}

    def record(self,event):
        self.phases[event['phase']]+=1;self.last=deepcopy(event)

    def baseline(self,model,part,spec,*,evaluation_event=None):
        if evaluation_event:evaluation_event('attempt',len(part))
        if hasattr(model,'_perturbation_key'):
            _,family,fold,rep=model._perturbation_key
        else:
            _,family,fold=self.fixture.model_keys[id(model)];rep=0
        values=self.fixture.raw[spec,family][part.index].copy();values[:,0]+=rep*.0005
        if evaluation_event:evaluation_event('complete',len(part))
        return values

    def fitter(self,subset,family,spec,seed,journal,stage,outer=None,inner=None):
        # This simulates the fitted state contract without any fit/optimizer.
        gc.collect()
        if any(ref() is not None for ref in self.news):raise AssertionError('SUBSET_MODEL_RETAINED')
        rep=len(self.fit_calls)%5+1
        journal.attempt('predictive',family=family,stage=stage,outer=outer)
        self.fit_calls.append((spec,family,outer,rep,seed))
        ids=tuple(subset.subject_id)
        key=(outer,rep)
        if key in self.samples:self.assertEqual(self.samples[key],ids)
        else:self.samples[key]=ids
        test=self.fixture.splits[outer-1][1]
        self.assertTrue(set(ids).isdisjoint(self.fixture.frame.iloc[test].subject_id))
        self.assertEqual(subset.index.tolist(),list(range(len(subset))))
        names=list(study.SPECS[spec]);periods,_=person_period(subset,names)
        x=periods[names].to_numpy(float);med=np.nanmedian(x,axis=0)
        imputed=np.where(np.isnan(x),med,x);ind=np.flatnonzero(subset[names].isna().any().to_numpy())
        model=constructed_model(spec);clinical=model.named_steps['preprocess'].named_transformers_['clinical']
        imputer=clinical.named_steps['impute'];imputer.statistics_=med
        imputer.indicator_.features_=ind
        scaler=clinical.named_steps['scale'];width=len(names)+len(ind)
        scaler.mean_=np.r_[imputed.mean(axis=0),np.isnan(x)[:,ind].mean(axis=0)]
        scaler.scale_=np.ones(width);scaler.var_=np.ones(width);scaler.n_features_in_=width
        if family!='logistic':
            model.named_steps['hazards'].estimator_=RandomForestClassifier() if family=='random_forest' else XGBClassifier()
        model._perturbation_key=(spec,family,outer,rep)
        self.news.append(weakref.ref(model))
        return model

    def shapley(self,model,part,spec,*,reference_values,max_coalitions,evaluation_event):
        p=self.baseline(model,part,spec);names=list(study.SPECS[spec]);groups=table.groups_for(spec)
        x=part[names].to_numpy(float);ref=np.asarray(reference_values)
        same=(x==ref)|np.isnan(x)
        active=np.column_stack([~same[:,list(cols)].all(axis=1) for _,cols in groups])
        coalitions=2**int(active.any(axis=0).sum())
        self.assertLessEqual(coalitions,max_coalitions)
        for _ in range(coalitions):evaluation_event('attempt',len(part));evaluation_event('complete',len(part))
        rep=getattr(model,'_perturbation_key',(None,None,None,0))[-1]
        values=np.zeros((len(part),len(groups),2))
        for j,(_,cols) in enumerate(groups):
            difference=np.nan_to_num(x[:,list(cols)]-ref[list(cols)],nan=0).mean(axis=1)
            values[:,j,0]=np.tanh(difference/10)*.001*(j+1)*(1+.01*rep)
            values[:,j,1]=values[:,j,0]*.05
        return dict(group_names=tuple(n for n,_ in groups),contributions=values,
                    prediction_cif=p,reference_cif=p-values.sum(axis=1),
                    cif_calls=coalitions,cif_state_rows=coalitions*len(part))

    def invoke(self,**changes):
        f=self.fixture
        options=dict(expected_input_fingerprint=f.fingerprint,expected_subjects=90,
                     fitter=self.fitter,shapley=self.shapley,constructed=True,progress=self.record)
        options.update(changes)
        expected=options.pop('expected',f.expected);candidates=options.pop('candidates',f.candidates)
        loader=options.pop('loader',lambda s,family,k:self.fixture.bundles[s,family,k])
        with patch.object(study,'checked_baseline',self.baseline):
            return study.evaluate_training_perturbations(f.frame,f.splits,loader,candidates,expected,**options)

    def test_full_150_480_matrix_aggregate_denominators_and_streamed_models(self):
        global MATRIX_ACCEPTANCE
        result=self.invoke();counts=result['operations']
        self.assertEqual((counts['model_loads_completed'],counts['predictive_fit_attempts'],
                          counts['predictive_fit_callbacks_completed'],counts['shapley_games_completed']),(30,150,150,480))
        self.assertEqual(counts['cif_attempts'],counts['cif_completed'])
        self.assertEqual(counts['cif_state_rows_attempted'],counts['cif_state_rows_completed'])
        self.assertLessEqual(counts['cif_attempts'],153780)
        self.assertEqual(len(self.fit_calls),150);self.assertEqual(len(self.samples),25)
        self.assertTrue(all(seed==20260926+fold-1 for _,_,fold,_,seed in self.fit_calls))
        self.assertEqual((self.phases['ALL_SIX_AD_OOF_VERIFIED'],self.phases['GAME_COMPLETE'],
                          self.phases['FOLD_MODEL_COMPLETE'],self.phases['COMPLETE']),(1,480,30,1))
        self.assertEqual(len(result['model_summaries']),6)
        self.assertEqual(len(result['fold_diagnostics']),30)
        for model in result['model_summaries']:
            self.assertEqual((model['unique_subjects'],model['subject_replicate_pairs']),(90,450))
            self.assertEqual((len(model['replicates']),len(model['common_eligibility'])),(10,6))
            for row in model['replicates']:
                self.assertEqual(set(row['contrast_signed_L1']),{'M','R','I','T'})
                for comparison in row['rank_sensitivity']:
                    self.assertEqual(comparison['subjects'],90)
                    for metric in ('spearman','top1','top2'):
                        self.assertEqual(comparison[metric]['eligible_subjects']+comparison[metric]['undefined_subjects'],90)
                        common=next(r for r in model['common_eligibility'] if r['comparison']==comparison['comparison'] and r['cause']==row['cause'])
                        self.assertEqual(comparison['all_repeat_common_active_and_qualified'][metric]['n'],
                                         common['all_repeat_common_active_and_qualified'][metric])
        self.assertLessEqual(result['signed_decomposition_max_error'],1e-12)
        self.assertLessEqual(result['shapley_reconstruction_max_error'],1e-12)
        text=json.dumps(result,allow_nan=False)
        for forbidden in ('subject_id','SYNTHETIC-','prediction_cif','contributions','train_positions','test_positions'):
            self.assertNotIn(forbidden,text)
        self.assertIsNone(result['predictive_fits']);self.assertTrue(result['constructed_callbacks'])
        gc.collect();self.assertTrue(all(r() is None for r in self.news))
        MATRIX_ACCEPTANCE=result

    def test_original_OOF_and_Brier_gate_before_fit_or_game(self):
        for field,value in (('oof_prediction_sha256','0'*64),('pooled_oof_brier',.99)):
            expected=deepcopy(self.fixture.expected);expected['seven']['logistic'][field]=value
            fit=Mock();shap=Mock()
            with self.assertRaisesRegex(ValueError,'AD_OOF|AD_BRIER'):
                self.invoke(expected=expected,fitter=fit,shapley=shap)
            fit.assert_not_called();shap.assert_not_called()

    def test_fit_exception_attempt_recorded_once_no_retry(self):
        fit=Mock(side_effect=RuntimeError('ARTIFICIAL_FIT_FAILURE'))
        with self.assertRaisesRegex(RuntimeError,'ARTIFICIAL_FIT_FAILURE'):self.invoke(fitter=fit)
        self.assertEqual(fit.call_count,1);self.assertEqual(self.phases['FAILED'],1)
        self.assertEqual(self.last['operations']['predictive_fit_attempts'],1)
        self.assertEqual(self.last['operations']['predictive_fit_callbacks_completed'],0)
        self.assertEqual(self.phases['COMPLETE'],0)

    def test_budget_and_resource_fail_before_callbacks(self):
        for changes in ({'max_cif_calls':30},{'max_state_rows':540},{'max_predictive_fits':0}):
            fit=Mock();shap=Mock(side_effect=self.shapley)
            with self.assertRaisesRegex(ValueError,'BUDGET'):self.invoke(fitter=fit,shapley=shap,**changes)
            fit.assert_not_called()
        loader=Mock()
        with self.assertRaisesRegex(ValueError,'TIME_OR_MEMORY'):self.invoke(loader=loader,max_memory_bytes=0)
        loader.assert_not_called()

    def test_missing_fit_journal_or_training_mutation_rejected(self):
        for mode in ('journal','input','statistics','reuse'):
            def fitter(subset,family,spec,seed,journal,stage,outer=None):
                if mode=='reuse':
                    journal.attempt('predictive');return self.fixture.bundles[spec,family,outer]['model']
                if mode=='journal':return constructed_model(spec)
                model=self.fitter(subset,family,spec,seed,journal,stage,outer)
                if mode=='input':subset.iloc[0,subset.columns.get_loc('age_years')]+=1
                else:model.named_steps['preprocess'].named_transformers_['clinical'].named_steps['scale'].mean_[0]+=1
                return model
            with self.subTest(mode=mode),self.assertRaisesRegex(ValueError,'FIT_INPUT|TRAINING_STATISTICS|MODEL_REUSE'):
                self.invoke(fitter=fitter)
            self.news=[];self.fit_calls=[]

    def test_bad_game_counts_prediction_or_missing_dummy_rejected(self):
        for mode in ('counts','prediction','reconstruction','groups'):
            def shap(*args,**kwargs):
                result=self.shapley(*args,**kwargs)
                if mode=='counts':result['cif_calls']-=1
                elif mode=='prediction':result['prediction_cif']+=.01
                elif mode=='reconstruction':result['reference_cif']+=.01
                else:result['group_names']=tuple(reversed(result['group_names']))
                return result
            with self.subTest(mode=mode),self.assertRaisesRegex(ValueError,'ACCOUNTING|PREDICTION|RECONSTRUCTION'):
                self.invoke(shapley=shap)

    def test_CIF_failure_keeps_attempt_without_completion(self):
        def failed_game(*args,**kwargs):
            kwargs['evaluation_event']('attempt',len(args[1]))
            raise RuntimeError('ARTIFICIAL_CIF_FAILURE')
        with self.assertRaisesRegex(RuntimeError,'ARTIFICIAL_CIF_FAILURE'):self.invoke(shapley=failed_game)
        counts=self.last['operations']
        self.assertEqual(counts['cif_attempts'],31);self.assertEqual(counts['cif_completed'],30)
        self.assertEqual(counts['predictive_fit_attempts'],0)

    def test_rank_policy_top2_internal_tie_boundary_tie_and_common_active(self):
        left=np.asarray([[4.,4.,1.,0.],[4.,2.,2.,0.],[4.,3.,2.,1.],[4.,3.,2.,1.]])
        right=left.copy();right[2]=[1.,2.,3.,4.]
        active=np.ones_like(left,dtype=bool);active[3,2:]=False
        row=study._rank_rows(left,right,active);summary=study._rank_summary(row)
        self.assertTrue(np.isnan(row['top1'][0]));self.assertEqual(row['top2'][0],1.)
        self.assertTrue(np.isnan(row['top2'][1]));self.assertEqual(row['top2'][2],0.)
        self.assertTrue(row['insufficient'][3]);self.assertEqual(summary['subjects'],4)
        old=study.paired(left,right,active)
        self.assertEqual(summary['top1']['eligible_subjects'],old['top_rank_eligible'])
        self.assertEqual(summary['spearman']['distribution'],old['spearman'])

    def test_evaluation_hooks_surround_actual_CIF_and_stop_before_prediction(self):
        model=constructed_model();frame=frame_for(model,rows=1);phases=[]
        value=table.explain_shapley(model,frame,'seven',evaluation_event=lambda phase,n:phases.append((phase,n)))
        self.assertEqual(len(phases),2*value['cif_calls'])
        self.assertTrue(all(phases[i:i+2]==[('attempt',1),('complete',1)] for i in range(0,len(phases),2)))
        with patch.object(table,'predict_cif',side_effect=AssertionError('NO_PREDICT')) as predict:
            with self.assertRaisesRegex(RuntimeError,'STOP_BEFORE_CIF'):
                table.explain_shapley(model,frame,'seven',evaluation_event=lambda *args:(_ for _ in ()).throw(RuntimeError('STOP_BEFORE_CIF')))
            predict.assert_not_called()

    def test_limits_cannot_expand_locked_design(self):
        for changes in ({'max_predictive_fits':151},{'max_cif_calls':153781},{'max_elapsed_seconds':21601},
                        {'max_memory_bytes':2147483649},{'max_cif_calls':True}):
            with self.assertRaisesRegex(ValueError,'LIMIT'):self.invoke(**changes)

    def test_repeated_memory_checks_reuse_native_structure_type(self):
        import os,ctypes
        before=len(ctypes._pointer_type_cache)
        measured=[study.peak_memory_bytes() for _ in range(2000)]
        self.assertTrue(all(type(value) is int and value>0 for value in measured))
        if os.name=='nt':self.assertEqual(len(ctypes._pointer_type_cache),before)


if __name__=='__main__':unittest.main()
