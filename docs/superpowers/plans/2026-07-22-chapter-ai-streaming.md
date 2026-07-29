# 章节讨论 DeepSeek 流式回复与 SSE 事件推送 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让章节讨论在保存用户消息后异步调用已配置且已验证的 DeepSeek，并将回复增量和终态经章节级 SSE 推送；完整 assistant 消息与 AI task 终态可靠落库。

**Architecture:** 用户消息和 queued `conversation_reply` 任务保持同一短事务；事务提交后事件监听器把 task ID 投递给有界 `ThreadPoolTaskExecutor`。Runner 用 CAS 领取任务，在事务外加载稳定 Prompt、创建 Provider 和消费 DeepSeek 流；每个增量发布 Spring 应用事件，由 Web 层 SSE 注册表转发。完成时在短事务内写入 assistant 消息、写入 `ai_tasks.result_message_id` 和 succeeded；失败、取消及重连均以数据库状态为准。

**Tech Stack:** Java 17、Spring Boot 3.5.8、Spring AI 1.1.2 / Spring AI Alibaba 1.1.2.2、MyBatis-Plus、Spring MVC `SseEmitter`、JUnit 5、Mockito、JDK `HttpServer`。

---

## 当前约束与数据契约

- 保留 `POST /api/conversations/{conversationId}/messages`：它返回已落库的 user 消息和 `aiTaskId`，不等待模型回复。
- 新增 `GET /api/chapters/{chapterId}/events`，响应类型为 `text/event-stream`；一个章节可有多个订阅者。
- 事件固定包含 `chapterId`、`occurredAt`，回复事件还固定包含 `conversationId`、`aiTaskId`、`messageId`。事件名为 `stream.ready`、`reply.started`、`reply.delta`、`reply.completed`、`reply.failed`、`reply.canceled`。
- `reply.delta` 只含本次新增文本；`reply.completed` 不再重复全文，只含已经持久化的 assistant `messageId` 和 task 状态。
- `ai_tasks.result_message_id` 从本 issue 起只表示最终 assistant 消息 ID。创建 queued task 时该字段为 `null`；任务输入消息通过 `chapter_conversation_messages.ai_task_id` 且 `message_role=user` 查询。
- 事件流不存 token，不保证断线 token 回放；客户端重新订阅后应读取现有消息和 task 查询接口。
- `knowledge.extraction.*`、`setting.candidate.created`、`foreshadowing.candidate.created`、`brief.updated` 只保留在设计文档中，不写入本次代码。

## 文件结构

| 文件 | 责任 |
| --- | --- |
| `moqi-service/.../llm/LlmProvider.java` | 增加厂商无关的流式回复边界。 |
| `moqi-service/.../llm/DeepSeekLlmProvider.java` | 用 Spring AI `StreamingChatModel` 把 DeepSeek SSE 转为安全的文本增量。 |
| `moqi-service/.../llm/DeepSeekProviderConfig.java` | 保持不可变配置快照，并覆盖 `toString()` 防止 API Key 出现在日志。 |
| `moqi-service/.../config/service/UserConfigService.java`、`impl/UserConfigServiceImpl.java` | 只读取得“已配置且最近测试成功”的 DeepSeek 运行时快照，不向 Web 层暴露 Key。 |
| `moqi-common/.../api/ErrorCode.java` | 增加模型不可用的稳定业务错误码。 |
| `moqi-service/.../chapter/stream/*` | 定义回复事件、提交事件、Spring 应用事件发布器及 task 执行配置。 |
| `moqi-service/.../chapter/service/impl/ConversationReplyTaskRunner.java` | CAS 领取、Prompt 装配、Provider 流消费、取消检查、成功/失败持久化。 |
| `moqi-service/.../chapter/service/impl/ChapterCollaborationServiceImpl.java` | 创建 user 消息与 queued task，并在同一事务内发布提交事件。 |
| `moqi-service/.../task/service/impl/AiTaskServiceImpl.java` | 取消时保留现有幂等语义，并发布 canceled 应用事件。 |
| `moqi-dal/.../AiTaskMapper.java`、`ChapterConversationMessageMapper.java` | 增加按条件领取任务、终态更新、按 task 查询输入消息的 Mapper 方法。 |
| `moqi-web/.../chapter/stream/ChapterSseEmitterRegistry.java` | 管理订阅、超时、完成和单订阅发送失败，监听服务层事件。 |
| `moqi-web/.../chapter/controller/ChapterEventController.java` | 暴露章节 SSE GET 端点；只负责参数校验和订阅。 |
| `moqi-service/src/test/.../llm/DeepSeekLlmProviderTest.java` | 覆盖上游 SSE 增量、完成、空增量和安全错误映射。 |
| `moqi-service/src/test/.../chapter/service/impl/ConversationReplyTaskRunnerTest.java` | 覆盖任务状态转换、持久化、取消竞争、失败与 Prompt 裁剪。 |
| `moqi-service/src/test/.../chapter/service/impl/ChapterCollaborationServiceImplTest.java` | 覆盖创建任务时 resultMessageId 为空且提交事件在事务内发布。 |
| `moqi-service/src/test/.../task/service/impl/AiTaskServiceImplTest.java` | 覆盖取消事件及终态不覆盖。 |
| `moqi-web/src/test/.../chapter/controller/ChapterEventControllerTest.java` | 覆盖 SSE content type、订阅、事件 JSON 与断开清理。 |
| `docs/项目结构与功能介绍.md`、`docs/2026-06-03-后端设计.md` | 同步当前模块职责、运行边界与恢复语义。 |

