"""Constructed FR-10 acceptance, with analytic truths and active I/O denial."""
from dataclasses import replace
from fractions import Fraction
from itertools import product
from pathlib import Path
from unittest.mock import patch
import json
import os
import sys
import unittest

import numpy as np
from research_runtime import graph_structure_stability as s

ACCOUNTING = dict(native_score_calls=0, native_score_returns=0, support_rejections=0,
                  numerical_or_input_errors=0, protected_io_attempts=0)
_native_score = s.rank_precision_scores
_guard_active = False
_initial_modules = set()


def setUpModule():
    global _guard_active, _initial_modules
    _guard_active = True
    _initial_modules = set(sys.modules)
    for key in ACCOUNTING: ACCOUNTING[key] = 0
    root = Path(__file__).resolve().parents[4]
    def audit(event, args):
        if not _guard_active: return
        if event == 'socket.connect':
            ACCOUNTING['protected_io_attempts'] += 1
            raise RuntimeError('CONSTRUCTED_NO_NETWORK')
        if event != 'open' or not isinstance(args[0], (str, bytes, os.PathLike)): return
        path = Path(os.fsdecode(args[0])).resolve()
        if (path.is_relative_to(root/'data') or path.suffix.lower() in
                ('.csv','.rda','.rds','.rdata','.joblib','.npz','.pkl','.pickle','.parquet','.xlsx','.xpt')
                or path.name.endswith('.private.json')):
            ACCOUNTING['protected_io_attempts'] += 1
            raise RuntimeError('CONSTRUCTED_NO_PROTECTED_INPUT')
    sys.addaudithook(audit)
    def tracked(values):
        ACCOUNTING['native_score_calls'] += 1
        try: result = _native_score(values)
        except s.NotEstimable:
            ACCOUNTING['support_rejections'] += 1; raise
        except Exception:
            ACCOUNTING['numerical_or_input_errors'] += 1; raise
        ACCOUNTING['native_score_returns'] += 1
        return result
    s.rank_precision_scores = tracked


def tearDownModule():
    global _guard_active
    _guard_active = False  # Audit hooks persist; never constrain later modules.
    s.rank_precision_scores = _native_score
    assert ACCOUNTING['protected_io_attempts'] == 0
    assert all(name not in set(sys.modules)-_initial_modules for name in ('research_runtime.graph_native',
               'research_runtime.discrete','research_runtime.training'))
    print(json.dumps(dict(acceptance_accounting=ACCOUNTING,clinical_reads=0,model_loads=0,
                          original_CIF_calls=0,optimization_fits=0)),flush=True)


def factorial():
    return np.array(list(product((-1.,1.), repeat=10)))


def cohort(n=128, missing=False, constant=False, binary=False):
    values=np.random.default_rng(9173).normal(size=(n,10))
    values[:,8]=values[:,0]  # A known within-candidate dependency.
    if constant:values[:,4]=0
    if binary:values[:,4]=0;values[:10,4]=1
    if missing:
        for k in range(1,5):
            for i in list(range(k,n,5))[-4:]:values[i,9]=np.nan
    ids=tuple(f'SYNTHETIC-STRUCTURE-{i:04}' for i in range(n))
    folds=[]
    for k in range(5):
        train=[i for i in range(n) if i%5!=k];test=[i for i in range(n) if i%5==k]
        current=tuple(ids[i] for i in train)
        folds.append(s.TrainingFold(k+1,current,values[train].copy(),current,tuple(ids[i] for i in test)))
    return folds


def run(folds,**kwargs):
    return s.evaluate_training_folds(folds,data_version='synthetic-structure-acceptance-v1',constructed=True,**kwargs)


