# 城市餐厨废油回收预约称重联单合规追踪服务

覆盖 **预约 → 派单 → 到店扫码核验 → 称重 → 运输轨迹 → 处置厂二次称重 → 合规闭环 → 结算** 的全链路，
以 **电子联单为单一口径**（司机、商户、处置厂、监管端读取同一份数据），
并处理取消、迟到、混入厨余、设备离线、手动补录、轨迹缺失、拒收、重量质疑、监管抽查等异常，
对长期产量/营业规模/回收频次不匹配的门店自动生成风险名单。

**监管抽查追踪扩展**：监管人员可按 **商户、日期、回收批次** 一键生成完整链路视图
（预约/称重/司机/车辆/到厂联单/结算）；对 **长期无回收记录** 的商户自动标记疑似违规并
**生成现场检查任务**，监管回填 **检查照片、约谈结果、整改期限**；商户恢复正常回收后
**风险状态逐轮巡查逐步下降**，持续无记录则 **进入重点巡查名单**。

## 技术栈

Java 17 · Spring Boot 3.3 · Spring Data JPA · Spring Kafka · PostgreSQL 15 · Kafka 3.8（KRaft）· Flyway · Maven

架构要点：

- **电子联单（manifests）单一口径**：派单时创建联单，称重/收料/结算在同一数据库事务内回写联单，
  各方通过 `GET /api/manifests/{id}` 看到完全相同的全链路数据，不存在各端各存一套口径的问题。
- **监管链路追踪**：`GET /api/regulatory/chains` 按门店/商户/商圈 + 日期聚合当日全部回收批次，
  每批次直接给出预约、派单、司机、车辆、处置厂、称重/轨迹条数、到厂收料联单与结算。
- **Kafka 领域事件总线**：7 个主题承载全部状态变更事件（含现场检查任务生成/回填/风险等级变化），
  审计消费者将事件落库 `audit_logs` 形成不可抵赖的审计流水，支撑监管追溯。
- **合规闭环引擎**：收料时比对出店重量、到厂重量、运输损耗率、油桶数量、轨迹完整性，
  阻断性异常（损耗超标/桶数不符/轨迹缺失）挂起联单，工单处理完毕后自动重估闭环。
- **风险分析与巡查**：按 90 天窗口比对门店实际回收量与按营业规模预估的产量，长期不匹配进入风险名单；
  巡查扫描按 30/60 天无回收阈值驱动门店风险画像 NORMAL → WATCH → KEY 演进。

## 本地启动

### 方式 A：Docker 一键启动整套（推荐，有 Docker 的开发机）

```bash
docker compose up -d --build   # PostgreSQL(5433) + Kafka 3.8 KRaft(9092) + 应用(8080)
```

应用容器等待 PG/Kafka 健康检查通过后启动，Flyway 自动建表（V1→V3）并写入演示数据。
启动完成后：

- 应用：http://localhost:8080
- **Swagger UI（OpenAPI 3.x，可在线调试）**：http://localhost:8080/swagger-ui/index.html
- OpenAPI JSON：http://localhost:8080/v3/api-docs （Swagger UI 右上角 Authorize 填 `X-Role=REGULATOR`、`X-Account-Id=regulator-1` 即可调监管接口）

### 方式 B：一键脚本（无 Docker 环境，本仓库验证环境即此方式）

```bash
bash scripts/start-local.sh   # 启动 PostgreSQL(5433) + Kafka(9092) + 应用(8080)
```

### 方式 C：本地已备好依赖，仅启动应用

```bash
docker compose up -d postgres kafka   # 或自行准备 PG 5433 / Kafka 9092
mvn spring-boot:run                   # Flyway 自动建表并写入演示数据
```

