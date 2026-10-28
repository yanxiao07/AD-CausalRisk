"""Fixed table OOF attribution study: caller-bound memory, aggregates only.

No files, deserialization, deployment or authorization. The separate training
perturbation API delegates fitting to the existing training.fit_base primitive;
the original fixed-model evaluate API still performs no predictive fitting.
Reuses existing aggregation helpers in the pinned graph-capable environment;
their import does not run graph models, optimizers or graph explanations.
"""
from itertools import combinations
import re
import time

import numpy as np
from sklearn.pipeline import Pipeline
from sklearn.linear_model import LogisticRegression
from sklearn.ensemble import RandomForestClassifier
from threadpoolctl import threadpool_limits

from adni_algorithm_candidates import StrictHazards
from . import table_explanation as table
from .discrete import predict_cif
from .estimators import SPECS, FAMILIES
from .training import checked_design, input_fingerprint, prediction_hash
from .evaluation import censor_survival, horizon_ipcw_weights
from .grouped_xai_study import distribution, paired, CONFIGS, REFERENCES
from .graph_acceptance import peak_memory_bytes
from .graph_explanation import rank
from .training import subject_subsample, fit_base, SEED
from .discrete import person_period
from types import SimpleNamespace
import weakref
import warnings

SPECIFICATIONS = ('seven','multimodal_values')


def _rank_rows(left,right,active):
    """Per-person reductions only; callers never persist these private rows."""
    n=len(left)
    out={key:np.full(n,np.nan) for key in ('spearman','top1','top2')}
    out.update(insufficient=np.zeros(n,dtype=bool),constant=np.zeros(n,dtype=bool),
               top1_tie=np.zeros(n,dtype=bool),top2_boundary_tie=np.zeros(n,dtype=bool))
    for i,(a,b,mask) in enumerate(zip(left,right,active)):
        if mask.sum()<3:
            out['insufficient'][i]=True;continue
        aa,bb=np.abs(a[mask]),np.abs(b[mask]);rho=rank(aa,bb)
        if rho is None:out['constant'][i]=True
        else:out['spearman'][i]=rho
        tie=any(np.isclose(x,x.max(),atol=1e-12,rtol=0).sum()!=1 for x in (aa,bb))
        out['top1_tie'][i]=tie
        if not tie:out['top1'][i]=float(np.argmax(aa)==np.argmax(bb))
        # A tie *inside* the top two is fine; only its membership boundary matters.
        order_a,order_b=np.argsort(-aa),np.argsort(-bb)
        boundary=any(np.isclose(x[order[1]],x[order[2]],atol=1e-12,rtol=0)
                     for x,order in ((aa,order_a),(bb,order_b)))
        out['top2_boundary_tie'][i]=boundary
        if not boundary:out['top2'][i]=len(set(order_a[:2])&set(order_b[:2]))/2
    out['l1']=np.abs(left-right).sum(axis=1)
    usable=active&(np.abs(left)>1e-6)&(np.abs(right)>1e-6)
    out['sign_eligible']=usable.sum(axis=1)
    out['sign_flips']=((np.sign(left)!=np.sign(right))&usable).sum(axis=1)
    out['active_groups']=active.sum(axis=1)
    return out


def _finite_distribution(values):
    return distribution([float(x) for x in np.asarray(values).ravel() if np.isfinite(x)])


def _rank_summary(rows):
    result=dict(subjects=len(rows['l1']),signed_L1=_finite_distribution(rows['l1']),
                common_active_groups=_finite_distribution(rows['active_groups']),
                sign_flip_groups=int(rows['sign_flips'].sum()),
                sign_eligible_groups=int(rows['sign_eligible'].sum()))
    for key in ('spearman','top1','top2'):
        values=rows[key];eligible=int(np.isfinite(values).sum())
        result[key]=dict(eligible_subjects=eligible,undefined_subjects=len(values)-eligible,
                        distribution=_finite_distribution(values))
    for key in ('insufficient','constant','top1_tie','top2_boundary_tie'):
        result[key+'_subjects']=int(rows[key].sum())
    return result


