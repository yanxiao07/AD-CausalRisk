"""Frozen course wire-schema and semantic checks using standard-library code.

Only the keyword subset present in the shipped contract is supported. Future
unknown keywords fail closed. This is not a general JSON Schema implementation.
"""
from datetime import date, datetime
import hashlib
import json
import math
from pathlib import Path
import re
from .deepagent_contract import (validate_request, AgentContractError, CONTRACT_VERSION,
                                WORKFLOW_VERSION, MODEL_VERSION, STAGES, FEATURES, LIMITATIONS)
from .course_fusion import select_fusion
from .course_graph import NODE_SPECS, PRIOR_NOTE

CONTRACT_FILE=Path(__file__).resolve().parents[1]/'contracts/python-agent.openapi.json'
FROZEN_CONTRACT_SHA256='90384cccce76dd35c8d31dbcc182d8c2f0ff32031948dc694ca7520cdf13eb6c'
REQUIRED_LIMITATIONS=tuple(LIMITATIONS)
KEYWORDS={'type','properties','required','additionalProperties','items','maxItems','const','enum',
          'format','pattern','minLength','maxLength','minimum','maximum','allOf','if','then','else'}
_HTTP_TOKEN=r"[!#$%&'*+.^_`|~0-9A-Za-z-]+"
_JSON_PARAMETER=re.compile(r'[ \t]*;[ \t]*('+_HTTP_TOKEN+r')[ \t]*=[ \t]*('+_HTTP_TOKEN+r'|"(?:[^"\\\r\n]|\\[^\r\n])*")[ \t]*')


class CourseProtocolError(ValueError):
    def __init__(self,code):self.code=code;super().__init__(code)


def require(condition,code='RESULT_SEMANTICS'):
    if not condition:raise CourseProtocolError(code)


def number(value):
    try:return type(value) in (int,float) and math.isfinite(value)
    except OverflowError:return False


def same(a,b):
    if type(a) is bool or type(b) is bool:return type(a) is type(b) and a==b
    return a==b


def json_tree(value,depth=0):
    require(depth<=64,'JSON_DEPTH')
    if isinstance(value,dict):
        require(all(isinstance(key,str) for key in value),'JSON_TYPES')
        for key in value:json_tree(key,depth)
        for child in value.values():json_tree(child,depth+1)
    elif isinstance(value,list):
        for child in value:json_tree(child,depth+1)
    elif isinstance(value,str):
        try:value.encode('utf-8')
        except UnicodeError:raise CourseProtocolError('JSON_TEXT') from None
    else:require(value is None or type(value) is bool or number(value),'JSON_TYPES')


def utf8_json_media(headers):
    """One JSON media declaration, with at most one plain UTF-8 charset."""
    if len(headers.get_all('Content-Type',[]))!=1:
        return False
    value=headers.get('Content-Type').strip(' \t')
    media,separator,params=value.partition(';')
    if media.strip(' \t').lower()!='application/json':
        return False
    suffix=separator+params
    position=0;seen_charset=False
    while position<len(suffix):
        match=_JSON_PARAMETER.match(suffix,position)
        if match is None:return False
        name,text=match.group(1).lower(),match.group(2)
        # Email header parsing silently joins charset*0/charset*1. HTTP JSON
        # accepts only a plain charset, never extended or continued metadata.
        if name.startswith('charset*'):return False
        if name=='charset':
            if text.startswith('"'):text=re.sub(r'\\(.)',r'\1',text[1:-1])
            if seen_charset or text.lower()!='utf-8':return False
            seen_charset=True
        position=match.end()
    return True


def identity_encoding(headers):
    """Content coding is absent or a single identity, never a coding chain."""
    values=headers.get_all('Content-Encoding',[])
    return not values or (len(values)==1 and values[0].strip().lower()=='identity')


def decode_json(raw):
    def pairs(items):
        result={}
        for key,value in items:
            require(key not in result,'INVALID_RESPONSE_JSON');result[key]=value
        return result
    def constant(_):raise CourseProtocolError('INVALID_RESPONSE_JSON')
    try:
        # json.loads(bytes) guesses UTF-16/32 even when HTTP declares UTF-8.
        # Decode explicitly so declarations and the actual wire agree.
        if isinstance(raw,(bytes,bytearray)):raw=raw.decode('utf-8')
        result=json.loads(raw,object_pairs_hook=pairs,parse_constant=constant)
        require(isinstance(result,dict),'INVALID_RESPONSE_JSON');json_tree(result)
        return result
    except (UnicodeError,ValueError,RecursionError):raise CourseProtocolError('INVALID_RESPONSE_JSON') from None