> 数据库连接等配置见 `src/main/resources/application.yml`（默认 `localhost:5433/wasteoil`，账号 `wasteoil/wasteoil123`）。
> 容器化部署时连接地址通过环境变量覆盖（见 `docker-compose.yml` 的 `SPRING_DATASOURCE_URL` / `SPRING_KAFKA_BOOTSTRAP_SERVERS`）。

## 测试账号（轻量鉴权：请求头 `X-Role` + `X-Account-Id`）

| 角色 | X-Role | X-Account-Id | 说明 |
|---|---|---|---|
| 商户 | `MERCHANT` | `merchant-1`（川香餐饮集团）/ `merchant-2`（老城美食广场） | 提交预约、取消、质疑重量、查回收证明与结算 |
| 回收公司 | `RECYCLER` | `recycler-1` | 派单、处理异常工单、查车辆效率 |
| 司机 | `DRIVER` | `driver-1`（张师傅）/ `driver-2`（李师傅） | 出发/到店/扫码核验/称重/轨迹/到厂 |
| 处置厂 | `PLANT` | `plant-1`（绿源废油处置厂） | 二次称重收料、接收/拒收 |
| 监管 | `REGULATOR` | `regulator-1` | 联单追溯、抽查、风险名单、趋势报表 |

演示基础数据（Flyway `V2__seed.sql` / `V3__regulatory_tracking.sql`）：门店 4 家（含商圈与营业规模，
其中"老城夜市小炒店"开业 40 天、从未交运废油，首扫即命中疑似违规）、油桶 10 只（`BAR-001`~`BAR-302`）、
车辆 2 台、司机 2 名、处置厂 1 家（接收时间 08:00-18:00）、称重设备 4 台（`DEV-001`~`DEV-004`）、
春熙路商圈早晚高峰限行规则。

## 验证方式

### 1. 自动化测试（38 个用例）

```bash
mvn test
```

> 集成测试需要本地 PostgreSQL（5433 端口，首次执行 `bash scripts/start-local.sh` 会自动创建 `wasteoil_test` 库）；Kafka 由测试内嵌启动，无需外部依赖。

- 单元测试：派单规划（容量/限行/接收时间窗）、合规闭环比对（损耗/桶数/轨迹/质检）、风险分析、
  监管巡查（疑似违规/重点巡查/风险逐步下降/回填/整改复核，共 21 个）
- 集成测试 `EndToEndIntegrationTest`：真实 PostgreSQL 测试库 + 内嵌 Kafka + MockMvc，
  覆盖全链路闭环、一桶一码追溯、重量质疑、监管抽查、商户取消、手动补录、拒收、
  损耗异常阻断与恢复、派单校验、角色鉴权、风险扫描、三类报表，以及
  监管链路追踪、现场检查任务自动生成→回填→重点巡查→恢复回收风险下降→复核关闭（13 个）

### 2. 端到端冒烟脚本（45 项断言，需应用已启动）

```bash
bash scripts/smoke.sh
```

按真实调用顺序走通：主流程闭环（联单 CLOSED、损耗 2kg、结算 947.2 元）→ 油桶追溯 →
商户质疑（结算冻结→按 297kg 调整为 950.4 元）→ 临时取消（派单/联单联动取消）→
手动补录（无原因 400，有原因留痕）→ 拒收（联单 REJECTED）→ 损耗 20% 阻断闭环（工单处理后恢复 CLOSED）→
抽查/风险名单/车辆效率/回收证明/监管趋势 →
**监管链路追踪（商户+日期+批次）→ 长期无回收自动生成现场检查任务（WATCH 50 分）→
回填照片/约谈/整改期限（升级 KEY 重点巡查）→ 再巡查不重复生成任务 →
商户恢复闭环回收后两轮巡查风险 80→40→0（KEY→WATCH→NORMAL）→ 整改复核关闭任务** →
Kafka 事件审计流水落库校验。

### 实际验证结论（本次交付前执行）

