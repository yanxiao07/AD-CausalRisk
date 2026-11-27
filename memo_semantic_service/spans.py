"""UIE 字段片段的校验与整理（仅标准库）。

模型只负责「从原文里找出片段」，不负责措辞：问句模板由 Java 侧组装。所以这一层唯一的
职责是把模型输出收窄成可核对的事实——每一段都必须能在原文里找到，找不到的直接丢掉。
患者复习时看到的内容必须来自自己写下的原文，模型改写过的片段一律不采信。
"""
from __future__ import annotations

LABELS = ('TIME', 'PLACE', 'ITEM', 'NEGATION', 'FREQUENCY', 'CONDITION')

SCHEMA = {
    'TIME': ['时间', '什么时候'],
    'PLACE': ['地点', '地方'],
    'ITEM': ['要带的东西', '物品'],
    'NEGATION': ['不能做的事情', '要避免的事情'],
    'FREQUENCY': ['频率', '次数'],
    'CONDITION': ['条件', '什么情况下'],
}

MAX_SPANS = 8
MAX_SPAN_LENGTH = 40
MIN_SPAN_LENGTH = 2


def flatten(raw):
    """把 Taskflow 的输出摊平成 (label, text, start, end)。"""
    for entry in raw or ():
        if not isinstance(entry, dict):
            continue
        for label, values in entry.items():
            if label not in LABELS:
                continue
            for value in values or ():
                if not isinstance(value, dict):
                    continue
                yield label, str(value.get('text', '')), value.get('start'), value.get('end')


def normalize(raw, text, limit=MAX_SPANS):
    """校验、去重、按原文顺序排序，返回 [{label, text, start}]。

    三条硬约束：
    1. 片段必须真的出现在原文里——模型编出来的事实不能进卡片；
    2. 同一字段里被更长片段包含的短片段丢掉，避免「时间」和「时间上午」两张卡；
    3. 超过长度上限的片段整段丢弃，而不是截断——截断会把事实切成半句。
    """
    source = text or ''
    kept = []
    for label, raw_text, start, end in flatten(raw):
        piece = raw_text.strip()
        if len(piece) < MIN_SPAN_LENGTH or len(piece) > MAX_SPAN_LENGTH:
            continue
        position = source.find(piece)
        if position < 0:
            continue
        kept.append({'label': label, 'text': piece, 'start': position})

    ordered = []
    for candidate in sorted(kept, key=lambda item: (item['start'], -len(item['text']))):
        for existing in ordered:
            if existing['label'] == candidate['label'] and (
                    candidate['text'] in existing['text'] or existing['text'] in candidate['text']):
                if len(candidate['text']) > len(existing['text']):
                    existing.update(candidate)
                break
        else:
            ordered.append(dict(candidate))
        if len(ordered) >= limit:
            break
    return ordered[:limit]