class NumericTruths(unittest.TestCase):
    def test_protocol_identity_and_restricted_candidates(self):
        self.assertEqual(s.PROTOCOL_SHA256,'7182ad9d7eb447b89094436d6b068a7e3652f088cd5337a2d091635f5bab1448')
        self.assertEqual(len(set(s.PAIRS)),11)
        self.assertEqual(len({v for pair in s.PAIRS for v in pair}),6)
        self.assertNotIn((0,9),s.PAIRS)

    def test_midranks_ties_and_duplicate_occurrences(self):
        for column,truth in [([4,1,1,3],[4,1.5,1.5,3]),([4,1,1,1,3],[5,2,2,2,4])]:
            x=np.tile(np.array(column,dtype=float)[:,None],(1,10));before=x.copy()
            np.testing.assert_array_equal(s.average_ranks(x)[:,0],truth)
            np.testing.assert_array_equal(x,before)

    def test_signed_zero_and_extreme_values_are_ranked_without_overflow(self):
        x=np.tile(np.array([-1e308,-0.,0.,1e308])[:,None],(1,10))
        np.testing.assert_array_equal(s.average_ranks(x)[:,0],[1,2.5,2.5,4])

    def test_independent_full_factorial_is_zero(self):
        np.testing.assert_allclose(s.rank_precision_scores(factorial()),np.zeros(11),atol=1e-14,rtol=0)

    def test_two_identical_columns_have_analytic_point_nine(self):
        x=factorial();x[:,8]=x[:,0]
        expected=np.zeros(11);expected[s.PAIRS.index((0,8))]=float(Fraction(9,10))
        np.testing.assert_allclose(s.rank_precision_scores(x),expected,atol=1e-14,rtol=0)

    def test_three_identical_columns_have_analytic_nine_over_nineteen(self):
        x=factorial();x[:,8]=x[:,0];x[:,2]=x[:,0]
        expected=np.zeros(11)
        for pair in ((0,8),(0,2),(2,8)):expected[s.PAIRS.index(pair)]=float(Fraction(9,19))
        np.testing.assert_allclose(s.rank_precision_scores(x),expected,atol=1e-14,rtol=0)

    def test_sign_reversal_has_analytic_negative_point_nine(self):
        x=factorial();x[:,8]=-x[:,0]
        value=s.rank_precision_scores(x)
        self.assertAlmostEqual(value[s.PAIRS.index((0,8))],-.9,places=14)

    def test_outside_candidate_relation_is_not_added(self):
        x=factorial();x[:,9]=x[:,0]
        np.testing.assert_allclose(s.rank_precision_scores(x),np.zeros(11),atol=1e-14,rtol=0)

    def test_pair_score_is_symmetric_under_endpoint_swap(self):
        x=np.random.default_rng(71).normal(size=(64,10));x[:,8]+=.7*x[:,0]
        swapped=x.copy();swapped[:,[0,8]]=swapped[:,[8,0]]
        j=s.PAIRS.index((0,8))
        self.assertAlmostEqual(s.rank_precision_scores(x)[j],s.rank_precision_scores(swapped)[j],places=12)

    def test_constant_is_not_estimable(self):
        x=factorial();x[:,4]=1
        with self.assertRaises(s.NotEstimable):s.rank_precision_scores(x)

    def test_malformed_values_not_recoded_as_missing(self):
        for value in (np.nan,np.inf,-np.inf):
            x=np.ones((50,10));x[0,0]=value
            with self.subTest(value=value),self.assertRaises(s.StructureStabilityError):s.rank_precision_scores(x)
        for x in (np.ones((50,10),dtype=bool),np.ones((50,10),dtype=complex),np.full((50,10),'1'),np.ones((50,9))):
            with self.assertRaises(s.StructureStabilityError):s.rank_precision_scores(x)

    def test_solve_failure_has_no_fallback(self):
        with patch.object(np.linalg,'solve',side_effect=np.linalg.LinAlgError('SYNTHETIC_PRIVATE_VALUE')) as solve:
            with self.assertRaisesRegex(s.StructureStabilityError,'STRUCTURE_SOLVE_FAILED') as err:s.rank_precision_scores(factorial())
            self.assertEqual(solve.call_count,1);self.assertNotIn('PRIVATE',str(err.exception))

    def test_bad_solve_residual_or_symmetry_fails(self):
        x=factorial();x[:,8]=x[:,0]
        for matrix in (np.eye(10),np.eye(10)+np.triu(np.ones((10,10)),1),np.full((10,10),np.nan)):
            with patch.object(np.linalg,'solve',return_value=matrix),self.assertRaises(s.StructureStabilityError):s.rank_precision_scores(x)

    def test_draws_reproducible_distinct_and_with_replacement(self):
        before=np.random.get_state()
        a=s._draw_indices(100,1,1);b=s._draw_indices(100,1,1)
        np.testing.assert_array_equal(a,b)
        self.assertFalse(np.array_equal(a,s._draw_indices(100,1,2)))
        self.assertFalse(np.array_equal(a,s._draw_indices(100,2,1)))
        self.assertLess(len(np.unique(a)),100)
        after=np.random.get_state()
        for left,right in zip(before,after):np.testing.assert_array_equal(left,right)


