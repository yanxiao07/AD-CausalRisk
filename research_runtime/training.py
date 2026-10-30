"""Fixed five-outer/three-inner research training; no readers or deployment.

Pure in-memory API. Reuses the portable model primitives and original numerical
functions. The CLI below generates artificial data only. Historical data intake,
graph model development and clinical qualification are outside this module.
"""
from __future__ import annotations
import argparse
import hashlib
from importlib.metadata import version
import json
from pathlib import Path
import warnings

import joblib
import numpy as np
import pandas as pd
from sklearn.model_selection import StratifiedKFold
from threadpoolctl import threadpool_limits

from .discrete import person_period, predict_cif, validate_contract
from .estimators import BASE, ADDED, FAMILIES, SPECS, SEED, make_candidate
from .evaluation import (checked_inner, calibrator_parameters, calibrated_values,
                         censor_survival, horizon_ipcw_weights, horizon_metrics,
                         paired_interval, calibration_description)

SCHEMA = 'research-fixed-nested-training-v1'
DEPENDENCIES = ('numpy','pandas','scikit-learn','scipy','joblib','threadpoolctl','xgboost')


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream,'sha256').hexdigest()


def write_json(path, value):
    with Path(path).open('x',encoding='utf-8') as stream:
        json.dump(value,stream,ensure_ascii=False,allow_nan=False,indent=2)
        stream.write('\n')


def prediction_hash(ad):
    return hashlib.sha256(np.asarray(ad,dtype='<f8').tobytes()).hexdigest()


def input_fingerprint(frame, specification):
    columns=['subject_id','observed_month','event_type',*SPECS[specification]]
    values=frame[columns]
    identity=json.dumps({'columns':columns,'dtypes':[str(dtype) for dtype in values.dtypes]},sort_keys=True).encode()
    return hashlib.sha256(identity+pd.util.hash_pandas_object(values,index=False).to_numpy().tobytes()).hexdigest()


def checked_design(frame, specification, splits=None):
    if specification not in ('seven','multimodal_values'):
        raise ValueError('CALIBRATION_SPECIFICATION_NOT_SUPPORTED')
    work=validate_contract(frame,list(SPECS[specification])).reset_index(drop=True)
    if np.isinf(work[list(SPECS[specification])].to_numpy(float)).any():
        raise ValueError('INFINITE_FEATURE')
    if min(work.event_type.value_counts().get(c,0) for c in (0,1,2))<5:
        raise ValueError('FIVE_FOLD_CAUSE_SUPPORT_REQUIRED')
    if splits is None:
        splits=list(StratifiedKFold(5,shuffle=True,random_state=SEED).split(work,work.event_type))
    if len(splits)!=5:
        raise ValueError('EXACTLY_FIVE_OUTER_FOLDS_REQUIRED')
    checked=[];seen=[];nested=[];universe=set(range(len(work)))
    for fold,(a,b) in enumerate(splits):
        a,b=np.asarray(a),np.asarray(b)
        if (a.ndim!=1 or b.ndim!=1 or a.dtype.kind not in 'iu' or b.dtype.kind not in 'iu'
                or len(a)==0 or len(b)==0 or len(set(a))!=len(a) or len(set(b))!=len(b)
                or set(a)&set(b) or set(a)|set(b)!=universe):
            raise ValueError('INVALID_OR_LEAKING_OUTER_FOLD')
        train,test=work.iloc[a],work.iloc[b]
        if set(train.event_type)!={0,1,2}:
            raise ValueError('OUTER_TRAINING_MISSING_CAUSE')
        known=test.event_type.ne(0)|test.observed_month.eq(24)
        if test.loc[known,'event_type'].eq(1).nunique()!=2:
            raise ValueError('OUTER_TEST_MISSING_KNOWN_OUTCOME')
        inner=checked_inner(train,SEED+10000+fold*100)
        nested.append([{'fit_positions':a[x].tolist(),'calibration_positions':a[y].tolist()} for x,y in inner])
        seen.extend(b.tolist());checked.append((a.astype(int),b.astype(int)))
    if sorted(seen)!=list(range(len(work))):
        raise ValueError('INCOMPLETE_OR_REPEATED_OUTER_TEST_COVERAGE')
    full=checked_inner(work,SEED+20000)
    return work,checked,{'outer_inner':nested,'full_inner':[
        {'fit_positions':a.tolist(),'calibration_positions':b.tolist()} for a,b in full]}