class CourseContract:
    def __init__(self):
        raw=CONTRACT_FILE.read_bytes();document=decode_json(raw)
        require(document['info']['version']=='course-agent-http-v1','CONTRACT_VERSION')
        self.sha256=hashlib.sha256(raw).hexdigest();self.schemas=document['components']['schemas']
        require(self.sha256==FROZEN_CONTRACT_SHA256,'FROZEN_CONTRACT_CHANGED')
        require(set(self.schemas)=={'AgentRequest','AgentResult','AgentError'},'CONTRACT_SCHEMAS')
        for schema in self.schemas.values():self.check_keywords(schema)

    @classmethod
    def check_keywords(cls,schema):
        require(isinstance(schema,dict) and set(schema)<=KEYWORDS,'UNSUPPORTED_SCHEMA_KEYWORD')
        for key,value in schema.items():
            if key=='properties':
                for child in value.values():cls.check_keywords(child)
            elif key in ('items','if','then','else'):cls.check_keywords(value)
            elif key=='allOf':
                for child in value:cls.check_keywords(child)

    def validate(self,name,value):
        json_tree(value)
        require(self.matches(self.schemas[name],value),'WIRE_SCHEMA_MISMATCH')

    @classmethod
    def matches(cls,schema,value):
        types=schema.get('type');types=[types] if isinstance(types,str) else types
        if types:
            checks={'null':value is None,'boolean':type(value) is bool,'string':isinstance(value,str),
                    'number':number(value),'integer':number(value) and float(value).is_integer(),
                    'array':isinstance(value,list),'object':isinstance(value,dict)}
            if not any(checks.get(kind,False) for kind in types):return False
        if 'const' in schema and not same(value,schema['const']):return False
        if 'enum' in schema and not any(same(value,item) for item in schema['enum']):return False
        if isinstance(value,dict):
            properties=schema.get('properties',{})
            if not set(schema.get('required',[]))<=set(value):return False
            if schema.get('additionalProperties') is False and not set(value)<=set(properties):return False
            if not all(cls.matches(child,value[key]) for key,child in properties.items() if key in value):return False
        if isinstance(value,list):
            if len(value)>schema.get('maxItems',len(value)):return False
            if 'items' in schema and not all(cls.matches(schema['items'],item) for item in value):return False
        if isinstance(value,str):
            if not schema.get('minLength',0)<=len(value)<=schema.get('maxLength',len(value)):return False
            if 'pattern' in schema and re.search(schema['pattern'],value) is None:return False
            try:
                if schema.get('format')=='date' and date.fromisoformat(value).isoformat()!=value:return False
                if schema.get('format')=='date-time':
                    if not re.fullmatch(r'[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]+)?(?:Z|[+-][0-9]{2}:[0-9]{2})',value):return False
                    if datetime.fromisoformat(value.replace('Z','+00:00')).utcoffset() is None:return False
            except ValueError:return False
        if number(value) and not schema.get('minimum',value)<=value<=schema.get('maximum',value):return False
        if not all(cls.matches(child,value) for child in schema.get('allOf',[])):return False
        if 'if' in schema:
            branch='then' if cls.matches(schema['if'],value) else 'else'
            if branch in schema and not cls.matches(schema[branch],value):return False
        return True


def validate_course_request(payload,contract):
    contract.validate('AgentRequest',payload)
    try:validate_request(payload)
    except AgentContractError:raise CourseProtocolError('REQUEST_SEMANTICS') from None


def context(payload):
    selected=select_fusion(payload)
    return tuple(selected[key] for key in ('visits','latest','indexDate','features','eligibleFeatures','blocked'))


