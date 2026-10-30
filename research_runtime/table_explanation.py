"""Grouped predictive attribution for caller-verified in-memory table models.

No readers, loaders, fitting or persistence. Conditional on the observed missing
mask, not an explanation of missingness effects, causal effects or interventions.
"""
import hashlib

import joblib
import numpy as np
import pandas as pd
from sklearn.compose import ColumnTransformer
from sklearn.impute import SimpleImputer, MissingIndicator
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler, OneHotEncoder
from threadpoolctl import threadpool_limits

from .estimators import SPECS
from .group_shapley import exact_values, _positive_int, _prepare_game
from .group_lime import local_surrogate
from .discrete import predict_cif


def groups_for(specification):
    if specification not in ('seven', 'multimodal_values'):
        raise ValueError('TABLE_VALUE_SPECIFICATION_REQUIRED')
    names = SPECS[specification]
    if specification == 'seven':
        return tuple((name, (i,)) for i,name in enumerate(names))
    return tuple((name, (i,)) for i,name in enumerate(names[:8])) + (('bilateral_hippocampus', (8,9)),)


def model_state_identity(model):
    """In-memory diagnostic state digest; not a model archive provenance check."""
    return hashlib.sha256(joblib.hash(model, hash_name='sha1').encode('ascii')).hexdigest()


def training_reference_statistics(model, specification):
    """Validate the original table layout and copy its saved raw-scale stats."""
    groups_for(specification)
    names = list(SPECS[specification]); n = len(names)
    if not isinstance(model, Pipeline) or list(model.named_steps) != ['preprocess','hazards']:
        raise ValueError('ORIGINAL_TABLE_PIPELINE_REQUIRED')
    preprocess = model.named_steps['preprocess']
    if not isinstance(preprocess, ColumnTransformer) or preprocess.remainder != 'drop' or preprocess.transformer_weights is not None:
        raise ValueError('ORIGINAL_TABLE_TRANSFORMER_REQUIRED')
    transformers = getattr(preprocess, 'transformers_', ())
    if (len(transformers) != 2 or [row[0] for row in transformers] != ['clinical','month']
            or list(transformers[0][2]) != names or list(transformers[1][2]) != ['month']
            or list(getattr(preprocess,'feature_names_in_',())) != names+['month']):
        raise ValueError('TABLE_PREPROCESSOR_FEATURE_ORDER')
    clinical = preprocess.named_transformers_['clinical']
    month = preprocess.named_transformers_['month']
    if (not isinstance(clinical,Pipeline) or list(clinical.named_steps) != ['impute','scale']
            or not isinstance(month,OneHotEncoder) or month.handle_unknown != 'ignore'
            or len(getattr(month,'categories_',())) != 1
            or not np.array_equal(month.categories_[0],np.arange(1,25))):
        raise ValueError('TABLE_PREPROCESSOR_CONFIGURATION')
    imputer = clinical.named_steps['impute']; scaler = clinical.named_steps['scale']
    if (not isinstance(imputer,SimpleImputer) or imputer.strategy != 'median'
            or imputer.add_indicator is not True or imputer.keep_empty_features is not True
            or not isinstance(imputer.missing_values,float) or not np.isnan(imputer.missing_values)
            or not isinstance(scaler,StandardScaler) or not scaler.with_mean or not scaler.with_std
            or list(getattr(imputer,'feature_names_in_',())) != names):
        raise ValueError('TABLE_SAVED_REFERENCE_POLICY')
    medians = np.asarray(getattr(imputer,'statistics_',()),dtype=float)
    indicator = getattr(imputer,'indicator_',None)
    indicator_features = np.asarray(getattr(indicator,'features_',()))
    if (medians.shape != (n,) or not np.isfinite(medians).all()
            or not isinstance(indicator,MissingIndicator) or indicator.features != 'missing-only'
            or indicator.error_on_new is not False
            or indicator_features.ndim != 1 or indicator_features.dtype.kind not in 'iu'
            or not np.array_equal(indicator_features,np.unique(indicator_features))
            or (indicator_features < 0).any() or (indicator_features >= n).any()):
        raise ValueError('TABLE_SAVED_IMPUTATION_OR_INDICATORS')
    width = n+len(indicator_features)
    means = np.asarray(getattr(scaler,'mean_',()),dtype=float)
    scales = np.asarray(getattr(scaler,'scale_',()),dtype=float)
    if (means.shape != (width,) or scales.shape != (width,)
            or getattr(scaler,'n_features_in_',None) != width
            or not np.isfinite(means).all() or not np.isfinite(scales).all() or (scales <= 0).any()):
        raise ValueError('TABLE_SAVED_SCALER_LAYOUT')
    classes = np.asarray(getattr(model.named_steps['hazards'],'classes_',()))
    if classes.shape != (3,) or not np.array_equal(np.sort(classes),[0,1,2]):
        raise ValueError('TABLE_THREE_CAUSE_MODEL_REQUIRED')
    return dict(median=medians.copy(), mean=means[:n].copy(),
                indicator_features=indicator_features.astype(int,copy=True),
                transformed_numeric_width=width)


