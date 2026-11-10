"""Export bounded research metadata; no data/model readers or execution permit."""
from copy import deepcopy
import hashlib
import json
import re

from .input_admission import POLICY, SPECS, UNITS

SCHEMA = 'research-experiment-config-v1'
GRAPH_SCHEMA = 'research-graph-experiment-config-v1'
GRAPH_FAMILY = 'conditional_graph_attention'
GRAPH_ARMS = ('self_only', 'prior', 'semantic_permutation')
GRAPH_SEEDS = (20260926, 20260927, 20260928)
GRAPH_PROTOCOL_SHA256 = 'ee74d09fff5f530e086dff5c0f3dd49513ffe6db42cb6ef8f05c8a4799413c49'
MAX_BYTES = 8192
_SHA = re.compile(r'[0-9a-f]{64}\Z')
_HASH_KEYS = {'manifestSha256', 'artifactSha256', 'trainingPlanSha256',
              'runtimeSourceSha256', 'softwareLockSha256'}
_SELECTION_KEYS = {'specification', 'family', 'predictionPolicy',
                   'predictionWindowMonths', 'referencePolicy', 'availabilityPolicy',
                   'splitPolicy', 'randomSeed', 'hashes'}
_GRAPH_SELECTION_KEYS = (_SELECTION_KEYS - {'randomSeed'}) | {'graph'}
_GRAPH_HASH_KEYS = (_HASH_KEYS - {'artifactSha256'}) | {'graphProtocolSha256'}
_GRAPH_KEYS = {'arm', 'initializationSeeds', 'aggregationPolicy', 'artifactSha256BySeed'}

class ExperimentConfigError(ValueError):
    """A stable error code without the supplied value or private field name."""

def _fail(code):
    raise ExperimentConfigError(code) from None

def _exact_dict(value, keys):
    if type(value) is not dict or set(value) != keys:
        _fail('CONFIG_FIELDS')

def _policies(value, reference_policies):
    for key in ('specification', 'family', 'predictionPolicy', 'referencePolicy',
                'availabilityPolicy', 'splitPolicy'):
        if type(value[key]) is not str:
            _fail('CONFIG_POLICY')
    if value['specification'] not in SPECS:
        _fail('CONFIG_INFORMATION_SET')
    if value['predictionPolicy'] != POLICY['predictionPolicy']:
        _fail('CONFIG_PREDICTION_POLICY')
    if type(value['predictionWindowMonths']) is not int or value['predictionWindowMonths'] != 24:
        _fail('CONFIG_WINDOW')
    if value['referencePolicy'] not in reference_policies:
        _fail('CONFIG_REFERENCE_POLICY')
    if value['availabilityPolicy'] not in POLICY['availabilityPolicies']:
        _fail('CONFIG_AVAILABILITY_POLICY')
    if value['splitPolicy'] not in ('EXTERNAL_FROZEN_SUBJECT_SPLIT', 'FINAL_MODEL_NO_SPLIT'):
        _fail('CONFIG_SPLIT_POLICY')

def _hashes(value, keys):
    _exact_dict(value, keys)
    if any(type(v) is not str or _SHA.fullmatch(v) is None for v in value.values()):
        _fail('CONFIG_RELEASE_HASH')

def _selection(value):
    _exact_dict(value, _SELECTION_KEYS)
    _policies(value, ('SAVED_PIPELINE_MEDIAN', 'SAVED_PIPELINE_MEAN'))
    if value['family'] not in ('logistic', 'random_forest', 'xgboost'):
        _fail('CONFIG_FAMILY')
    if type(value['randomSeed']) is not int or not 0 <= value['randomSeed'] < 2**32:
        _fail('CONFIG_SEED')
    _hashes(value['hashes'], _HASH_KEYS)
    return deepcopy(value)

