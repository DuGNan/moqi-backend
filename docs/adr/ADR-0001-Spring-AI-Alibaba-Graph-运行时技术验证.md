# ADR-0001：Spring AI Alibaba Graph 运行时技术验证

- 状态：接受
- 决策日期：2026-07-29
- 关联 Issue：[DuGNan/Moqi#42](https://github.com/DuGNan/Moqi/issues/42)
- 验证版本：Spring AI Alibaba Graph Core `1.1.2.2`

## 背景

Moqi 需要可恢复、可中断、可观测的小说创作工作流。现有 `ai_tasks` 适合表达粗粒度异步任务，但不能独立表达多步骤运行、人工确认点、checkpoint 和恢复历史。Spring AI Alibaba Graph 官方将 Graph 定位为长时间、有状态 Agent 的底层运行时，并提供图编排、流式执行、checkpoint 和 human-in-the-loop 能力。

本 ADR 只决定框架采用边界，不新增生产 Agent 表，不替换现有任务执行器，也不改变前端或生产接口。

## 验证方式

Spike 位于 `moqi-service/src/test/java/com/dugnan/moqi/spike/graph`，Graph Core 和 MySQL Connector 均为 `test` scope，不进入生产 classpath。验证图为：

```text
loadContext -> draft -> review -> interrupt -> resume -> complete
```

`draft` 节点通过现有供应商无关 `LlmProvider` V2 调用 Fake Provider。运行标识使用：

```text
ai-task:{taskId}:run:{runId}
```

验证覆盖：

- Graph 流式输出、中断、人工确认后恢复和重复 resume 拒绝；
- Provider V2 文本增量到业务事件的适配；
- Provider 超时、失败和取消传播；
- MySQL checkpoint、重建 Saver/Graph 后的恢复；
- checkpoint 损坏后的失败行为；
- 内置序列化和依赖边界。

MySQL 实测使用独立的 `moqi_graph_spike` 本地测试库，不访问 `moqi_dev`，不调用真实模型。

## 证据与发现

| 项目 | 结果 |
|---|---|
| Java / Spring Boot 兼容性 | Java 17、Spring Boot 3.5.8、Spring AI 1.1.2 下可以编译并运行 |
| 最小图与流式事件 | 通过；Fake Provider 增量可在 Graph 节点执行期间转换为框架无关事件 |
| interrupt / resume | 内存 Saver 通过；恢复后从 `complete` 继续，不重复调用 Provider |
| 重复 resume | Graph 原生接口不足以表达 Moqi 幂等语义；Spike 通过 checkpoint 终态检查拒绝 |
| 取消 | Graph 不自动理解 `LlmStreamCall`；通过适配器保存活动调用并向上游传播取消后通过 |
| 超时与失败 | Provider 安全错误可传播，但需要适配器统一映射 Graph/Reactor 包装 |
| 内置 `MysqlSaver` | 不满足生产要求；同秒 checkpoint 顺序不稳定 |
| 自定义确定性 MySQL Saver | 使用单调 `sequence_id` 和 JSON 状态后，重建 Saver/Graph 的恢复通过 |
| 状态损坏 | 能阻止静默恢复为空状态，但内置 Saver 只抛通用 `RuntimeException`，需要错误归类 |
| 依赖成本 | Graph Core 还传递 RAG、Vector Store、ZhiPuAI、MCP、Commons Exec 等当前不需要的依赖 |

### 内置 MysqlSaver 的阻断问题

`1.1.2.2` 的 `GRAPH_CHECKPOINT` 只有随机 UUID 主键和秒级 `saved_at`，读取语句仅使用：

```sql
ORDER BY c.saved_at DESC
```

一个图运行会在同一秒写入多个 checkpoint，但表中没有单调序号或可作为第二排序键的字段。实测重建 Saver 后的同一组数据可能恢复成功，也可能再次停在 `review`。这属于非确定性恢复，不能作为小说创作生产数据的可靠基础。

内置 Saver 还把状态编码为 Java 对象序列化后的 Base64 载荷。该方式对类名、`serialVersionUID`、集合实现和版本升级敏感，不适合作为 Moqi 的长期可演进状态契约。

## 决策

采用结论为：**部分采用**。

1. 可以在后续生产 Issue 中使用 Graph Core 的图编排、流式执行和中断模型。
2. 不采用 `1.1.2.2` 内置 `MysqlSaver`，也不直接持久化框架 Java 对象序列化结果。
3. Graph 必须位于独立的 Agent Runtime 适配层之后。Controller、领域 Service、`ai_tasks` 和 SSE 层不得引用 `CompiledGraph`、`RunnableConfig`、`OverAllState` 等框架类型。
4. Moqi 自有 checkpoint 存储必须使用稳定的业务 `runId`、单调 step/sequence、显式 `schema_version`、JSON payload、节点标识、下一节点、状态和创建时间；恢复查询必须使用确定性顺序。
5. `ai_tasks` 继续作为粗粒度入口。后续 `agent_runs`/`agent_run_steps` 承载多步骤运行，`taskId + runId` 映射到框架 thread ID。
6. Provider 调用仍通过 Provider V2；活动 `LlmStreamCall` 由运行时适配器登记，任务取消和超时向上游传播。
7. Graph 事件先转换成 Moqi 内部事件，再适配现有章节 SSE；不得直接把框架输出结构暴露给前端。
8. resume 必须先以数据库状态和版本做条件更新，保证人工确认、取消与重复请求的幂等性；不能只依赖 Graph 内存状态。

## 生产封装边界

后续适配层至少需要以下框架无关端口：

- `AgentRuntime.start(command)`
- `AgentRuntime.resume(runId, confirmation)`
- `AgentRuntime.cancel(runId)`
- `AgentRuntime.load(runId)`
- `AgentRuntimeEventSink.publish(event)`
- `AgentCheckpointRepository`

Graph 实现、节点定义、状态转换和 Saver 适配只存在于基础设施实现包中。领域写入仍由现有 Service 在用户确认后执行，Graph 节点不能直接把候选文本写成权威正文、设定或事件。

## 备选方案与退出条件

如果后续版本仍不能提供可靠的 MySQL 顺序、稳定 JSON schema、可控依赖或可接受的 API 兼容性，则停止采用 Graph Core，改用 Moqi 自有最小状态机：

```text
agent_runs
  -> agent_run_steps
  -> agent_checkpoints
  -> outbox/events
```

执行器按数据库状态领取 step，以版本条件更新保证幂等；中断表示 `waiting_confirmation`，resume 创建下一 step；Provider V2 和现有任务取消信号保持不变。

重新评估内置 Saver 必须同时满足：

- checkpoint 有稳定单调顺序；
- 状态使用可版本化 JSON 契约；
- 重启恢复、重复 resume、损坏载荷和并发恢复回归测试全部通过；
- 依赖树和 API 变更成本可接受。

## 参考资料

- [Spring AI Alibaba v1.1.2.2 Release](https://github.com/alibaba/spring-ai-alibaba/releases/tag/v1.1.2.2)
- [Spring AI Alibaba 官方仓库与 Graph 定位](https://github.com/alibaba/spring-ai-alibaba)
- [Spring AI Alibaba Graph 人类反馈示例](https://java2ai.com/docs/frameworks/graph-core/examples/human-in-the-loop/)
