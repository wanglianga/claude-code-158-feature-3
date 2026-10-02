package com.wasteoil;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wasteoil.audit.AuditLogRepository;
import com.wasteoil.inspection.InspectionTaskRepository;
import com.wasteoil.merchant.Store;
import com.wasteoil.merchant.StoreRepository;
import com.wasteoil.risk.RiskEntryRepository;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端集成测试：真实 PostgreSQL（测试库）+ 内嵌 Kafka + MockMvc。
 * 覆盖主流程闭环与各类异常场景。
 */
@SpringBootTest
@AutoConfigureMockMvc
@EmbeddedKafka(partitions = 1)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EndToEndIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper om;
    @Autowired
    AuditLogRepository auditLogRepository;
    @Autowired
    RiskEntryRepository riskEntryRepository;
    @Autowired
    InspectionTaskRepository inspectionTaskRepository;
    @Autowired
    StoreRepository storeRepository;

    // 跨用例共享的主流程产物
    static long happyManifestId;
    static long happyDispatchId;

    // ---------- 工具方法 ----------

    private JsonNode postJson(String url, String role, String account, String body, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(post(url)
                        .header("X-Role", role)
                        .header("X-Account-Id", account)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return om.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode getJson(String url, String role, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(get(url).header("X-Role", role).header("X-Account-Id", "test"))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return om.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static String tomorrowAt(int hour, int minute) {
        return LocalDate.now(ZONE).plusDays(1).atTime(LocalTime.of(hour, minute))
                .atZone(ZONE).toInstant().toString();
    }

    private long createAppointment(long storeId, int barrels, String estimatedWeight) throws Exception {
        JsonNode resp = postJson("/api/appointments", "MERCHANT", "merchant-1", """
                {"storeId": %d, "barrelCount": %d, "estimatedWeightKg": %s,
                 "windowStart": "%s", "windowEnd": "%s"}
                """.formatted(storeId, barrels, estimatedWeight, tomorrowAt(10, 0), tomorrowAt(11, 0)), 200);
        return resp.path("data").path("id").asLong();
    }

    private long planDispatch(long appointmentId, String start, String end) throws Exception {
        JsonNode resp = postJson("/api/dispatches", "RECYCLER", "recycler-1", """
                {"appointmentId": %d, "vehicleId": 1, "driverId": 1, "plantId": 1,
                 "plannedStart": "%s", "plannedEnd": "%s", "routeNote": "测试路线"}
                """.formatted(appointmentId, start, end), 200);
        return resp.path("data").path("id").asLong();
    }

    private long manifestIdOf(long dispatchId) throws Exception {
        JsonNode resp = getJson("/api/manifests", "REGULATOR", 200);
        for (JsonNode m : resp.path("data")) {
            if (m.path("dispatchId").asLong() == dispatchId) {
                return m.path("id").asLong();
            }
        }
        throw new IllegalStateException("未找到派单 " + dispatchId + " 对应的联单");
    }

    /** 司机执行到店核验 */
    private void arriveAndVerify(long dispatchId, String storeQr, String deviceCode) throws Exception {
        postJson("/api/dispatches/" + dispatchId + "/depart", "DRIVER", "driver-1", "{}", 200);
        postJson("/api/dispatches/" + dispatchId + "/arrive", "DRIVER", "driver-1", "{}", 200);
        postJson("/api/dispatches/" + dispatchId + "/verify", "DRIVER", "driver-1", """
                {"storeQr": "%s", "deviceCode": "%s", "handoverName": "王店长",
                 "photoUrls": "https://img.example.com/p1.jpg"}
                """.formatted(storeQr, deviceCode), 200);
    }

    private void weigh(long dispatchId, String barrelCode, String weight, String source, String note) throws Exception {
        postJson("/api/weighings", "DRIVER", "driver-1", """
                {"dispatchId": %d, "barrelCode": "%s", "weightKg": %s, "deviceCode": "DEV-001",
                 "source": "%s", "photoUrl": "https://img.example.com/w.jpg",
                 "handoverName": "王店长", "note": %s}
                """.formatted(dispatchId, barrelCode, weight, source,
                note == null ? "null" : "\"" + note + "\""), 200);
    }

    private void uploadTracksAndArrivePlant(long dispatchId) throws Exception {
        postJson("/api/tracks/" + dispatchId, "DRIVER", "driver-1", """
                {"points": [{"lat": 30.66, "lng": 104.06, "recordedAt": "%s"},
                            {"lat": 30.60, "lng": 104.10, "recordedAt": "%s"}]}
                """.formatted(Instant.now(), Instant.now()), 200);
        postJson("/api/dispatches/" + dispatchId + "/arrive-plant", "DRIVER", "driver-1", "{}", 200);
    }

    /** 门店 3（老城大排档）完成一趟合规回收闭环，返回联单 ID */
    private long runStore3Pickup(String barrelCode) throws Exception {
        postJson("/api/weighings/devices/DEV-003/heartbeat", "RECYCLER", "recycler-1", "{}", 200);
        long appointmentId = createAppointment(3, 1, "90");
        long dispatchId = planDispatch(appointmentId, tomorrowAt(10, 0), tomorrowAt(11, 0));
        postJson("/api/dispatches/" + dispatchId + "/depart", "DRIVER", "driver-1", "{}", 200);
        postJson("/api/dispatches/" + dispatchId + "/arrive", "DRIVER", "driver-1", "{}", 200);
        postJson("/api/dispatches/" + dispatchId + "/verify", "DRIVER", "driver-1", """
                {"storeQr": "STORE-QR-3", "deviceCode": "DEV-003", "handoverName": "刘老板",
                 "photoUrls": "https://img.example.com/store3.jpg"}
                """, 200);
        postJson("/api/weighings", "DRIVER", "driver-1", """
                {"dispatchId": %d, "barrelCode": "%s", "weightKg": 80, "deviceCode": "DEV-003",
                 "source": "DEVICE", "photoUrl": "https://img.example.com/w3.jpg",
                 "handoverName": "刘老板"}
                """.formatted(dispatchId, barrelCode), 200);
        postJson("/api/dispatches/" + dispatchId + "/load-complete", "DRIVER", "driver-1", "{}", 200);
        uploadTracksAndArrivePlant(dispatchId);
        long manifestId = manifestIdOf(dispatchId);
        postJson("/api/receipts", "PLANT", "plant-1", """
                {"manifestId": %d, "arrivalWeightKg": 79, "barrelCount": 1,
                 "qualityCheck": "OK", "decision": "ACCEPT", "receivedBy": "厂验收员"}
                """.formatted(manifestId), 200);
        return manifestId;
    }

    // ---------- 主流程 ----------

    @Test
    @Order(1)
    void 全链路闭环_预约到结算() throws Exception {
        // 设备心跳
        postJson("/api/weighings/devices/DEV-001/heartbeat", "RECYCLER", "recycler-1", "{}", 200);

        long appointmentId = createAppointment(1, 2, "300");
        long dispatchId = planDispatch(appointmentId, tomorrowAt(10, 0), tomorrowAt(11, 0));
        happyDispatchId = dispatchId;

        arriveAndVerify(dispatchId, "STORE-QR-1", "DEV-001");
        weigh(dispatchId, "BAR-001", "150", "DEVICE", null);
        weigh(dispatchId, "BAR-002", "148", "DEVICE", null);
        postJson("/api/dispatches/" + dispatchId + "/load-complete", "DRIVER", "driver-1", "{}", 200);
        uploadTracksAndArrivePlant(dispatchId);

        long manifestId = manifestIdOf(dispatchId);
        happyManifestId = manifestId;

        // 处置厂二次称重 296kg（损耗 2kg，约 0.67%）
        JsonNode receipt = postJson("/api/receipts", "PLANT", "plant-1", """
                {"manifestId": %d, "arrivalWeightKg": 296, "barrelCount": 2,
                 "qualityCheck": "OK", "decision": "ACCEPT", "receivedBy": "厂验收员"}
                """.formatted(manifestId), 200);
        assertThat(receipt.path("data").path("decision").asText()).isEqualTo("ACCEPT");

        // 联单应闭环：CLOSED，损耗已计算
        JsonNode detail = getJson("/api/manifests/" + manifestId, "REGULATOR", 200);
        JsonNode manifest = detail.path("data").path("manifest");
        assertThat(manifest.path("status").asText()).isEqualTo("CLOSED");
        assertThat(manifest.path("complianceResult").asText()).isEqualTo("PASS");
        assertThat(manifest.path("exitWeightKg").asDouble()).isEqualTo(298.0);
        assertThat(manifest.path("arrivalWeightKg").asDouble()).isEqualTo(296.0);
        assertThat(manifest.path("lossKg").asDouble()).isEqualTo(2.0);
        // 全链路数据齐备：称重 2 条、轨迹 2 条、收料 1 条、结算 1 条
        assertThat(detail.path("data").path("weighings").size()).isEqualTo(2);
        assertThat(detail.path("data").path("tracks").size()).isEqualTo(2);
        assertThat(detail.path("data").path("receipt").isNull()).isFalse();
        assertThat(detail.path("data").path("settlement").isNull()).isFalse();

        // 结算金额 = 296 * 3.20 = 947.20
        JsonNode settlement = getJson("/api/settlements/by-manifest/" + manifestId, "MERCHANT", 200);
        assertThat(settlement.path("data").path("amount").asDouble()).isEqualTo(947.20);
        assertThat(settlement.path("data").path("status").asText()).isEqualTo("CONFIRMED");

        // Kafka 事件应已被审计消费者落库
        await().atMost(15, TimeUnit.SECONDS).until(() -> auditLogRepository.findAll().stream()
                .anyMatch(a -> "ManifestClosed".equals(a.getEventType())));
    }

    @Test
    @Order(2)
    void 一桶一码追溯() throws Exception {
        JsonNode trace = getJson("/api/barrels/BAR-001/trace", "REGULATOR", 200);
        assertThat(trace.path("data").path("barrel").path("status").asText()).isEqualTo("AT_PLANT");
        assertThat(trace.path("data").path("weighingRecords").size()).isEqualTo(1);
        assertThat(trace.path("data").path("weighingRecords").get(0).path("weightKg").asDouble())
                .isEqualTo(150.0);
    }

    @Test
    @Order(3)
    void 商户质疑重量_冻结结算_协商调整() throws Exception {
        // 商户发起质疑
        JsonNode dispute = postJson("/api/disputes", "MERCHANT", "merchant-1", """
                {"manifestId": %d, "reason": "出店称重 298kg，到厂 296kg，对损耗 2kg 有异议"}
                """.formatted(happyManifestId), 200);
        long disputeId = dispute.path("data").path("id").asLong();

        // 联单进入 DISPUTED，结算冻结
        JsonNode manifest = getJson("/api/manifests/" + happyManifestId, "MERCHANT", 200);
        assertThat(manifest.path("data").path("manifest").path("status").asText()).isEqualTo("DISPUTED");
        JsonNode settlement = getJson("/api/settlements/by-manifest/" + happyManifestId, "MERCHANT", 200);
        assertThat(settlement.path("data").path("status").asText()).isEqualTo("FROZEN");

        // 协商解决：按 297kg 结算
        postJson("/api/disputes/" + disputeId + "/resolve", "RECYCLER", "recycler-1", """
                {"resolution": "双方复核称重记录，按 297kg 结算", "adjustedWeightKg": 297}
                """, 200);
        JsonNode after = getJson("/api/settlements/by-manifest/" + happyManifestId, "MERCHANT", 200);
        assertThat(after.path("data").path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(after.path("data").path("amount").asDouble()).isEqualTo(950.40);
        JsonNode closed = getJson("/api/manifests/" + happyManifestId, "MERCHANT", 200);
        assertThat(closed.path("data").path("manifest").path("status").asText()).isEqualTo("CLOSED");
    }

    @Test
    @Order(4)
    void 监管抽查不合格_登记异常并进风险名单() throws Exception {
        postJson("/api/inspections", "REGULATOR", "regulator-1", """
                {"manifestId": %d, "result": "FAIL", "note": "现场照片不清晰，交接记录不完整"}
                """.formatted(happyManifestId), 200);

        JsonNode tickets = getJson("/api/exceptions/by-ref?refType=MANIFEST&refId=" + happyManifestId,
                "REGULATOR", 200);
        boolean hasInspectionTicket = false;
        for (JsonNode t : tickets.path("data")) {
            if ("REGULATORY_INSPECTION".equals(t.path("type").asText())) {
                hasInspectionTicket = true;
            }
        }
        assertThat(hasInspectionTicket).isTrue();

        JsonNode risks = getJson("/api/risks", "REGULATOR", 200);
        boolean hasRisk = false;
        for (JsonNode r : risks.path("data")) {
            if ("INSPECTION_FAIL".equals(r.path("indicator").asText())) {
                hasRisk = true;
            }
        }
        assertThat(hasRisk).isTrue();
    }

    // ---------- 异常场景 ----------

    @Test
    @Order(10)
    void 商户临时取消_联动取消派单与联单() throws Exception {
        long appointmentId = createAppointment(1, 1, "100");
        long dispatchId = planDispatch(appointmentId, tomorrowAt(10, 0), tomorrowAt(11, 0));
        long manifestId = manifestIdOf(dispatchId);

        postJson("/api/appointments/" + appointmentId + "/cancel", "MERCHANT", "merchant-1",
                "{\"reason\": \"今日店休，暂停回收\"}", 200);

        JsonNode appointment = getJson("/api/appointments/" + appointmentId, "MERCHANT", 200);
        assertThat(appointment.path("data").path("status").asText()).isEqualTo("CANCELLED");
        JsonNode dispatch = getJson("/api/dispatches/" + dispatchId, "RECYCLER", 200);
        assertThat(dispatch.path("data").path("status").asText()).isEqualTo("CANCELLED");
        JsonNode manifest = getJson("/api/manifests/" + manifestId, "REGULATOR", 200);
        assertThat(manifest.path("data").path("manifest").path("status").asText()).isEqualTo("CANCELLED");

        JsonNode tickets = getJson("/api/exceptions/by-ref?refType=APPOINTMENT&refId=" + appointmentId,
                "RECYCLER", 200);
        assertThat(tickets.path("data").get(0).path("type").asText()).isEqualTo("MERCHANT_CANCEL");
    }

    @Test
    @Order(11)
    void 手动补录_必须填原因并留痕() throws Exception {
        long appointmentId = createAppointment(1, 1, "100");
        long dispatchId = planDispatch(appointmentId, tomorrowAt(10, 0), tomorrowAt(11, 0));
        arriveAndVerify(dispatchId, "STORE-QR-1", "DEV-001");

        // 无原因 → 400
        postJson("/api/weighings", "DRIVER", "driver-1", """
                {"dispatchId": %d, "barrelCode": "BAR-003", "weightKg": 120, "source": "MANUAL"}
                """.formatted(dispatchId), 400);

        // 有原因 → 成功，联单记录手动补录条数
        weigh(dispatchId, "BAR-003", "120", "MANUAL", "设备故障，人工磅秤复核");
        long manifestId = manifestIdOf(dispatchId);
        JsonNode manifest = getJson("/api/manifests/" + manifestId, "REGULATOR", 200);
        assertThat(manifest.path("data").path("manifest").path("manualWeighCount").asInt()).isEqualTo(1);

        JsonNode tickets = getJson("/api/exceptions/by-ref?refType=MANIFEST&refId=" + manifestId,
                "REGULATOR", 200);
        boolean hasManual = false;
        for (JsonNode t : tickets.path("data")) {
            if ("MANUAL_ENTRY".equals(t.path("type").asText())) {
                hasManual = true;
            }
        }
        assertThat(hasManual).isTrue();
    }

    @Test
    @Order(12)
    void 处置厂拒收_联单进入拒收态() throws Exception {
        postJson("/api/weighings/devices/DEV-002/heartbeat", "RECYCLER", "recycler-1", "{}", 200);
        long appointmentId = createAppointment(2, 1, "100");
        long dispatchId = planDispatch(appointmentId, tomorrowAt(10, 0), tomorrowAt(11, 0));
        arriveAndVerify(dispatchId, "STORE-QR-2", "DEV-002");
        postJson("/api/weighings", "DRIVER", "driver-1", """
                {"dispatchId": %d, "barrelCode": "BAR-101", "weightKg": 100, "deviceCode": "DEV-002",
                 "source": "DEVICE", "handoverName": "陈店长"}
                """.formatted(dispatchId), 200);
        postJson("/api/dispatches/" + dispatchId + "/load-complete", "DRIVER", "driver-1", "{}", 200);
        uploadTracksAndArrivePlant(dispatchId);
        long manifestId = manifestIdOf(dispatchId);

        postJson("/api/receipts", "PLANT", "plant-1", """
                {"manifestId": %d, "arrivalWeightKg": 100, "barrelCount": 1,
                 "qualityCheck": "CONTAMINATED", "decision": "REJECT",
                 "rejectReason": "油桶混入大量厨余垃圾，拒绝接收", "receivedBy": "厂验收员"}
                """.formatted(manifestId), 200);

        JsonNode manifest = getJson("/api/manifests/" + manifestId, "REGULATOR", 200);
        assertThat(manifest.path("data").path("manifest").path("status").asText()).isEqualTo("REJECTED");
        JsonNode tickets = getJson("/api/exceptions/by-ref?refType=MANIFEST&refId=" + manifestId,
                "REGULATOR", 200);
        boolean hasReject = false;
        for (JsonNode t : tickets.path("data")) {
            if ("PLANT_REJECT".equals(t.path("type").asText())) {
                hasReject = true;
            }
        }
        assertThat(hasReject).isTrue();
    }

    @Test
    @Order(13)
    void 运输损耗异常_阻断闭环_处理后恢复() throws Exception {
        postJson("/api/weighings/devices/DEV-002/heartbeat", "RECYCLER", "recycler-1", "{}", 200);
        long appointmentId = createAppointment(2, 1, "100");
        long dispatchId = planDispatch(appointmentId, tomorrowAt(10, 0), tomorrowAt(11, 0));
        arriveAndVerify(dispatchId, "STORE-QR-2", "DEV-002");
        postJson("/api/weighings", "DRIVER", "driver-1", """
                {"dispatchId": %d, "barrelCode": "BAR-102", "weightKg": 100, "deviceCode": "DEV-002",
                 "source": "DEVICE", "handoverName": "陈店长"}
                """.formatted(dispatchId), 200);
        postJson("/api/dispatches/" + dispatchId + "/load-complete", "DRIVER", "driver-1", "{}", 200);
        uploadTracksAndArrivePlant(dispatchId);
        long manifestId = manifestIdOf(dispatchId);

        // 出店 100kg，到厂仅 80kg，损耗 20% 超阈值
        postJson("/api/receipts", "PLANT", "plant-1", """
                {"manifestId": %d, "arrivalWeightKg": 80, "barrelCount": 1,
                 "qualityCheck": "OK", "decision": "ACCEPT", "receivedBy": "厂验收员"}
                """.formatted(manifestId), 200);

        JsonNode manifest = getJson("/api/manifests/" + manifestId, "REGULATOR", 200);
        assertThat(manifest.path("data").path("manifest").path("status").asText()).isEqualTo("EXCEPTION");
        assertThat(manifest.path("data").path("manifest").path("complianceResult").asText())
                .isEqualTo("EXCEPTION");

        // 处理阻断性异常工单后，联单自动重估并闭环
        JsonNode tickets = getJson("/api/exceptions/by-ref?refType=MANIFEST&refId=" + manifestId,
                "RECYCLER", 200);
        long ticketId = -1;
        for (JsonNode t : tickets.path("data")) {
            if ("TRANSPORT_LOSS_ABNORMAL".equals(t.path("type").asText())) {
                ticketId = t.path("id").asLong();
            }
        }
        assertThat(ticketId).isPositive();
        postJson("/api/exceptions/" + ticketId + "/resolve", "RECYCLER", "recycler-1",
                "{\"resolution\": \"确认为运输途中阀门渗漏，已维修并备案\"}", 200);

        JsonNode closed = getJson("/api/manifests/" + manifestId, "REGULATOR", 200);
        assertThat(closed.path("data").path("manifest").path("status").asText()).isEqualTo("CLOSED");
    }

    // ---------- 校验与鉴权 ----------

    @Test
    @Order(20)
    void 派单校验_容量不足与限行冲突() throws Exception {
        // 车辆容量 5000，预约 6000 → 拒绝
        long bigAppointment = createAppointment(1, 1, "6000");
        postJson("/api/dispatches", "RECYCLER", "recycler-1", """
                {"appointmentId": %d, "vehicleId": 1, "driverId": 1, "plantId": 1,
                 "plannedStart": "%s", "plannedEnd": "%s"}
                """.formatted(bigAppointment, tomorrowAt(10, 0), tomorrowAt(11, 0)), 400);

        // 春熙路商圈 17:00-19:30 限行 → 拒绝
        long appointment = createAppointment(1, 1, "100");
        JsonNode resp = postJson("/api/dispatches", "RECYCLER", "recycler-1", """
                {"appointmentId": %d, "vehicleId": 1, "driverId": 1, "plantId": 1,
                 "plannedStart": "%s", "plannedEnd": "%s"}
                """.formatted(appointment, tomorrowAt(17, 30), tomorrowAt(18, 0)), 400);
        assertThat(resp.path("message").asText()).contains("限行");
    }

    @Test
    @Order(21)
    void 角色鉴权_商户不能派单() throws Exception {
        postJson("/api/dispatches", "MERCHANT", "merchant-1", """
                {"appointmentId": 1, "vehicleId": 1, "driverId": 1, "plantId": 1,
                 "plannedStart": "%s", "plannedEnd": "%s"}
                """.formatted(tomorrowAt(10, 0), tomorrowAt(11, 0)), 403);
    }

    // ---------- 报表与风险 ----------

    @Test
    @Order(30)
    void 风险扫描_长期不匹配的门店进入风险名单() throws Exception {
        postJson("/api/risks/scan", "REGULATOR", "regulator-1", "{}", 200);
        JsonNode risks = getJson("/api/risks", "REGULATOR", 200);
        boolean yieldMismatch = false;
        for (JsonNode r : risks.path("data")) {
            if ("YIELD_MISMATCH".equals(r.path("indicator").asText())) {
                yieldMismatch = true;
            }
        }
        // 门店预期产量（60kg/天*90天）远高于实际回收量，应被识别
        assertThat(yieldMismatch).isTrue();
    }

    @Test
    @Order(31)
    void 报表_车辆效率与商户回收证明与监管趋势() throws Exception {
        JsonNode efficiency = getJson("/api/reports/vehicle-efficiency", "RECYCLER", 200);
        assertThat(efficiency.path("data").size()).isGreaterThanOrEqualTo(1);
        JsonNode first = efficiency.path("data").get(0);
        assertThat(first.path("plateNo").asText()).isNotBlank();

        JsonNode certificate = getJson("/api/reports/stores/1/certificate", "MERCHANT", 200);
        assertThat(certificate.path("data").path("manifestCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(certificate.path("data").path("totalWeightKg").asDouble()).isGreaterThan(0);

        JsonNode trend = getJson("/api/reports/regulator/trend", "REGULATOR", 200);
        assertThat(trend.path("data").size()).isGreaterThanOrEqualTo(1);
    }

    // ---------- 监管抽查追踪扩展 ----------

    @Test
    @Order(40)
    void 监管抽查_按商户日期批次生成完整链路() throws Exception {
        // 按商户 + 日期区间查询（覆盖此前主流程与各异常批次）
        String from = LocalDate.now(ZONE).minusDays(2).toString();
        String to = LocalDate.now(ZONE).plusDays(2).toString();
        JsonNode storeChain = getJson(
                "/api/supervision/stores/1/chains?from=" + from + "&to=" + to, "REGULATOR", 200);
        assertThat(storeChain.path("data").path("storeName").asText()).contains("川香");
        assertThat(storeChain.path("data").path("batchCount").asInt()).isGreaterThanOrEqualTo(1);

        // 主流程批次应包含预约/司机/车辆/到厂联单/结算完整链路
        JsonNode happyBatch = null;
        for (JsonNode b : storeChain.path("data").path("batches")) {
            if (b.path("manifestId").asLong() == happyManifestId) {
                happyBatch = b;
            }
        }
        assertThat(happyBatch).isNotNull();
        assertThat(happyBatch.path("appointment").path("storageLocation").asText()).isNotBlank();
        assertThat(happyBatch.path("dispatch").path("driver").path("name").asText()).isEqualTo("张师傅");
        assertThat(happyBatch.path("dispatch").path("vehicle").path("plateNo").asText()).isEqualTo("川A12345");
        assertThat(happyBatch.path("dispatch").path("plant").path("name").asText()).contains("绿源");
        assertThat(happyBatch.path("weighings").size()).isEqualTo(2);
        assertThat(happyBatch.path("plantReceipt").path("decision").asText()).isEqualTo("ACCEPT");
        assertThat(happyBatch.path("settlement").path("amount").asDouble()).isEqualTo(950.40);
        // 该批次此前被监管抽查过（order 4 的 FAIL 登记）
        assertThat(happyBatch.path("inspections").size()).isGreaterThanOrEqualTo(1);
        assertThat(happyBatch.path("inspections").get(0).path("result").asText()).isEqualTo("FAIL");

        // 单批次链路接口
        JsonNode one = getJson("/api/supervision/manifests/" + happyManifestId + "/chain",
                "REGULATOR", 200);
        assertThat(one.path("data").path("manifestNo").asText()).startsWith("M");
        assertThat(one.path("data").path("trackPointCount").asInt()).isEqualTo(2);

        // 按街道抽查：春熙路上的门店当日应能查到商户与批次
        JsonNode streetChains = getJson(
                "/api/supervision/streets/春熙路/chains?date=" + LocalDate.now(ZONE),
                "REGULATOR", 200);
        boolean containsStore1 = false;
        for (JsonNode c : streetChains.path("data")) {
            if (c.path("storeId").asLong() == 1L) {
                containsStore1 = true;
            }
        }
        assertThat(containsStore1).isTrue();
    }

    @Test
    @Order(41)
    void 长期无回收_检查任务回填_恢复正常回收风险逐步下降() throws Exception {
        // 门店 3 在 order(30) 的风险扫描中已因长期无回收被标记 WATCH 并自动生成现场检查任务
        JsonNode tasks = getJson("/api/inspection-tasks/by-store/3", "REGULATOR", 200);
        long taskId = -1;
        for (JsonNode t : tasks.path("data")) {
            if ("NO_RECYCLING".equals(t.path("triggerReason").asText())
                    && "OPEN".equals(t.path("status").asText())) {
                taskId = t.path("id").asLong();
            }
        }
        assertThat(taskId).isPositive();

        // 认领并回填：现场照片、约谈结果、整改期限，检查不合格
        postJson("/api/inspection-tasks/" + taskId + "/claim", "REGULATOR", "regulator-1", "{}", 200);
        String deadline = LocalDate.now(ZONE).plusDays(15).toString();
        JsonNode completed = postJson("/api/inspection-tasks/" + taskId + "/complete",
                "REGULATOR", "regulator-1", """
                        {"inspectionPhotoUrls": "https://img.example.com/inspect1.jpg,https://img.example.com/inspect2.jpg",
                         "interviewResult": "约谈负责人，承认前期将废油交无资质人员收运",
                         "rectificationDeadline": "%s",
                         "inspectionResult": "UNQUALIFIED",
                         "note": "现场无隔油池台账，责令限期整改"}
                        """.formatted(deadline), 200);
        assertThat(completed.path("data").path("status").asText()).isEqualTo("COMPLETED");
        assertThat(completed.path("data").path("inspectionPhotoUrls").asText()).contains("inspect2.jpg");
        assertThat(completed.path("data").path("rectificationDeadline").asText()).isEqualTo(deadline);

        // 检查不合格，关联风险条目被复核为 CONFIRMED
        JsonNode risks = getJson("/api/risks", "REGULATOR", 200);
        long store3RiskId = -1;
        for (JsonNode r : risks.path("data")) {
            if (r.path("storeId").asLong() == 3L && "NO_RECYCLING".equals(r.path("indicator").asText())) {
                store3RiskId = r.path("id").asLong();
                assertThat(r.path("status").asText()).isEqualTo("CONFIRMED");
                assertThat(r.path("riskLevel").asText()).isEqualTo("WATCH");
            }
        }
        assertThat(store3RiskId).isPositive();

        // 另开一张人工检查任务，用于验证风险解除时未结任务被自动取消
        JsonNode manual = postJson("/api/inspection-tasks", "REGULATOR", "regulator-1", """
                {"storeId": 3, "dueDate": "%s", "note": "复查整改情况"}
                """.formatted(LocalDate.now(ZONE).plusDays(10)), 200);
        long manualTaskId = manual.path("data").path("id").asLong();

        // 模拟"持续无回收"：将该风险升级为重点巡查
        Store store3 = storeRepository.findById(3L).orElseThrow();
        var riskRepo = riskEntryRepository.findById(store3RiskId).orElseThrow();
        riskRepo.setRiskLevel(com.wasteoil.risk.RiskLevel.KEY_PATROL);
        riskRepo.setConsecutiveNoRecyclingCycles(2);
        riskEntryRepository.save(riskRepo);
        assertThat(getJson("/api/risks/key-patrol", "REGULATOR", 200).path("data").size())
                .isGreaterThanOrEqualTo(1);

        // 恢复正常回收第一批：重点巡查 → 关注（逐步下降，不立即解除）
        runStore3Pickup("BAR-201");
        JsonNode afterFirst = getJson("/api/risks", "REGULATOR", 200);
        for (JsonNode r : afterFirst.path("data")) {
            if (r.path("id").asLong() == store3RiskId) {
                assertThat(r.path("riskLevel").asText()).isEqualTo("WATCH");
                assertThat(r.path("status").asText()).isEqualTo("CONFIRMED");
            }
        }

        // 第二批合规回收：关注 → 解除，未结人工检查任务自动取消
        runStore3Pickup("BAR-202");
        JsonNode afterSecond = getJson("/api/risks", "REGULATOR", 200);
        boolean stillActive = false;
        for (JsonNode r : afterSecond.path("data")) {
            if (r.path("id").asLong() == store3RiskId && !"RECOVERED".equals(r.path("status").asText())) {
                stillActive = true;
            }
        }
        assertThat(stillActive).isFalse();
        JsonNode manualTask = getJson("/api/inspection-tasks/" + manualTaskId, "REGULATOR", 200);
        assertThat(manualTask.path("data").path("status").asText()).isEqualTo("CANCELLED");

        // 恢复后该商户当日抽查链路可见 2 个回收批次
        JsonNode chain = getJson(
                "/api/supervision/stores/3/chains?date=" + LocalDate.now(ZONE), "REGULATOR", 200);
        assertThat(chain.path("data").path("batchCount").asInt()).isEqualTo(2);
        assertThat(chain.path("data").path("batches").get(0).path("settlement").path("amount").asDouble())
                .isGreaterThan(0);

        // 监管现场检查任务事件进入审计流水
        await().atMost(15, TimeUnit.SECONDS).until(() -> auditLogRepository.findAll().stream()
                .anyMatch(a -> "InspectionTaskCompleted".equals(a.getEventType())));
    }
}
