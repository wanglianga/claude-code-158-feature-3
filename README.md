# 城市餐厨废油回收预约称重联单合规追踪服务

覆盖 **预约 → 派单 → 到店扫码核验 → 称重 → 运输轨迹 → 处置厂二次称重 → 合规闭环 → 结算** 的全链路，
以 **电子联单为单一口径**（司机、商户、处置厂、监管端读取同一份数据），
并处理取消、迟到、混入厨余、设备离线、手动补录、轨迹缺失、拒收、重量质疑、监管抽查等异常，
对长期产量/营业规模/回收频次不匹配的门店自动生成风险名单。

**监管抽查追踪扩展**：监管人员抽查某条街餐饮商户废油去向时，服务按 **商户（门店）+ 日期 + 回收批次**
生成完整链路（预约、称重、司机、车辆、到厂联单、结算）；某商户长期无回收记录时标记疑似违规并
自动生成**现场检查任务**，监管人员可回填**检查照片、约谈结果、整改期限**；商户恢复正常回收后
**风险状态逐步下降**（重点巡查 → 关注 → 解除），持续无记录则进入**重点巡查名单**。

## 技术栈

Java 17 · Spring Boot 3.3 · Spring Data JPA · Spring Kafka · PostgreSQL 15 · Kafka 3.8（KRaft）· Flyway · Maven

架构要点：

- **电子联单（manifests）单一口径**：派单时创建联单，称重/收料/结算在同一数据库事务内回写联单，
  各方通过 `GET /api/manifests/{id}` 看到完全相同的全链路数据，不存在各端各存一套口径的问题。
- **Kafka 领域事件总线**：8 个主题承载全部状态变更事件，审计消费者将事件落库 `audit_logs`
  形成不可抵赖的审计流水，支撑监管追溯。
- **合规闭环引擎**：收料时比对出店重量、到厂重量、运输损耗率、油桶数量、轨迹完整性，
  阻断性异常（损耗超标/桶数不符/轨迹缺失）挂起联单，工单处理完毕后自动重估闭环。
- **风险分级与恢复**：按 90 天窗口比对门店实际回收量与按营业规模预估的产量；长期无回收先标记
  **疑似违规（WATCH 关注）** 并自动生成现场检查任务，连续多周期无回收升级为 **KEY_PATROL 重点巡查**；
  恢复正常回收后随每个新回收批次**逐步降级**（重点巡查→关注→解除），并自动取消未结检查任务。
- **监管抽查链路**：按商户、日期、回收批次（联单）组装废油去向完整链路
  （预约/称重/司机/车辆/到厂联单/结算/异常/抽查记录），支持按街道批量抽查。
- **现场检查任务**：长期无回收自动派单，监管回填现场照片、约谈结果、整改期限；逾期自动置 OVERDUE。

## 本地启动

### 方式 A：一键脚本（无 Docker 环境，本仓库验证环境即此方式）

```bash
bash scripts/start-local.sh   # 启动 PostgreSQL(5433) + Kafka(9092) + 应用(8080)
```

### 方式 B：Docker 一键启动（有 Docker 的开发机）

```bash
docker compose up -d --build   # PostgreSQL(5433) + Kafka(9092/9094) + 应用(8080)
```

应用启动时 Flyway 自动建表（V1~V3）并写入演示数据，随后访问 http://localhost:8080 即可。

仅起依赖做本地开发：

```bash
docker compose up -d postgres kafka   # 仅依赖
mvn spring-boot:run                   # 应用(8080) 本地运行
```

> 数据库连接等配置见 `src/main/resources/application.yml`（默认 `localhost:5433/wasteoil`，账号 `wasteoil/wasteoil123`）。

## 测试账号（轻量鉴权：请求头 `X-Role` + `X-Account-Id`）

| 角色 | X-Role | X-Account-Id | 说明 |
|---|---|---|---|
| 商户 | `MERCHANT` | `merchant-1`（川香餐饮集团）/ `merchant-2`（老城美食广场） | 提交预约、取消、质疑重量、查回收证明与结算 |
| 回收公司 | `RECYCLER` | `recycler-1` | 派单、处理异常工单、查车辆效率 |
| 司机 | `DRIVER` | `driver-1`（张师傅）/ `driver-2`（李师傅） | 出发/到店/扫码核验/称重/轨迹/到厂 |
| 处置厂 | `PLANT` | `plant-1`（绿源废油处置厂） | 二次称重收料、接收/拒收 |
| 监管 | `REGULATOR` | `regulator-1` | 联单追溯、抽查、风险名单、趋势报表 |

演示基础数据（Flyway `V2__seed.sql`）：门店 3 家（含商圈与营业规模）、油桶 8 只（`BAR-001`~`BAR-202`）、
车辆 2 台、司机 2 名、处置厂 1 家（接收时间 08:00-18:00）、称重设备 3 台（`DEV-001`~`DEV-003`）、
春熙路商圈早晚高峰限行规则。

## 验证方式

### 1. 自动化测试（41 个用例）

```bash
mvn test
```

> 集成测试需要本地 PostgreSQL（5433 端口，首次执行 `bash scripts/start-local.sh` 会自动创建 `wasteoil_test` 库）；Kafka 由测试内嵌启动，无需外部依赖。

