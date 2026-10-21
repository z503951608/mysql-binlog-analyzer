# MySQL Binlog Analyzer

基于 Spring Boot 的 MySQL binlog 在线 / 离线分析工具。通过 Web 界面连接 MySQL 或上传 binlog 文件，解析 ROW 格式的行变更事件，并生成可执行的 **redo / undo SQL**，用于误操作恢复、数据审计和变更排查。

## 功能特性

- **在线分析**：直接连接 MySQL 读取 binlog 流，可选择起始 binlog 文件，也可连续分析到最新日志。
- **离线分析**：上传本地 binlog 文件进行分析；也可先用内置 mysqlbinlog 从服务器下载 binlog，再离线分析。
- **redo / undo SQL**：将每条行变更还原为正反两条 SQL，支持 INSERT / UPDATE / DELETE。
- **数据字典**：自动读取库、表、字段与主键信息，生成可直接执行的 SQL；优先使用主键生成 WHERE 条件，无主键时回退为全部列并给出提示。
- **结果过滤**：支持按库、表、操作类型、时间范围、SQL 关键字过滤，并可只看 redo 或 undo。
- **结果导出**：将全部或选中记录导出为 `.sql` 文件，支持 utf8、utf8mb4、gbk、gb18030、big5、latin1 编码。
- **任务管理**：异步分析、实时进度、取消任务、记录数上限保护、过期任务自动清理。
- **无需前端构建**：静态页面由 Spring Boot 直接托管，启动后即可使用。

## redo / undo 生成规则

| binlog 事件 | redo（正向重放） | undo（反向回滚） |
| --- | --- | --- |
| INSERT | `INSERT INTO ... VALUES (...)` | `DELETE FROM ... WHERE 主键（无主键时使用全部列）` |
| UPDATE | `UPDATE ... SET 变更后值 WHERE 变更前值` | `UPDATE ... SET 变更前值 WHERE 变更后值` |
| DELETE | `DELETE FROM ... WHERE 主键（无主键时使用全部列）` | `INSERT INTO ... VALUES (...)` |

## 工作原理

1. 解析 binlog：在线分析使用 `BinaryLogClient` 流式读取，离线分析使用 `BinaryLogFileReader` 读取上传文件。
2. 通过 `TableMap` 事件定位数据库和表，并读取实时的表结构（数据字典）。
3. 按字段类型将行镜像转换为 SQL 字面量（数值、日期时间、二进制、enum/set、bit 等）。
4. 生成 redo / undo SQL，在页面中展示，并支持导出。

> 在线和离线分析都需要先连接一个包含目标表结构的 MySQL 实例，用于读取数据字典。

## 环境要求

- JDK 8
- Maven 3.5+
- MySQL 5.6 及以上，已开启 binlog，且 `binlog_format=ROW`（建议 `binlog_row_image=FULL`）
- 现代浏览器

可在 MySQL 中确认以下配置：

```sql
SHOW VARIABLES LIKE 'log_bin';
SHOW VARIABLES LIKE 'binlog_format';
SHOW VARIABLES LIKE 'binlog_row_image';
```

## 快速开始

```bash
git clone https://github.com/z503951608/mysql-binlog-analyzer.git
cd mysql-binlog-analyzer
mvn clean package
java -jar target/mysql-binlog-analyzer.jar
```

启动后访问：<http://localhost:8080>

开发调试时也可以直接运行：

```bash
mvn spring-boot:run
```

## 数据库权限

分析账号至少需要以下权限：

```sql
GRANT SELECT, REPLICATION CLIENT, REPLICATION SLAVE ON *.* TO 'binlog_reader'@'%';
```

| 权限 | 用途 |
| --- | --- |
| `SELECT` | 读取数据字典（库、表、字段信息） |
| `REPLICATION CLIENT` | 执行 `SHOW BINARY LOGS` 获取日志列表 |
| `REPLICATION SLAVE` | 在线读取 binlog、通过 mysqlbinlog 下载日志 |

## 使用流程

1. 填写主机、端口、用户名、密码，连接并选择目标数据库。
2. 选择分析模式：
   - **在线**：选择起始 binlog 文件；勾选“分析到最新日志”后会连续读取后续日志，到达最新文件末尾并空闲 3 秒后自动结束。
   - **离线**：上传 `.binlog` 文件（会校验文件头），或先通过“下载在线 binlog 日志”把服务器日志下载到本地。
3. 选择表、操作类型、时间范围、关键字和展示内容（全部 / redo / undo），开始分析。
4. 查看 SQL 明细，勾选需要的记录导出为 `.sql` 文件。

## 配置说明