### Task 1: 固化流式 Provider 边界与安全映射

**Files:**

- Modify: `moqi-service/src/main/java/com/dugnan/moqi/llm/LlmProvider.java`
- Modify: `moqi-service/src/main/java/com/dugnan/moqi/llm/DeepSeekLlmProvider.java`
- Modify: `moqi-service/src/main/java/com/dugnan/moqi/llm/DeepSeekProviderConfig.java`
- Create: `moqi-service/src/main/java/com/dugnan/moqi/llm/LlmStreamDelta.java`
- Test: `moqi-service/src/test/java/com/dugnan/moqi/llm/DeepSeekLlmProviderTest.java`

- [ ] **Step 1: 写出上游 SSE 到文本增量的失败测试。**

在 `DeepSeekLlmProviderTest` 的本机 `HttpServer` 中返回两个 DeepSeek SSE chunk 和完成标记，断言 `stream(...)` 按 `"你好"`、`"，墨契"` 顺序调用消费者；再分别覆盖空文本 chunk 被忽略、空流抛出 `INVALID_RESPONSE`、401 映射为 `AUTHENTICATION`、超时映射为 `TIMEOUT`。测试请求必须断言 `stream=true`、Bearer 只出现在请求头、模型名和 `max_tokens` 正确。

- [ ] **Step 2: 运行测试确认当前接口尚不能流式调用。**

Run: `mvn -pl moqi-service -am -Dtest=DeepSeekLlmProviderTest '-Dsurefire.failIfNoSpecifiedTests=false' test`

Expected: FAIL，编译提示 `LlmProvider` 不存在 `stream` 方法或测试辅助类型不存在。

- [ ] **Step 3: 增加最小的厂商无关流式类型和方法。**

在 `LlmProvider` 增加如下方法，并新增不可变 `LlmStreamDelta`：

```java
void stream(LlmRequest request, Consumer<LlmStreamDelta> consumer);

public record LlmStreamDelta(String text) {
}
```

`DeepSeekLlmProvider.stream` 复用 `LlmRequest` 的 system/user prompt 和 `maxTokens`，使用 Spring AI 的流式 ChatModel API，过滤空文本、按到达顺序调用 consumer，并在流结束时确认至少收到一个非空文本。任何运行时异常只能经已有 `mapException` 转换为 `LlmProviderException`，不得携带响应体、URL 查询参数或 Key。

`DeepSeekProviderConfig` 覆盖 record 默认 `toString()`，只能输出 baseUrl 和 model，并固定使用 `apiKey=****`；为它增加直接断言，确保测试密钥不出现在字符串中。

- [ ] **Step 4: 运行定向测试确认通过。**

Run: `mvn -pl moqi-service -am -Dtest=DeepSeekLlmProviderTest '-Dsurefire.failIfNoSpecifiedTests=false' test`

Expected: BUILD SUCCESS；所有已有非流式和新增流式 Provider 测试通过。

