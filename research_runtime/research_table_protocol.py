"""Standard-library semantics for the draft research table response.

Expected request/model/scope must be independently held by the consumer.
Validation proves consistency, not authentic provenance or clinical validity.
"""
import hashlib
import json
import math
import re

from python_service.deepagent_contract import RUN_ID_RE
from .input_admission import _snapshot, _model, _item, _sha, _token, _date

CONTRACT = 'research-table-agent-v1'
REQUEST_FIELDS = {'contractVersion', 'runId', 'modelKey', 'subjectCode',
    'dataVersion', 'indexDate', 'availabilityPolicy', 'predictionWindowMonths', 'features'}
ERRORS = {
    'REQUEST_JSON_INVALID': 400, 'REQUEST_TOO_LARGE': 413,
    'REQUEST_FIELDS_INVALID': 400, 'RESEARCH_CONTRACT_MISMATCH': 409,
    'RUN_ID_INVALID': 400, 'MODEL_NOT_BOUND': 409, 'SUBJECT_NOT_BOUND': 403,
    'SNAPSHOT_BINDING_MISMATCH': 409, 'INDEPENDENT_EXECUTION_PERMISSION_REQUIRED': 403,
    'HOST_BINDING_INVALID': 503, 'INPUT_DECLARATION_INVALID': 400, 'ASSESSMENT_FAILED': 500,
}
RESPONSE_FIELDS = {'contractVersion','runId','modelKey','subjectCode','dataVersion','indexDate',
    'availabilityPolicy','predictionWindowMonths','dataScope','status','model','admission',
    'prediction','explanation','binding','requestSha256','computation','synchronousExecution',
    'persistentRunStore','clinicalUse','sourceAuthenticityCertified','historicalAvailabilityCertified',
    'individualCausalEffectIdentified','defaultModelChanged'}
FLAGS = ('clinicalUse','sourceAuthenticityCertified','historicalAvailabilityCertified','individualCausalEffectIdentified')


class ResearchAgentError(ValueError):
    def __init__(self, code, run_id=None):
        self.code, self.http_status = code, ERRORS[code]
        self.run_id = run_id
        super().__init__(code)

    def response(self):
        return dict(contractVersion=CONTRACT, runId=self.run_id, status='FAILED',
            code=self.code, message='研究评估未完成，请按错误码核对请求或宿主配置。')


class ResearchResponseError(ValueError):
    pass


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, allow_nan=False,
        sort_keys=True, separators=(',', ':')).encode('utf-8')).hexdigest()


def _require(condition):
    if not condition:
        raise ResearchResponseError('RESEARCH_RESPONSE_INVALID') from None


def _keys(value, keys):
    _require(type(value) is dict and set(value) == set(keys))


def _number(value):
    return type(value) in (int, float) and math.isfinite(value)


def _pair(value):
    _require(type(value) is list and len(value) == 2 and all(_number(v) and 0 <= v <= 1 for v in value)
        and sum(value) <= 1 + 1e-12)
    return value


