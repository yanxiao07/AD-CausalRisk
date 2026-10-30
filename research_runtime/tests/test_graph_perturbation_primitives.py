"""In-memory constant graph fixtures; no optimizer fits or source readers."""
from copy import deepcopy
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import numpy as np
import pandas as pd

from research_runtime import graph_explanation as ex, graph_training as train
from research_runtime.graph_native import FEATURES, GraphHazards, make_graph_estimator
from research_runtime.training import synthetic_cohort, checked_design, subject_subsample
from research_runtime.discrete import person_period
from research_runtime.tests.test_graph_training import constant_fit


class GraphPrimitiveTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import torch
        torch.set_num_threads(1)
        cls.frame,cls.splits,_=checked_design(synthetic_cohort(),'multimodal_values')
        a,b=cls.splits[0];cls.subset,_=subject_subsample(cls.frame,a,b,fold=1,replicate=1)
        periods,labels=person_period(cls.frame.iloc[a],list(FEATURES))
        cls.model=make_graph_estimator('prior',20260926)
        constant_fit(cls.model.named_steps['hazards'],periods,labels)
        cls.test=cls.frame.iloc[b[:3]].copy(deep=True)

    def test_default_and_explicit_own_reference_exactly_match(self):
        model=deepcopy(type(self).model);part=self.test.copy();baseline=ex.checked_cif(model,part)
        left=ex.telescoping(model,part,baseline,ex.Calls(17))
        right=ex.telescoping(model,part,baseline,ex.Calls(17),reference_values=model.named_steps['hazards'].medians_)
        np.testing.assert_array_equal(left[0],right[0])
        for order in ex.ORDERS:np.testing.assert_array_equal(left[1][order],right[1][order])

    def test_cross_reference_preserves_model_and_full_CIF(self):
        model=deepcopy(type(self).model);identity=ex.model_identity(model);snapshot=self.test.copy(deep=True)
        full=ex.checked_cif(model,self.test);ref=model.named_steps['hazards'].medians_.copy();ref[0]+=2
        calls=ex.Calls(17,max_state_rows=17*len(self.test))
        start,values,error=ex.telescoping(model,self.test,full,calls,reference_values=ref)
        self.assertEqual((calls.count,calls.completed),(17,17));self.assertLessEqual(error,1e-12)
        for value in values.values():np.testing.assert_allclose(start+value.sum(1),full,atol=1e-12,rtol=0)
        self.assertEqual(identity,ex.model_identity(model));self.assertTrue(snapshot.equals(self.test))
        np.testing.assert_array_equal(full,ex.checked_cif(model,self.test))

    def test_nullable_integer_fractional_reference_keeps_caller_and_mask(self):
        part=pd.DataFrame({n:pd.Series([10,pd.NA],dtype='Int64') for n in FEATURES})
        snapshot=part.copy(deep=True);reference=np.arange(10,dtype=float)+.5
        model=SimpleNamespace(named_steps={'hazards':SimpleNamespace(medians_=reference.copy())})
        def predict(_model,state):
            x=np.nan_to_num(state[list(FEATURES)].to_numpy(dtype=float),nan=0)
            return np.column_stack((.1+.0001*x.sum(1),np.full(len(x),.02)))
        baseline=predict(model,part.astype({n:'float64' for n in FEATURES}))
        start,values,_=ex.telescoping(model,part,baseline,predict,reference_values=reference)
        self.assertTrue(part.equals(snapshot))
        for value in values.values():
            self.assertTrue((value[1]==0).all());np.testing.assert_allclose(start+value.sum(1),baseline,atol=1e-12)

    def test_invalid_reference_rejected_before_CIF(self):
        for reference in (np.zeros(9),np.full(10,np.nan),np.ones(10,dtype=bool),['bad']*10):
            callback=unittest.mock.Mock()
            with self.assertRaises(ValueError):ex.telescoping(type(self).model,self.test,np.zeros((len(self.test),2)),callback,reference_values=reference)
            callback.assert_not_called()

    def test_partial_bilateral_mask_rejected_before_CIF(self):
        part=self.test.copy();part.loc[part.index[0],FEATURES[8]]=np.nan;part.loc[part.index[0],FEATURES[9]]=5
        callback=unittest.mock.Mock()
        with self.assertRaisesRegex(ValueError,'PARTIAL'):ex.telescoping(type(self).model,part,np.zeros((len(part),2)),callback)
        callback.assert_not_called()

    def test_invalid_baseline_and_boolean_values_rejected_before_CIF(self):
        for baseline in (np.zeros((len(self.test),3)),np.full((len(self.test),2),np.nan),np.ones((len(self.test),2))):
            callback=unittest.mock.Mock()
            with self.assertRaises(ValueError):ex.telescoping(type(self).model,self.test,baseline,callback)
            callback.assert_not_called()
        part=self.test.copy();part[FEATURES[0]]=True
        with self.assertRaises(ValueError):ex.telescoping(type(self).model,part,np.zeros((len(part),2)),lambda *_:None)

    def test_injected_state_mutation_is_rejected(self):
        part=self.test.copy();before=part.copy()
        def mutate(_model,state):
            state.iloc[0,state.columns.get_loc(FEATURES[0])]+=1
            return np.zeros((len(state),2))
        with self.assertRaisesRegex(ValueError,'MUTATED'):ex.telescoping(type(self).model,part,np.zeros((len(part),2)),mutate)
        self.assertTrue(part.equals(before))

    def test_injected_model_mutation_is_rejected(self):
        model=deepcopy(type(self).model)
        def mutate(_model,state):
            model.named_steps['hazards'].medians_[0]+=1
            return np.zeros((len(state),2))
        with self.assertRaisesRegex(ValueError,'MUTATED'):ex.telescoping(model,self.test,np.zeros((len(self.test),2)),mutate)

    def test_calls_event_counts_and_subject_state_rows(self):
        events=[];calls=ex.Calls(1,max_state_rows=len(self.test),evaluation_event=events.append)
        calls(type(self).model,self.test)
        self.assertEqual((calls.count,calls.completed,calls.state_rows_attempted,calls.state_rows_completed),(1,1,len(self.test),len(self.test)))
        self.assertEqual([e['phase'] for e in events],['CIF_ATTEMPT','CIF_COMPLETE'])
        self.assertFalse(any('subject_id' in e for e in events))
        with self.assertRaisesRegex(ValueError,'BUDGET'):calls(type(self).model,self.test)

    def test_calls_failed_attempt_is_distinct_and_not_retried(self):
        calls=ex.Calls(10)
        with patch.object(ex,'checked_cif',side_effect=ValueError('INJECTED')) as method:
            with self.assertRaisesRegex(ValueError,'INJECTED'):calls(type(self).model,self.test)
            with self.assertRaisesRegex(ValueError,'NO_RETRY'):calls(type(self).model,self.test)
            self.assertEqual(method.call_count,1)
        self.assertEqual((calls.count,calls.completed),(1,0));self.assertTrue(calls.failed)

    def test_calls_row_budget_and_callback_failure_before_prediction(self):
        calls=ex.Calls(2,max_state_rows=len(self.test)-1)
        with patch.object(ex,'checked_cif') as method:
            with self.assertRaisesRegex(ValueError,'BUDGET'):calls(type(self).model,self.test)
            method.assert_not_called();self.assertEqual(calls.count,0)
        def fail(_event):raise ValueError('EVENT_FAILED')
        calls=ex.Calls(2,evaluation_event=fail)
        with patch.object(ex,'checked_cif') as method:
            with self.assertRaisesRegex(ValueError,'EVENT_FAILED'):calls(type(self).model,self.test)
            method.assert_not_called();self.assertEqual((calls.count,calls.completed),(1,0))

    def test_invalid_call_budget_arguments(self):
        for kwargs in (dict(limit=True),dict(limit=-1),dict(max_state_rows=True),dict(evaluation_event=1)):
            with self.assertRaises(ValueError):ex.Calls(**kwargs)

    def test_fit_primitive_reuses_native_once_and_counts_accepted_result(self):
        budget=train.GraphFitBudget(1);events=[];budget.evaluation_event=events.append;snapshot=self.subset.copy()
        with patch.object(GraphHazards,'fit',autospec=True,side_effect=constant_fit) as method:
            model=train.fit_graph_subjects(self.subset,'prior',20260926,budget=budget)
            self.assertEqual(method.call_count,1)
        self.assertEqual((budget.attempts,budget.fit_returns,budget.completed),(1,1,1))
        self.assertTrue(self.subset.equals(snapshot));self.assertEqual(model.named_steps['hazards'].arm,'prior')
        self.assertEqual([e['phase'] for e in events],['FIT_ATTEMPT','FIT_RETURN','FIT_COMPLETE'])

    def test_fit_failures_lock_budget_without_retry(self):
        budget=train.GraphFitBudget(9)
        with patch.object(GraphHazards,'fit',side_effect=ValueError('INJECTED')) as method:
            with self.assertRaisesRegex(ValueError,'INJECTED'):train.fit_graph_subjects(self.subset,'prior',20260926,budget=budget)
            with self.assertRaisesRegex(ValueError,'NO_RETRY'):train.fit_graph_subjects(self.subset,'prior',20260926,budget=budget)
            self.assertEqual(method.call_count,1)
        self.assertEqual((budget.attempts,budget.fit_returns,budget.completed),(1,0,0))

    def test_fit_budget_zero_rejects_before_native_or_preprocessing(self):
        with patch.object(GraphHazards,'fit') as method:
            with self.assertRaisesRegex(ValueError,'BUDGET'):train.fit_graph_subjects(self.subset,'prior',20260926,budget=train.GraphFitBudget(0))
            method.assert_not_called()

    def test_fit_classes_bad_seed_or_partial_MRI_reject_before_fit(self):
        for bad in ('seed','classes','mri'):
            part=self.subset.copy();seed=20260926
            if bad=='seed':seed=True
            if bad=='classes':part=part.loc[part.event_type.ne(2)]
            if bad=='mri':part.loc[part.index[0],FEATURES[8]]=np.nan;part.loc[part.index[0],FEATURES[9]]=2
            with patch.object(GraphHazards,'fit') as method:
                with self.assertRaises(ValueError):train.fit_graph_subjects(part,'prior',seed,budget=train.GraphFitBudget(1))
                method.assert_not_called()

    def test_fit_wrong_training_stats_rejected_after_return(self):
        def wrong(self,X,y):
            constant_fit(self,X,y);self.medians_[0]+=1;return self
        budget=train.GraphFitBudget(1)
        with patch.object(GraphHazards,'fit',autospec=True,side_effect=wrong):
            with self.assertRaisesRegex(ValueError,'PREPROCESSING'):train.fit_graph_subjects(self.subset,'prior',20260926,budget=budget)
        self.assertEqual((budget.attempts,budget.fit_returns,budget.completed),(1,1,0));self.assertTrue(budget.failed)

    def test_fit_duplicate_dataframe_indices_are_reset_before_expansion(self):
        part=self.subset.copy();part.index=np.zeros(len(part),dtype=int)
        expected,_=person_period(self.subset.reset_index(drop=True),list(FEATURES))
        seen=[]
        def inspect(self,X,y):
            seen.append(X.equals(expected));return constant_fit(self,X,y)
        with patch.object(GraphHazards,'fit',autospec=True,side_effect=inspect):
            train.fit_graph_subjects(part,'prior',20260926,budget=train.GraphFitBudget(1))
        self.assertEqual(seen,[True])

    def test_fit_resource_failure_distinguishes_return_from_acceptance(self):
        budget=train.GraphFitBudget(1,max_memory_bytes=100)
        with patch.object(train,'peak_memory_bytes',side_effect=[1,101]),patch.object(GraphHazards,'fit',autospec=True,side_effect=constant_fit):
            with self.assertRaisesRegex(ValueError,'RESOURCE'):train.fit_graph_subjects(self.subset,'prior',20260926,budget=budget)
        self.assertEqual((budget.attempts,budget.fit_returns,budget.completed),(1,1,0));self.assertTrue(budget.failed)

    def test_fit_invalid_budget_configuration(self):
        for kwargs in (dict(limit=True),dict(limit=226),dict(limit=1,max_fit_seconds=np.nan),
                       dict(limit=1,max_memory_bytes=True),dict(limit=1,evaluation_event=0)):
            with self.assertRaises(ValueError):train.GraphFitBudget(**kwargs)


if __name__=='__main__':unittest.main()
