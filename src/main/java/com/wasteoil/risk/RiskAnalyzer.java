package com.wasteoil.risk;

import com.wasteoil.config.WasteOilProperties;
import com.wasteoil.inspection.InspectionTaskService;
import com.wasteoil.inspection.InspectionTrigger;
import com.wasteoil.kafka.EventPublisher;
import com.wasteoil.kafka.KafkaTopics;
import com.wasteoil.manifest.Manifest;
import com.wasteoil.manifest.ManifestRepository;
import com.wasteoil.manifest.ManifestStatus;
import com.wasteoil.merchant.Store;
import com.wasteoil.merchant.StoreRepository;
import com.wasteoil.weighing.WeighingRecord;
import com.wasteoil.weighing.WeighingRecordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 风险分析：追踪长期趋势。同一门店的废油产量、营业规模与回收频次
 * 长期不匹配时进入风险名单，供监管部门重点关注。
 *
 * <p><b>长期无回收的风险生命周期（疑似违规 → 重点巡查 → 恢复降级）：</b>
 * <ol>
 *   <li>扫描窗口内无任何合规回收：标记疑似违规（{@link RiskLevel#WATCH}），
 *       并自动生成一张现场检查任务；</li>
 *   <li>连续多个扫描周期仍无回收记录：升级为{@link RiskLevel#KEY_PATROL} 重点巡查名单，
 *       再生成一张重点巡查任务；</li>
 *   <li>商户恢复正常回收（出现新的合规回收批次）：风险随每一批次<b>逐步下降</b>，
 *       KEY_PATROL → WATCH → RECOVERED，风险解除时自动取消未结检查任务。</li>
 * </ol>
 */
@Slf4j
@Service
public class RiskAnalyzer {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final String IND_NO_RECYCLING = "NO_RECYCLING";

    private static final Set<ManifestStatus> COUNTED = EnumSet.of(
            ManifestStatus.VERIFIED, ManifestStatus.CLOSED, ManifestStatus.DISPUTED);
    private static final List<String> ACTIVE_STATUS = List.of("OPEN", "CONFIRMED");

    private final StoreRepository storeRepository;
    private final ManifestRepository manifestRepository;
    private final WeighingRecordRepository weighingRecordRepository;
    private final RiskEntryRepository riskEntryRepository;
    private final InspectionTaskService inspectionTaskService;
    private final EventPublisher eventPublisher;
    private final WasteOilProperties properties;

    public RiskAnalyzer(StoreRepository storeRepository,
                        ManifestRepository manifestRepository,
                        WeighingRecordRepository weighingRecordRepository,
                        RiskEntryRepository riskEntryRepository,
                        InspectionTaskService inspectionTaskService,
                        EventPublisher eventPublisher,
                        WasteOilProperties properties) {
        this.storeRepository = storeRepository;
        this.manifestRepository = manifestRepository;
        this.weighingRecordRepository = weighingRecordRepository;
        this.riskEntryRepository = riskEntryRepository;
        this.inspectionTaskService = inspectionTaskService;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
    }

    /**
     * 全量扫描所有在用门店，返回本次新产生的风险条目数。
     */
    @Transactional
    public int scanAll() {
        int created = 0;
        for (Store store : storeRepository.findAll()) {
            if (!"ACTIVE".equals(store.getStatus())) {
                continue;
            }
            created += analyzeStore(store).size();
        }
        return created;
    }

    /**
     * 分析单个门店（纯逻辑，便于测试）。
     *
     * @return 本次新登记的风险条目
     */
    @Transactional
    public List<RiskEntry> analyzeStore(Store store) {
        int windowDays = properties.getRiskWindowDays();
        LocalDate windowEnd = LocalDate.now();
        LocalDate windowStart = windowEnd.minusDays(windowDays);
        Instant from = windowStart.atStartOfDay(ZONE).toInstant();
        Instant to = Instant.now();

        List<Manifest> manifests = manifestRepository
                .findByStoreIdAndCreatedAtBetween(store.getId(), from, to).stream()
                .filter(m -> COUNTED.contains(m.getStatus()))
                .toList();

        BigDecimal totalReceived = manifests.stream()
                .map(m -> m.getArrivalWeightKg() == null ? BigDecimal.ZERO : m.getArrivalWeightKg())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long pickupCount = manifests.size();
        Manifest latest = manifests.stream()
                .max(java.util.Comparator.comparing(Manifest::getId))
                .orElse(null);
        Instant latestPickupAt = latest == null ? null : latest.getCreatedAt();
        Long latestManifestId = latest == null ? null : latest.getId();

        List<RiskEntry> created = new ArrayList<>();

        // 1. 产量与营业规模不匹配：实际回收量远低于按规模预估的产量
        BigDecimal expected = store.getExpectedDailyOilKg()
                .multiply(BigDecimal.valueOf(windowDays));
        if (expected.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal ratio = totalReceived.divide(expected, 4, RoundingMode.HALF_UP);
            if (ratio.compareTo(properties.getRiskYieldRatio()) < 0) {
                created.add(registerYieldMismatch(store, windowDays, totalReceived, expected,
                        ratio, windowStart, windowEnd));
            }
        }

        // 2. 回收频次异常（核心）：长期无回收 → 疑似违规 → 现场检查 → 重点巡查 / 恢复降级
        created.addAll(evaluateNoRecycling(store, windowStart, windowEnd,
                pickupCount, latestManifestId, latestPickupAt));

        // 3. 手动补录占比过高：称重数据可信度低
        long manualCount = manifests.stream()
                .flatMap(m -> weighingRecordRepository.findByManifestId(m.getId()).stream())
                .filter(w -> "MANUAL".equals(w.getSource()))
                .count();
        long totalWeigh = manifests.stream()
                .mapToLong(m -> weighingRecordRepository.findByManifestId(m.getId()).size())
                .sum();
        if (totalWeigh >= 5 && manualCount * 2 > totalWeigh) {
            created.add(registerSimple(store, "MANUAL_WEIGH_HEAVY",
                    String.format("窗口期内 %d 条称重中 %d 条为手动补录，数据可信度低",
                            totalWeigh, manualCount),
                    40, RiskLevel.WATCH, windowStart, windowEnd));
        }

        return created;
    }

    /**
     * 长期无回收风险状态机。返回新登记的风险条目（升级不产生新条目时返回空集合）。
     */
    private List<RiskEntry> evaluateNoRecycling(Store store, LocalDate windowStart, LocalDate windowEnd,
                                                long pickupCount, Long latestManifestId,
                                                Instant latestPickupAt) {
        List<RiskEntry> created = new ArrayList<>();
        Optional<RiskEntry> activeOpt = riskEntryRepository
                .findFirstByStoreIdAndIndicatorAndStatusInOrderByIdDesc(
                        store.getId(), IND_NO_RECYCLING, ACTIVE_STATUS);

        if (pickupCount == 0) {
            if (activeOpt.isEmpty()) {
                // 首次发现：疑似违规，关注名单 + 现场检查任务
                RiskEntry entry = new RiskEntry();
                entry.setStoreId(store.getId());
                entry.setIndicator(IND_NO_RECYCLING);
                entry.setDetail(String.format("近 %d 天无任何合规回收联单，废油去向不明，疑似违规",
                        properties.getRiskWindowDays()));
                entry.setScore(60);
                entry.setRiskLevel(RiskLevel.WATCH);
                entry.setConsecutiveNoRecyclingCycles(1);
                entry.setWindowStart(windowStart);
                entry.setWindowEnd(windowEnd);
                entry.setLastEvaluatedAt(Instant.now());
                RiskEntry saved = riskEntryRepository.save(entry);
                log.info("风险名单新增(长期无回收): store={} level=WATCH", store.getId());
                inspectionTaskService.autoCreate(store.getId(), saved.getId(),
                        InspectionTrigger.NO_RECYCLING, defaultDueDate(windowEnd), saved.getDetail());
                publishRisk(saved, "RiskSuspected");
                created.add(saved);
            } else {
                // 已在风险名单：累计无回收周期，必要时升级重点巡查
                RiskEntry entry = activeOpt.get();
                if (entry.getRecoveredAt() != null) {
                    // 曾短暂恢复，本窗口又归零，视为反弹，重新进入无回收观察
                    entry.setRecoveredAt(null);
                }
                boolean newCycle = !windowEnd.equals(entry.getWindowEnd());
                if (newCycle) {
                    int cycles = entry.getConsecutiveNoRecyclingCycles() == null ? 1
                            : entry.getConsecutiveNoRecyclingCycles() + 1;
                    entry.setConsecutiveNoRecyclingCycles(cycles);
                    entry.setWindowStart(windowStart);
                    entry.setWindowEnd(windowEnd);
                    if (entry.getRiskLevel() == RiskLevel.WATCH
                            && cycles >= properties.getNoRecyclingEscalateCycles()) {
                        entry.setRiskLevel(RiskLevel.KEY_PATROL);
                        entry.setScore(90);
                        entry.setDetail(String.format(
                                "连续 %d 个扫描周期无合规回收记录，列入重点巡查名单，废油去向不明",
                                cycles));
                        log.info("风险升级为重点巡查: store={} cycles={}", store.getId(), cycles);
                        inspectionTaskService.autoCreate(store.getId(), entry.getId(),
                                InspectionTrigger.KEY_PATROL, defaultDueDate(windowEnd), entry.getDetail());
                        publishRisk(entry, "RiskEscalated");
                    }
                }
                entry.setLastEvaluatedAt(Instant.now());
                riskEntryRepository.save(entry);
            }
        } else {
            // 窗口内有合规回收：若该门店曾在风险名单，按"新回收批次"逐步降级
            activeOpt.ifPresent(entry -> applyRecoveryStep(entry, latestManifestId, latestPickupAt));
        }
        return created;
    }

    /**
     * 恢复正常回收的一次降级。每个新于最近记录的合规回收批次使风险下降一级：
     * KEY_PATROL → WATCH → RECOVERED（解除并取消未结现场检查任务）。
     *
     * @return true 表示本次发生了一次降级
     */
    boolean applyRecoveryStep(RiskEntry entry, Long pickupManifestId, Instant pickupAt) {
        if (pickupManifestId != null
                && entry.getLastPickupManifestId() != null
                && pickupManifestId <= entry.getLastPickupManifestId()) {
            // 该回收批次已计入过降级，不重复处理（逐步而非一次性清除）
            return false;
        }
        if (pickupManifestId != null) {
            entry.setLastPickupManifestId(pickupManifestId);
        }
        if (pickupAt != null) {
            entry.setLastPickupAt(pickupAt);
        }
        if (entry.getRecoveredAt() == null) {
            entry.setRecoveredAt(pickupAt == null ? Instant.now() : pickupAt);
        }
        entry.setLastEvaluatedAt(Instant.now());

        if (entry.getRiskLevel() == RiskLevel.KEY_PATROL) {
            entry.setRiskLevel(RiskLevel.WATCH);
            entry.setScore(60);
            entry.setDetail("商户已恢复正常回收，风险由重点巡查下调为关注，继续观察回收连续性");
            riskEntryRepository.save(entry);
            publishRisk(entry, "RiskDowngraded");
            log.info("风险降级: store={} KEY_PATROL→WATCH", entry.getStoreId());
            return true;
        }
        // WATCH：再出现一批合规回收即解除
        entry.setStatus("RECOVERED");
        entry.setRiskLevel(RiskLevel.WATCH);
        entry.setScore(0);
        entry.setDetail("商户已连续恢复正常回收，风险解除");
        riskEntryRepository.save(entry);
        publishRisk(entry, "RiskRecovered");
        log.info("风险解除: store={} NO_RECYCLING RECOVERED", entry.getStoreId());
        inspectionTaskService.cancelOpenTasksByStore(entry.getStoreId(), "风险解除（恢复正常回收）");
        return true;
    }

    /**
     * 合规闭环产生新回收批次时调用，即时反映"恢复正常回收 → 风险逐步下降"。
     * 仅对存在进行中的长期无回收风险条目生效。
     */
    @Transactional
    public boolean notifyPickup(Manifest manifest) {
        Optional<RiskEntry> active = riskEntryRepository
                .findFirstByStoreIdAndIndicatorAndStatusInOrderByIdDesc(
                        manifest.getStoreId(), IND_NO_RECYCLING, ACTIVE_STATUS);
        return active.filter(entry -> applyRecoveryStep(entry, manifest.getId(), manifest.getCreatedAt()))
                .isPresent();
    }

    private RiskEntry registerYieldMismatch(Store store, int windowDays, BigDecimal totalReceived,
                                            BigDecimal expected, BigDecimal ratio,
                                            LocalDate windowStart, LocalDate windowEnd) {
        return registerSimple(store, "YIELD_MISMATCH",
                String.format("近 %d 天回收 %.1f kg，按营业规模预估 %.1f kg，比值 %.2f 低于阈值 %.2f，疑似废油流向不明",
                        windowDays, totalReceived, expected, ratio, properties.getRiskYieldRatio()),
                70, RiskLevel.WATCH, windowStart, windowEnd);
    }

    private RiskEntry registerSimple(Store store, String indicator, String detail, int score,
                                     RiskLevel level, LocalDate windowStart, LocalDate windowEnd) {
        // 同一门店同一指标同一窗口的未处理风险不重复登记
        var existing = riskEntryRepository.findByStoreIdAndIndicatorAndWindowEndAndStatus(
                store.getId(), indicator, windowEnd, "OPEN");
        if (existing.isPresent()) {
            return existing.get();
        }
        RiskEntry entry = new RiskEntry();
        entry.setStoreId(store.getId());
        entry.setIndicator(indicator);
        entry.setDetail(detail);
        entry.setScore(score);
        entry.setRiskLevel(level);
        entry.setWindowStart(windowStart);
        entry.setWindowEnd(windowEnd);
        RiskEntry saved = riskEntryRepository.save(entry);
        log.info("风险名单新增: store={} indicator={} level={} score={}",
                store.getId(), indicator, level, score);
        return saved;
    }

    private LocalDate defaultDueDate(LocalDate from) {
        return from.plusDays(properties.getInspectionDueDays());
    }

    private void publishRisk(RiskEntry entry, String type) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("riskId", entry.getId());
        payload.put("storeId", entry.getStoreId());
        payload.put("indicator", entry.getIndicator());
        payload.put("riskLevel", entry.getRiskLevel().name());
        payload.put("status", entry.getStatus());
        payload.put("score", entry.getScore());
        payload.put("consecutiveNoRecyclingCycles", entry.getConsecutiveNoRecyclingCycles());
        eventPublisher.publish(KafkaTopics.SUPERVISION_EVENTS,
                "risk-" + entry.getId(), type, payload);
    }
}