def _rule_terms(visits,latest,features):
    """Check the frozen demo arithmetic without calling a workflow or scorer.

    Retain raw terms for threshold/level checks: rounding a displayed term first
    loses the side of a direction or level boundary. This is a response check,
    not a new prediction, learned model or clinical validation.
    """
    def clipped(value,low,high):return max(low,min(high,value))
    terms={}
    if latest.get('mmse') is not None:terms['MMSE']=clipped((27.-float(latest['mmse']))/10.,-.08,.22)
    if latest.get('moca') is not None:terms['MoCA']=clipped((25.-float(latest['moca']))/12.,-.04,.14)
    if latest.get('cdrSb') is not None:terms['CDR_SB']=clipped(float(latest['cdrSb'])/10.,0.,.20)
    if len(visits)>=2 and visits[0].get('mmse') is not None and latest.get('mmse') is not None:
        terms['MMSE_DELTA']=clipped(-(float(latest['mmse'])-float(visits[0]['mmse']))/30.,-.08,.14)
    if 'APOE_E4_COUNT' in features:
        terms['APOE_E4_COUNT']=clipped(float(features['APOE_E4_COUNT']['featureValue'])*.045,0.,.09)
    side='MRI_HIPPOCAMPUS_L' if 'MRI_HIPPOCAMPUS_L' in features else 'MRI_HIPPOCAMPUS_R'
    if side in features:terms['MRI_HIPPOCAMPUS']=clipped((.42-float(features[side]['featureValue']))*.22,-.03,.07)
    raw_score=.18
    for value in terms.values():raw_score+=value
    raw_score=clipped(raw_score,.05,.95)
    return terms,round(raw_score,4),'LOW' if raw_score<.34 else 'MEDIUM' if raw_score<.65 else 'HIGH'


def _quality_warnings(payload,visits,latest,index,features,eligible,blocked):
    """The fixed workflow's declared quality decisions, independent of wire text order."""
    warnings=[]
    if len(eligible)!=len(payload['features']):warnings.append('索引访视之后采集的特征已排除，防止时间泄漏')
    if FEATURES-set(features):warnings.append('部分遗传或 MRI 指标缺失，图谱对应路径标为 MISSING')
    if sum(row['visitDate']==index for row in visits)>1:warnings.append('最近访视日期对应多个记录，索引访视无法唯一确定')
    groups={}
    for row in eligible:groups.setdefault((row['featureName'],row['recordedAt']),set()).add(row.get('featureValue'))
    if any(len(values)>1 for values in groups.values()):warnings.append('同一特征在同一天存在冲突值，需人工核对')
    if payload['predictionWindowMonths']!=24:warnings.append('当前合成研究规则仅支持 24 个月预警任务')
    if blocked:
        if latest is None or all(latest.get(key) is None for key in ('mmse','moca','cdrSb')):
            warnings.append('缺少可用的核心认知访视，风险排序已阻断')
    else:
        for field,message in [('mmse','最近访视缺少 MMSE，解释完整度下降'),
                              ('moca','最近访视缺少 MoCA，未将该模态纳入本次评分'),
                              ('cdrSb','最近访视缺少 CDR-SB，结果需人工复核')]:
            if latest.get(field) is None:warnings.append(message)
        if 'APOE_E4_COUNT' not in features:warnings.append('APOE 特征缺失，个体化遗传上下文不完整')
        if not {'MRI_HIPPOCAMPUS_L','MRI_HIPPOCAMPUS_R'}&set(features):warnings.append('MRI 海马特征缺失，结构性上下文不完整')
    return warnings


def validate_course_result(result,payload,contract):
    try:return _validate_course_result(result,payload,contract)
    except (KeyError,TypeError,AttributeError,OverflowError,RecursionError):
        raise CourseProtocolError('RESULT_STRUCTURE') from None


