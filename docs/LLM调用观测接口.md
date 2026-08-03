# LLM 调用观测接口

## 适用范围

本文描述当前后端已落地的模型调用明细、聚合与估算成本查询。接口仅返回固定本地用户
`local-user` 可见的记录；后续引入真实认证主体时，应由认证上下文替换该固定用户边界。

## 调用记录语义

- 每次实际 Provider 尝试对应一条 `llm_model_calls` 记录。
- `logicalCallId` 表示一次业务逻辑调用，`attemptNo` 表示该逻辑调用下的物理尝试序号。
- 终态为 `succeeded`、`failed`、`canceled` 或 `unknown`；`unknown` 表示服务重启后无法确认上一个进程中调用的真实结果。
- `TIMEOUT` 与 `RATE_LIMITED` 作为独立错误码聚合，避免把失败重试合并为一次成功调用。
- `estimatedCost` 使用调用发生时间命中的 `llm_model_prices` 版本计算，`costStatus=estimated` 表示估算值，
  不是供应商账单；缺少 token 或有效单价时为 `unpriced`。
- 当前 Provider 元数据没有区分缓存命中 token，因此输入成本按 cache miss 单价保守估算。

观测白名单只包含业务 ID、Provider/模型、配置版本、模板版本、请求引用哈希、token、耗时、终态和安全错误分类。
数据库、日志与接口均不保存或返回 API Key、完整 Prompt、正文、隐藏推理和原始异常消息。

## 最近调用

`GET /api/llm-calls`

可选参数：

| 参数 | 含义 |
| --- | --- |
| `from` / `to` | ISO-8601 本地日期时间；默认最近 30 天，区间为 `[from, to)` |
| `workId` / `chapterId` | 作品或章节过滤 |
| `provider` / `model` | Provider 或模型过滤 |
| `workflowType` | 工作流过滤 |
| `callStatus` | `running/succeeded/completed/failed/canceled/unknown` |
| `page` | 从 1 开始，默认 1 |
| `pageSize` | 默认 20，最大 100 |

结果按 `startedAt DESC, id DESC` 稳定分页。响应不包含 `requestHash`、Prompt、正文、隐藏推理或原始错误消息。

## 聚合

`GET /api/llm-calls/summary`

支持与最近调用相同的时间、作品、Provider、模型和工作流过滤。`groupBy` 只允许：

- `date`
- `user`
- `work`
- `model`
- `workflow`

每个分组分别返回 `attemptCount` 与 `logicalCallCount`，并汇总成功、失败、取消、超时、限流、
输入/输出/总 token、估算成本、未计价次数和平均耗时。查询使用服务端白名单选择分组 SQL，
不接受客户端 SQL 片段。

## 扩展边界

`workflowType`、`operationType`、`errorCategory`、`elapsedMillis`、token 和成本字段可作为后续指标或告警输入。
当前范围不引入外部 APM、独立计费或供应商账单对账，也不自动调整模型策略。