- 单元测试（27 个）：派单规划（容量/限行/接收时间窗）、合规闭环比对（损耗/桶数/轨迹/质检）、
  风险分析（10 个：含长期无回收升级/恢复逐步降级/检查任务联动）、现场检查任务（6 个：自动派单去重/回填/逾期/取消）
- 集成测试 `EndToEndIntegrationTest`（14 个）：真实 PostgreSQL 测试库 + 内嵌 Kafka + MockMvc，
  覆盖全链路闭环、一桶一码追溯、重量质疑、监管抽查、商户取消、手动补录、拒收、
  损耗异常阻断与恢复、派单校验、角色鉴权、风险扫描、三类报表，以及
  **监管抽查完整链路、长期无回收检查任务回填、恢复正常回收风险逐步下降与任务自动取消**。

### 2. 端到端冒烟脚本（45 项断言，需应用已启动）

```bash
bash scripts/smoke.sh
```

按真实调用顺序走通：主流程闭环（联单 CLOSED、损耗 2kg、结算 947.2 元）→ 油桶追溯 →
商户质疑（结算冻结→按 297kg 调整为 950.4 元）→ 临时取消（派单/联单联动取消）→
手动补录（无原因 400，有原因留痕）→ 拒收（联单 REJECTED）→ 损耗 20% 阻断闭环（工单处理后恢复 CLOSED）→
抽查/风险名单/车辆效率/回收证明/监管趋势 →
**监管抽查完整链路（预约/称重/司机/车辆/到厂联单/结算 + 按街道抽查）→
长期无回收自动生成检查任务 → 回填照片/约谈结果/整改期限（不合格复核风险）→
恢复正常回收风险解除、未结任务自动取消、当日抽查链路可见批次** → Kafka 事件审计流水落库校验。

### 实际验证结论（本次交付前执行）

- `mvn test`：**41/41 通过**（单元 27 + 集成 14，集成测试使用 EmbeddedKafka + PostgreSQL 测试库）
- `bash scripts/smoke.sh`：**45/45 通过**（真实 Kafka 3.8 KRaft + PostgreSQL 15 + 应用实跑）
- 冒烟期间应用日志 0 条 ERROR；8 个 Kafka 主题全部创建；`audit_logs` 审计流水随事件持续增长。
  冒烟实跑可见 `RiskSuspected`、`RiskRecovered` 与 `InspectionTaskCreated/Claimed/Completed/Cancelled`
  落库；`RiskEscalated`（升级重点巡查）、`RiskDowngraded`（重点巡查→关注）由自动化单元/集成测试覆盖。

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
| GET | `/api/supervision/streets/{street}/chains?date=` | 监管 | 按街道抽查：返回街道各商户当日回收批次链路 |
| GET | `/api/supervision/stores/{id}/chains?date=&from=&to=` | 监管 | 按商户+日期（区间）查完整回收链路 |
| GET | `/api/supervision/manifests/{id}/chain` | 监管/回收 | 单个回收批次（联单）完整链路：预约/称重/司机/车辆/到厂联单/结算 |
| POST/GET | `/api/risks/scan` `/api/risks?status=&riskLevel=` | 监管 | 风险扫描与风险名单（可按 WATCH/KEY_PATROL 过滤） |
| GET | `/api/risks/key-patrol` | 监管/回收 | 重点巡查名单（连续多周期无回收） |
| POST | `/api/risks/{id}/review` | 监管 | 风险复核（CONFIRMED / DISMISSED） |
| POST/GET | `/api/inspection-tasks` `/api/inspection-tasks/{id}` | 监管 | 人工发起 / 查看现场检查任务 |
| GET | `/api/inspection-tasks/by-store/{storeId}` | 监管/回收 | 门店的现场检查任务列表 |
| POST | `/api/inspection-tasks/{id}/claim` | 监管 | 认领检查任务（进入检查中） |
| POST | `/api/inspection-tasks/{id}/complete` | 监管 | 回填检查照片、约谈结果、整改期限、合格/不合格结论并结案 |
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
| 监管街道抽查 | 按商户+日期+回收批次返回完整链路；按街道批量汇总，无批次即当日无回收 |
| 长期无回收 | 首周期标记疑似违规（WATCH）并自动生成现场检查任务；连续多周期无回收升级 KEY_PATROL 重点巡查 |
| 现场检查回填 | 监管回填照片、约谈结果、整改期限；不合格将关联风险复核为 CONFIRMED；超期自动 OVERDUE |
| 恢复正常回收 | 每出现一批新的合规回收，风险下降一级（重点巡查→关注→解除），风险解除自动取消未结检查任务 |

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
├── inspection/    监管抽查登记 + 现场检查任务（自动派单/回填照片/约谈/整改期限/逾期）
├── supervision/   监管街道抽查：按商户+日期+批次组装废油去向完整链路
├── risk/          长期趋势风险分析、风险分级（关注/重点巡查）与恢复降级
├── report/        监管趋势/车辆效率/回收证明
├── audit+ kafka/  领域事件总线与审计流水落库
└── merchant|barrel/ 基础数据与一桶一码追溯
```