- [ ] **Step 5: 提交 Provider 增量。**

```powershell
git add moqi-service/src/main/java/com/dugnan/moqi/llm moqi-service/src/test/java/com/dugnan/moqi/llm/DeepSeekLlmProviderTest.java
git commit -m "feat: 支持 DeepSeek 流式回复"
```

### Task 2: 建立任务 CAS、运行时配置与 Prompt 装配

**Files:**

- Modify: `moqi-service/src/main/java/com/dugnan/moqi/config/service/UserConfigService.java`
- Modify: `moqi-service/src/main/java/com/dugnan/moqi/config/service/impl/UserConfigServiceImpl.java`
- Modify: `moqi-common/src/main/java/com/dugnan/moqi/common/api/ErrorCode.java`
- Modify: `moqi-dal/src/main/java/com/dugnan/moqi/chapter/mapper/AiTaskMapper.java`
- Modify: `moqi-dal/src/main/java/com/dugnan/moqi/chapter/mapper/ChapterConversationMessageMapper.java`
- Create: `moqi-service/src/main/java/com/dugnan/moqi/chapter/service/impl/ConversationReplyTaskRunner.java`
- Create: `moqi-service/src/main/java/com/dugnan/moqi/chapter/service/impl/ChapterConversationPromptFactory.java`
- Test: `moqi-service/src/test/java/com/dugnan/moqi/chapter/service/impl/ConversationReplyTaskRunnerTest.java`
- Test: `moqi-service/src/test/java/com/dugnan/moqi/config/service/impl/UserConfigServiceImplTest.java`

- [ ] **Step 1: 为运行时模型门槛和任务 CAS 写失败测试。**

覆盖以下场景：未配置 Key、`lastTestStatus` 不是 success、或者模型不在白名单时，Runner 标记 task failed 且不创建 Provider；两个 runner 同时执行同一 queued task 时只有一个执行 Provider；running task 在每个 delta 前检查到 canceled 后停止转发且不写 assistant；Provider 抛安全异常后 task 为 failed 且只保存安全错误码/消息；成功后 task 的 `resultMessageId` 是 assistant 消息 ID。

- [ ] **Step 2: 运行测试确认 Runner 与运行时快照尚不存在。**

Run: `mvn -pl moqi-service -am -Dtest=ConversationReplyTaskRunnerTest,UserConfigServiceImplTest '-Dsurefire.failIfNoSpecifiedTests=false' test`

Expected: FAIL，缺少 Runner、运行时配置查询和 Mapper 条件更新能力。

- [ ] **Step 3: 增加内部运行时配置查询。**

给 `UserConfigService` 增加仅供服务层调用的方法：

```java
DeepSeekProviderConfig requireAvailableDeepSeekConfig();
```

实现读取 `model.active` 原始 JSON；仅当 apiKey、provider、baseUrl、defaultModel 合法且 `lastTestStatus=success` 时构造快照。否则抛 `BusinessException(ErrorCode.MODEL_UNAVAILABLE, "DeepSeek 模型不可用，请先完成连接测试")`，并在 `ErrorCode` 中增加 `MODEL_UNAVAILABLE`。该方法绝不记录或返回 Key 到 Controller、事件或异常文本。

- [ ] **Step 4: 增加 Mapper 原子操作和 Prompt 工厂。**

`AiTaskMapper` 增加 `claimConversationReply(taskId, expectedVersion, modifiedAt)`、`markSucceeded(...)` 与 `markFailed(...)`：每个更新都以 `id`、`deleted=0`、`version` 和允许的前置状态为条件，并递增 version。`ChapterConversationMessageMapper` 增加按 `ai_task_id`、`message_role=user` 读取输入消息的查询。

`ChapterConversationPromptFactory` 一次性读取章节、作品、最近固定条数会话消息、最新 Brief 和已存在的必要知识摘要；生成固定分段 system prompt 与 user prompt。正文、消息和知识内容应按字符预算裁剪，不能在循环中逐条查询数据库。

- [ ] **Step 5: 实现 Runner 的事务边界。**

`ConversationReplyTaskRunner.run(Long taskId)` 的顺序必须固定为：

