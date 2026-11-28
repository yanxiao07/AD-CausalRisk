"""UIE 字段抽取的可选本地服务。

与 `python_service` 刻意分开：那一层是运行期只用标准库的 Agent 边界，有测试钉住
「不许引入第三方包」。UIE 需要 paddlepaddle，所以单独成包，只在显式配置
`memo.extraction.base-url` 时才启动；不启动时 Java 侧完全走规则抽取。
"""