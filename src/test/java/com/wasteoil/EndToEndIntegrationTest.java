package com.wasteoil;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wasteoil.audit.AuditLogRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
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
    JdbcTemplate jdbc;

    // 跨用例共享的主流程产物
    static long happyManifestId;
    static long happyDispatchId;
    // 长期无回收门店 4 的现场检查任务（巡查扫描自动生成）
    static long noRecyclingTaskId;

    /**
     * 重置全部交易数据并复位基础数据状态，保证测试套件在同一持久测试库上可重复执行
     * （Flyway 只迁移不清数据；基础主数据 stores/merchants/barrels 等由种子迁移保留）。
     */
    @BeforeAll
    void resetTransactionalData() {
        // 先清引用联单/派单的子表，再清主表
        jdbc.update("DELETE FROM weighing_records");
        jdbc.update("DELETE FROM track_points");
        jdbc.update("DELETE FROM pickup_verifications");
        jdbc.update("DELETE FROM plant_receipts");
        jdbc.update("DELETE FROM inspections");
        jdbc.update("DELETE FROM disputes");
        jdbc.update("DELETE FROM exception_tickets");
        jdbc.update("DELETE FROM settlements");
        jdbc.update("DELETE FROM manifests");
        jdbc.update("DELETE FROM dispatch_orders");
        jdbc.update("DELETE FROM appointments");
        jdbc.update("DELETE FROM audit_logs");
        jdbc.update("DELETE FROM risk_entries");
        jdbc.update("DELETE FROM site_inspection_tasks");
        jdbc.update("DELETE FROM store_risk_profiles");
        // 复位油桶为在店、演示门店 4 为"开业 40 天从未回收"
        jdbc.update("UPDATE barrels SET status = 'AT_STORE', updated_at = now()");
        jdbc.update("UPDATE stores SET status = 'ACTIVE'");
        jdbc.update("UPDATE stores SET created_at = now() - interval '40 days' WHERE id = 4");
        jdbc.update("UPDATE weighing_devices SET status = 'ONLINE', last_heartbeat_at = now()");
        jdbc.update("ALTER SEQUENCE manifest_no_seq RESTART WITH 1");
        jdbc.update("ALTER SEQUENCE site_inspection_no_seq RESTART WITH 1");
    }

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
    void 监管链路追踪_按商户日期批次组装完整链路() throws Exception {
        String today = LocalDate.now(ZONE).toString();
        JsonNode chain = getJson("/api/regulatory/chains?storeId=1&date=" + today, "REGULATOR", 200);
        JsonNode stores = chain.path("data").path("stores");
        assertThat(stores.size()).isGreaterThanOrEqualTo(1);
        JsonNode storeChain = stores.get(0);
        assertThat(storeChain.path("storeId").asLong()).isEqualTo(1L);
        assertThat(storeChain.path("batchCount").asInt()).isGreaterThanOrEqualTo(1);
        // 找到主流程联单对应的批次
        JsonNode batch = null;
        for (JsonNode b : storeChain.path("batches")) {
            if (b.path("manifestId").asLong() == happyManifestId) {
                batch = b;
            }
        }
        assertThat(batch).isNotNull();
        // 预约 / 司机 / 车辆 / 到厂联单 / 结算 全链路齐备
        assertThat(batch.path("appointment").path("id").asLong()).isPositive();
        assertThat(batch.path("driver").path("name").asText()).isEqualTo("张师傅");
        assertThat(batch.path("vehicle").path("plateNo").asText()).isEqualTo("川A12345");
        assertThat(batch.path("plant").path("name").asText()).contains("处置厂");
        assertThat(batch.path("receipt").path("decision").asText()).isEqualTo("ACCEPT");
        assertThat(batch.path("settlement").path("amount").asDouble()).isEqualTo(950.40);
        assertThat(batch.path("weighingCount").asInt()).isEqualTo(2);
        assertThat(batch.path("trackCount").asInt()).isEqualTo(2);

        // 必须指定查询范围
        getJson("/api/regulatory/chains?date=" + today, "REGULATOR", 400);
        // 非监管无权
        getJson("/api/regulatory/chains?storeId=1&date=" + today, "MERCHANT", 403);
    }

    @Test
    @Order(41)
    void 巡查扫描_长期无回收_疑似违规并生成现场检查任务() throws Exception {
        JsonNode scan = postJson("/api/regulatory/patrol/scan", "REGULATOR", "regulator-1", "{}", 200);
        // 种子门店 4 开业 40 天从未回收，至少生成 1 个任务
        assertThat(scan.path("data").path("tasksCreated").asInt()).isGreaterThanOrEqualTo(1);

        JsonNode tasks = getJson("/api/regulatory/tasks?storeId=4", "REGULATOR", 200);
        assertThat(tasks.path("data").size()).isGreaterThanOrEqualTo(1);
        JsonNode task = tasks.path("data").get(0);
        assertThat(task.path("status").asText()).isEqualTo("PENDING");
        assertThat(task.path("source").asText()).isEqualTo("RISK_SCAN");
        assertThat(task.path("reason").asText()).contains("无合规回收");
        assertThat(task.path("daysNoRecycling").asInt()).isGreaterThanOrEqualTo(30);
        noRecyclingTaskId = task.path("id").asLong();

        JsonNode profile = getJson("/api/regulatory/risk-profiles/4", "REGULATOR", 200);
        assertThat(profile.path("data").path("riskLevel").asText()).isEqualTo("WATCH");
        assertThat(profile.path("data").path("riskScore").asInt()).isEqualTo(50);
        assertThat(profile.path("data").path("keyPatrol").asBoolean()).isFalse();
    }

    @Test
    @Order(42)
    void 回填现场检查_认定违规责令整改_进入重点巡查名单() throws Exception {
        // 缺少检查照片 → 400
        postJson("/api/regulatory/tasks/" + noRecyclingTaskId + "/fill", "REGULATOR", "regulator-1", """
                {"checkResult": "NON_COMPLIANT", "photoUrls": [],
                 "interviewResult": "约谈老板", "rectifyDeadline": "%s"}
                """.formatted(LocalDate.now(ZONE).plusDays(7)), 400);

        JsonNode filled = postJson("/api/regulatory/tasks/" + noRecyclingTaskId + "/fill",
                "REGULATOR", "regulator-1", """
                {"checkResult": "NON_COMPLIANT",
                 "photoUrls": ["https://img.example.com/site-1.jpg", "https://img.example.com/site-2.jpg"],
                 "interviewResult": "现场约谈：老板承认废油交无资质收运人，责令改正",
                 "rectifyDeadline": "%s"}
                """.formatted(LocalDate.now(ZONE).plusDays(7)), 200);
        assertThat(filled.path("data").path("status").asText()).isEqualTo("RECTIFYING");
        assertThat(filled.path("data").path("photoUrls").asText()).contains("site-1.jpg");
        assertThat(filled.path("data").path("inspectedAt").asText()).isNotBlank();

        JsonNode keyPatrol = getJson("/api/regulatory/risk-profiles/key-patrol", "REGULATOR", 200);
        boolean containsStore4 = false;
        for (JsonNode p : keyPatrol.path("data")) {
            if (p.path("storeId").asLong() == 4L) {
                containsStore4 = true;
                assertThat(p.path("riskLevel").asText()).isEqualTo("KEY");
            }
        }
        assertThat(containsStore4).isTrue();
    }

    @Test
    @Order(43)
    void 再次巡查仍无记录_不重复生成任务且维持重点巡查() throws Exception {
        JsonNode scan = postJson("/api/regulatory/patrol/scan", "REGULATOR", "regulator-1", "{}", 200);
        assertThat(scan.path("data").path("tasksCreated").asInt()).isZero();
        JsonNode profile = getJson("/api/regulatory/risk-profiles/4", "REGULATOR", 200);
        assertThat(profile.path("data").path("riskLevel").asText()).isEqualTo("KEY");
        assertThat(profile.path("data").path("keyPatrol").asBoolean()).isTrue();
    }

    @Test
    @Order(44)
    void 恢复正常回收后两轮巡查_风险逐步下降并复核关闭任务() throws Exception {
        // 门店 4 完成一次完整合规回收闭环
        postJson("/api/weighings/devices/DEV-004/heartbeat", "RECYCLER", "recycler-1", "{}", 200);
        long appointmentId = createAppointment(4, 1, "100");
        long dispatchId = planDispatch(appointmentId, tomorrowAt(10, 0), tomorrowAt(10, 30));
        arriveAndVerify(dispatchId, "STORE-QR-4", "DEV-004");
        postJson("/api/weighings", "DRIVER", "driver-1", """
                {"dispatchId": %d, "barrelCode": "BAR-301", "weightKg": 90, "deviceCode": "DEV-004",
                 "source": "DEVICE", "handoverName": "赵老板"}
                """.formatted(dispatchId), 200);
        postJson("/api/dispatches/" + dispatchId + "/load-complete", "DRIVER", "driver-1", "{}", 200);
        uploadTracksAndArrivePlant(dispatchId);
        long manifestId = manifestIdOf(dispatchId);
        postJson("/api/receipts", "PLANT", "plant-1", """
                {"manifestId": %d, "arrivalWeightKg": 90, "barrelCount": 1,
                 "qualityCheck": "OK", "decision": "ACCEPT", "receivedBy": "厂验收员"}
                """.formatted(manifestId), 200);
        assertThat(getJson("/api/manifests/" + manifestId, "REGULATOR", 200)
                .path("data").path("manifest").path("status").asText()).isEqualTo("CLOSED");

        // 第一轮巡查：风险分 80 → 40，退出重点巡查，仍为 WATCH
        postJson("/api/regulatory/patrol/scan", "REGULATOR", "regulator-1", "{}", 200);
        JsonNode first = getJson("/api/regulatory/risk-profiles/4", "REGULATOR", 200);
        assertThat(first.path("data").path("riskScore").asInt()).isEqualTo(40);
        assertThat(first.path("data").path("riskLevel").asText()).isEqualTo("WATCH");
        assertThat(first.path("data").path("keyPatrol").asBoolean()).isFalse();

        // 第二轮巡查：风险清零恢复正常
        postJson("/api/regulatory/patrol/scan", "REGULATOR", "regulator-1", "{}", 200);
        JsonNode second = getJson("/api/regulatory/risk-profiles/4", "REGULATOR", 200);
        assertThat(second.path("data").path("riskScore").asInt()).isZero();
        assertThat(second.path("data").path("riskLevel").asText()).isEqualTo("NORMAL");

        // 整改复核通过，任务关闭
        JsonNode closed = postJson("/api/regulatory/tasks/" + noRecyclingTaskId + "/verify",
                "REGULATOR", "regulator-1",
                "{\"passed\": true, \"closeNote\": \"已签订合规收运合同并恢复交运，复核通过\"}", 200);
        assertThat(closed.path("data").path("status").asText()).isEqualTo("VERIFIED_CLOSED");
    }
}