class SelectionTruths(unittest.TestCase):
    def panel(self,valid,pos,neg,full):
        return s.summarize_selections(np.full(11,full),[np.full(11,.2)]*pos+[np.full(11,-.2)]*neg+
            [np.zeros(11)]*(valid-pos-neg)+[None]*(100-valid))

    def test_frequency_and_sign_equalities_inclusive(self):
        row=self.panel(100,72,8,.2)['pairs'][0]
        self.assertTrue(row['retained_candidate']);self.assertEqual(row['frequency'],.8);self.assertEqual(row['sign_concordance'],.9)

    def test_negative_dominance_matches_negative_full_sample(self):
        self.assertTrue(self.panel(100,8,72,-.2)['pairs'][0]['retained_candidate'])

    def test_valid_denominator_is_ninety_not_one_hundred(self):
        row=self.panel(90,65,7,.3)['pairs'][0]
        self.assertEqual(row['frequency'],float(Fraction(72,90)));self.assertTrue(row['retained_candidate'])

    def test_eighty_nine_valid_with_all_selected_is_null(self):
        result=self.panel(89,89,0,.3);self.assertEqual(result['status'],'NOT_ESTIMABLE')
        for row in result['pairs']:
            for key in ('full_score','selected','positive','negative','frequency','sign_concordance','retained_candidate'):self.assertIsNone(row[key])

    def test_no_selection_is_zero_with_null_sign(self):
        row=self.panel(100,0,0,0.)['pairs'][0]
        self.assertEqual(row['frequency'],0);self.assertIsNone(row['sign_concordance']);self.assertFalse(row['retained_candidate'])

    def test_sign_full_sample_and_frequency_conflicts(self):
        for pos,neg,full,reason in [(40,40,.3,'SIGN_INCONSISTENT'),(90,0,-.3,'FULL_SAMPLE_SIGN_CONFLICT'),
                                    (90,0,.19,'FULL_SAMPLE_BELOW_THRESHOLD'),(79,0,.3,'FREQUENCY_BELOW_THRESHOLD')]:
            row=self.panel(100,pos,neg,full)['pairs'][0]
            self.assertFalse(row['retained_candidate']);self.assertIn(reason,row['reasons'])

    def test_schedule_and_score_shape_are_strict(self):
        for draws in ([np.ones(11)]*99,[np.full(11,np.nan)]*100,[np.ones(10)]*100):
            with self.assertRaises(s.StructureStabilityError):s.summarize_selections(np.ones(11),draws)