```text
CAS queued -> running
发布 reply.started
读取运行时配置与 Prompt（无事务）
Provider.stream 每个 delta 前检查 task 仍为 running，发布 reply.delta
短事务：再次检查 running，插入 assistant，CAS running -> succeeded
发布 reply.completed
```

失败路径用短事务 CAS `running -> failed` 后发布 `reply.failed`；如果 CAS 失败且最新状态为 canceled，只发布/保留 canceled，不得写 assistant 或 failed。不得把 Provider 调用包进 `@Transactional`。

- [ ] **Step 6: 运行定向测试确认通过。**

Run: `mvn -pl moqi-service -am -Dtest=ConversationReplyTaskRunnerTest,UserConfigServiceImplTest '-Dsurefire.failIfNoSpecifiedTests=false' test`

Expected: BUILD SUCCESS；测试证明单任务只会被领取一次、取消不被迟到结果覆盖、最终消息与 succeeded 绑定。

- [ ] **Step 7: 提交任务执行内核。**

```powershell
git add moqi-common/src/main/java/com/dugnan/moqi/common/api/ErrorCode.java moqi-service/src/main/java/com/dugnan/moqi/config moqi-service/src/main/java/com/dugnan/moqi/chapter moqi-dal/src/main/java/com/dugnan/moqi/chapter moqi-service/src/test/java/com/dugnan/moqi/config moqi-service/src/test/java/com/dugnan/moqi/chapter
git commit -m "feat: 执行章节讨论流式任务"
```

### Task 3: 事务提交后派发和取消通知

**Files:**

- Modify: `moqi-service/src/main/java/com/dugnan/moqi/chapter/service/impl/ChapterCollaborationServiceImpl.java`
- Modify: `moqi-service/src/main/java/com/dugnan/moqi/task/service/impl/AiTaskServiceImpl.java`
- Create: `moqi-service/src/main/java/com/dugnan/moqi/chapter/stream/ConversationReplyTaskSubmittedEvent.java`
- Create: `moqi-service/src/main/java/com/dugnan/moqi/chapter/stream/ChapterReplyEvent.java`
- Create: `moqi-service/src/main/java/com/dugnan/moqi/chapter/stream/ConversationReplyTaskDispatcher.java`
- Create: `moqi-service/src/main/java/com/dugnan/moqi/chapter/stream/ChapterAiTaskExecutionConfiguration.java`
- Test: `moqi-service/src/test/java/com/dugnan/moqi/chapter/service/impl/ChapterCollaborationServiceImplTest.java`
- Test: `moqi-service/src/test/java/com/dugnan/moqi/task/service/impl/AiTaskServiceImplTest.java`

- [ ] **Step 1: 写出提交后才投递和取消后通知的失败测试。**

断言 `sendMessage(... createAiTask=true)` 创建的 task 初始 `resultMessageId` 为 null、user message 的 `aiTaskId` 已回填、并发布 `ConversationReplyTaskSubmittedEvent(taskId)`；断言提交监听器仅在事务 after-commit 阶段调用 executor。取消成功时断言发布 `ChapterReplyEvent.canceled(...)`；取消已完成或已失败任务时不得伪造 canceled 事件。

- [ ] **Step 2: 运行测试确认事件和派发器尚不存在。**

Run: `mvn -pl moqi-service -am -Dtest=ChapterCollaborationServiceImplTest,AiTaskServiceImplTest '-Dsurefire.failIfNoSpecifiedTests=false' test`

Expected: FAIL，缺少事件类型、after-commit 监听器或新断言。

- [ ] **Step 3: 最小修改创建和取消链路。**