配置文件位于 `src/main/resources/application.yml`，所有配置均以 `binlog.` 为前缀：

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `binlog.upload-dir` | `./data/binlogs` | 离线 binlog 文件保存目录 |
| `binlog.max-results` | `20000` | 单次分析保留的最大 SQL 记录数，防止大日志耗尽内存 |
| `binlog.idle-timeout-ms` | `3000` | 在线分析到达最新日志后，空闲多久自动结束（毫秒） |
| `binlog.mysqlbinlog-path` | 空 | mysqlbinlog 可执行文件路径，支持目录或相对路径 |
| `binlog.client-server-id` | `20200621` | 在线分析使用的从库 server-id |
| `binlog.job-retention-minutes` | `120` | 分析任务在内存中的保留时长（分钟） |
| `binlog.download-timeout-seconds` | `300` | 下载在线 binlog 的超时时间（秒） |

`mysqlbinlog` 的查找顺序：

1. `binlog.mysqlbinlog-path` 配置的路径
2. `MYSQLBINLOG_PATH` 环境变量
3. 项目内置工具（`src/main/resources/tools/mysqlbinlog`，Windows x64）
4. 系统 `PATH`

## REST API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/dictionary` | 连接 MySQL 并加载数据字典 |
| `GET` | `/api/dictionary/status` | 查看当前连接状态 |
| `GET` | `/api/dictionary/tables?schema={schema}` | 查询指定库的数据表 |
| `POST` | `/api/dictionary/disconnect` | 断开连接 |
| `GET` | `/api/binlogs` | 查询服务器上的 binlog 列表 |
| `POST` | `/api/binlogs/download` | 准备在线 binlog 下载，返回下载票据 |
| `GET` | `/api/binlogs/stream?token={token}` | 下载 binlog 文件 |
| `POST` | `/api/uploads` | 上传离线 binlog（multipart，字段名 `file`） |
| `GET` | `/api/uploads` | 查询已上传的 binlog 列表 |
| `POST` | `/api/analysis` | 创建分析任务 |
| `GET` | `/api/analysis/{id}` | 查询任务进度与结果 |
| `POST` | `/api/analysis/{id}/cancel` | 取消分析任务 |
| `POST` | `/api/analysis/{id}/export` | 导出 redo / undo SQL |

创建分析任务示例：

```json
{
  "online": true,
  "schema": "demo",
  "table": "orders",
  "startFile": "mysql-bin.000123",
  "analyzeToLatest": true,
  "startTime": "2026-10-21 10:00:00",
  "endTime": "2026-10-21 12:00:00",
  "operation": "all",
  "display": "all",
  "keyword": "",
  "charset": "utf8mb4"
}
```

离线分析时将 `online` 设为 `false`，并用 `uploadId` 指定已上传的文件；`operation` 可选 `all`、`insert`、`update`、`delete`，`display` 可选 `all`、`redo`、`undo`。

## 项目结构

```text
src/main/java/com/binlog/analyzer
├── config/       # 配置属性
├── controller/   # REST 接口
├── model/        # 请求 / 响应与数据模型
├── service/      # binlog 解析、SQL 生成、导出、下载等核心逻辑
├── util/         # 时间、字符集、流处理工具
└── web/          # 全局异常处理与会话支持

src/main/resources
├── application.yml
├── static/       # 原生 HTML / CSS / JavaScript 页面
└── tools/        # 内置 mysqlbinlog（Windows x64）
```

## 已知限制与注意事项

- 仅支持 **ROW 格式** 的 binlog；STATEMENT / MIXED 格式无法还原行数据。
- 表结构以分析时连接的数据库为准，无法回溯历史 DDL。若表结构已变更，旧日志生成的 SQL 可能不准确，列数不一致时页面会给出警告。
- 不支持加密的 binlog（`SHOW BINARY LOGS` 中 `Encrypted=YES`）。
- 内置的 mysqlbinlog 为 Windows x64 版本；在 Linux / macOS 上请配置 `binlog.mysqlbinlog-path` 或将其加入 `PATH`。
- 分析结果保存在内存中，默认最多 20000 条；任务完成后默认保留 120 分钟，服务重启后结果丢失。上传文件保存在 `./data/binlogs`。
- 上传单个文件上限为 4GB（Spring multipart 配置）。
- 应用本身没有登录鉴权，数据库口令保存在服务端 Session 中。请勿直接暴露到公网，建议仅在内网使用。
- 生成的 SQL 在执行前请先在测试环境验证，并做好数据备份。

## 技术栈

- Java 8、Spring Boot 2.2.6
- mysql-binlog-connector-java 0.31.0
- MySQL Connector/J
- 原生 HTML / CSS / JavaScript

## 许可证

仓库暂未添加 LICENSE 文件。如需公开分发或用于商业场景，请先补充合适的许可证。
