# 研究评估证据核对

服务于第3→4环节的研究员复核。示例用七/十变量完成、QC 阻断和可用性证据缺失四个构造场景，检查请求关联、运行状态、同次输出绑定、历史可用性和解释范围。只读取本仓库固定人工样例。模型可见的证据仅含这些检查所需的状态、布尔值和构造绑定字段。

从仓库根目录直接运行确定性报告：

```sh
python -m examples.research_evidence.review all
```

在独立 Python 3.11 环境中运行真实 Deep Agents SDK 的离线检查：

```sh
python -m pip install -r examples/research_evidence/requirements.lock
python -m examples.research_evidence.verify
```

设置 `OPENAI_API_KEY` 后，可显式选择模型，用相同人工证据对照：

```sh
python -m examples.research_evidence.review all --openai-model gpt-4.1-mini
```

宿主只开放 `read_evidence`，记录实际取回的证据 ID。草稿的每项结论和引用须与确定性结果一致，且引用须已读取。最终 `findings` 始终由规则生成。 `agent` 记录接受或回退、原因、已读取 ID 及耗时，便于比较。离线检查含超时、错误结论、未读/虚构引用和禁用工具。每次 OpenAI 请求超时 15 秒，关闭自动重试，SDK 限制循环步数。

检查的是构造评估报告。真实来源资格、历史首次可用、科学因果结论与临床使用依赖其独立证据。示例依赖只安装到独立环境。Java 适配见[项目 README](../../README.md)和[候选研究契约](../../docs/Research_Table_Agent_Contract.md)。

离线 SDK 已验证四个构造场景和六个回退场景。真实模型对照遇到限流时回退到规则结果；成功响应的准确性和耗时仍待实测。