def evaluate_training_perturbations(frame,splits,loader,candidates,expected_AD,*,
        expected_input_fingerprint,expected_subjects=1160,
        fitter=fit_base,shapley=table.explain_shapley,constructed=False,progress=None,
        max_predictive_fits=150,max_cif_calls=153780,max_state_rows=35676960,
        max_elapsed_seconds=21600,max_memory_bytes=2147483648):
    """Stream the locked five-replicate, six-family/specification OOF matrix.

    No readers or persistence. Trusted hosts bind source/input/model bytes,
    authorization and hard process resource limits. Six historical AD gates
    precede every fit. A callback failure propagates once, without a result;
    progress carries only aggregate diagnostics and attempted/completed counts.
    ``constructed=True`` labels simulated callbacks, never real training evidence.
    """
    started=time.perf_counter();original=frame.copy(deep=True)
    work,splits,_=checked_design(frame,'multimodal_values',splits)
    if (type(constructed) is not bool or len(work)!=expected_subjects
            or input_fingerprint(work,'multimodal_values')!=expected_input_fingerprint):
        raise ValueError('PERTURBATION_INPUT_FINGERPRINT_OR_MODE')
    if any(not 1<=len(b)<=256 for _,b in splits):raise ValueError('PERTURBATION_TEST_FOLD_SIZE')
    expected_keys={(s,f,k) for s in SPECIFICATIONS for f in FAMILIES for k in range(1,6)}
    index={(r['specification'],r['family'],r['outer_fold']):r for r in candidates}
    if len(candidates)!=30 or set(index)!=expected_keys:raise ValueError('PERTURBATION_CANDIDATE_MATRIX')
    for spec in SPECIFICATIONS:
        if (set(expected_AD.get(spec,{}))!=set(FAMILIES)|{'historical_other_cause_OOF_hash'}
                or expected_AD[spec]['historical_other_cause_OOF_hash'] is not None):
            raise ValueError('PERTURBATION_HISTORICAL_AD_ONLY')
        for family in FAMILIES:
            item=expected_AD[spec][family]
            if (not isinstance(item.get('oof_prediction_sha256'),str)
                    or re.fullmatch('[0-9a-f]{64}',item['oof_prediction_sha256']) is None
                    or type(item.get('pooled_oof_brier')) not in (int,float)
                    or not np.isfinite(item['pooled_oof_brier'])):
                raise ValueError('PERTURBATION_OOF_EXPECTATION')
    for value,cap in ((max_predictive_fits,150),(max_cif_calls,153780),(max_state_rows,35676960)):
        if type(value) is not int or not 0<=value<=cap:raise ValueError('PERTURBATION_LIMIT')
    if (type(max_elapsed_seconds) not in (int,float) or not np.isfinite(max_elapsed_seconds)
            or not 0<=max_elapsed_seconds<=21600 or type(max_memory_bytes) is not int
            or not 0<=max_memory_bytes<=2147483648):raise ValueError('PERTURBATION_RESOURCE_LIMIT')
    counts=dict(model_load_attempts=0,model_loads_completed=0,predictive_fit_attempts=0,
        predictive_fit_callbacks_completed=0,cif_attempts=0,cif_completed=0,
        cif_state_rows_attempted=0,cif_state_rows_completed=0,shapley_games_completed=0)
    context={};pending=[];maximum=0.;identity_error=0.;models={};identities={}
    raw={(s,f):np.empty((len(work),2)) for s in SPECIFICATIONS for f in FAMILIES}
    weights=np.empty(len(work));target=work.event_type.eq(1).to_numpy(float)
    model_refs=[];parts={};fold_diagnostics=[]

    def emit(phase,**labels):
        if progress:progress(dict(phase=phase,**context,**labels,operations=counts.copy()))
    def resource(calls=0,rows=0,fits=0):
        if (counts['cif_attempts']+calls>max_cif_calls or counts['cif_state_rows_attempted']+rows>max_state_rows
                or counts['predictive_fit_attempts']+fits>max_predictive_fits):
            raise ValueError('PERTURBATION_BUDGET_EXCEEDED')
        if time.perf_counter()-started>max_elapsed_seconds or peak_memory_bytes()>max_memory_bytes:
            raise ValueError('PERTURBATION_TIME_OR_MEMORY_LIMIT')
    def unchanged():
        if not frame.equals(original):raise ValueError('PERTURBATION_CALLER_INPUT_CHANGED')
    def evaluation_event(phase,rows):
        if type(rows) is not int or rows<1:raise ValueError('PERTURBATION_CIF_ROW_COUNTER')
        if phase=='attempt':
            resource(1,rows)
            if pending:raise ValueError('PERTURBATION_OVERLAPPING_CIF')
            counts['cif_attempts']+=1;counts['cif_state_rows_attempted']+=rows
            pending.append(rows);emit('CIF_ATTEMPT')
        elif phase=='complete':
            if pending!=[rows]:raise ValueError('PERTURBATION_UNMATCHED_CIF_COMPLETION')
            counts['cif_completed']+=1;counts['cif_state_rows_completed']+=rows;pending.clear()
        else:raise ValueError('PERTURBATION_CIF_PHASE')
    def baseline(model,part,spec):
        before=(counts['cif_attempts'],counts['cif_completed'])
        value=checked_baseline(model,part,spec,evaluation_event=evaluation_event)
        if tuple(counts[k]-v for k,v in zip(('cif_attempts','cif_completed'),before))!=(1,1):
            raise ValueError('PERTURBATION_BASELINE_ACCOUNTING')
        return value
    def active(part,spec,reference):
        x=part[list(SPECS[spec])].to_numpy(float)
        same=(x==reference)|(np.isnan(x))
        return np.column_stack([~same[:,list(cols)].all(axis=1) for _,cols in table.groups_for(spec)])
    def game(model,part,spec,reference,expected,name):
        nonlocal maximum
        context['game']=name;mask=active(part,spec,reference);coalitions=2**int(mask.any(axis=0).sum())
        resource(coalitions,coalitions*len(part));before=counts.copy();snapshot=part.copy(deep=True)
        value=shapley(model,part,spec,reference_values=reference,max_coalitions=128 if spec=='seven' else 512,
                      evaluation_event=evaluation_event)
        v=np.asarray(value['contributions']);out=np.asarray(value['prediction_cif']);ref=np.asarray(value['reference_cif'])
        names=tuple(n for n,_ in table.groups_for(spec))
        if (not part.equals(snapshot) or tuple(value['group_names'])!=names or v.shape!=(len(part),len(names),2)
                or out.shape!=(len(part),2) or ref.shape!=out.shape or not np.isfinite(v).all()
                or not np.isfinite(out).all() or not np.isfinite(ref).all()
                or (ref<0).any() or (ref.sum(axis=1)>1+1e-12).any()
                or np.max(np.abs(out-expected))>1e-12 or np.any(v[~mask]!=0)):
            raise ValueError('PERTURBATION_GAME_INPUT_GROUPS_PREDICTION_OR_DUMMY')
        error=float(np.max(np.abs(ref+v.sum(axis=1)-out)))
        if error>1e-12:raise ValueError('PERTURBATION_GAME_RECONSTRUCTION')
        maximum=max(maximum,error)
        if (counts['cif_attempts']-before['cif_attempts']!=coalitions
                or counts['cif_completed']-before['cif_completed']!=coalitions
                or counts['cif_state_rows_attempted']-before['cif_state_rows_attempted']!=coalitions*len(part)
                or value['cif_calls']!=coalitions or value['cif_state_rows']!=coalitions*len(part)):
            raise ValueError('PERTURBATION_GAME_ACCOUNTING')
        counts['shapley_games_completed']+=1;emit('GAME_COMPLETE')
        return v.copy(),mask
    def append(key,value):
        parts.setdefault(key,[]).append(np.asarray(value).copy())

    try:
        # Original models are held only through the shared identity gate, then
        # popped one fold-model at a time. Subset models are never accumulated.
        for fold,(train,test) in enumerate(splits,1):
            weights[test]=horizon_ipcw_weights(work.iloc[test],censor_survival(work.iloc[train]))
            for spec in SPECIFICATIONS:
                for family in FAMILIES:
                    context.clear();context.update(specification=spec,family=family,fold=fold)
                    resource(1,len(test));counts['model_load_attempts']+=1;emit('MODEL_LOAD_ATTEMPT')
                    model=checked_outer_bundle(loader(spec,family,fold),index[spec,family,fold])
                    counts['model_loads_completed']+=1;models[spec,family,fold]=model
                    identities[spec,family,fold]=table.model_state_identity(model)
                    raw[spec,family][test]=baseline(model,work.iloc[test].copy(),spec)
                reference=table.training_reference_statistics(models[spec,FAMILIES[0],fold],spec)
                for family in FAMILIES[1:]:
                    other=table.training_reference_statistics(models[spec,family,fold],spec)
                    if any(not np.array_equal(reference[k],other[k]) for k in ('median','mean','indicator_features')):
                        raise ValueError('PERTURBATION_ORIGINAL_TRAINING_REFERENCE_MISMATCH')
        if any(len({id(getter(m)) for m in models.values()})!=30 for getter in
                (lambda m:m,lambda m:m.named_steps['preprocess'],lambda m:m.named_steps['hazards'].estimator_)):
            raise ValueError('PERTURBATION_ORIGINAL_MODEL_OBJECT_REUSE')
        for (spec,family),value in raw.items():
            item=expected_AD[spec][family]
            if prediction_hash(value[:,0])!=item['oof_prediction_sha256']:raise ValueError('PERTURBATION_AD_OOF_CHANGED')
            if abs(float(np.mean(weights*(target-value[:,0])**2))-item['pooled_oof_brier'])>1e-12:
                raise ValueError('PERTURBATION_AD_BRIER_CHANGED')
        context.clear();emit('ALL_SIX_AD_OOF_VERIFIED');unchanged()
        for spec in SPECIFICATIONS:
            names=list(SPECS[spec]);g=len(table.groups_for(spec))
            for family in FAMILIES:
                for fold,(train,test) in enumerate(splits,1):
                    context.clear();context.update(specification=spec,family=family,fold=fold)
                    model=models.pop((spec,family,fold));original_id=identities[spec,family,fold]
                    if table.model_state_identity(model)!=original_id:raise ValueError('PERTURBATION_ORIGINAL_MODEL_CHANGED')
                    part=work.iloc[test].copy(deep=True);r0=table.training_reference_statistics(model,spec)['median']
                    a,mask0=game(model,part,spec,r0,raw[spec,family][test],'A')
                    replicate_games=[];diagnostics=[]
                    observed=np.column_stack([~part[names].iloc[:,list(cols)].isna().any(axis=1).to_numpy()
                                              for _,cols in table.groups_for(spec)])
                    for rep in range(1,6):
                        context.pop('game',None);context['replicate']=rep;unchanged()
                        subset,positions=subject_subsample(work,train,test,fold=fold,replicate=rep)
                        snapshot=subset.copy(deep=True);resource(fits=1)
                        counts['predictive_fit_attempts']+=1;emit('FIT_ATTEMPT');unchanged()
                        marked=[]
                        def fit_attempt(kind,**labels):
                            if kind!='predictive' or marked:raise ValueError('PERTURBATION_FIT_JOURNAL')
                            marked.append(True)
                        with threadpool_limits(limits=1):
                            new=fitter(subset,family,spec,SEED+fold-1,SimpleNamespace(attempt=fit_attempt),
                                       'training_subject_perturbation',outer=fold)
                        if marked!=[True] or not subset.equals(snapshot):raise ValueError('PERTURBATION_FIT_INPUT_OR_JOURNAL')
                        if any(ref() is new for ref in model_refs) or new is model or any(new is m for m in models.values()):
                            raise ValueError('PERTURBATION_SUBSET_MODEL_REUSE')
                        if (new.named_steps['preprocess'] is model.named_steps['preprocess']
                                or new.named_steps['hazards'].estimator_ is model.named_steps['hazards'].estimator_):
                            raise ValueError('PERTURBATION_SHARED_PREPROCESS_OR_ESTIMATOR')
                        model_refs.append(weakref.ref(new));stats=table.training_reference_statistics(new,spec)
                        _checked_family(new,family)
                        periods,_=person_period(subset,names)
                        training_values=periods[names].to_numpy(float)
                        with warnings.catch_warnings(record=True) as caught:
                            warnings.simplefilter('always',RuntimeWarning)
                            expected_median=np.nan_to_num(np.nanmedian(training_values,axis=0),nan=0.)
                        expected_mean=np.where(np.isnan(training_values),expected_median,training_values).mean(axis=0)
                        expected_indicators=np.flatnonzero(subset[names].isna().any().to_numpy())
                        if (not np.array_equal(stats['median'],expected_median)
                                or not np.allclose(stats['mean'],expected_mean,atol=1e-12,rtol=1e-12)
                                or not np.array_equal(stats['indicator_features'],expected_indicators)):
                            raise ValueError('PERTURBATION_SUBSET_TRAINING_STATISTICS')
                        counts['predictive_fit_callbacks_completed']+=1
                        new_identity=table.model_state_identity(new);rb=stats['median']
                        prediction=baseline(new,part.copy(deep=True),spec)
                        b,_=game(new,part,spec,r0,prediction,'B')
                        c,maskb=game(model,part,spec,rb,raw[spec,family][test],'C')
                        d,_=game(new,part,spec,rb,prediction,'D')
                        contrasts={'M':b-a,'R':c-a,'I':d-b-c+a,'T':d-a}
                        error=float(np.max(np.abs(contrasts['T']-contrasts['M']-contrasts['R']-contrasts['I'])))
                        if error>1e-12:raise ValueError('PERTURBATION_SIGNED_IDENTITY')
                        identity_error=max(identity_error,error)
                        for cause in (0,1):
                            for label,values in contrasts.items():
                                append((spec,family,rep,cause,'contrast',label),np.abs(values[:,:,cause]).sum(axis=1))
                        append((spec,family,rep,'brier'),weights[test]*(target[test]-prediction[:,0])**2)
                        diagnostics.append(dict(replicate=rep,training_subjects=len(subset),
                            event_counts=[int(subset.event_type.eq(k).sum()) for k in (0,1,2)],
                            observed_feature_counts=subset[names].notna().sum().astype(int).tolist(),
                            indicator_features=stats['indicator_features'].tolist(),
                            training_statistic_RuntimeWarnings=sum(issubclass(w.category,RuntimeWarning) for w in caught),
                            all_missing_feature_indices=np.flatnonzero(subset[names].isna().all().to_numpy()).tolist()))
                        if (table.model_state_identity(new)!=new_identity or table.model_state_identity(model)!=original_id):
                            raise ValueError('PERTURBATION_MODEL_CHANGED')
                        replicate_games.append((b,c,d,maskb))
                        del new,periods,contrasts
                        emit('REPLICATE_COMPLETE');resource()
                    # Keep contributions within this fold; retain only scalar
                    # per-person reductions for exact pooled OOF quantiles.
                    for comparison in ('model','reference','total'):
                        masks=[mask0 if comparison=='model' else mask0&item[3] for item in replicate_games]
                        common=np.logical_and.reduce(masks)
                        for cause in (0,1):
                            common_rows=[]
                            for rep,((b,c,d,_),mask) in enumerate(zip(replicate_games,masks),1):
                                right={'model':b,'reference':c,'total':d}[comparison][:,:,cause]
                                row=_rank_rows(a[:,:,cause],right,mask)
                                for key,value in row.items():append((spec,family,rep,cause,comparison,key),value)
                                append((spec,family,rep,cause,comparison,'observed_inactive'),(observed&~mask).sum(axis=1))
                                common_rows.append(_rank_rows(a[:,:,cause],right,common))
                            for metric in ('spearman','top1','top2'):
                                valid=np.logical_and.reduce([np.isfinite(row[metric]) for row in common_rows])
                                append((spec,family,cause,comparison,'common_n',metric),valid)
                                for rep,row in enumerate(common_rows,1):
                                    append((spec,family,rep,cause,comparison,'fixed_'+metric),row[metric][valid])
                    fold_diagnostics.append(dict(specification=spec,family=family,fold=fold,
                        test_subjects=len(test),replicates=diagnostics))
                    emit('FOLD_MODEL_COMPLETE',aggregate=dict(test_subjects=len(test),
                        AD_model_L1_means=[float(np.abs(item[0][:,:,0]-a[:,:,0]).sum(axis=1).mean()) for item in replicate_games]))
                    if table.model_state_identity(model)!=original_id:raise ValueError('PERTURBATION_ORIGINAL_MODEL_CHANGED')
                    del model,a,replicate_games,b,c,d,right,common_rows
        unchanged();resource()
        if (models or pending or counts['predictive_fit_callbacks_completed']!=150
                or counts['shapley_games_completed']!=480 or counts['cif_attempts']!=counts['cif_completed']):
            raise ValueError('PERTURBATION_INCOMPLETE_MATRIX')
        def joined(key):return np.concatenate(parts[key])
        result_rows=[]
        for spec in SPECIFICATIONS:
            for family in FAMILIES:
                rows=[];common_eligibility=[]
                for cause in (0,1):
                    for rep in range(1,6):
                        sensitivity=[]
                        for comparison in ('model','reference','total'):
                            keys=('spearman','top1','top2','insufficient','constant','top1_tie','top2_boundary_tie',
                                  'l1','sign_eligible','sign_flips','active_groups')
                            reduction={k:joined((spec,family,rep,cause,comparison,k)) for k in keys}
                            sensitivity.append(dict(comparison=comparison,**_rank_summary(reduction),
                                observed_not_common_active_groups=int(joined((spec,family,rep,cause,comparison,'observed_inactive')).sum()),
                                all_repeat_common_active_and_qualified={k:_finite_distribution(joined((spec,family,rep,cause,comparison,'fixed_'+k)))
                                                                      for k in ('spearman','top1','top2')}))
                        rows.append(dict(replicate=rep,cause='AD' if cause==0 else 'OTHER_DEMENTIA',
                            contrast_signed_L1={k:_finite_distribution(joined((spec,family,rep,cause,'contrast',k))) for k in ('M','R','I','T')},
                            rank_sensitivity=sensitivity))
                    for comparison in ('model','reference','total'):
                        common_eligibility.append(dict(cause='AD' if cause==0 else 'OTHER_DEMENTIA',comparison=comparison,
                            subjects=len(work),all_repeat_common_active_and_qualified={metric:int(joined((spec,family,cause,comparison,'common_n',metric)).sum())
                                                                                    for metric in ('spearman','top1','top2')}))
                empirical={}
                for cause in (0,1):
                    for contrast in ('M','R','I','T'):
                        means=[float(joined((spec,family,rep,cause,'contrast',contrast)).mean()) for rep in range(1,6)]
                        empirical[('AD' if cause==0 else 'OTHER_DEMENTIA')+'_'+contrast]=dict(min=float(min(means)),median=float(np.median(means)),max=float(max(means)))
                result_rows.append(dict(specification=spec,family=family,unique_subjects=len(work),
                    subject_replicate_pairs=len(work)*5,replicates=rows,common_eligibility=common_eligibility,
                    empirical_range_of_replicate_mean_L1=empirical,
                    fixed_original_IPCW_AD_brier=dict(original=float(np.mean(weights*(target-raw[spec,family][:,0])**2)),
                        subset=[float(joined((spec,family,rep,'brier')).mean()) for rep in range(1,6)])))
        emit('COMPLETE')
        return dict(status='CONSTRUCTED_TRAINING_PERTURBATION_MATRIX_COMPLETE' if constructed else 'TRAINING_PERTURBATION_MATRIX_COMPLETE',
            constructed_callbacks=constructed,subjects=len(work),operations=counts,
            predictive_fits=None if constructed else counts['predictive_fit_callbacks_completed'],calibration_fits=0,
            all_six_original_AD_OOF_and_Brier_verified=True,historical_other_cause_OOF_hash_verified=False,
            model_summaries=result_rows,fold_diagnostics=fold_diagnostics,
            shapley_reconstruction_max_error=maximum,signed_decomposition_max_error=identity_error,
            participant_outputs_saved=False,population_confidence_interval=False,
            individual_causal_effect_identified=False,clinical_use=False,
            elapsed_seconds=time.perf_counter()-started,peak_memory_bytes=peak_memory_bytes())
    except Exception as exc:
        try:emit('FAILED',failure_type=type(exc).__name__)
        except Exception:pass
        raise


