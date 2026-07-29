# moqi-backend

墨契后端是基于 Java 17、Spring Boot、Maven、MySQL、MyBatis-Plus 和 Flyway 的多模块服务。

## 模块结构

- `moqi-start`：启动与装配模块，放 `SpringBootApplication`、运行配置、Flyway 迁移、Mapper 扫描。
- `moqi-web`：接口层，放 Controller、Web 异常处理、请求响应 DTO、参数校验入口。
- `moqi-service`：服务层，放业务服务接口、服务实现与应用编排。
- `moqi-dal`：持久层，放 Entity、Mapper 和后续持久化相关配置。
- `moqi-common`：公用层，放统一返回、错误码、基础异常、常量、通用枚举。

完整分层、领域模型、接口、数据流和数据库说明见
[`docs/项目结构与功能介绍.md`](docs/项目结构与功能介绍.md)。

## 当前能力

- 作品、章节、共创会话、结构化章节共识、章节大纲和正文版本管理。
- 章节生成预览、采纳、拒绝和重新生成，以及设定、伏笔、摘要和关键事件知识层。
- Provider V2、加密凭据、模型状态与连接测试、AI 任务查询和取消。
- 章节讨论真实 DeepSeek 流式回复、assistant 消息持久化和章节 SSE 事件。
- 数据库结构由 `moqi-start/src/main/resources/db/migration` 下的 Flyway V1-V12 管理。

## 本地运行

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-local-mysql.ps1
mvn -pl moqi-start -am spring-boot:run
```

- 业务健康检查：`http://127.0.0.1:8080/api/health`
- Actuator：`http://127.0.0.1:8080/actuator/health`

## 验证

```powershell
mvn clean verify
git diff --check
```

P3C 在 Maven `verify` 阶段以报告模式运行；构建成功与规范报告结果需要分别核对。

## 本地数据库基线

- MySQL 路径：`E:\middleware\mysql-8.4.9`
- 端口：`3306`
- 默认库名：`moqi_dev`
- 默认用户名：`root`
- 默认密码：空

如需覆盖连接信息，可通过环境变量 `MOQI_DB_URL`、`MOQI_DB_USERNAME`、`MOQI_DB_PASSWORD` 调整。

## 当前边界

- 章节讨论和共识收束可调用真实模型；章节正文生成和大纲刷新当前仍使用可替换的确定性本地实现。
- SSE 注册表是单实例内存实现，横向扩容前需要替换为跨实例事件分发。
- 当前不包含登录鉴权和真实多用户权限。
- 凭据必须通过现有加密配置链路管理，不得写入 README、日志、测试输出或 Git 历史。
