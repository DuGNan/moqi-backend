# Issue #36 Spring AI Alibaba 依赖基线实施计划

> **For Codex:** REQUIRED SUB-SKILL: Use executing-plans to implement this plan task-by-task.

**目标：** 将后端升级到 Spring Boot 3.5.8，并建立 Spring AI Alibaba 1.1.2.2、Spring AI 1.1.2 与 DeepSeek Chat starter 的可编译依赖基线，同时不发起真实模型调用。

**架构：** 在父 POM 统一管理框架版本，在 `moqi-service` 引入 DeepSeek Chat starter，使后续 LLM Provider 与业务编排归属服务层。运行配置默认将 Chat Model 设为 `none`，避免在 #36 阶段因数据库尚未接入模型凭据而误报可用或发起网络请求。

**技术栈：** Java 17、Spring Boot 3.5.8、Spring AI Alibaba 1.1.2.2、Spring AI 1.1.2、Maven、JUnit 5、AssertJ。

---

### 任务 1：建立 DeepSeek 依赖契约

**文件：**
- 新增：`moqi-service/src/test/java/com/dugnan/moqi/llm/DeepSeekDependencyBaselineTest.java`

1. 编写测试，验证 `DeepSeekChatModel` 实现 Spring AI `ChatModel`。
2. 在未引入依赖时运行定向测试并确认编译失败。

### 任务 2：升级框架并引入 DeepSeek starter

**文件：**
- 修改：`pom.xml`
- 修改：`moqi-service/pom.xml`
- 修改：`moqi-start/src/main/resources/application.yml`

1. 将 Spring Boot 升级到 3.5.8。
2. 通过 BOM 固定 Spring AI Alibaba 1.1.2.2，并显式记录 Spring AI 1.1.2 基线。
3. 在服务层引入 `spring-ai-starter-model-deepseek`。
4. 默认配置 `spring.ai.model.chat=none`，确保未进入真实 LLM 调用阶段。
5. 运行定向测试，确认依赖契约通过。

### 任务 3：文档与兼容性验证

**文件：**
- 修改：`docs/项目结构与功能介绍.md`

1. 记录当前 AI 框架基线、模块归属和默认禁用边界。
2. 检查依赖树中的 Spring Boot、Spring AI、Spring AI Alibaba 版本。
3. 执行 `mvn clean verify`，单独汇总构建结果与 P3C 报告。
4. 执行 `git diff --check`。

### 任务 4：交付

1. 使用中文提交信息提交并推送当前工作分支。
2. 执行一次只读代码复审；发现问题后由主 Agent 修复并复验。
3. 创建中文 Pull Request，关联并保留 #36 到评审合并阶段。