def _checked_family(model,family):
    hazard=model.named_steps['hazards']
    if not isinstance(hazard,StrictHazards):raise ValueError('TABLE_ORIGINAL_HAZARDS_REQUIRED')
    if family=='xgboost':
        from xgboost import XGBClassifier
        expected=XGBClassifier
    else:expected=LogisticRegression if family=='logistic' else RandomForestClassifier
    if not isinstance(getattr(hazard,'estimator_',None),expected):
        raise ValueError('TABLE_OUTER_ESTIMATOR_FAMILY')


def checked_outer_bundle(bundle, candidate):
    """Validate already-deserialized outer metadata against a caller-pinned row.

    The caller must verify trusted archive bytes BEFORE deserialization. A valid
    metadata declaration alone does not attest the archive, rows or model.
    """
    spec=candidate.get('specification');family=candidate.get('family');fold=candidate.get('outer_fold')
    if (spec not in SPECIFICATIONS or family not in FAMILIES or type(fold) is not int or not 1<=fold<=5
            or not isinstance(candidate.get('plan_sha256'),str)
            or re.fullmatch(r'[0-9a-f]{64}',candidate['plan_sha256']) is None
            or not isinstance(bundle,dict)):
        raise ValueError('TABLE_OUTER_CANDIDATE')
    role='NESTED_OUTER_RESEARCH_VALIDATION' if spec=='seven' else 'MULTIMODAL_OUTER_VALIDATION'
    key='training_plan_sha256' if spec=='seven' else 'plan_sha256'
    if (candidate.get('role')!=role or candidate.get('clinical_use') is not False
            or bundle.get('role')!=role or bundle.get('family')!=family or bundle.get('outer_fold')!=fold
            or type(bundle.get('outer_fold')) is not int or bundle.get('columns')!=list(SPECS[spec])
            or bundle.get(key)!=candidate.get('plan_sha256') or bundle.get('clinical_use') is not False):
        raise ValueError('TABLE_OUTER_IDENTITY')
    if spec=='multimodal_values' and (bundle.get('arm')!=spec or bundle.get('retrospective_only') is not True
            or bundle.get('prediction_policy')!='raw_uncalibrated_24month_CIF'):
        raise ValueError('TABLE_OUTER_RAW_POLICY')
    model=bundle.get('model');table.training_reference_statistics(model,spec)
    _checked_family(model,family)
    return model