- `mvn test`：**38/38 通过**（单元 21 + 集成 17，集成测试使用 EmbeddedKafka + PostgreSQL 测试库；
  已在同一持久测试库上连续执行两遍均全绿，`@BeforeAll` 会重置全量交易数据，可重复运行）
- `bash scripts/smoke.sh`：**49/49 通过**（全新空库由 Flyway 执行 V1→V3，PostgreSQL 15 +
  Kafka KRaft 4.3.1 实跑 + 应用实跑；本环境从 archive.apache.org 拉取 3.8 包速度过慢，
  改用协议兼容的 4.3.1 仅用于本地冒烟，`docker compose` 默认仍为 Kafka 3.8）
- 冒烟期间应用日志 **0 条 ERROR**；7 个 Kafka 主题全部创建；
  新增 `SiteInspectionTaskGenerated` / `SiteInspectionFilled` / `SiteInspectionClosed` /
  `StoreRiskLevelChanged` 事件均在 `audit_logs` 落库；
  终态：门店 4 现场检查任务 `VERIFIED_CLOSED`、风险画像 `NORMAL/0`、退出重点巡查名单

## 主要 API

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | `/api/appointments` | 商户 | 提交回收预约（门店/桶数/预计重量/存放位置/营业时段/联系人） |
| POST | `/api/appointments/{id}/cancel` | 商户 | 临时取消（联动取消派单与联单，登记异常） |
| POST | `/api/dispatches` | 回收公司 | 生成派单（校验车辆容量、商圈限行、处置厂接收时间窗，同时创建电子联单） |
| POST | `/api/dispatches/{id}/depart` `/arrive` | 司机 | 出发 / 到店（超时自动登记司机迟到） |
| POST | `/api/dispatches/{id}/verify` | 司机 | 扫码核验门店二维码、称重设备、交接人、现场照片 |
| POST | `/api/weighings` | 司机 | 称重（`DEVICE` 直传 / `MANUAL` 手动补录须填原因；离线设备数据打标） |
| POST | `/api/weighings/devices/{code}/heartbeat` | 设备 | 称重设备心跳 |
| POST | `/api/dispatches/{id}/load-complete` | 司机 | 装车完成起运（联单转 IN_TRANSIT） |
| POST | `/api/tracks/{dispatchId}` | 司机 | 批量上传运输轨迹点 |
| POST | `/api/receipts` | 处置厂 | 收料：二次称重 + 质检 + 接收/拒收，自动触发合规比对 |
| GET | `/api/manifests/{id}` | 各端 | 联单全链路详情（单一口径：预约/派单/称重/轨迹/收料/结算/异常） |
| GET | `/api/barrels/{code}/trace` | 各端 | 一桶一码追溯 |
| POST | `/api/disputes` `/api/disputes/{id}/resolve` | 商户/回收公司 | 重量质疑与协商解决（结算冻结/调整） |
| GET/POST | `/api/exceptions` `/api/exceptions/{id}/resolve` | 回收公司/监管 | 异常工单查询与处理（阻断类处理后联单自动重估） |
| POST | `/api/inspections` | 监管 | 抽查登记（不合格自动入风险名单） |
| POST/GET | `/api/risks/scan` `/api/risks` | 监管 | 风险扫描与风险名单 |
| GET | `/api/regulatory/chains?storeId=&merchantId=&district=&date=` | 监管 | 按商户/日期/回收批次生成完整链路（预约/称重/司机/车辆/到厂联单/结算） |
| POST | `/api/regulatory/patrol/scan` | 监管 | 巡查扫描：长期无回收标记疑似违规并生成现场检查任务，持续无回收列入重点巡查 |
| GET/POST | `/api/regulatory/tasks` | 监管 | 现场检查任务查询（按状态/门店）与手工发起 |
| POST | `/api/regulatory/tasks/{id}/fill` | 监管 | 回填现场检查：照片（必填）、约谈结果、整改期限（认定违规必填） |
| POST | `/api/regulatory/tasks/{id}/verify` | 监管 | 整改期满复核（通过关闭/不通过续整改） |
| GET | `/api/regulatory/risk-profiles` | 监管 | 门店动态风险画像（NORMAL/WATCH/KEY） |
| GET | `/api/regulatory/risk-profiles/key-patrol` | 监管 | 重点巡查名单 |
| GET | `/api/reports/regulator/trend` | 监管 | 废油流向合规处置的长期趋势 |
| GET | `/api/reports/vehicle-efficiency` | 回收公司 | 车辆效率（趟次/运量/准点） |
| GET | `/api/reports/stores/{id}/certificate` | 商户 | 回收证明（联单清单 + 合计 + 结算金额） |
| GET | `/api/settlements?storeId=` | 商户 | 结算单查询 |

