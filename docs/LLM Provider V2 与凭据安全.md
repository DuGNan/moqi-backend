# LLM Provider V2 与凭据安全

## 适用范围

本文描述后端当前有效的模型调用契约、DeepSeek 适配、流式取消、用户模型凭据存储、旧数据迁移和主密钥轮换边界。

## Provider V2 契约

`LlmRequest` 由有序 `LlmMessage` 和 `LlmOptions` 组成。消息角色包含 `SYSTEM`、`USER`、`ASSISTANT`、`TOOL`；当前 DeepSeek 实现不执行工具调用，收到 `TOOL` 请求会明确返回不支持能力错误。

通用选项包括：

- `maxOutputTokens`
- `temperature`
- `stopSequences`
- `responseFormat`：`TEXT` 或 `JSON_OBJECT`

`LlmResponse` 同时提供原始文本、可选 JSON object 和 `LlmResponseMetadata`。元数据包含 provider、model、finishReason、输入/输出/总 token 和供应商请求 ID。

流式调用通过 `LlmStreamEvent` 区分文本增量、usage/metadata 和 completed。`LlmProvider.stream` 启动订阅后立即返回 `LlmStreamCall`；调用方可取消、等待终态并查询是否结束。

DeepSeek 当前能力声明：

| 能力 | 当前值 |
| --- | --- |
| streaming | `true` |
| structuredOutput | `true`，使用 JSON object |
| toolCalling | `false`，Provider 未接入工具协议 |
| maxContextTokens | `1,000,000` |
| maxOutputTokens | `384,000` |

## 章节回复取消

运行中 `conversation_reply` 任务取消成功后发布独立内部 `AiTaskCancellationSignal`。事务提交后的监听器按 `aiTaskId` 查找活动 `LlmStreamCall` 并调用 `cancel()`。

注册表处理以下顺序：

- 先取消、后注册：登记 pending cancellation，注册时立即取消。
- 重复取消：`LlmStreamCall.cancel()` 幂等。
- 取消与完成竞争：Reactor call handle 只允许一个终态获胜。
- Runner 结束：`finally` 解除活动调用和 pending 标记。

数据库任务状态是权威事实源。Runner 只在任务仍为 `running` 时转发 delta，并在持久化 assistant 消息前再次检查状态；网络取消结果不会把数据库终态反向改写。

## 凭据存储

API Key 不进入 `user_configs.config_value`。独立表 `llm_credentials` 保存：

- `user_id`
- `provider`
- `credential_type`
- Base64 编码的 ciphertext 与 GCM tag
- Base64 编码的随机 nonce
- `key_id`
- 安全末尾摘要
- version 与审计时间

加密使用 JDK `AES/GCM/NoPadding`、256 位主密钥、每次 12 字节随机 nonce 和 128 位 tag。AAD 固定为 `userId + provider + credentialType`，密文移动到其他身份后认证失败。

包含 Key、密文或 nonce 的运行时对象均覆盖字符串表示；日志、异常、响应、SSE 和测试输出不得包含这些值。

## 运行时主密钥

主密钥只从运行时环境读取：

```text
MOQI_CREDENTIAL_ACTIVE_KEY_ID=key-v2
MOQI_CREDENTIAL_KEYS=key-v1=<Base64AES256Key>,key-v2=<Base64AES256Key>
```

规则：

- 每个 Base64 值解码后必须为 32 字节。
- 新写入始终使用 active key。
- 解密按凭据记录的 `key_id` 选择历史 key。
- active key 和历史 key 不得写入仓库、数据库普通配置、启动参数示例值或日志。
- 没有旧明文且没有凭据写入/读取时，主密钥可暂不配置；发现旧明文时必须配置，否则应用启动失败。

## 配置 API

`PUT /api/user-configs/model.active` 保持现有请求兼容：

- `apiKey` 非空：加密创建或替换凭据。
- `apiKey` 缺省或空白：保留现有凭据。
- `clearApiKey=true`：物理删除对应凭据。
- `apiKey` 与 `clearApiKey=true` 同时提交：返回参数错误。

配置 JSON 与凭据变更加入同一事务，配置 CAS 或凭据写入任一步失败都会整体回滚。读取只返回 `apiKeyConfigured` 与 `apiKeyMasked`。

连接测试在数据库事务外解密最小作用域运行时快照并访问 DeepSeek，随后用原配置版本执行短 CAS 写回。供应商鉴权失败只保存固定安全错误。

## 旧明文迁移

应用启动后扫描未删除的 `model.active`：

1. 找到顶层明文 `apiKey`。
2. 验证 active/history key ring 可用。
3. 在同一事务内创建凭据或验证既有凭据可解密。
4. 从配置 JSON 删除 `apiKey`。
5. 使用配置 `id + user_id + config_key + version + deleted` 条件更新。

迁移重复执行不会重复创建凭据。主密钥缺失、keyId 不存在、GCM 认证失败、旧配置畸形、凭据写入失败或 CAS 冲突都会失败关闭并回滚；日志只记录 configId 和迁移数量。

## 主密钥轮换

1. 将新 key 加入 `MOQI_CREDENTIAL_KEYS`，保留仍被记录引用的历史 key。
2. 把 `MOQI_CREDENTIAL_ACTIVE_KEY_ID` 指向新 key。
3. 调用维护流程执行 `LlmCredentialService.rotateAll()`：按旧 keyId 解密，再用 active key 与新随机 nonce 加密并 CAS 更新。
4. 确认数据库中不再引用旧 keyId 后，才能从运行时 key ring 移除旧 key。

当前未提供公开 HTTP 轮换端点，避免把高权限密钥维护操作暴露给普通用户配置接口。外部 KMS 不在当前范围，`CredentialKeyRing` 保留未来替换边界。
