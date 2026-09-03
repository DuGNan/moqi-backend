# Agent Runtime 恢复语义

## 适用范围

本说明描述后端内部 `AgentRuntime` 的可恢复执行基础设施。它只提供 Run 当前状态的只读查询，不提供通用 HTTP 工作流启动、恢复或取消入口，也不定义小说生成、评估或知识抽取步骤。

## 场景小说生成接入

`scene_novel_generation` 工作流由场景正文生成服务在创建批次时以幂等键启动。工作流顺序执行加载批次、逐场景生成和收束步骤；每个已成功场景通过 checkpoint 跳过，失败场景可按场景步骤重试，取消会传播到正在进行的 Provider 流。

场景生成在首次执行时创建并绑定 `StoryContextSnapshot`，随后重试和进程恢复只读取该快照，因而不会因当前设定或规划变化而静默改写历史依据。恢复时会重新校验已持久化的 Provider、模型、配置版本和凭据版本；任一配置不再可用时停止运行并返回冲突，而不会替换为另一模型。

工作流仅向 SSE 发送批次或场景资源状态，完整候选正文、prompt、密钥和隐藏推理不进入事件、checkpoint 或运行日志。

## 事实源与状态

`agent_runs` 是一次工作流执行的事实源，状态为 `queued`、`running`、`waiting_for_human`、`succeeded`、`failed`、`canceled` 或 `timed_out`。步骤尝试独立记录在 `agent_run_steps`；成功步骤不会在恢复后再次执行。

每次成功步骤均在短事务内写入 `agent_checkpoints`。checkpoint 只保存带 schema version、SHA-256 校验和及 Run 内单调 sequence 的 JSON 状态，不保存 Java 对象图或模型隐藏推理；哈希基于键排序后的规范 JSON 计算，不依赖数据库对 JSON 空白的归一化表现。远程 Provider 调用始终位于数据库事务外。

启动幂等键在同一用户和工作流范围内唯一，并绑定作品、章节、可选 AI Task、输入快照版本与输入哈希。完全相同的请求返回既有 Run；任一绑定项不同均返回幂等冲突，不会跨业务对象复用旧 Run。

## 中断、恢复与终态竞争

人工中断写入 `agent_interruptions`，恢复 token 仅通过内部事件或可信应用端口交付，数据库只保存其 SHA-256 哈希与版本。令牌丢失时，可信调用方可调用 `reissueResumeToken` 重签；旧 token 随版本和哈希原子更新立即失效，新 token 仍只返回一次。恢复请求必须同时匹配 Run、token 哈希、token version 和确认内容；相同确认的重复请求幂等，不同确认、过期或已消费 token 返回冲突。恢复成功后从 checkpoint 的 `nextStepKey` 继续，经哈希校验的确认 JSON 只作为该 checkpoint 后续步骤的 `humanResponse` 提供，不会重复执行触发中断的已完成步骤。

取消、超时和步骤完成使用状态与版本条件更新竞争。终态一旦写入，迟到的模型结果不会写入 checkpoint、步骤输出或领域结果；取消与超时会同步终止仍在等待的人工中断，并通知 Run 级 `LlmStreamCall` 注册表。仅正在执行的 Run 保留取消信号直至执行线程退出，排队或等待态取消不会在注册表残留。

## 重启恢复

应用就绪后执行一次完整恢复：先快照数据库中遗留的 `running` Run，再重新派发 `queued` Run，避免把本次启动刚领取的任务误判为上一进程遗留。快照中的 `running` Run 会校验最新 checkpoint；checkpoint 合法时把未完成步骤标记为重启失败并从其 `nextStepKey` 重新排队。checkpoint 缺失、损坏或不支持 schema 时，Runtime 会在同一事务内调用工作流失败处理，同步终止领域候选、Run、活动 Step 和关联 AI Task，并发布最终生命周期事件；尚未达到最大尝试次数的活动 Step 保持可重试，人工重试复用原 Run 和冻结输入。重复恢复已进入终态的 Run 不会再次调用工作流失败处理。周期扫描只重新派发 `queued` Run，不扫描当前进程可能仍在正常执行的 `running` Run，避免重复领取长耗时步骤。`waiting_for_human` 保持等待，不会自动生成新 token。已超过 `timeout_at` 的非终态 Run 转为 `timed_out`。

章节 SSE 只发布 `agent_run.updated` 的 Run、Step、checkpoint 和中断引用；不携带输入快照、checkpoint 内容、提示词、模型正文、异常堆栈或恢复 token。客户端断线重连后可通过 `GET /api/agent-runs/{runId}` 查询数据库事实，当前单用户基线固定按 `local-user` 校验 Run 归属；响应包含当前状态、步骤键、checkpoint sequence 和中断引用，不包含 token 明文。