def validate_response(response, *, expected_request, expected_model, expected_data_scope,
                      expected_context_sha256=None):
    """Return a copy after identity, denominator, binding and numeric checks.

    Never obtain expected_model/context identity by copying received fields.
    Caller authorization and source attestation remain outside this function.
    """
    try:
        result = _snapshot(response, 'RESEARCH_RESPONSE_INVALID')
        request = _snapshot(expected_request, 'RESEARCH_RESPONSE_INVALID')
        model = _snapshot(expected_model, 'RESEARCH_RESPONSE_INVALID')
        _keys(result, RESPONSE_FIELDS); _keys(request, REQUEST_FIELDS)
        _model(model)
        _require(model['family'] in ('logistic','random_forest','xgboost'))
        _require(type(request['runId']) is str and RUN_ID_RE.fullmatch(request['runId']) is not None)
        _require(all(_token(request[k]) for k in ('modelKey','subjectCode','dataVersion')))
        _date(request['indexDate'])
        _require(request['availabilityPolicy'] in ('RETROSPECTIVE_SNAPSHOT','AS_OF_INDEX'))
        _require(expected_data_scope in ('CONSTRUCTED_VALIDATION','CONTROLLED_RESEARCH'))
        names = model['features']
        _keys(request['features'], names)
        for name, item in request['features'].items():
            _item(item, name)
        _require(request['contractVersion'] == CONTRACT and result['contractVersion'] == CONTRACT)
        for key in ('runId','modelKey','subjectCode','dataVersion','indexDate','availabilityPolicy'):
            _require(result[key] == request[key])
        _require(type(request['predictionWindowMonths']) is int and request['predictionWindowMonths'] == 24)
        _require(type(result['predictionWindowMonths']) is int and result['predictionWindowMonths'] == 24)
        _require(result['dataScope'] == expected_data_scope and result['model'] == model)
        _require(result['requestSha256'] == digest(request))
        _require(result['synchronousExecution'] is True and result['persistentRunStore'] is False)
        _require(result['defaultModelChanged'] is False and all(result[k] is False for k in FLAGS))
        admission = result['admission']
        _keys(admission, ('schemaVersion','status','recordCount','blockedRecordCount','featureOrder',
            'missingFeatureCounts','availabilityPolicy','model','issues','predictionAllowed',*FLAGS))
        _require(admission['schemaVersion'] == 'research-input-admission-result-v1')
        _require(type(admission['recordCount']) is int and admission['recordCount'] == 1)
        _require(admission['model'] == model and admission['featureOrder'] == names
            and admission['availabilityPolicy'] == request['availabilityPolicy'])
        _require(admission['predictionAllowed'] is False and all(admission[k] is False for k in FLAGS))
        _keys(admission['missingFeatureCounts'], names)
        for name in names:
            count = admission['missingFeatureCounts'][name]
            _require(type(count) is int and count == int(request['features'][name]['value'] is None))
        _require(type(admission['issues']) is list)
        for issue in admission['issues']:
            _keys(issue, ('code','severity','feature','count'))
            _require(type(issue['code']) is str and re.fullmatch(r'[A-Z][A-Z0-9_]*',issue['code']) is not None
                and issue['severity'] in ('BLOCK','WARN') and issue['feature'] in [None,*names]
                and type(issue['count']) is int and issue['count'] == 1)
        computation = result['computation']
        _keys(computation, ('directCifCalls','explanationCifCalls'))
        _require(all(type(v) is int for v in computation.values()))
        if result['status'] == 'BLOCKED':
            _require(admission['status'] == 'BLOCKED' and type(admission['blockedRecordCount']) is int
                and admission['blockedRecordCount'] == 1 and any(i['severity'] == 'BLOCK' for i in admission['issues']))
            _require(all(result[k] is None for k in ('prediction','explanation','binding'))
                and all(value == 0 for value in computation.values()))
            return result
        _require(result['status'] == 'COMPLETED' and admission['status'] == 'METADATA_CONSISTENT'
            and type(admission['blockedRecordCount']) is int and admission['blockedRecordCount'] == 0
            and not any(i['severity'] == 'BLOCK' for i in admission['issues']))
        _require(computation['directCifCalls'] == 1 and 1 <= computation['explanationCifCalls'] <= 512)
        point = result['prediction']
        _keys(point, ('adCif24m','otherDementiaCif24m'))
        values = _pair([point['adCif24m'],point['otherDementiaCif24m']])
        explanation, binding = result['explanation'], result['binding']
        _keys(binding, ('inputSha256','trustedContextSha256','model','modelStateToken','referenceSha256',
            'referencePolicy','featureOrder','causeOrder','predictionWindowMonths','predictionPolicy'))
        core_request = dict(schemaVersion='research-real-input-admission-v1', purpose='OFFLINE_RESEARCH_ADMISSION',
            dataVersion=request['dataVersion'], indexDate=request['indexDate'],
            availabilityPolicy=request['availabilityPolicy'], predictionWindowMonths=24, model=model,
            records=[dict(subjectCode=request['subjectCode'],features=request['features'])])
        _require(binding['inputSha256'] == digest(core_request) and binding['model'] == model
            and binding['featureOrder'] == names and binding['causeOrder'] == ['AD','OTHER_DEMENTIA']
            and type(binding['predictionWindowMonths']) is int and binding['predictionWindowMonths'] == 24
            and binding['predictionPolicy'] == model['predictionPolicy'])
        _require(all(_sha(binding[k]) for k in ('trustedContextSha256','modelStateToken','referenceSha256'))
            and binding['referencePolicy'] in ('SAVED_TABLE_MEDIAN','SAVED_TABLE_MEAN'))
        if expected_context_sha256 is not None:
            _require(_sha(expected_context_sha256) and binding['trustedContextSha256'] == expected_context_sha256)
        _keys(explanation, ('method','groups','referenceCif','predictionCif','contributions',
            'reconstructionMaxError','missingnessEffectsExplained','isCausalEffect','limeStatus'))
        groups = names if len(names) == 7 else [*names[:8],'bilateral_hippocampus']
        _require(explanation['method'] == 'exact-single-reference-group-shapley-v1'
            and explanation['groups'] == groups and explanation['missingnessEffectsExplained'] is False
            and explanation['isCausalEffect'] is False and explanation['limeStatus'] == 'NOT_EVALUATED_NO_LOCAL_FIT')
        for key in ('referenceCif','predictionCif','contributions'):
            _require(type(explanation[key]) is list and len(explanation[key]) == 1)
        baseline, predicted = _pair(explanation['referenceCif'][0]), _pair(explanation['predictionCif'][0])
        contributions = explanation['contributions'][0]
        _require(type(contributions) is list and len(contributions) == len(groups))
        _require(all(type(row) is list and len(row) == 2 and all(_number(x) for x in row) for row in contributions))
        error = max(abs(baseline[cause] + sum(row[cause] for row in contributions) - values[cause]) for cause in range(2))
        _require(all(abs(predicted[i]-values[i]) <= 1e-12 for i in range(2)) and error <= 1e-12)
        reported = explanation['reconstructionMaxError']
        _require(_number(reported) and 0 <= reported <= 1e-12 and abs(reported-error) <= 1e-12)
        return result
    except Exception:
        raise ResearchResponseError('RESEARCH_RESPONSE_INVALID') from None
