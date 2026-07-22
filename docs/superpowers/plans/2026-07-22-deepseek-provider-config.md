# DeepSeek Provider 与模型配置 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** 在不接入 AI 任务和前端的前提下，交付可替换 DeepSeek Provider、数据库模型配置、连接测试与持久化状态。

**Architecture:** `UserConfigServiceImpl` 保持现有入口与乐观锁，针对 `model.active` 执行固定字段规范化、密钥保留/替换/删除和脱敏读取。独立 `llm` 包提供 Provider、工厂、请求响应和安全错误映射。模型状态 Controller 增加测试端点，服务先调用远端、再条件更新数据库，确保事务不跨网络且配置竞争返回 409。

**Tech Stack:** Java 21、Spring Boot、Spring AI Alibaba 1.1.2、MyBatis-Plus、JUnit 5、Mockito、JDK HttpServer。

---

### Task 1: 配置请求、密钥语义与脱敏

- [ ] 扩展 `UserConfigModels.UpdateUserConfigRequest` 与 `ModelStatus`。
- [ ] 先在 `UserConfigServiceImplTest` 增加创建、保留、替换、删除、冲突、脱敏、非模型拒绝与版本冲突测试并确认失败。
- [ ] 最小修改 `UserConfigServiceImpl` 使测试通过，配置变更重置测试状态。
- [ ] 运行 `mvn -pl moqi-service -am -Dtest=UserConfigServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false test`。
- [ ] 中文提交并推送。

### Task 2: Provider 与安全错误映射

- [ ] 新增 `LlmProvider`、Provider 请求/结果、安全错误类型和动态工厂。
- [ ] 使用本机 `HttpServer` 先写请求路径、Bearer、模型、64 token、content/reasoningContent 与 HTTP/超时/断连/空响应/非法 JSON 测试并确认失败。
- [ ] 使用 `DeepSeekApi`、`DeepSeekChatModel`、JDK HTTP request factory 实现 DeepSeek Provider；连接 5 秒、读取 60 秒、单次尝试。
- [ ] 运行 Provider 定向测试和依赖树检查。
- [ ] 中文提交并推送。

### Task 3: 连接测试编排与 HTTP 兼容

- [ ] 先用 Fake Provider 增加未配置、成功、失败、配置并发变化测试并确认失败。
- [ ] 实现测试编排：远程调用不加事务，按原版本原子写回，失败只保存安全消息。
- [ ] 为 `POST /api/system/model-status/test` 增加请求 DTO、Controller 方法和兼容性测试。
- [ ] 验证旧 PUT payload、旧 GET 字段和新增 `configVersion`。
- [ ] 中文提交并推送。

### Task 4: 文档与完整验证

- [ ] 更新 `docs/项目结构与功能介绍.md` 当前态说明。
- [ ] 执行所有定向测试、`mvn clean verify`、依赖检查和 `git diff --check`。
- [ ] 单独汇总 Surefire、构建与 P3C/PMD 报告结果。
- [ ] 中文提交并推送，安排一次只读复审，修复后复核。
- [ ] 创建中文 PR，关联并等待 issue #38 验收。