在 `sendMessage` 中把 `aiTask.setResultMessageId(message.getId())` 删除，改为 `null`；在同一事务内通过 `ApplicationEventPublisher` 发布提交事件。`ConversationReplyTaskDispatcher` 使用：

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void dispatch(ConversationReplyTaskSubmittedEvent event) {
    taskExecutor.execute(() -> taskRunner.run(event.aiTaskId()));
}
```

配置一个命名为 `chapterAiTaskExecutor` 的有界 `ThreadPoolTaskExecutor`：固定小核心线程、有限队列、明确线程名前缀，并使用拒绝策略把饱和任务标记 failed 和发布安全失败事件；不得使用无界默认 executor 或自行创建裸线程。

- [ ] **Step 4: 让取消与运行器共享权威状态。**

`AiTaskServiceImpl.cancelTask` 保留现有 `id + version + status in (queued,running)` 条件更新。更新成功后读取 task 的 chapter/conversation/user message 关联并发布 canceled；Runner 在发送每个 delta 前和成功事务开始前均重新检查 running。取消不尝试伪造完整 assistant 消息。

- [ ] **Step 5: 运行定向测试确认通过。**

Run: `mvn -pl moqi-service -am -Dtest=ChapterCollaborationServiceImplTest,AiTaskServiceImplTest '-Dsurefire.failIfNoSpecifiedTests=false' test`

Expected: BUILD SUCCESS；提交前不执行远程任务、提交后仅投递一次、取消语义保持幂等。

- [ ] **Step 6: 提交派发和取消语义。**

```powershell
git add moqi-service/src/main/java/com/dugnan/moqi/chapter moqi-service/src/main/java/com/dugnan/moqi/task moqi-service/src/test/java/com/dugnan/moqi/chapter moqi-service/src/test/java/com/dugnan/moqi/task
git commit -m "feat: 提交后派发章节讨论任务"
```

### Task 4: 提供章节级 SSE 订阅和事件转发

**Files:**

- Create: `moqi-web/src/main/java/com/dugnan/moqi/chapter/stream/ChapterSseEmitterRegistry.java`
- Create: `moqi-web/src/main/java/com/dugnan/moqi/chapter/stream/ChapterReplyEventListener.java`
- Create: `moqi-web/src/main/java/com/dugnan/moqi/chapter/controller/ChapterEventController.java`
- Test: `moqi-web/src/test/java/com/dugnan/moqi/chapter/controller/ChapterEventControllerTest.java`

- [ ] **Step 1: 写出 SSE 端点和事件序列失败测试。**

使用 MockMvc 建立 `GET /api/chapters/2/events`，断言响应 `Content-Type` 为 `text/event-stream`，初始事件名为 `stream.ready` 且只包含 chapterId/occurredAt。直接向 listener 发布 started、两个 delta、completed 事件，断言同一 emitter 接收到正确 event name 和 JSON 字段；在 emitter 超时、完成或 `send` 抛 `IOException` 后断言注册表删除该订阅，其他订阅仍可收事件。

- [ ] **Step 2: 运行测试确认 SSE 控制器尚不存在。**

Run: `mvn -pl moqi-web -am -Dtest=ChapterEventControllerTest '-Dsurefire.failIfNoSpecifiedTests=false' test`

Expected: FAIL，返回 404 或缺少 `ChapterEventController`。

- [ ] **Step 3: 实现受控的订阅注册表。**

注册表以 `chapterId -> concurrent subscriber set` 组织 `SseEmitter`，设置有限超时；注册时绑定 `onTimeout`、`onCompletion`、`onError` 删除自己。`send` 仅向目标章节的订阅快照发送；单个订阅发送失败时关闭并删除该订阅，绝不让异常传播回 Runner 或阻塞其他订阅。

- [ ] **Step 4: 实现 Controller 与应用事件监听器。**

`ChapterEventController.subscribe(Long chapterId)` 先校验章节存在，再登记 emitter 并立刻发送：

```java
SseEmitter.event()
        .name("stream.ready")
        .data(new ChapterStreamReady(chapterId, LocalDateTime.now()));
