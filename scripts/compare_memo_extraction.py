"""规则基线与 uie-nano 的对照脚本（同一组样例）。

规则一侧的数字不在这里重算：它来自基线测试记录的结果（samples 里的 ruleBaseline），
基线测试已经把这些行为钉死。脚本负责把模型一侧跑出来，逐条对比三件事：

  漏抽  模型没找到的字段（expected 里有、spans 里没有）
  错配  模型找到但字段类型对不上的片段
  越界  模型编出原文里没有的片段（服务端已丢弃，这里再确认一次）

用法：
  python scripts/compare_memo_extraction.py                      # 只打印规则基线
  python scripts/compare_memo_extraction.py --service http://127.0.0.1:8092
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from urllib.request import ProxyHandler, Request, build_opener

ROOT = Path(__file__).resolve().parents[1]
SAMPLES = ROOT / 'memo_semantic_service/examples/memo_samples.json'
HTTP = build_opener(ProxyHandler({}))


def load_samples():
    return json.loads(SAMPLES.read_text(encoding='utf-8'))['samples']


def rule_totals(samples, key):
    covered = sum(1 for sample in samples if sample[key] == 'covered')
    fields = sum(len(sample['expected']) for sample in samples)
    return covered, len(samples), fields


def call_service(base, text):
    request = Request(
        base.rstrip('/') + '/api/memo/v1/extract',
        data=json.dumps({'text': text}, ensure_ascii=False).encode('utf-8'),
        method='POST',
        headers={'Content-Type': 'application/json'},
    )
    with HTTP.open(request, timeout=10) as response:
        return json.loads(response.read().decode('utf-8'))['spans']


def judge(sample, spans):
    """返回 (命中, 漏抽, 错配, 越界)。"""
    expected = sample['expected']
    hit = []
    missed = []
    for item in expected:
        found = [
            span for span in spans
            if span['label'] == item['label'] and item['value'] in span['text']
        ]
        if found:
            hit.append(item)
        else:
            missed.append(item)
    expected_texts = [item['value'] for item in expected]
    mismatched = [
        span for span in spans
        if span['label'] not in {item['label'] for item in expected}
    ]
    out_of_source = [
        span for span in spans if span['text'] not in sample['text']
    ]
    return hit, missed, mismatched, out_of_source


def main(argv=None):
    parser = argparse.ArgumentParser(description='规则基线与 uie-nano 对照')
    parser.add_argument('--service', default=None, help='语义服务地址，例如 http://127.0.0.1:8092')
    args = parser.parse_args(argv)

    samples = load_samples()
    old_covered, total, fields = rule_totals(samples, 'ruleBaseline')
    new_covered, _, _ = rule_totals(samples, 'fieldRule')
    print(f'旧规则基线：{old_covered}/{total} 条备忘产出结构化问答，覆盖 {old_covered}/{fields} 个标注字段')
    print(f'字段规则（A 部分）：{new_covered}/{total} 条备忘产出结构化问答，覆盖 {new_covered}/{fields} 个标注字段')

    if not args.service:
        print('未指定 --service，只打印规则基线。')
        return 0

    hits = misses = wrong = out = 0
    print()
    print(f'{"样例":<14}{"命中":<6}{"漏抽":<6}{"错配":<6}{"越界":<6}')
    for sample in samples:
        try:
            spans = call_service(args.service, sample['text'])
        except Exception as failure:
            print(f'服务不可用：{failure}')
            return 1
        hit, missed, mismatched, out_of_source = judge(sample, spans)
        hits += len(hit)
        misses += len(missed)
        wrong += len(mismatched)
        out += len(out_of_source)
        print(f'{sample["id"]:<14}{len(hit):<6}{len(missed):<6}{len(mismatched):<6}{len(out_of_source):<6}')

    print()
    print(f'模型合计：命中 {hits}，漏抽 {misses}，错配 {wrong}，越界 {out}（共 {fields} 个标注字段）')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())