def _validate_course_result(result,payload,contract):
    validate_course_request(payload,contract);contract.validate('AgentResult',result)
    require(all(result[key]==payload[key] for key in ('runId','dataVersion','modelVersion','contractVersion')),'RESULT_IDENTITY')
    require(result['workflowVersion']==WORKFLOW_VERSION and result['dataSource']=='SYNTHETIC_DEMO' and result['reviewRequired'] is True,'RESULT_SCOPE')
    require(set(REQUIRED_LIMITATIONS)<=set(result['limitations']),'RESULT_SCOPE')
    visits,latest,index,features,eligible,blocked=context(payload)
    missing=sorted(FEATURES-set(features));require(sorted(result['missingModalities'])==missing,'MISSING_MODALITIES')
    stages=result['stages'];expected=('DATA_FUSION',) if blocked else STAGES
    require(tuple(stage['stage'] for stage in stages)==expected and result['currentStage']==expected[-1],'STAGE_SEQUENCE')
    summary=stages[0]['summary']
    require(all(type(summary.get(key)) is int for key in ('visitCount','featureCount','excludedPostIndexFeatures'))
            and isinstance(summary.get('missingModalities'),list),'FUSION_EVIDENCE')
    require(summary.get('indexDate')==index and summary.get('visitCount')==len(visits)
            and summary.get('featureCount')==len(features) and summary.get('excludedPostIndexFeatures')==len(payload['features'])-len(eligible)
            and sorted(summary.get('missingModalities',[]))==missing and summary.get('qualityRuleVersion')=='synthetic-qa-v1','FUSION_EVIDENCE')
    require((result['status']=='BLOCKED')==blocked,'QUALITY_GATE')
    warnings=_quality_warnings(payload,visits,latest,index,features,eligible,blocked)
    require(len(result['warnings'])==len(set(result['warnings'])) and set(result['warnings'])==set(warnings)
            and result['qualityStatus']==('BLOCKED' if blocked else 'WARN' if warnings else 'PASS'),'QUALITY_WARNINGS')
    graph=result['graph']
    if blocked:
        require(stages[0]['status']=='BLOCKED' and graph['nodes']==graph['edges']==graph['paths']==[]
                and result['explanations']==[] and bool(result['warnings']),'BLOCKED_HAS_OUTPUT')
        _validate_output_narrative(result,{})
        return result
    require(all(stage['status']=='COMPLETED' for stage in stages),'STAGE_SEQUENCE')
    require((result['qualityStatus']=='WARN')==bool(result['warnings']),'QUALITY_WARNINGS')
    require(graph['graphVersion']=='synthetic-prior-v1','GRAPH_VERSION')
    nodes={node['id']:node for node in graph['nodes']}
    require(len(nodes)==len(graph['nodes'])==5 and set(nodes)==FEATURES|{'COGNITION','WARNING'},'GRAPH_NODES')
    for key,node in nodes.items():
        active=key in features or key in ('COGNITION','WARNING')
        require(node['status']==('ACTIVE' if active else 'MISSING'),'GRAPH_ACTIVATION')
        require(node['evidenceLevel']==('MODEL' if key=='WARNING' else 'PRIOR'),'GRAPH_EVIDENCE')
        if key in FEATURES:require(node.get('observedAt')==(features.get(key) or {}).get('recordedAt'),'GRAPH_SOURCE_DATE')
        else:require(node.get('observedAt') is None,'GRAPH_SOURCE_DATE')
    edge_map={(edge['from'],edge['to']):edge for edge in graph['edges']}
    expected_edges={(key,'COGNITION') for key in FEATURES}|{('COGNITION','WARNING')}
    require(len(edge_map)==len(graph['edges'])==4 and set(edge_map)==expected_edges,'GRAPH_EDGES')
    for (source,_),edge in edge_map.items():require(edge['status']==nodes[source]['status'],'GRAPH_ACTIVATION')
    paths=graph['paths'];require(len(paths)==4,'GRAPH_PATHS')
    require({tuple(path['nodes']) for path in paths}==expected_edges,'GRAPH_PATHS')
    for path in paths:require(path['status']==edge_map[tuple(path['nodes'])]['status'],'GRAPH_PATHS')
    explanations=result['explanations'];terms={item['feature'] for item in explanations}
    wanted={label for name,label in [('mmse','MMSE'),('moca','MoCA'),('cdrSb','CDR_SB')] if latest.get(name) is not None}
    if len(visits)>=2 and visits[0].get('mmse') is not None and latest.get('mmse') is not None:wanted.add('MMSE_DELTA')
    if 'APOE_E4_COUNT' in features:wanted.add('APOE_E4_COUNT')
    if {'MRI_HIPPOCAMPUS_L','MRI_HIPPOCAMPUS_R'}&set(features):wanted.add('MRI_HIPPOCAMPUS')
    require(len(terms)==len(explanations) and terms==wanted,'EXPLANATION_SOURCES')
    require(all(item['source']=='graph-masked-synthetic-rule-v1' for item in explanations),'EXPLANATION_SOURCES')
    score=result['riskScore'];require(.05<=score<=.95 and abs(score-round(score,4))<=1e-12,'SCORE_PRECISION')
    reconstructed=max(.05,min(.95,.18+sum(item['contribution'] for item in explanations)))
    # Each original contribution and the final score are rounded to four decimals.
    tolerance=(len(explanations)+1)*.00005+1e-12
    require(abs(reconstructed-score)<=tolerance,'EXPLANATION_RECONSTRUCTION')
    level=result['riskLevel'];rounding=.00005+1e-12
    require((level=='LOW' and score<=.34+rounding) or (level=='MEDIUM' and .34-rounding<=score<=.65+rounding)
            or (level=='HIGH' and score>=.65-rounding),'SCORE_LEVEL')
    raw_terms,expected_score,expected_level=_rule_terms(visits,latest,features)
    require(all(item['contribution']==round(raw_terms[item['feature']],4) for item in explanations),'EXPLANATION_RULE_VALUES')
    require(all(item['direction']==('UP' if raw_terms[item['feature']]>.002 else 'DOWN' if raw_terms[item['feature']]<-.002 else 'NEUTRAL')
                for item in explanations),'EXPLANATION_DIRECTION')
    require(score==expected_score and level==expected_level,'SCORE_RULE_VALUE')
    active_paths=sum(path['status']=='ACTIVE' for path in paths)
    require(type(stages[1]['summary'].get('activePaths')) is int and stages[1]['summary']['activePaths']==active_paths
            and stages[2]['summary'].get('method')=='graph-masked-synthetic-rule-v1'
            and type(stages[3]['summary'].get('contributionCount')) is int
            and stages[3]['summary']['contributionCount']==len(explanations),'STAGE_EVIDENCE')
    _validate_output_narrative(result,raw_terms)
    return result


