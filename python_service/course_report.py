"""Portable reports for the original course AgentRequest/AgentResult pair.

Validation and rendering only: no service start, model call or business storage.
Teaching output is a teaching view, not a Java patient-authorization boundary.
"""
import argparse
import copy
import csv
import hashlib
from html import escape
import io
import json
from pathlib import Path
from .course_protocol import CourseContract, CourseProtocolError, decode_json, validate_course_result
from .deepagent_contract import LIMITATIONS
from .research_report import REPORT_STYLE, STYLESHEET_PATH

REPORT_VERSION = 'course-synthetic-report-v1'
MAX_PAIR_BYTES = 256 * 1024
BOUNDARIES = [
    '本报告仅适用于明确声明的课程合成数据；不是诊疗报告。',
    '演示排序分数不是已经校准的个人患病概率，条件图箭头不证明病因或治疗收益。',
    '报告校验不认证输入来源或服务响应真伪，不构成新的模型执行、训练或临床验证。',
    '教学版不是患者摘要；用户角色、对象权限、业务保存与导出审计仍由Java处理。',
]


class CourseReportError(ValueError):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def require(condition, code):
    if not condition: raise CourseReportError(code)


def _encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False).encode('utf-8')


def build_course_report(request, result, *, audience='research'):
    require(type(audience) is str and audience in ('research', 'teaching'), 'REPORT_AUDIENCE')
    try:
        # Check and copy before mapping; an unrelated/real/failed DTO is rejected.
        raw_request, raw_result = _encoded(request), _encoded(result)
        require(len(raw_request) + len(raw_result) <= MAX_PAIR_BYTES, 'REPORT_SIZE')
        contract = CourseContract()
        validate_course_result(result, request, contract)
        request, result = copy.deepcopy(request), copy.deepcopy(result)
    except CourseReportError:
        raise
    except (CourseProtocolError, KeyError, TypeError, ValueError, OverflowError, RecursionError, UnicodeError, OSError):
        raise CourseReportError('REPORT_INPUT_INVALID') from None
    technical = audience == 'research'
    explanations = [{key: row[key] for key in ('feature', 'label', 'message', 'direction')} |
                    {'contribution': row['contribution'] if technical else None} for row in result['explanations']]
    return {'reportVersion': REPORT_VERSION, 'scope': 'COURSE_SYNTHETIC_REPORT', 'audience': audience,
            'dataSource': 'SYNTHETIC_DEMO', 'subjectCode': request['subjectCode'], 'runId': result['runId'],
            'contractVersion': result['contractVersion'], 'modelVersion': result['modelVersion'],
            'workflowVersion': result['workflowVersion'], 'dataVersion': result['dataVersion'],
            'requestSha256': hashlib.sha256(raw_request).hexdigest(), 'resultSha256': hashlib.sha256(raw_result).hexdigest(),
            'contractSha256': contract.sha256, 'status': result['status'], 'qualityStatus': result['qualityStatus'],
            'predictionWindowMonths': request['predictionWindowMonths'], 'riskScore': result['riskScore'],
            'riskLevel': result['riskLevel'], 'predictionAvailable': result['status'] == 'COMPLETED',
            'missingModalities': result['missingModalities'], 'warnings': result['warnings'],
            'explanations': explanations, 'graph': result['graph'] if technical else None,
            'stages': [{key: row[key] for key in ('stage','status','durationMs')} for row in result['stages']] if technical else [],
            'sources': {'visits': request['visits'], 'features': request['features']} if technical else None,
            'limitations': list(dict.fromkeys(BOUNDARIES + LIMITATIONS + result['limitations'])),
            'reviewRequired': True, 'modelExecutedByReport': False, 'persisted': False,
            'sourceAuthenticityVerified': False, 'clinicalValidityEstablished': False,
            'causalEffectIdentified': False}


