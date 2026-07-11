# moqi-backend

墨契后端当前采用 Maven 多模块结构，按项目职责拆为：

- `moqi-start`：启动与装配模块，放 `SpringBootApplication`、运行配置、Flyway 迁移、Mapper 扫描。
- `moqi-web`：接口层，放 Controller、Web 异常处理、请求响应 DTO、参数校验入口。
- `moqi-service`：服务层，放业务服务接口、服务实现与应用编排。
- `moqi-dal`：持久层，放 Entity、Mapper 和后续持久化相关配置。
- `moqi-common`：公用层，放统一返回、错误码、基础异常、常量、通用枚举。

## 包结构约定

- `moqi-start`
  - `com.dugnan.moqi`
  - `com.dugnan.moqi.config`
- `moqi-common`
  - `com.dugnan.moqi.common.api`
  - `com.dugnan.moqi.common.exception`
  - `com.dugnan.moqi.common.constant`
  - `com.dugnan.moqi.common.enums`
- `moqi-web`
  - `com.dugnan.moqi.<biz>.controller`
  - `com.dugnan.moqi.<biz>.dto`
  - `com.dugnan.moqi.web.exception`
- `moqi-service`
  - `com.dugnan.moqi.<biz>.service`
  - `com.dugnan.moqi.<biz>.service.impl`
- `moqi-dal`
  - `com.dugnan.moqi.common.entity`
  - `com.dugnan.moqi.<biz>.entity`
  - `com.dugnan.moqi.<biz>.mapper`

当前已有业务域示例：

- `health`：健康检查链路。
- `work`：作品与章节相关持久化模型。

## 当前目录说明

- `docs/`：后端设计与数据库设计文档。
- `scripts/`：本地 MySQL 启停脚本。
- `config/`：预留给后续独立配置文件或环境模板。

## 本地运行

1. 启动本地 MySQL：
   `powershell -ExecutionPolicy Bypass -File .\scripts\start-local-mysql.ps1`
2. 运行聚合测试：
   `mvn test`
3. 启动后端：
   `mvn -pl moqi-start -am spring-boot:run`
4. 验证健康检查：
   `http://127.0.0.1:8080/api/health`
5. 验证 Actuator：
   `http://127.0.0.1:8080/actuator/health`

## 本地数据库基线

- MySQL 路径：`E:\middleware\mysql-8.4.9`
- 端口：`3307`
- 默认库名：`moqi_dev`
- 默认用户名：`root`
- 默认密码：空

如需覆盖连接信息，可通过环境变量 `MOQI_DB_URL`、`MOQI_DB_USERNAME`、`MOQI_DB_PASSWORD` 调整。