def checked_original_folds(frame, private):
    """Keep the original stored position AND subject sequences; never resample."""
    if not isinstance(private,list) or len(private)!=5:raise ValueError('TABLE_ORIGINAL_FIVE_FOLDS')
    splits=[]
    for row in private:
        if not isinstance(row,dict):raise ValueError('TABLE_ORIGINAL_FOLD_SCHEMA')
        a,b=row.get('train_positions'),row.get('test_positions')
        if (not isinstance(a,list) or not isinstance(b,list) or not a or not b
                or any(type(x) is not int or not 0<=x<len(frame) for x in a+b)):
            raise ValueError('TABLE_ORIGINAL_FOLD_POSITIONS')
        if (frame.iloc[a].subject_id.tolist()!=row.get('train_subjects')
                or frame.iloc[b].subject_id.tolist()!=row.get('test_subjects')):
            raise ValueError('TABLE_ORIGINAL_FOLD_SUBJECTS')
        splits.append((np.asarray(a),np.asarray(b)))
    return checked_design(frame,'multimodal_values',splits)[1]


def checked_baseline(model, frame, specification, *, evaluation_event=None):
    original=frame.copy(deep=True);identity=table.model_state_identity(model)
    if evaluation_event:evaluation_event('attempt',len(frame))
    with threadpool_limits(limits=1):result=predict_cif(model,frame,list(SPECS[specification]))
    if (not original.equals(frame) or identity!=table.model_state_identity(model)
            or result.subject_id.tolist()!=frame.subject_id.tolist()):
        raise ValueError('TABLE_BASELINE_INPUT_MODEL_OR_ORDER')
    values=result[['ad_cif_24m','other_dementia_cif_24m']].to_numpy(float)
    if (values.shape!=(len(frame),2) or not np.isfinite(values).all() or (values<0).any()
            or (values>1).any() or (values.sum(axis=1)>1+1e-12).any()):
        raise ValueError('TABLE_BASELINE_TWO_CAUSE_CIF')
    if evaluation_event:evaluation_event('complete',len(frame))
    return values