def _graph_selection(value):
    _exact_dict(value, _GRAPH_SELECTION_KEYS)
    _policies(value, ('SAVED_GRAPH_MEDIAN', 'SAVED_GRAPH_MEAN'))
    if value['specification'] != 'multimodal_values':
        _fail('CONFIG_GRAPH_INFORMATION_SET')
    _hashes(value['hashes'], _GRAPH_HASH_KEYS)
    if value['hashes']['graphProtocolSha256'] != GRAPH_PROTOCOL_SHA256:
        _fail('CONFIG_GRAPH_PROTOCOL')
    graph = value['graph']
    _exact_dict(graph, _GRAPH_KEYS)
    if type(graph['arm']) is not str or graph['arm'] not in GRAPH_ARMS:
        _fail('CONFIG_GRAPH_ARM')
    seeds = graph['initializationSeeds']
    if type(seeds) is not list or any(type(seed) is not int for seed in seeds):
        _fail('CONFIG_GRAPH_SEEDS')
    aggregation = graph['aggregationPolicy']
    if type(aggregation) is not str:
        _fail('CONFIG_GRAPH_AGGREGATION')
    if aggregation == 'SINGLE_SEED_CIF':
        if len(seeds) != 1 or seeds[0] not in GRAPH_SEEDS:
            _fail('CONFIG_GRAPH_SEEDS')
    elif aggregation == 'MEAN_3_SEED_CIF':
        if seeds != list(GRAPH_SEEDS):
            _fail('CONFIG_GRAPH_SEEDS')
    else:
        _fail('CONFIG_GRAPH_AGGREGATION')
    artifacts = graph['artifactSha256BySeed']
    _hashes(artifacts, {str(seed) for seed in seeds})
    if len(set(artifacts.values())) != len(seeds):
        _fail('CONFIG_GRAPH_ARTIFACTS_NOT_DISTINCT')
    return deepcopy(value)

def _payload(selection):
    is_graph = (type(selection) is dict and type(selection.get('family')) is str
                and selection['family'] == GRAPH_FAMILY)
    selected = _graph_selection(selection) if is_graph else _selection(selection)
    names = list(SPECS[selected['specification']])
    return {'schemaVersion': GRAPH_SCHEMA if is_graph else SCHEMA,
            'purpose': 'REPRODUCIBILITY_METADATA_ONLY',
            'executionAuthorized': False, 'clinicalUse': False, 'selection': selected,
            'features': names, 'units': {name: UNITS[name] for name in names}}

def _encode(payload):
    return json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(',', ':'),
                      allow_nan=False).encode('utf-8') + b'\n'

def export_experiment_config(selection):
    """Return deterministic UTF-8 JSON bytes for explicitly selected metadata.

    Project existing plans into the documented selection before calling. Unknown
    fields fail instead of silently carrying paths, records, folds or parameters.
    Release digests are caller declarations, not proof of their origin or rights.
    """
    return _encode(_payload(selection))

def _unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            _fail('CONFIG_DUPLICATE_KEY')
        result[key] = value
    return result

def _constant(_):
    _fail('CONFIG_JSON')

def load_experiment_config(raw, *, expected_sha256):
    """Check a bounded export against an independently retained byte digest.

    This parses metadata only: it never opens the referenced files or returns
    an inference permit, training request or source/QC qualification.
    """
    if type(raw) is not bytes or not 1 <= len(raw) <= MAX_BYTES:
        _fail('CONFIG_BYTES')
    if type(expected_sha256) is not str or _SHA.fullmatch(expected_sha256) is None:
        _fail('CONFIG_EXPECTED_HASH')
    if hashlib.sha256(raw).hexdigest() != expected_sha256:
        _fail('CONFIG_HASH_MISMATCH')
    try:
        value = json.loads(raw.decode('utf-8'), object_pairs_hook=_unique_pairs,
                           parse_constant=_constant)
    except ExperimentConfigError:
        raise
    except (UnicodeError, ValueError, TypeError, RecursionError):
        _fail('CONFIG_JSON')
    _exact_dict(value, {'schemaVersion', 'purpose', 'executionAuthorized', 'clinicalUse',
                        'selection', 'features', 'units'})
    expected = _payload(value['selection'])
    # Type-aware canonical bytes keep 0/False and 1/True distinct.
    try:
        actual_bytes = _encode(value)
    except (ValueError, TypeError, OverflowError, RecursionError, UnicodeError):
        _fail('CONFIG_JSON')
    if actual_bytes != _encode(expected):
        _fail('CONFIG_CONTENT_MISMATCH')
    return expected