def render_course_html(request, result, *, audience='research', stylesheet_href=None):
    report = build_course_report(request, result, audience=audience)
    require(stylesheet_href in (None, STYLESHEET_PATH), 'REPORT_STYLESHEET')
    e = lambda value: escape(str(value), quote=True)
    style = f'<link rel="stylesheet" href="{STYLESHEET_PATH}">' if stylesheet_href else f'<style>{REPORT_STYLE}</style>'
    score = '不可用（质量阻断）' if not report['predictionAvailable'] else e(report['riskScore'])
    explanation_rows = ''.join(f'<tr><th scope="row">{e(row["label"])}</th><td>{e(row["message"])}</td>'
        f'<td>{e(row["direction"])}</td><td>{e(row["contribution"]) if row["contribution"] is not None else "教学版省略"}</td></tr>' for row in report['explanations'])
    content = '<section><h2>解释与路径</h2>'
    if explanation_rows:
        content += '<div class="table-scroll" tabindex="0" role="region" aria-label="课程解释"><table><thead><tr><th>特征</th><th>说明</th><th>方向</th><th>规则贡献</th></tr></thead><tbody>'+explanation_rows+'</tbody></table></div>'
    else:
        content += '<p>本次质量阻断，没有可用分数、解释或路径。</p>'
    if report['graph'] is not None:
        paths = ''.join('<li>'+e(' → '.join(row['nodes']))+' · '+e(row['status'])+'</li>' for row in report['graph']['paths'])
        if paths: content += '<p>路径为输入资格和研究先验，非已验证因果机制。</p><ul>'+paths+'</ul>'
    content += '</section>'
    if report['sources'] is not None:
        rows = []
        for visit in report['sources']['visits']:
            for name in ('mmse', 'moca', 'cdrSb'):
                rows.append((visit['visitCode']+' / '+name, visit.get(name), '分', visit['visitDate']))
        for feature in report['sources']['features']:
            rows.append((feature['featureName'], feature.get('featureValue'), feature['unit'], feature['recordedAt']))
        source_rows = ''.join('<tr>'+''.join(f'<td>{e("缺失" if value is None else value)}</td>' for value in row)+'</tr>' for row in rows)
        content += '<section><h2>原合成输入与声明来源</h2><div class="table-scroll" tabindex="0" role="region" aria-label="合成输入"><table><thead><tr><th>字段</th><th>原值</th><th>单位</th><th>声明日期</th></tr></thead><tbody>'+source_rows+'</tbody></table></div></section>'
        stage_rows = ''.join(f'<tr><td>{e(row["stage"])}</td><td>{e(row["status"])}</td><td>{e(row["durationMs"])} ms</td></tr>' for row in report['stages'])
        content += '<section><h2>上游返回的执行阶段</h2><p>仅保留原响应耗时；生成报告没有重新执行这些阶段。</p><table><thead><tr><th>阶段</th><th>状态</th><th>原耗时</th></tr></thead><tbody>'+stage_rows+'</tbody></table></section>'
    warnings = ''.join(f'<li>{e(value)}</li>' for value in report['warnings'])
    limits = ''.join(f'<li>{e(value)}</li>' for value in report['limitations'])
    metadata = ''.join(f'<dt>{e(name)}</dt><dd>{e(report[name])}</dd>' for name in ('subjectCode','runId','modelVersion','workflowVersion','dataVersion'))
    if audience == 'research': metadata += ''.join(f'<dt>{e(name)}</dt><dd>{e(report[name])}</dd>' for name in ('requestSha256','resultSha256','contractSha256'))
    return f'<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>课程合成报告</title>{style}</head><body><main><h1>课程合成演示报告</h1><p class="scope">仅合成案例 · 非诊疗报告 · {e(audience)} · {e(report["status"])}</p><p class="print-help">浏览器打印可另存PDF；不是服务端原生PDF导出。示例文件中的耗时不代表本次运行。</p><section><h2>质量与演示排序</h2><p>质量：{e(report["qualityStatus"])}；窗口：{report["predictionWindowMonths"]}个月；等级：{e(report["riskLevel"])}</p><p class="value">{score}</p><p>缺失模态：{e("、".join(report["missingModalities"]) or "无")}</p><ul>{warnings}</ul></section>{content}<section><h2>版本与内容绑定</h2><p>内容hash不是来源签名或授权凭证。</p><dl>{metadata}</dl></section><section><h2>限制与复核</h2><ul>{limits}</ul></section></main></body></html>'


def render_course_csv(request, result, *, audience='research'):
    """Lossless field/value CSV of the validated report, without executing a model.

    Each value is a JSON literal: null, false and zero stay distinct. Strings
    retain their JSON quotes, so source text cannot start a spreadsheet formula.
    Nested values remain one JSON cell rather than duplicating personal rows.
    """
    report = build_course_report(request, result, audience=audience)
    stream = io.StringIO(newline='')
    writer = csv.writer(stream, lineterminator='\r\n')
    writer.writerow(('field', 'valueJson'))
    for field in sorted(report):
        writer.writerow((field, _encoded(report[field]).decode('utf-8')))
    return stream.getvalue()


def main():
    parser = argparse.ArgumentParser(description='Validate and render declared synthetic course request/result files; no prediction')
    parser.add_argument('--request', type=Path, required=True); parser.add_argument('--result', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--audience', choices=('research','teaching'), default='research')
    parser.add_argument('--format', choices=('html','json','csv'), default='html')
    args = parser.parse_args()
    try:
        require(args.request.stat().st_size + args.result.stat().st_size <= MAX_PAIR_BYTES, 'REPORT_SIZE')
        request, result = decode_json(args.request.read_bytes()), decode_json(args.result.read_bytes())
        if args.format == 'html':
            output = render_course_html(request, result, audience=args.audience)
        elif args.format == 'csv':
            output = render_course_csv(request, result, audience=args.audience)
        else:
            output = json.dumps(build_course_report(request, result, audience=args.audience), ensure_ascii=False, indent=2)
        # Never replace an existing result or accidentally overwrite either input.
        with args.output.open('x', encoding='utf-8', newline='\n') as stream: stream.write(output)
    except (OSError, ValueError) as error:
        print(json.dumps({'status': 'REJECTED', 'code': getattr(error, 'code', 'REPORT_IO_OR_JSON') or 'REPORT_IO_OR_JSON'}))
        return 1
    print(json.dumps({'status': 'CREATED', 'scope': 'COURSE_SYNTHETIC_REPORT', 'modelExecuted': False}))
    return 0


if __name__ == '__main__': raise SystemExit(main())