def _masks(frame, model, specification, reference):
    current,groups=table._frame(frame,specification)
    baseline=table.table_reference(model,frame,specification,reference)
    names=list(SPECS[specification]);x=current[names].to_numpy();r=baseline[names].to_numpy()
    observed=np.column_stack([~np.isnan(x[:,list(cols)]).any(axis=1) for _,cols in groups])
    same=(x==r)|(np.isnan(x)&np.isnan(r))
    active=np.column_stack([~same[:,list(cols)].all(axis=1) for _,cols in groups])
    return observed,active


def evaluate(frame, splits, loader, candidates, expected_AD, *, expected_input_fingerprint,
             expected_subjects=1160,max_cif_calls=21630,max_state_rows=4576560,max_local_fits=1200,
             max_elapsed_seconds=7200,max_memory_bytes=2147483648,
             shapley=table.explain_shapley,lime=table.explain_lime,progress=None):
    """Run all six OOF gates first; fail without a partial scientific result.

    ``loader(specification,family,fold)`` returns one caller-verified outer
    bundle. The trusted host handles exact bytes, authorization and hard caps.
    Constructed callers may use smaller cohorts, with identical fixed matrix.
    """
    started=time.perf_counter();original=frame.copy(deep=True)
    work,splits,_=checked_design(frame,'multimodal_values',splits)
    if (len(work)!=expected_subjects or input_fingerprint(work,'multimodal_values')!=expected_input_fingerprint):
        raise ValueError('TABLE_STUDY_INPUT_FINGERPRINT')
    if any(not 10<=len(b)<=256 for _,b in splits):raise ValueError('TABLE_STUDY_TEST_FOLD_SIZE')
    keys={(spec,family,fold) for spec in SPECIFICATIONS for family in FAMILIES for fold in range(1,6)}
    index={(x['specification'],x['family'],x['outer_fold']):x for x in candidates}
    if len(candidates)!=30 or set(index)!=keys:raise ValueError('TABLE_STUDY_CANDIDATE_MATRIX')
    for spec in SPECIFICATIONS:
        if (set(expected_AD.get(spec,{}))!=set(FAMILIES)|{'historical_other_cause_OOF_hash'}
                or expected_AD[spec]['historical_other_cause_OOF_hash'] is not None):
            raise ValueError('TABLE_HISTORICAL_AD_ONLY_EVIDENCE_REQUIRED')
        for family in FAMILIES:
            e=expected_AD[spec][family]
            if (not isinstance(e.get('oof_prediction_sha256'),str) or len(e['oof_prediction_sha256'])!=64
                    or type(e.get('pooled_oof_brier')) not in (int,float) or not np.isfinite(e['pooled_oof_brier'])):
                raise ValueError('TABLE_OOF_EXPECTATION')
    counters=dict(cif_calls=0,cif_state_rows=0,local_surrogate_fits=0)
    def reserve(calls,rows,fits=0):
        if (counters['cif_calls']+calls>max_cif_calls or counters['cif_state_rows']+rows>max_state_rows
                or counters['local_surrogate_fits']+fits>max_local_fits):
            raise ValueError('TABLE_STUDY_BUDGET_EXCEEDED')
        if time.perf_counter()-started>max_elapsed_seconds or peak_memory_bytes()>max_memory_bytes:
            raise ValueError('TABLE_STUDY_TIME_OR_MEMORY_LIMIT')
    def charge(calls,rows,fits=0):
        if any(type(x) is not int or x<0 for x in (calls,rows,fits)):raise ValueError('TABLE_OPERATION_COUNTER')
        reserve(calls,rows,fits)
        for key,value in zip(counters,(calls,rows,fits)):counters[key]+=value
    def event(phase,**labels):
        if progress:progress(dict(phase=phase,**labels,**counters))
    models={};identities={};raw={(s,f):np.empty((len(work),2)) for s in SPECIFICATIONS for f in FAMILIES}
    weights=np.empty(len(work));target=work.event_type.eq(1).to_numpy(float)
    for fold,(train,test) in enumerate(splits,1):
        weights[test]=horizon_ipcw_weights(work.iloc[test],censor_survival(work.iloc[train]))
        for spec in SPECIFICATIONS:
            for family in FAMILIES:
                reserve(1,len(test))
                model=checked_outer_bundle(loader(spec,family,fold),index[spec,family,fold])
                models[spec,family,fold]=model;identities[spec,family,fold]=table.model_state_identity(model)
                table._frame(work.iloc[test],spec)
                raw[spec,family][test]=checked_baseline(model,work.iloc[test],spec);charge(1,len(test))
                event('BASELINE',specification=spec,family=family,fold=fold)
            reference=table.training_reference_statistics(models[spec,FAMILIES[0],fold],spec)
            for family in FAMILIES[1:]:
                other=table.training_reference_statistics(models[spec,family,fold],spec)
                if any(not np.array_equal(reference[k],other[k]) for k in ('median','mean','indicator_features')):
                    raise ValueError('TABLE_FAMILY_TRAINING_REFERENCE_MISMATCH')
    if any(len({id(getter(m)) for m in models.values()})!=30 for getter in (
            lambda m:m,lambda m:m.named_steps['preprocess'],lambda m:m.named_steps['hazards'].estimator_)):
        raise ValueError('TABLE_MODEL_OBJECT_REUSE')
    for (spec,family),values in raw.items():
        expected=expected_AD[spec][family]
        if prediction_hash(values[:,0])!=expected['oof_prediction_sha256']:raise ValueError('TABLE_AD_OOF_CHANGED')
        if abs(float(np.mean(weights*(target-values[:,0])**2))-expected['pooled_oof_brier'])>1e-12:
            raise ValueError('TABLE_AD_BRIER_CHANGED')
    event('ALL_SIX_AD_OOF_VERIFIED')
    rng=np.random.default_rng(20261027)
    selected=[np.sort(rng.choice(test,10,replace=False)) for _,test in splits]
    subset=np.concatenate(selected);slot={int(p):i for i,p in enumerate(subset)}
    observed={};active={};shap={};local={};fidelity={};maximum=0.
    for spec in SPECIFICATIONS:
        g=len(table.groups_for(spec));observed[spec]=np.empty((len(work),g),dtype=bool)
        for ref in REFERENCES:
            active[spec,ref]=np.empty((len(work),g),dtype=bool)
            for family in FAMILIES:shap[spec,family,ref]=np.empty((len(work),g,2))
        for family in FAMILIES:
            for name,*_ in CONFIGS:
                local[spec,family,name]=np.empty((50,g,2));fidelity[spec,family,name]=[]
    def checked_value(value,spec,part,baseline,is_shap):
        g=len(table.groups_for(spec));names=tuple(n for n,_ in table.groups_for(spec))
        v=np.asarray(value['contributions']);out=np.asarray(value['prediction_cif'])
        if (tuple(value['group_names'])!=names or v.shape!=(len(part),g,2) or out.shape!=baseline.shape
                or not np.isfinite(v).all() or not np.isfinite(out).all()
                or np.max(np.abs(out-baseline))>1e-12):raise ValueError('TABLE_XAI_GROUPS_OR_PREDICTION')
        mask=np.column_stack([part[list(SPECS[spec])[cols[0]]].notna().to_numpy() for _,cols in table.groups_for(spec)])
        if np.any(v[~mask]!=0):raise ValueError('TABLE_XAI_MISSING_GROUP_CONTRIBUTION')
        if is_shap:
            err=float(np.max(np.abs(np.asarray(value['reference_cif'])+v.sum(axis=1)-baseline)))
            if not np.isfinite(err) or err>1e-12:raise ValueError('TABLE_SHAPLEY_RECONSTRUCTION')
            return v,err
        return v,0.
    for fold,(_,test) in enumerate(splits,1):
        part=work.iloc[test]
        for spec in SPECIFICATIONS:
            limit=128 if spec=='seven' else 512
            for ref in REFERENCES:
                obs,act=_masks(part,models[spec,FAMILIES[0],fold],spec,ref)
                observed[spec][test]=obs;active[spec,ref][test]=act
            for family in FAMILIES:
                model=models[spec,family,fold]
                for ref in REFERENCES:
                    reserve(limit,len(test)*limit)
                    value=shapley(model,part,spec,reference_kind=ref,max_coalitions=limit)
                    v,err=checked_value(value,spec,part,raw[spec,family][test],True)
                    maximum=max(maximum,err);shap[spec,family,ref][test]=v
                    charge(value['cif_calls'],value['cif_state_rows'])
                    event('SHAPLEY',specification=spec,family=family,fold=fold,reference=ref)
                for position in selected[fold-1]:
                    for name,ref,seed,width in CONFIGS:
                        reserve(2,96,1)
                        value=lime(model,work.iloc[[position]],spec,reference_kind=ref,seed=seed,
                            kernel_width=width,samples=64,ridge=1e-6,holdout_samples=32,
                            reserve_holdout=True,batch_predictions=True,max_evaluations=96)
                        v,_=checked_value(value,spec,work.iloc[[position]],raw[spec,family][[position]],False)
                        local[spec,family,name][slot[int(position)]]=v[0]
                        fidelity[spec,family,name].append((slot[int(position)],value))
                        charge(value['cif_calls'],value['cif_state_rows'],value['local_surrogate_fits'])
                if table.model_state_identity(model)!=identities[spec,family,fold]:raise ValueError('TABLE_STUDY_MODEL_CHANGED')
                event('FOLD_MODEL_COMPLETE',specification=spec,family=family,fold=fold)
    importance=[]
    for (spec,family,ref),values in shap.items():
        for fold,positions in [(None,np.arange(len(work))),*[(i,b) for i,(_,b) in enumerate(splits,1)]]:
            rows=values[positions];obs=observed[spec][positions]
            importance.append(dict(specification=spec,family=family,reference=ref,outer_fold=fold,subjects=len(positions),
                groups=[dict(group=name,observed_subjects=int(obs[:,i].sum()),
                    mean_signed=rows[:,i].mean(axis=0).tolist(),mean_absolute=np.abs(rows[:,i]).mean(axis=0).tolist(),
                    observed_mean_absolute=np.abs(rows[obs[:,i],i]).mean(axis=0).tolist() if obs[:,i].any() else None)
                    for i,(name,_) in enumerate(table.groups_for(spec))]))
    sensitivity=[]
    def compare(kind,spec,left,right,mask,**labels):
        for cause in (0,1):
            sensitivity.append(dict(kind=kind,specification=spec,cause='AD' if cause==0 else 'OTHER_DEMENTIA',
                **labels,**paired(left[:,:,cause],right[:,:,cause],mask)))
    for spec in SPECIFICATIONS:
        for ref in REFERENCES:
            for a,b in combinations(FAMILIES,2):compare('shapley_algorithms',spec,shap[spec,a,ref],shap[spec,b,ref],active[spec,ref],reference=ref,families=[a,b])
        for family in FAMILIES:
            compare('shapley_reference',spec,shap[spec,family,'median'],shap[spec,family,'mean'],active[spec,'median']&active[spec,'mean'],family=family)
            compare('matched_methods',spec,shap[spec,family,'median'][subset],local[spec,family,'base'],active[spec,'median'][subset],family=family)
            for name,ref,_,_ in CONFIGS[1:]:compare('lime_configuration',spec,local[spec,family,'base'],local[spec,family,name],(active[spec,'median']&active[spec,ref])[subset],family=family,configuration=name)
        for name,ref,_,_ in CONFIGS:
            for a,b in combinations(FAMILIES,2):compare('lime_algorithms',spec,local[spec,a,name],local[spec,b,name],active[spec,ref][subset],configuration=name,families=[a,b])
    fits=[];eligibility=[]
    for (spec,family,name),rows in fidelity.items():
        for cause in (0,1):
            held=[r['heldout_fidelity'] for _,r in rows if r['heldout_fidelity']['samples']]
            residual=[abs(float(r['observed_residual'][0,cause])) for _,r in rows]
            rmse=[r['weighted_rmse'][cause] for r in held]
            fits.append(dict(specification=spec,family=family,configuration=name,cause='AD' if cause==0 else 'OTHER_DEMENTIA',
                subjects=len(rows),heldout_eligible_subjects=len(held),undefined_heldout_subjects=len(rows)-len(held),
                neighborhood_RMSE=distribution([r['weighted_rmse'][cause] for _,r in rows]),
                neighborhood_MAE=distribution([r['weighted_mae'][cause] for _,r in rows]),
                neighborhood_R2=distribution([r['weighted_r2'][cause] for _,r in rows]),
                observed_absolute_residual=distribution(residual),observed_residual_over_001=int(sum(x>.01 for x in residual)),
                heldout_RMSE=distribution(rmse),heldout_RMSE_over_001=int(sum(x>.01 for x in rmse)),
                heldout_MAE=distribution([r['weighted_mae'][cause] for r in held]),
                heldout_R2=distribution([r['weighted_r2'][cause] for r in held])))
    for spec in SPECIFICATIONS:
        for name,*_ in CONFIGS:
            eligible=[{i for i,r in fidelity[spec,f,name] if r['heldout_fidelity']['samples']} for f in FAMILIES]
            eligibility.append(dict(specification=spec,configuration=name,selected_unique_people=50,person_model_pairs=150,
                heldout_eligible_person_model_pairs=sum(len(x) for x in eligible),
                unique_people_with_any_heldout=len(set.union(*eligible)),unique_people_with_all_three_heldout=len(set.intersection(*eligible))))
    reserve(0,0)
    if (not frame.equals(original) or input_fingerprint(work,'multimodal_values')!=expected_input_fingerprint
            or any(table.model_state_identity(models[k])!=v for k,v in identities.items())):
        raise ValueError('TABLE_STUDY_INPUT_OR_MODEL_CHANGED')
    return dict(status='FIXED_TABLE_GROUPED_XAI_COMPLETE',subjects=len(work),outer_model_loads=30,
        shapley_model_reference_applications=60,lime_subject_model_config_applications=1200,
        lime_subset_unique_people=50,all_six_AD_OOF_and_Brier_verified=True,historical_other_cause_OOF_hash_verified=False,
        importance=importance,sensitivity=sensitivity,lime_fidelity=fits,lime_heldout_eligibility=eligibility,
        shapley_reconstruction_max_error=maximum,operations=counters,predictive_fits=0,calibration_fits=0,preprocessing_fits=0,
        participant_outputs_saved=False,individual_causal_effect_identified=False,clinical_use=False,independent_validation=False,
        elapsed_seconds=time.perf_counter()-started,peak_memory_bytes=peak_memory_bytes())