## 异常场景处理一览

| 场景 | 机制 |
|---|---|
| 商户临时取消 | 预约/派单/联单联动取消，登记 `MERCHANT_CANCEL` 工单 |
| 司机迟到 | 到店超时判定 + 每分钟定时扫描，登记 `DRIVER_LATE` |
| 油桶混入厨余 | 收料质检 `CONTAMINATED`，联单标记 `qualityIssue` 并登记工单（不阻断闭环） |
| 称重设备离线 | 心跳超时判定离线，离线数据在联单上计数留痕，登记 `DEVICE_OFFLINE` |
| 司机手动补录 | 必须填写原因，联单记录补录条数，登记 `MANUAL_ENTRY` |
| 运输轨迹缺失 | 定时扫描 + 收料时合规校验，阻断闭环直至工单处理 |
| 处置厂拒收 | 联单 `REJECTED`，登记 `PLANT_REJECT` 由回收公司处置 |
| 商户质疑重量 | 联单 `DISPUTED`、结算冻结，协商后按调整重量重新结算并闭环 |
| 监管抽查 | 抽查登记，不合格自动登记异常并将门店列入风险名单 |
| 长期产量不匹配 | 每日定时 + 手动扫描，90 天窗口产量/规模/频次比对，生成风险名单 |
| 监管链路追踪 | 按商户+日期聚合当日回收批次，每批次串联预约/称重/司机/车辆/到厂联单/结算同一口径 |
| 长期无回收（疑似违规） | 每日 02:30 + 手动巡查扫描，≥30 天无合规回收标记 WATCH（50 分）并自动生成现场检查任务 |
| 现场检查回填 | 监管回填检查照片（必填）、约谈结果、整改期限；认定违规任务转责令整改并升级 KEY（80 分）重点巡查 |
| 持续无回收 | 连续两轮巡查仍无回收或无回收满 60 天 → KEY，列入重点巡查名单；未结任务不重复生成 |
| 恢复正常回收 | 每轮巡查风险分下调一档（80→40→0），连续两轮确认恢复后 KEY→WATCH→NORMAL 并退出重点巡查 |

## 目录结构

```
src/main/java/com/wasteoil/
├── appointment/   预约提交与取消
├── dispatch/      派单规划（容量/限行/时间窗）与运输执行
├── weighing/      扫码核验、设备/手动称重、设备心跳
├── track/         运输轨迹与缺失扫描
├── plant/         处置厂二次称重收料
├── compliance/    合规闭环比对引擎
├── manifest/      电子联单（单一口径）与全链路详情
├── settlement/    结算（联单为唯一结算依据）
├── exception/     异常工单（阻断/非阻断）
├── dispute/       商户重量质疑
├── inspection/    监管抽查
├── regulatory/    监管链路追踪（商户/日期/批次）、巡查扫描、现场检查任务、风险画像与重点巡查
├── risk/          长期趋势风险分析与风险名单
├── report/        监管趋势/车辆效率/回收证明
├── audit+ kafka/  领域事件总线与审计流水落库
└── merchant|barrel/ 基础数据与一桶一码追溯
```