def subject_subsample(frame, train_positions, test_positions, *, fold, replicate):
    """Fixed 80% event-stratified deletion within an existing outer train set.

    Returns a copied, reset-index frame and original row positions. No fitting,
    fold generation, redraw or persistence. Test values never set the sample.
    Five replicates describe this deletion mechanism, not bootstrap intervals.
    """
    if (type(fold) is not int or not 1 <= fold <= 5
            or type(replicate) is not int or not 1 <= replicate <= 5):
        raise ValueError('SUBSAMPLE_FOLD_OR_REPLICATE')
    work = validate_contract(frame, [])
    a,b = np.asarray(train_positions),np.asarray(test_positions)
    if (a.ndim != 1 or b.ndim != 1 or a.dtype.kind not in 'iu' or b.dtype.kind not in 'iu'
            or not len(a) or not len(b) or len(set(a)) != len(a) or len(set(b)) != len(b)
            or set(a)&set(b) or set(a)|set(b) != set(range(len(work)))):
        raise ValueError('SUBSAMPLE_ORIGINAL_OUTER_PARTITION')
    rng = np.random.Generator(np.random.PCG64(20261028+100*fold+replicate))
    selected = []
    for cause in (0,1,2):
        positions = np.sort(a[work.iloc[a].event_type.to_numpy() == cause])
        count = len(positions)*4//5
        if count < 1:
            raise ValueError('SUBSAMPLE_INSUFFICIENT_CAUSE')
        selected.extend(rng.choice(positions,size=count,replace=False).tolist())
    selected = np.asarray(sorted(selected),dtype=int)
    return work.iloc[selected].copy(deep=True).reset_index(drop=True),selected


class Journal:
    def __init__(self, folder, families, reuse, *, limits=None):
        default={'predictive':(18 if reuse else 24)*len(families),'calibration':6*len(families)}
        if limits is not None and (not isinstance(limits,dict) or set(limits)!=set(default)
                or any(type(x) is not int or x<0 for x in limits.values()) or not sum(limits.values())):
            raise ValueError('INVALID_EXPLICIT_FIT_LIMITS')
        self.limits=default if limits is None else dict(limits)
        self.counts=dict.fromkeys(self.limits,0)
        self.stream=(folder/'Fit_Ledger.jsonl').open('x',encoding='utf-8')
    def attempt(self, kind, **context):
        if kind not in self.limits or self.counts[kind]>=self.limits[kind]:
            raise ValueError('FIT_BUDGET_EXCEEDED')
        self.counts[kind]+=1
        self.stream.write(json.dumps({'kind':kind,'attempt':self.counts[kind],**context})+'\n')
        self.stream.flush()
    def close(self):
        self.stream.close()


def fit_base(train, family, specification, seed, journal, stage, outer=None, inner=None):
    model=make_candidate(family,specification,seed)
    columns=list(SPECS[specification]);periods,y=person_period(train,columns)
    if set(np.unique(y))!={0,1,2}:
        raise ValueError('TRAINING_MISSING_CAUSE')
    journal.attempt('predictive',family=family,stage=stage,outer=outer,inner=inner,seed=seed,subjects=len(train))
    model.fit(periods,y)
    with warnings.catch_warnings():
        warnings.simplefilter('ignore',RuntimeWarning)
        expected=np.nan_to_num(np.nanmedian(periods[columns].to_numpy(float),axis=0),nan=0)
    actual=model.named_steps['preprocess'].named_transformers_['clinical'].named_steps['impute'].statistics_
    if not np.array_equal(expected,actual):
        raise ValueError('TRAINING_PERSON_MONTH_IMPUTATION_MISMATCH')
    return model


def predict(model, frame, specification):
    before=joblib.hash(model)
    result=predict_cif(model,frame,list(SPECS[specification]))
    if joblib.hash(model)!=before:
        raise ValueError('BASE_MUTATED_ON_PREDICT')
    values=result[['ad_cif_24m','other_dementia_cif_24m']].to_numpy()
    if not np.isfinite(values).all() or (values<0).any() or (values.sum(axis=1)>1+1e-12).any():
        raise ValueError('INVALID_TWO_CAUSE_CIF')
    return values