def _frame(frame, specification):
    groups = groups_for(specification); names = list(SPECS[specification])
    if not isinstance(frame,pd.DataFrame) or not 1 <= len(frame) <= 256 or frame.columns.duplicated().any():
        raise ValueError('TABLE_EXPLANATION_FRAME')
    if not {'subject_id',*names} <= set(frame):
        raise ValueError('TABLE_EXPLANATION_COLUMNS')
    snapshot = frame[['subject_id',*names]].copy(deep=True)
    ids = snapshot.subject_id
    if ids.isna().any() or ids.duplicated().any() or not all(type(x) is str and x for x in ids):
        raise ValueError('TABLE_UNIQUE_SUBJECT_IDS')
    for name in names:
        if (not pd.api.types.is_numeric_dtype(snapshot[name])
                or pd.api.types.is_bool_dtype(snapshot[name])
                or pd.api.types.is_complex_dtype(snapshot[name])):
            raise ValueError('TABLE_NUMERIC_OR_NULL')
        if pd.api.types.is_integer_dtype(snapshot[name]) and any(int(float(x)) != int(x) for x in snapshot[name].dropna()):
            raise ValueError('TABLE_NUMERIC_REPRESENTATION_LOSS')
    snapshot = snapshot.astype({name:'float64' for name in names})
    values = snapshot[names].to_numpy()
    if np.isinf(values).any():
        raise ValueError('TABLE_FINITE_OR_NULL')
    if specification != 'seven' and not np.array_equal(np.isnan(values[:,8]),np.isnan(values[:,9])):
        raise ValueError('TABLE_BILATERAL_MRI_PAIR')
    return snapshot, groups


def table_reference(model, frame, specification, reference_kind='median'):
    if reference_kind not in ('median','mean'):
        raise ValueError('TABLE_REFERENCE_KIND')
    stats = training_reference_statistics(model,specification)
    result,_ = _frame(frame,specification)
    for i,name in enumerate(SPECS[specification]):
        result.loc[result[name].notna(),name] = stats[reference_kind][i]
    return result


class _Game:
    def __init__(self,model,frame,specification,reference_kind,limit,reference_values=None,evaluation_event=None):
        if evaluation_event is not None and not callable(evaluation_event):
            raise ValueError('TABLE_EVALUATION_EVENT_CALLBACK')
        self.evaluation_event = evaluation_event
        self.model = model; self.specification = specification
        self.snapshot,self.groups = _frame(frame,specification)
        self.names = list(SPECS[specification])
        self.identity = model_state_identity(model)
        self.reference = table_reference(model,self.snapshot,specification,reference_kind)
        self.reference_policy = 'outer_training_person_month_'+reference_kind
        if reference_values is not None:
            supplied = np.asarray(reference_values)
            if (reference_kind != 'median' or supplied.dtype.kind not in 'fi'
                    or supplied.shape != (len(self.names),) or not np.isfinite(supplied).all()):
                raise ValueError('TABLE_EXPLICIT_RAW_SCALE_MEDIAN_REFERENCE')
            supplied = supplied.astype(float,copy=True)
            for i,name in enumerate(self.names):
                self.reference.loc[self.reference[name].notna(),name] = supplied[i]
            self.reference_policy = 'caller_supplied_raw_scale_median_same_missingness'
        self.count = 0; self.state_rows = 0; self.limit = limit

    def predict(self,states):
        if self.count >= self.limit:
            raise ValueError('TABLE_CIF_CALL_BUDGET')
        if len(states) == len(self.snapshot):
            current = self.snapshot.copy(deep=True)
        elif len(self.snapshot) == 1:
            current = self.snapshot.iloc[np.zeros(len(states),dtype=int)].reset_index(drop=True).copy()
            current['subject_id'] = [f'XAI-TABLE-STATE-{i}' for i in range(len(states))]
        else:
            raise ValueError('TABLE_GAME_ROW_ALIGNMENT')
        current.loc[:,self.names] = states
        before = current.copy(deep=True)
        if self.evaluation_event:self.evaluation_event('attempt',len(states))
        self.count += 1; self.state_rows += len(states)
        with threadpool_limits(limits=1):
            result = predict_cif(self.model,current,self.names)
        if not current.equals(before) or result.subject_id.tolist() != current.subject_id.tolist():
            raise ValueError('TABLE_CIF_INPUT_OR_ORDER_CHANGED')
        if self.evaluation_event:self.evaluation_event('complete',len(states))
        return result[['ad_cif_24m','other_dementia_cif_24m']].to_numpy(float)

    def finish(self,frame,result,reference_kind):
        current,_ = _frame(frame,self.specification)
        if not current.equals(self.snapshot) or model_state_identity(self.model) != self.identity:
            raise ValueError('TABLE_EXPLANATION_INPUT_OR_MODEL_CHANGED')
        result.update(model_identity=self.identity,reference_policy=self.reference_policy,
                      specification=self.specification,cif_calls=self.count,cif_state_rows=self.state_rows,
                      predictive_fits=0,calibration_fits=0,missingness_effects_explained=False)
        return result