```

`ChapterReplyEventListener` 监听服务层 `ChapterReplyEvent`，把 `reply.started`、`reply.delta`、`reply.completed`、`reply.failed`、`reply.canceled` 原样映射到 registry。事件 DTO 不得包含 apiKey、Prompt 全文、Provider 原始异常或完整 assistant 内容。

- [ ] **Step 5: 运行 Web 定向测试确认通过。**

Run: `mvn -pl moqi-web -am -Dtest=ChapterEventControllerTest,ChapterCollaborationControllerTest '-Dsurefire.failIfNoSpecifiedTests=false' test`

Expected: BUILD SUCCESS；既有共创 HTTP 契约仍通过，新增 SSE 端点可订阅并按事件名推送。

- [ ] **Step 6: 提交 Web SSE 适配层。**

```powershell
git add moqi-web/src/main/java/com/dugnan/moqi/chapter moqi-web/src/test/java/com/dugnan/moqi/chapter
git commit -m "feat: 推送章节讨论 SSE 事件"
```

### Task 5: 启动恢复、文档和完整验证

**Files:**

- Create: `moqi-service/src/main/java/com/dugnan/moqi/chapter/stream/ConversationReplyTaskRecoveryRunner.java`
- Modify: `docs/项目结构与功能介绍.md`
- Modify: `docs/2026-06-03-后端设计.md`
- Test: `moqi-service/src/test/java/com/dugnan/moqi/chapter/service/impl/ConversationReplyTaskRunnerTest.java`

- [ ] **Step 1: 写出启动恢复失败测试。**

覆盖启动扫描 queued task 并投递、把遗留 running task 安全转回 queued 后投递、canceled/succeeded/failed task 不投递；同一 task 在恢复与新提交同时发生时仍只能由 CAS 领取一次。

- [ ] **Step 2: 运行恢复测试确认失败。**

Run: `mvn -pl moqi-service -am -Dtest=ConversationReplyTaskRunnerTest '-Dsurefire.failIfNoSpecifiedTests=false' test`

Expected: FAIL，缺少 `ConversationReplyTaskRecoveryRunner` 或恢复查询方法。

- [ ] **Step 3: 实现受限恢复。**

使用 `ApplicationRunner` 在应用就绪后只扫描 `task_type=conversation_reply` 的 queued/running 任务；running 任务通过版本条件更新退回 queued，再交给同一 after-commit/dispatcher 提交路径。扫描量设置固定上限，超出时记录不含业务内容的警告；不要在启动线程内直接发起 Provider 调用。

- [ ] **Step 4: 更新当前态文档。**

在项目结构文档写明：POST 创建消息/任务、SSE 事件职责、数据库事实源、断线恢复、模型可用门槛、无 token 回放和无新中间件。后端设计同步 task 状态、`result_message_id` 语义、恢复与取消规则。根项目已批准的设计规格不在后端仓库 PR 中重复提交；如实际 DTO 与规格不一致，先回到根项目文档单独修订后再合并代码。

- [ ] **Step 5: 执行完整验证。**

Run:

```powershell
mvn clean verify
Get-ChildItem -Recurse -Filter pmd.xml | ForEach-Object { $_.FullName }
git diff --check
```

Expected: Maven 构建与测试成功；逐个读取生成的 `target/pmd.xml`，单独报告 P3C/PMD 问题数量与位置；`git diff --check` 无输出。

- [ ] **Step 6: 人工接口验证。**

使用 Fake Provider 启动本地服务，并在 `E:\coding\WeWrite\.local\qa-artifacts\issue-39\` 保存不含密钥的 curl 输出或测试记录，依次验证：创建会话、订阅 SSE、发送 user message、收到 started/delta/completed、查询消息与 task、取消另一条慢流、重连后查询状态。不得启动或改动现有用户 8080 进程；如需新实例，使用不同端口。

- [ ] **Step 7: 提交、推送和创建 PR。**

```powershell
git add docs moqi-service moqi-dal moqi-web
git commit -m "feat: 完成章节讨论流式 AI 回复"
git push origin feature/39-chapter-ai-streaming
```

创建中文 PR，关联 `Closes #39`，正文分别列出：流式协议、事务/取消语义、测试结果、P3C/PMD 报告与未实现的前端消费/知识抽取边界。提交 PR 前进行主 Agent 代码复查；PR 至少获得一次 approval 后才可合并。

## 自检

- 规格覆盖：Task 1 覆盖 DeepSeek SSE；Task 2 覆盖运行时可用性、Prompt 和状态 CAS；Task 3 覆盖 after-commit、执行器和取消；Task 4 覆盖章节 SSE 与慢订阅；Task 5 覆盖恢复、文档、构建、P3C 与人工链路。
- 协议一致性：事件名只使用 `stream.ready` 和 `reply.*`；`resultMessageId` 只在成功时指向 assistant；所有回复事件使用 task/conversation/chapter/message 可关联字段。
- 范围控制：没有 Redis、MQ、WebSocket、token 回放、前端渲染或知识提取实现；后续前端 issue 只消费本计划冻结的 HTTP/SSE 契约。