def calibrated(raw, parameters):
    return np.column_stack(calibrated_values(raw[:,0],raw[:,1],parameters))


def crossfit(frame, family, specification, seed, journal, stage, outer=None, forbidden=()):
    if set(frame.subject_id)&set(forbidden):
        raise ValueError('OUTER_TEST_ENTERED_CALIBRATION')
    scores=np.full((len(frame),2),np.nan);weights=np.full(len(frame),np.nan)
    for inner,(a,b) in enumerate(checked_inner(frame,seed)):
        train,test=frame.iloc[a],frame.iloc[b]
        model=fit_base(train,family,specification,seed+inner,journal,stage,outer,inner+1)
        scores[b]=predict(model,test,specification)
        weights[b]=horizon_ipcw_weights(test,censor_survival(train))
    if not np.isfinite(scores).all() or not np.isfinite(weights).all():
        raise ValueError('INCOMPLETE_INNER_CROSSFIT')
    return scores,weights


def save_artifact(folder, model, parameters, frame, family, specification, fold, policy, reuse, plan_sha):
    raw=predict(model,frame,specification)
    before=np.column_stack((raw,calibrated(raw,parameters)))
    name=('final' if fold is None else f'outer_{fold}')+f'__{family}'
    payload={'schemaVersion':SCHEMA,'role':'PORTABLE_FINAL' if fold is None else 'PORTABLE_OUTER',
             'family':family,'specification':specification,'columns':list(SPECS[specification]),
             'outer_fold':fold,'calibrator':parameters,'research_policy':policy,
             'training_plan_sha256':plan_sha,
             'clinical_use':False,'causal_effect_identified':False,'automatic_deployment':False,
             'base_state_joblib_hash':joblib.hash(model)}
    if reuse:
        path=folder/(name+'.calibration.json');write_json(path,payload)
        digest=sha(path)
        restored=json.loads(path.read_text(encoding='utf-8'));restored_model=model
    else:
        path=folder/(name+'.joblib');payload['model']=model
        with path.open('xb') as stream:joblib.dump(payload,stream,compress=3)
        digest=sha(path)
        restored=joblib.load(path);restored_model=restored['model']  # Only this new trusted artifact.
    # Tree serialization may change object-memory hashes without changing any
    # predictions. The source-state hash is provenance, not a pickle identity.
    identity=('schemaVersion','role','family','specification','columns','outer_fold',
              'calibrator','research_policy','clinical_use','causal_effect_identified',
              'automatic_deployment','base_state_joblib_hash','training_plan_sha256')
    if any(restored.get(key)!=payload[key] for key in identity) or sha(path)!=digest:
        raise ValueError('SAVED_ARTIFACT_IDENTITY_MISMATCH')
    after_raw=predict(restored_model,frame,specification)
    after=np.column_stack((after_raw,calibrated(after_raw,restored['calibrator'])))
    error=float(np.max(abs(before-after)))
    if error!=0:raise ValueError('SAVED_CIF_ROUNDTRIP_MISMATCH')
    if sha(path)!=digest:raise ValueError('SAVED_ARTIFACT_CHANGED')
    return {'file':path.name,'sha256':digest,'family':family,'outer_fold':fold,
            'maximum_raw_and_calibrated_two_cause_error':error}