def explain_shapley(model,frame,specification,*,reference_kind='median',max_coalitions=512,
                    reference_values=None,evaluation_event=None):
    """Use the model's reference by default, or an explicit raw-scale median.

    A supplied finite feature vector supports crossed model/reference games.
    Only observed cells are replaced; model preprocessing and missingness stay
    untouched. The caller must establish the vector's training provenance.
    This does not certify an external reference, fold eligibility or permission.
    """
    _positive_int(max_coalitions,'COALITION_BUDGET',512)
    game = _Game(model,frame,specification,reference_kind,max_coalitions,reference_values,evaluation_event)
    result = exact_values(game.predict,game.snapshot[game.names].to_numpy(),
                          game.reference[game.names].to_numpy(),game.groups,max_coalitions=max_coalitions)
    return game.finish(frame,result,reference_kind)


def explain_lime(model,frame,specification,*,reference_kind='median',samples=64,
                 seed=20261027,kernel_width=.75,ridge=1e-6,holdout_samples=32,
                 reserve_holdout=True,batch_predictions=True,max_evaluations=512):
    if not isinstance(frame,pd.DataFrame) or len(frame) != 1:
        raise ValueError('TABLE_LIME_ONE_SUBJECT')
    if type(reserve_holdout) is not bool or type(batch_predictions) is not bool:
        raise ValueError('TABLE_LIME_OPTIONS')
    _positive_int(samples,'SAMPLE_BUDGET',512)
    game = _Game(model,frame,specification,reference_kind,max_evaluations)
    values = game.snapshot[game.names].to_numpy(); reference = game.reference[game.names].to_numpy()
    same = (values == reference) | (np.isnan(values) & np.isnan(reference))
    active = int(sum(not same[:,list(cols)].all() for _,cols in game.groups))
    fit_samples = min(samples,2**active,max(active+1,2**active//2)) if reserve_holdout else samples
    # Collect already evaluated states only: add MAE without new CIF or fits.
    evaluated = []
    def collect(states):
        output = game.predict(states)
        evaluated.append((states.copy(),output.copy()))
        return output
    result = local_surrogate(collect,values,reference,game.groups,samples=fit_samples,
        seed=seed,kernel_width=kernel_width,ridge=ridge,max_evaluations=max_evaluations,
        batch_predict=collect if batch_predictions else None,holdout_samples=holdout_samples)
    active_indices = _prepare_game(values,reference,game.groups)[-1]
    states = np.concatenate([row[0] for row in evaluated])
    outputs = np.concatenate([row[1] for row in evaluated])
    masks = np.column_stack([
        ((states[:,list(game.groups[g][1])] == values[:,list(game.groups[g][1])])
         | (np.isnan(states[:,list(game.groups[g][1])]) & np.isnan(values[:,list(game.groups[g][1])]))).all(axis=1)
        for g in active_indices]) if active_indices else np.zeros((len(states),0))
    predicted = result['surrogate_intercept'] + masks @ result['contributions'][0,active_indices]
    weights = np.exp(-np.mean((1-masks.astype(float))**2,axis=1)/kernel_width**2) if active_indices else np.ones(len(states))
    count = result['neighborhood_evaluations']
    result['weighted_mae'] = np.average(np.abs(outputs[:count]-predicted[:count]),axis=0,weights=weights[:count]).tolist()
    result['heldout_fidelity']['weighted_mae'] = (np.average(np.abs(outputs[count:]-predicted[count:]),axis=0,weights=weights[count:]).tolist()
        if result['heldout_fidelity']['samples'] else None)
    result.update(requested_fit_samples=samples,reserved_holdout=reserve_holdout,
                  actual_fit_sample_budget=fit_samples,active_group_count=int(active))
    return game.finish(frame,result,reference_kind)
