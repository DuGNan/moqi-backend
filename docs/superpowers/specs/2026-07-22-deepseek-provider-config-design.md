# DeepSeek Provider 与模型配置设计

## 目标与边界

本设计为 `model.active` 增加 DeepSeek 模型配置、密钥保存、连接测试与状态持久化。保持现有用户配置和模型状态 HTTP 接口兼容，不接入 Prompt、AI 任务、讨论消息或前端页面。

## 配置模型

- 继续使用 `user_configs` 的 `model.active` JSON，不新增表。
- 服务端固定 `provider=deepseek`、`providerName=DeepSeek`、`baseUrl=https://api.deepseek.com`。
- 模型只允许 `deepseek-v4-flash` 与 `deepseek-v4-pro`，缺省使用 `deepseek-v4-flash`。
- `PUT /api/user-configs/model.active` 在原请求上增加顶层 `apiKey`、`clearApiKey`。缺省或空白 Key 保留旧值，非空 Key 替换，`clearApiKey=true` 删除；二者同时出现返回 400。
- 明文 Key 暂存数据库；读取配置时删除 `apiKey`，仅返回 `apiKeyConfigured` 和只显示末四位的 `apiKeyMasked`。
- 每次修改配置都清空最近测试时间和错误，并把 `lastTestStatus` 重置为 `not_tested`。保存仍使用现有版本乐观锁。
- 非 `model.active` 配置拒绝 `apiKey` 或 `clearApiKey`。

## Provider

`LlmProvider` 是不依赖具体厂商的稳定调用边界；`DeepSeekLlmProvider` 使用 Spring AI 1.1.2 的 `DeepSeekApi` 与 `DeepSeekChatModel`。工厂在每次调用时从数据库快照动态创建 Provider，不缓存含 Key 的客户端。

DeepSeek HTTP 客户端固定连接超时 5 秒、读取超时 60 秒、重试 0 次。错误只映射为预定义中文安全消息，不记录响应体、请求头、Key 或带查询参数的 URL。

## 连接测试与并发

`POST /api/system/model-status/test` 接收 `baseVersion`，读取同版本配置后在事务外发送“仅回复 OK”的请求，最大输出 64 token。普通 `content` 或 `reasoningContent` 任一非空即成功；空结果或非法结构失败。

远程请求结束后，以原配置版本为条件原子写回测试状态并递增版本。若测试期间配置变化，返回 409 且不覆盖新配置。可预期 Provider 失败写入 `failed` 并以 200 返回模型状态；未配置 Key 返回 400，且不访问网络。

`available` 仅在当前配置的 `lastTestStatus=success` 时为 true。`checkedAt` 使用最近测试时间，未测试为 null。模型状态响应新增 `configVersion`，其余字段保持兼容。

## 安全与后续工作

本阶段明文方案只用于本地单用户开发，不能部署到生产或多人共享环境。issue #35 跟踪加密迁移。自动化测试只使用测试专用假值、Fake Provider 或本机 HTTP 服务，不需要真实 Key。