def run_nested(frame, output, specification='seven', families=FAMILIES, *, splits=None,
               existing_models=None, expected_oof=None):
    """Use a caller-provided cohort, optionally reuse in-memory bases.

    Caller owns real-data eligibility/authorization and original model identity.
    No files are read except newly written artifacts. Reuse requires all 5 outer
    plus final bases per family and original complete AD OOF digests. Supplied
    models are never refitted or serialized again. No participant scores saved.
    """
    families=tuple(families)
    if not families or len(set(families))!=len(families) or any(f not in FAMILIES for f in families):
        raise ValueError('INVALID_FIXED_FAMILIES')
    work,splits,nested=checked_design(frame,specification,splits)
    reuse=existing_models is not None
    required={(family,fold) for family in families for fold in (1,2,3,4,5,None)}
    if reuse and (set(existing_models)!=required or expected_oof is None or set(expected_oof)!=set(families)):
        raise ValueError('EXACT_SAVED_BASES_AND_OOF_DIGESTS_REQUIRED')
    if not reuse and expected_oof is not None:
        raise ValueError('EXPECTED_OOF_REQUIRES_REUSED_BASES')
    folder=Path(output);folder.mkdir(parents=True,exist_ok=False)
    journal=Journal(folder,families,reuse)
    write_json(folder/'Folds.private.json',{'outer':[
        {'train_positions':a.tolist(),'test_positions':b.tolist()} for a,b in splits],**nested})
    software={name:version(name) for name in DEPENDENCIES}
    write_json(folder/'Training_Plan.json',{
        'schemaVersion':SCHEMA,'specification':specification,'families':list(families),
        'features':list(SPECS[specification]),'subjects':len(work),'seed':SEED,
        'outer_folds':5,'inner_folds':3,'reuse_existing_bases':reuse,'limits':journal.limits,
        'input_fingerprint':input_fingerprint(work,specification),
        'folds_sha256':sha(folder/'Folds.private.json'),'expected_raw_oof_sha256':expected_oof,
        'software':software,'source_sha256':{name:sha(Path(__file__).parent/name) for name in
            ('training.py','evaluation.py','discrete.py','estimators.py')},
        'clinical_use':False,'causal_effect_identified':False,'automatic_deployment':False})
    plan_sha=sha(folder/'Training_Plan.json')
    raw={f:np.full((len(work),2),np.nan) for f in families}
    corrected={f:np.full(len(work),np.nan) for f in families}
    weights=np.full(len(work),np.nan);fold_rows=[];artifacts=[]
    try:
        with threadpool_limits(limits=1):
            # All original OOF identities are verified before the first new fit.
            if reuse:
                for fold,(_,b) in enumerate(splits,1):
                    for family in families:raw[family][b]=predict(existing_models[family,fold],work.iloc[b],specification)
                for family in families:
                    if prediction_hash(raw[family][:,0])!=expected_oof[family]:
                        raise ValueError('ORIGINAL_RAW_OOF_MISMATCH')
            for fold,(a,b) in enumerate(splits):
                train,test=work.iloc[a],work.iloc[b];g=censor_survival(train)
                weights[b]=horizon_ipcw_weights(test,g);metrics={}
                for family in families:
                    model=existing_models[family,fold+1] if reuse else fit_base(
                        train,family,specification,SEED+fold,journal,'outer_base',fold+1)
                    if not reuse:raw[family][b]=predict(model,test,specification)
                    scores,cw=crossfit(train,family,specification,SEED+10000+fold*100,
                                       journal,'outer_inner',fold+1,test.subject_id)
                    journal.attempt('calibration',family=family,stage='outer',outer=fold+1,subjects=len(train))
                    params=calibrator_parameters(scores[:,0],train.event_type.eq(1).to_numpy(int),cw)
                    corrected[family][b]=calibrated(raw[family][b],params)[:,0]
                    artifacts.append(save_artifact(folder,model,params,test,family,specification,fold+1,'candidate',reuse,plan_sha))
                    metrics[family]={policy:horizon_metrics(test,p,g) for policy,p in
                                     [('raw',raw[family][b,0]),('calibrated',corrected[family][b])]}
                fold_rows.append({'fold':fold+1,'test_subjects':len(test),'metrics':metrics})
            summaries=[];contrasts={};policies={};target=work.event_type.eq(1).to_numpy(float)
            for family in families:
                errors={policy:weights*(target-p)**2 for policy,p in
                        [('raw',raw[family][:,0]),('calibrated',corrected[family])]}
                delta=errors['calibrated']-errors['raw'];interval=paired_interval(delta,seed=SEED,n_resamples=1000)
                contrasts[family]={'calibrated_minus_raw_brier':float(delta.mean()),'conditional_95_interval':interval}
                policies[family]='calibrated' if interval[1]<0 else 'raw'
                for policy,p in [('raw',raw[family][:,0]),('calibrated',corrected[family])]:
                    means={key:float(sum(row['test_subjects']*row['metrics'][family][policy][key]
                                           for row in fold_rows)/len(work)) for key in fold_rows[0]['metrics'][family][policy]}
                    if abs(means['ipcw_brier_24m']-errors[policy].mean())>1e-12:
                        raise ValueError('POOLED_BRIER_DENOMINATOR_MISMATCH')
                    summaries.append({'family':family,'policy':policy,'fold_weighted_mean_metrics':means,
                                      'pooled_oof_brier':float(errors[policy].mean()),
                                      'oof_prediction_sha256':prediction_hash(p),
                                      'calibration_description':calibration_description(work,p,weights)})
            for family in families:
                scores,cw=crossfit(work,family,specification,SEED+20000,journal,'full_crossfit')
                journal.attempt('calibration',family=family,stage='full',subjects=len(work))
                params=calibrator_parameters(scores[:,0],work.event_type.eq(1).to_numpy(int),cw)
                model=existing_models[family,None] if reuse else fit_base(work,family,specification,SEED,journal,'final_base')
                artifacts.append(save_artifact(folder,model,params,work,family,specification,None,policies[family],reuse,plan_sha))
        if journal.counts!=journal.limits:
            raise ValueError('INCOMPLETE_FIXED_DELIVERY')
        result={'schemaVersion':SCHEMA,'status':'FIXED_NESTED_TRAINING_COMPLETE','subjects':len(work),
                'specification':specification,'families':list(families),'reuse_existing_bases':reuse,
                'fits':journal.counts.copy(),'limits':journal.limits,'artifacts':artifacts,
                'models':summaries,'fold_results':fold_rows,'paired_calibration_brier':contrasts,
                'research_policies':policies,'software':software,'training_plan_sha256':plan_sha,
                'clinical_use':False,'causal_effect_identified':False,'default_model_changed':False,
                'participant_predictions_saved':False,
                'interval_scope':'Fixed realized folds/models/censoring; no retraining or complete selection uncertainty',
                'source_scope':'Portable predictive baseline training; not cohort construction or causal graph validation'}
        write_json(folder/'Training_Result.json',result)
        write_json(folder/'Run_State.json',{'status':'COMPLETE','fits':journal.counts})
        return result
    except Exception as error:
        write_json(folder/'Run_State.json',{'status':'FAILED','error_type':type(error).__name__,'attempted_fits':journal.counts})
        raise
    finally:
        journal.close()


