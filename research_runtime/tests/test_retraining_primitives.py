"""No-fit acceptance for train-only deletion and explicit-reference games."""
from copy import deepcopy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import numpy as np
import pandas as pd

from research_runtime import table_explanation as table
from research_runtime.training import subject_subsample, Journal
from research_runtime.tests.test_table_explanation import constructed_model, frame_for


class RetrainingPrimitivesTests(unittest.TestCase):
    def test_default_reference_unchanged(self):
        model=constructed_model();frame=frame_for(model,rows=1)
        stats=table.training_reference_statistics(model,'seven')['median']
        before=table.model_state_identity(model)
        default=table.explain_shapley(model,frame,'seven')
        supplied=table.explain_shapley(model,frame,'seven',reference_values=stats)
        for key in ('contributions','reference_cif','prediction_cif'):
            np.testing.assert_array_equal(default[key],supplied[key])
        self.assertEqual(table.model_state_identity(model),before)
        self.assertEqual(default['reference_policy'],'outer_training_person_month_median')
        self.assertEqual(supplied['reference_policy'],'caller_supplied_raw_scale_median_same_missingness')

    def test_four_games_model_reference_decomposition_and_missingness(self):
        original=constructed_model();new=deepcopy(original)
        new.named_steps['hazards'].estimator_.coef_[1,0] *= 2
        frame=frame_for(original,rows=1);frame.loc[0,'mmse']=np.nan
        ref=table.training_reference_statistics(original,'seven')['median'];changed=ref+.2
        a,b,c,d=[table.explain_shapley(m,frame,'seven',reference_values=r) for m,r in
                 ((original,ref),(new,ref),(original,changed),(new,changed))]
        av,bv,cv,dv=[x['contributions'] for x in (a,b,c,d)]
        np.testing.assert_allclose(dv-av,(bv-av)+(cv-av)+(dv-bv-cv+av),atol=1e-12)
        np.testing.assert_array_equal(a['prediction_cif'],c['prediction_cif'])
        np.testing.assert_array_equal(b['prediction_cif'],d['prediction_cif'])
        self.assertTrue(all(np.all(x['contributions'][:,2]==0) for x in (a,b,c,d)))
        self.assertGreater(np.abs(dv-bv-cv+av).max(),1e-8)

    def test_explicit_reference_invalid_before_cif(self):
        model=constructed_model();frame=frame_for(model)
        with patch.object(table,'predict_cif',side_effect=AssertionError('MUST_NOT_PREDICT')):
            for bad in ([True]*7,[None]*7,[np.inf]*7,[[1.]*7],['1']*7,[1.]*6,[np.nan]*7):
                with self.subTest(bad=repr(bad)),self.assertRaisesRegex(ValueError,'EXPLICIT'):
                    table.explain_shapley(model,frame,'seven',reference_values=bad)
            with self.assertRaisesRegex(ValueError,'EXPLICIT'):
                table.explain_shapley(model,frame,'seven',reference_kind='mean',reference_values=np.ones(7))

    def test_external_reference_is_copied_before_callback(self):
        model=constructed_model();frame=frame_for(model,rows=1)
        supplied=table.training_reference_statistics(model,'seven')['median']
        expected=table.explain_shapley(model,frame,'seven',reference_values=supplied.copy())
        old=table.predict_cif
        def callback(*args,**kwargs):
            supplied[:]=99
            return old(*args,**kwargs)
        with patch.object(table,'predict_cif',callback):
            actual=table.explain_shapley(model,frame,'seven',reference_values=supplied)
        np.testing.assert_array_equal(expected['contributions'],actual['contributions'])

    def test_original_input_and_model_mutation_rejection_still_applies(self):
        model=constructed_model();frame=frame_for(model,rows=1)
        model.named_steps['hazards'].estimator_.mutate_marker=True
        with self.assertRaisesRegex(ValueError,'MODEL_CHANGED'):
            table.explain_shapley(model,frame,'seven',reference_values=np.ones(7))

    def frame(self):
        return pd.DataFrame({'subject_id':[f'ARTIFICIAL-SUB-{p}' for p in range(45)],
            'observed_month':24,'event_type':[0]*20+[1]*16+[2]*9,
            'x':np.arange(45,dtype=float)})

    def test_subsample_matches_prespecified_random_draw(self):
        frame=self.frame();before=frame.copy(deep=True)
        subset,positions=subject_subsample(frame,np.arange(40),np.arange(40,45),fold=1,replicate=1)
        rng=np.random.Generator(np.random.PCG64(20261129))
        expected=sorted([*rng.choice(np.arange(20),16,replace=False),
                         *rng.choice(np.arange(20,36),12,replace=False),
                         *rng.choice(np.arange(36,40),3,replace=False)])
        np.testing.assert_array_equal(positions,expected)
        self.assertEqual(subset.event_type.value_counts().to_dict(),{0:16,1:12,2:3})
        self.assertEqual(subset.index.tolist(),list(range(31)))
        self.assertTrue(frame.equals(before))
        subset.iloc[0,subset.columns.get_loc('x')]=-99
        self.assertTrue(frame.equals(before))

    def test_subsample_test_features_and_order_cannot_change_selection(self):
        frame=self.frame()
        _,a=subject_subsample(frame,np.arange(40),np.arange(40,45),fold=2,replicate=2)
        frame.loc[40:,'x']=1e9
        _,b=subject_subsample(frame,np.arange(39,-1,-1),np.arange(44,39,-1),fold=2,replicate=2)
        np.testing.assert_array_equal(a,b)

    def test_subsample_partition_and_cause_rejection(self):
        frame=self.frame()
        for train,test in (([0,0,1],[2]),(list(range(40)),[39,40,41,42,43,44]),
                           (list(range(40)),[40]),(np.arange(40,dtype=float),np.arange(40,45))):
            with self.assertRaisesRegex(ValueError,'PARTITION'):
                subject_subsample(frame,train,test,fold=1,replicate=1)
        with self.assertRaisesRegex(ValueError,'INSUFFICIENT'):
            subject_subsample(frame,np.arange(37),np.arange(37,45),fold=1,replicate=1)
        with self.assertRaisesRegex(ValueError,'FOLD_OR_REPLICATE'):
            subject_subsample(frame,np.arange(40),np.arange(40,45),fold=True,replicate=1)

    def test_journal_explicit_limits_and_original_defaults(self):
        with tempfile.TemporaryDirectory() as folder:
            journal=Journal(Path(folder),['logistic'],False,limits={'predictive':1,'calibration':0})
            try:
                journal.attempt('predictive',stage='artificial')
                with self.assertRaisesRegex(ValueError,'BUDGET'):
                    journal.attempt('predictive')
                with self.assertRaisesRegex(ValueError,'BUDGET'):
                    journal.attempt('calibration')
                self.assertEqual(journal.counts,{'predictive':1,'calibration':0})
            finally:journal.close()
        with tempfile.TemporaryDirectory() as folder:
            journal=Journal(Path(folder),['logistic'],False)
            self.assertEqual(journal.limits,{'predictive':24,'calibration':6});journal.close()
        for limits in ({'predictive':True,'calibration':0},{'predictive':0,'calibration':0},{'predictive':1}):
            with tempfile.TemporaryDirectory() as folder,self.assertRaisesRegex(ValueError,'LIMITS'):
                Journal(Path(folder),['logistic'],False,limits=limits)