class FoldAcceptance(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.result=run(cohort())

    def test_actual_five_fold_matrix_505_attempts(self):
        result=self.result;self.assertEqual(result['status'],'COMPLETE')
        expected=dict(score_attempts=505,score_returns=505,full_score_attempts=5,full_score_returns=5,
            bootstrap_score_attempts=500,bootstrap_score_returns=500,draw_attempts=500,draw_returns=500,
            numerical_score_calls=505,numerical_score_returns=505,valid_draws=500,invalid_draws=0)
        self.assertEqual(result['operations'],expected)
        self.assertEqual(len(result['folds']),5)

    def test_repeat_is_exactly_deterministic_and_input_unchanged(self):
        folds=cohort();before=[f.values.copy() for f in folds]
        self.assertEqual(self.result,run(folds))
        for f,x in zip(folds,before):np.testing.assert_array_equal(f.values,x)

    def test_first_fold_scores_do_not_depend_on_its_held_out_values(self):
        folds=cohort();held=set(folds[0].held_out_ids)
        for fold in folds[1:]:
            for i,subject in enumerate(fold.subject_ids):
                if subject in held:fold.values[i]=fold.values[i]*-3+7
        # Changed people occur only as held-out IDs in fold one. The shared
        # snapshots in other training folds are changed consistently.
        self.assertEqual(run(folds)['folds'][0],self.result['folds'][0])

    def test_aggregate_whitelist_excludes_subjects_draws_and_matrices(self):
        result=self.result
        self.assertEqual(set(result),{'schemaVersion','protocolSha256','dataVersion','constructed','status','folds','priorDirections',
            'crossFold','operations','statisticalEstimatesArePredictiveFits','fits','modelLoads','CIFCalls','clinicalUse',
            'causalEffectIdentified','directionLearned','individualStructureEstimated','graphChanged','defaultModelChanged',
            'populationConfidenceInterval','falseDiscoveryControl','provenanceVerified','historicalRealtimeCertified',
            'participantOutputsSaved','privacyFormalDPCertified'})
        for row in result['folds']:
            self.assertEqual(set(row),{'status','reason','scheduled_draws','valid_draws','invalid_draws','pairs','fold','eligible_subject_band','invalid_reasons'})
            self.assertEqual(row['eligible_subject_band'],'100-199')
            for pair in row['pairs']:self.assertEqual(set(pair),{'pair','full_score','selected','positive','negative','frequency','sign_concordance','retained_candidate','reasons'})
        text=json.dumps(result,allow_nan=False)
        self.assertNotIn('SYNTHETIC-STRUCTURE-',text)
        self.assertTrue(all(result[k] is False for k in ('clinicalUse','causalEffectIdentified','directionLearned','graphChanged','populationConfidenceInterval','provenanceVerified','privacyFormalDPCertified')))

    def test_one_unsupported_fold_prevents_cross_fold_intersection(self):
        result=run(cohort(n=80,missing=True))
        self.assertEqual(result['folds'][0]['eligible_subject_band'],'<50')
        self.assertEqual(result['folds'][0]['scheduled_draws'],0)
        self.assertEqual(result['operations']['score_attempts'],404)
        self.assertIsNone(result['crossFold']['retainedPairs'])
        self.assertEqual(sum(f['status']=='ESTIMABLE' for f in result['folds']),4)

    def test_full_constant_ends_fold_without_draws(self):
        result=run(cohort(constant=True));self.assertEqual(result['operations']['score_attempts'],5)
        self.assertEqual(result['operations']['draw_attempts'],0)
        self.assertTrue(all(f['reason']=='CONSTANT_FULL_TRAINING_RANK' for f in result['folds']))

    def test_duplicate_draw_copies_not_distinct_people(self):
        with patch.object(s,'_draw_indices',side_effect=lambda n,k,b:np.zeros(n,dtype=int)) as draw:
            result=run(cohort());self.assertEqual(draw.call_count,500)
        self.assertEqual(result['operations']['numerical_score_calls'],5)
        self.assertEqual(result['operations']['score_attempts'],505)
        self.assertEqual(result['operations']['invalid_draws'],500)
        self.assertTrue(all(f['invalid_reasons']['INSUFFICIENT_DISTINCT_SUBJECTS']==100 for f in result['folds']))

    def test_constant_draw_after_sufficient_unique_people_is_invalid_for_all_pairs(self):
        with patch.object(s,'_draw_indices',side_effect=lambda n,k,b:np.resize(np.arange(20,n),n)):
            result=run(cohort(binary=True))
        self.assertEqual(result['operations']['numerical_score_calls'],505)
        self.assertEqual(result['operations']['score_returns'],5)
        self.assertTrue(all(f['invalid_reasons']['CONSTANT_RANK_FEATURE']==100 for f in result['folds']))
        self.assertTrue(all(pair['frequency'] is None for f in result['folds'] for pair in f['pairs']))

    def test_ninety_and_eighty_nine_valid_draws_are_not_redrawn(self):
        native=s._draw_indices
        for valid in (90,89):
            def draws(n,k,b):return native(n,k,b) if b<=valid else np.zeros(n,dtype=int)
            with patch.object(s,'_draw_indices',side_effect=draws) as draw:result=run(cohort())
            self.assertEqual(draw.call_count,500);self.assertEqual(result['operations']['valid_draws'],5*valid)
            self.assertTrue(all(f['valid_draws']==valid and f['invalid_draws']==100-valid for f in result['folds']))
            self.assertEqual(result['status'],'COMPLETE' if valid==90 else 'COMPLETE_WITH_NOT_ESTIMABLE_FOLDS')

    def test_training_identity_order_and_overlap_rejected_before_scores(self):
        for mutation in ('duplicate','order','overlap','features'):
            folds=cohort();f=folds[0]
            if mutation=='duplicate':folds[0]=replace(f,subject_ids=(f.subject_ids[0],)*len(f.subject_ids))
            if mutation=='order':folds[0]=replace(f,expected_training_ids=tuple(reversed(f.expected_training_ids)))
            if mutation=='overlap':folds[0]=replace(f,held_out_ids=(f.subject_ids[0],)+f.held_out_ids[1:])
            if mutation=='features':folds[0]=replace(f,features=tuple(reversed(s.FEATURES)))
            with self.subTest(mutation=mutation),self.assertRaises(s.StructureStabilityError) as err:run(folds)
            self.assertEqual(err.exception.operations['score_attempts'],0)

    def test_changed_universe_or_shared_snapshot_is_rejected(self):
        for mutation in ('universe','shared'):
            folds=cohort()
            if mutation=='universe':folds[0]=replace(folds[0],held_out_ids=folds[0].held_out_ids[:-1])
            else:folds[0].values[0,0]+=1
            with self.subTest(mutation=mutation),self.assertRaises(s.StructureStabilityError) as err:run(folds)
            self.assertEqual(err.exception.operations['score_attempts'],0)

    def test_infinite_value_in_an_already_missing_row_is_not_discarded(self):
        folds=cohort();folds[0].values[0,:2]=[np.nan,np.inf]
        with self.assertRaises(s.StructureStabilityError) as err:run(folds)
        self.assertEqual(err.exception.operations['score_attempts'],0)

    def test_bad_draw_is_hard_stop_without_resampling(self):
        with patch.object(s,'_draw_indices',side_effect=lambda n,k,b:np.full(n,n,dtype=int)) as draw:
            with self.assertRaises(s.StructureStabilityError) as err:run(cohort())
        self.assertEqual(draw.call_count,1);self.assertEqual(err.exception.operations['score_attempts'],1)
        self.assertEqual(err.exception.operations['draw_returns'],0)

    def test_numerical_error_aborts_without_partial_result_or_retry(self):
        with patch.object(np.linalg,'solve',side_effect=np.linalg.LinAlgError('SYNTHETIC_PRIVATE')) as solve:
            with self.assertRaisesRegex(s.StructureStabilityError,'STRUCTURE_SOLVE_FAILED') as err:run(cohort())
        self.assertEqual(solve.call_count,1);self.assertEqual(err.exception.operations['score_attempts'],1)
        self.assertEqual(err.exception.operations['numerical_score_returns'],0)
        self.assertNotIn('PRIVATE',str(err.exception))

    def test_returned_nonfinite_score_not_counted_as_accepted(self):
        with patch.object(s,'rank_precision_scores',return_value=np.full(11,np.nan)):
            with self.assertRaises(s.StructureStabilityError) as err:run(cohort())
        self.assertEqual(err.exception.operations['numerical_score_returns'],1)
        self.assertEqual(err.exception.operations['score_returns'],0)

    def test_progress_error_is_redacted_and_not_retried(self):
        events=[]
        def observer(event):events.append(event);raise RuntimeError('SYNTHETIC_PRIVATE')
        with self.assertRaisesRegex(s.StructureStabilityError,'STRUCTURE_PROGRESS_FAILED') as err:run(cohort(),progress=observer)
        self.assertEqual(len(events),1);self.assertEqual(err.exception.operations['score_attempts'],2)
        self.assertNotIn('PRIVATE',str(err.exception))

    def test_progress_cannot_mutate_counts_and_input_mutation_fails(self):
        folds=cohort()
        def observer(event):event['operations']['score_attempts']=1000000;folds[0].values[0,0]+=1
        with self.assertRaisesRegex(s.StructureStabilityError,'STRUCTURE_CALLER_INPUT_CHANGED') as err:run(folds,progress=observer)
        self.assertEqual(err.exception.operations['score_attempts'],2)

    def test_progress_cannot_replace_frozen_fold_metadata(self):
        folds=cohort();changed=[]
        def observer(event):
            if not changed:
                folds[1]=replace(folds[1],fold=99);changed.append(True)
            event['operations']['score_attempts']=1000000
        self.assertEqual(run(folds,progress=observer),self.result)

    def test_unexpected_support_failure_is_not_reclassified_as_constant(self):
        with patch.object(s,'rank_precision_scores',side_effect=s.NotEstimable('SYNTHETIC_PRIVATE')) as score:
            with self.assertRaisesRegex(s.StructureStabilityError,'STRUCTURE_UNEXPECTED_SUPPORT_FAILURE') as err:run(cohort())
        self.assertEqual(score.call_count,1);self.assertEqual(err.exception.operations['score_returns'],0)
        self.assertNotIn('PRIVATE',str(err.exception))

    def test_score_budgets_are_enforced_before_increment(self):
        counts=s._Counters()
        for _ in range(5):counts.reserve('full')
        with self.assertRaises(s.StructureStabilityError):counts.reserve('full')
        for _ in range(500):counts.reserve('bootstrap')
        with self.assertRaises(s.StructureStabilityError):counts.reserve('bootstrap')
        self.assertEqual(counts.value['score_attempts'],505)

    def test_real_mode_and_non_synthetic_version_are_not_admitted(self):
        for version,constructed in [('real-v1',True),('synthetic-v1',False),('synthetic-v1',1)]:
            with self.assertRaises(s.StructureStabilityError) as err:s.evaluate_training_folds(cohort(),data_version=version,constructed=constructed)
            self.assertEqual(err.exception.operations['score_attempts'],0)


if __name__=='__main__':unittest.main(verbosity=2)