def _validate_output_narrative(result,raw_terms):
    """Check the fixed rule's entire narrative; never classify arbitrary prose.

    A disclaimer does not neutralize a conflicting claim in another field.
    New prose or metadata requires a reviewed rule change, even when accepted
    structurally by the original wire schema. No scorer or network is called.
    """
    require(len(result['limitations'])==len(REQUIRED_LIMITATIONS)
            and set(result['limitations'])==set(REQUIRED_LIMITATIONS),'OUTPUT_LIMITATIONS')
    summary_keys=({'indexDate','visitCount','featureCount','excludedPostIndexFeatures',
                   'missingModalities','qualityRuleVersion'}, {'activePaths'}, {'method'}, {'contributionCount'})
    for stage,allowed in zip(result['stages'],summary_keys):
        require(set(stage['summary'])==allowed,'OUTPUT_SUMMARY_FIELDS')
    labels={name:label for name,label,_ in NODE_SPECS}
    for node in result['graph']['nodes']:
        require(node['label']==labels[node['id']],'OUTPUT_GRAPH_TEXT')
    for edge in result['graph']['edges']:
        require(edge['note']==PRIOR_NOTE and edge['direction']=='CONDITIONAL'
                and edge['evidenceLevel']=='PRIOR','OUTPUT_GRAPH_TEXT')
    for path in result['graph']['paths']:
        require(path['evidenceLevel']=='PRIOR','OUTPUT_GRAPH_TEXT')
    text={
        'MMSE':('MMSE 认知评分','当前认知评分对风险排序贡献偏高','当前认知评分对风险排序贡献偏低'),
        'MoCA':('MoCA 认知评分','MoCA 信号使排序分数上移','MoCA 信号使排序分数下移'),
        'CDR_SB':('CDR-SB','CDR-SB 信号在当前排序中贡献上移',None),
        'MMSE_DELTA':('纵向 MMSE 变化','纵向变化使排序分数上移','纵向变化暂未使排序分数上移'),
        'APOE_E4_COUNT':('APOE ε4 计数','遗传模态作为个体化上下文进入排序',None),
        'MRI_HIPPOCAMPUS':('MRI 海马数值','影像模态作为结构性上下文进入排序',None),
    }
    for item in result['explanations']:
        name=item['feature'];label,positive,negative=text[name]
        # Text follows the original raw contribution, not displayed rounding or
        # the separate +/-0.002 direction thresholds (zero delta is special).
        up=raw_terms[name]>0 if name=='MMSE_DELTA' else raw_terms[name]>=0
        expected=positive if up or negative is None else negative
        # Read old saved results without rewriting their original text. Only
        # this exact copy change is accepted; other messages remain strict.
        messages=(expected,)
        if name=='CDR_SB':
            messages+=('CDR-SB 信号在当前演示排序中贡献上移',)
        require(item['label']==label and item['message'] in messages
                and item['interpretation']=='预测贡献，不是因果效应','OUTPUT_EXPLANATION_TEXT')