def synthetic_cohort():
    """Ninety hand-constructed rows, no empirical patient distribution."""
    rng=np.random.default_rng(17);n=90;event=np.tile([0,1,2],n//3)
    signal=np.where(event==1,1.,np.where(event==2,.1,-.8))+rng.normal(0,.15,n)
    values=np.column_stack((72+5*signal,14-signal,26-3*signal,2+signal,.5+.2*signal,
                            18+6*signal,4+2*signal,(event==1).astype(float),
                            3300-300*signal,3350-300*signal))
    frame=pd.DataFrame(values,columns=BASE+ADDED)
    frame['subject_id']=[f'SYNTHETIC-{i:03}' for i in range(n)]
    frame['observed_month']=np.where(event==0,24,np.where(event==1,18,14))
    frame['event_type']=event
    frame.loc[(np.arange(n)%10)==0,'mmse']=np.nan
    frame.loc[(np.arange(n)%4)==0,list(ADDED)]=np.nan
    frame.loc[(event==0)&(np.arange(n)%2==0),'observed_month']=12
    return frame


def main():
    parser=argparse.ArgumentParser(description='Synthetic-only fixed nested baseline training; no clinical input files')
    parser.add_argument('--output',required=True,type=Path)
    parser.add_argument('--specification',choices=('seven','multimodal_values'),default='seven')
    parser.add_argument('--family',choices=(*FAMILIES,'all'),default='logistic')
    args=parser.parse_args()
    try:
        families=FAMILIES if args.family=='all' else (args.family,)
        result=run_nested(synthetic_cohort(),args.output,args.specification,families)
        print(json.dumps({'status':result['status'],'purpose':'SYNTHETIC_ACCEPTANCE',
                          'fits':result['fits'],'saved_artifacts':len(result['artifacts']),
                          'clinical_use':False}))
        return 0
    except Exception as error:
        print(json.dumps({'status':'REJECTED','error_type':type(error).__name__,'clinical_use':False}))
        return 1


if __name__=='__main__':
    raise SystemExit(main())
