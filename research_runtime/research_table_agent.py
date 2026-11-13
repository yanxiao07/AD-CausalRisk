"""In-process candidate transport for research table assessment; no listener.

Model/context/subject and execution permission belong to the independently
configured host, never the request. Java still owns object access and storage.
"""
from copy import deepcopy

from python_service.course_protocol import decode_json, CourseProtocolError
from python_service.deepagent_contract import RUN_ID_RE
from .research_table_protocol import (CONTRACT, REQUEST_FIELDS, ResearchAgentError,
    digest, validate_response)
from .input_admission import (SCHEMA, POLICY, AdmissionError,
    _snapshot, _context, _token, validate_snapshot)
from .inmemory_inference import authorize_once
from .table_assessment import TableAssessment

def _fail(code, run_id=None):
    raise ResearchAgentError(code, run_id) from None


class ResearchTableAgent:
    """One host-bound model, subject and frozen evidence snapshot.

    This supplies no authentication service or artifact/source certification.
    data_scope is the host's declared use scope, never a verified origin label.
    There is no run store/replay: Java must persist its own idempotent task.
    """
    def __init__(self, *, model_key, subject_code, assessment, trusted_context,
                 data_scope):
        try:
            context = _snapshot(trusted_context, 'CONTEXT_FORMAT')
            _context(context)
            valid = (type(assessment) is TableAssessment and _token(model_key)
                and _token(subject_code) and assessment.model_identity == context['model']
                and data_scope in ('CONSTRUCTED_VALIDATION', 'CONTROLLED_RESEARCH'))
            if not valid:
                _fail('HOST_BINDING_INVALID')
        except ResearchAgentError:
            raise
        except Exception:
            _fail('HOST_BINDING_INVALID')
        self._model_key, self._subject_code = model_key, subject_code
        self._assessment, self._context = assessment, context
        self._data_scope = data_scope

    def handle_json(self, raw, *, authorization_granted=False,
                    prediction_authorization_ref=None, explanation_authorization_ref=None,
                    max_coalitions=512):
        if type(raw) is not bytes:
            _fail('REQUEST_JSON_INVALID')
        if len(raw) > POLICY['maximumJSONBytes']:
            _fail('REQUEST_TOO_LARGE')
        try:
            body = decode_json(raw)
        except (CourseProtocolError, ValueError, RecursionError, UnicodeError):
            _fail('REQUEST_JSON_INVALID')
        if type(body) is not dict or set(body) != REQUEST_FIELDS:
            _fail('REQUEST_FIELDS_INVALID')
        if type(body['runId']) is not str or not RUN_ID_RE.fullmatch(body['runId']):
            _fail('RUN_ID_INVALID')
        run_id = body['runId']
        if body['contractVersion'] != CONTRACT:
            _fail('RESEARCH_CONTRACT_MISMATCH', run_id)
        if (authorization_granted is not True or not _token(prediction_authorization_ref)
                or not _token(explanation_authorization_ref)):
            _fail('INDEPENDENT_EXECUTION_PERMISSION_REQUIRED', run_id)
        if body['modelKey'] != self._model_key:
            _fail('MODEL_NOT_BOUND', run_id)
        if body['subjectCode'] != self._subject_code:
            _fail('SUBJECT_NOT_BOUND', run_id)
        if any(body[key] != self._context[key]
               for key in ('dataVersion', 'indexDate', 'availabilityPolicy')):
            _fail('SNAPSHOT_BINDING_MISMATCH', run_id)
        request = dict(schemaVersion=SCHEMA, purpose='OFFLINE_RESEARCH_ADMISSION',
            dataVersion=body['dataVersion'], indexDate=body['indexDate'],
            availabilityPolicy=body['availabilityPolicy'],
            predictionWindowMonths=body['predictionWindowMonths'],
            model=deepcopy(self._context['model']),
            records=[dict(subjectCode=body['subjectCode'], features=body['features'])])
        try:
            admission = validate_snapshot(request, trusted_context=self._context)
        except AdmissionError:
            _fail('INPUT_DECLARATION_INVALID', run_id)
        try:
            grant = None if admission['status'] != 'METADATA_CONSISTENT' else authorize_once(
                request, trusted_context=self._context, authorization_granted=True,
                authorization_ref=prediction_authorization_ref)
            result = self._assessment.run(request, trusted_context=self._context, permit=grant,
                explanation_authorized=True, explanation_authorization_ref=explanation_authorization_ref,
                max_coalitions=max_coalitions)
            if result['status'] not in ('COMPLETED', 'BLOCKED'):
                _fail('ASSESSMENT_FAILED', run_id)
            point = result['prediction']['predictions']
            response = dict(contractVersion=CONTRACT, runId=run_id,
                modelKey=self._model_key, subjectCode=self._subject_code,
                dataVersion=body['dataVersion'], indexDate=body['indexDate'],
                availabilityPolicy=body['availabilityPolicy'], predictionWindowMonths=24,
                dataScope=self._data_scope, status=result['status'],
                model=self._assessment.model_identity, admission=result['prediction']['admission'],
                prediction=None if point is None else dict(adCif24m=point[0]['ad_cif_24m'],
                    otherDementiaCif24m=point[0]['other_dementia_cif_24m']),
                explanation=result['explanation'], binding=result.get('basis'),
                requestSha256=digest(body),
                computation=dict(directCifCalls=result['prediction']['predictionCalls'],
                    explanationCifCalls=result['explanationCifCalls']),
                synchronousExecution=True, persistentRunStore=False,
                clinicalUse=False, sourceAuthenticityCertified=False,
                historicalAvailabilityCertified=False, individualCausalEffectIdentified=False,
                defaultModelChanged=False)
            return validate_response(response, expected_request=body,
                expected_model=self._context['model'], expected_data_scope=self._data_scope,
                expected_context_sha256=digest(self._context))
        except ResearchAgentError:
            raise
        except Exception:
            _fail('ASSESSMENT_FAILED', run_id)
