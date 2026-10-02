package com.wasteoil.risk;

import com.wasteoil.config.WasteOilProperties;
import com.wasteoil.inspection.InspectionTaskService;
import com.wasteoil.inspection.InspectionTrigger;
import com.wasteoil.kafka.EventPublisher;
import com.wasteoil.manifest.Manifest;
import com.wasteoil.manifest.ManifestRepository;
import com.wasteoil.manifest.ManifestStatus;
import com.wasteoil.merchant.Store;
import com.wasteoil.merchant.StoreRepository;
import com.wasteoil.weighing.WeighingRecord;
import com.wasteoil.weighing.WeighingRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 风险分析单元测试：产量/营业规模/回收频次长期不匹配进入风险名单；
 * 长期无回收的"关注 → 重点巡查 → 恢复降级"生命周期与现场检查任务生成。
 */
@ExtendWith(MockitoExtension.class)
class RiskAnalyzerTest {

    @Mock
    StoreRepository storeRepository;
    @Mock
    ManifestRepository manifestRepository;
    @Mock
    WeighingRecordRepository weighingRecordRepository;
    @Mock
    RiskEntryRepository riskEntryRepository;
    @Mock
    InspectionTaskService inspectionTaskService;
    @Mock
    EventPublisher eventPublisher;

    RiskAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        WasteOilProperties props = new WasteOilProperties();
        analyzer = new RiskAnalyzer(storeRepository, manifestRepository,
                weighingRecordRepository, riskEntryRepository,
                inspectionTaskService, eventPublisher, props);
        lenient().when(riskEntryRepository.findByStoreIdAndIndicatorAndWindowEndAndStatus(
                any(), any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(riskEntryRepository.findFirstByStoreIdAndIndicatorAndStatusInOrderByIdDesc(
                any(), any(), anyList())).thenReturn(Optional.empty());
        lenient().when(riskEntryRepository.save(any())).thenAnswer(i -> {
            RiskEntry e = i.getArgument(0);
            if (e.getId() == null) {
                e.setId(123L);
            }
            return e;
        });
    }

    private Store store(long id, String expectedDailyOilKg) {
        Store s = new Store();
        s.setId(id);
        s.setStatus("ACTIVE");
        s.setExpectedDailyOilKg(new BigDecimal(expectedDailyOilKg));
        return s;
    }

    private Manifest closedManifest(long id, String arrivalWeightKg, Instant createdAt) {
        Manifest m = new Manifest();
        m.setId(id);
        m.setStatus(ManifestStatus.CLOSED);
        m.setArrivalWeightKg(new BigDecimal(arrivalWeightKg));
        m.setCreatedAt(createdAt);
        return m;
    }

    @Test
    void 长期无回收记录_进入风险名单() {
        when(manifestRepository.findByStoreIdAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(List.of());

        List<RiskEntry> entries = analyzer.analyzeStore(store(1L, "60"));

        assertThat(entries).extracting(RiskEntry::getIndicator)
                .contains("YIELD_MISMATCH", "NO_RECYCLING");
    }

    @Test
    void 产量远低于营业规模_进入风险名单() {
        // 90 天预估 60*90=5400kg，实际仅 500kg → 比值 0.09 < 0.3
        when(manifestRepository.findByStoreIdAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(List.of(closedManifest(1L, "500", Instant.now())));
        when(weighingRecordRepository.findByManifestId(1L)).thenReturn(List.of());

        List<RiskEntry> entries = analyzer.analyzeStore(store(1L, "60"));

        assertThat(entries).extracting(RiskEntry::getIndicator).contains("YIELD_MISMATCH");
        assertThat(entries).extracting(RiskEntry::getIndicator).doesNotContain("NO_RECYCLING");
    }

    @Test
    void 产量与规模匹配_不进风险名单() {
        // 90 天预估 900kg，实际 800kg → 比值 0.89
        when(manifestRepository.findByStoreIdAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(List.of(closedManifest(1L, "800", Instant.now())));
        WeighingRecord device = new WeighingRecord();
        device.setSource("DEVICE");
        when(weighingRecordRepository.findByManifestId(1L)).thenReturn(List.of(device));

        List<RiskEntry> entries = analyzer.analyzeStore(store(1L, "10"));

        assertThat(entries).isEmpty();
    }

    @Test
    void 手动补录占比过高_进入风险名单() {
        when(manifestRepository.findByStoreIdAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(List.of(closedManifest(1L, "800", Instant.now())));
        WeighingRecord manual = new WeighingRecord();
        manual.setSource("MANUAL");
        when(weighingRecordRepository.findByManifestId(1L))
                .thenReturn(List.of(manual, manual, manual, manual, manual));

        List<RiskEntry> entries = analyzer.analyzeStore(store(1L, "10"));

        assertThat(entries).extracting(RiskEntry::getIndicator).contains("MANUAL_WEIGH_HEAVY");
    }

    // ---------- 长期无回收生命周期 ----------

    @Test
    void 首次无回收_标记关注并生成现场检查任务() {
        when(manifestRepository.findByStoreIdAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(List.of());

        List<RiskEntry> entries = analyzer.analyzeStore(store(3L, "15"));

        RiskEntry noRecycling = entries.stream()
                .filter(e -> "NO_RECYCLING".equals(e.getIndicator())).findFirst().orElseThrow();
        assertThat(noRecycling.getRiskLevel()).isEqualTo(RiskLevel.WATCH);
        assertThat(noRecycling.getConsecutiveNoRecyclingCycles()).isEqualTo(1);
        // 自动生成一张"长期无回收"现场检查任务
        verify(inspectionTaskService).autoCreate(eq(3L), eq(noRecycling.getId()),
                eq(InspectionTrigger.NO_RECYCLING), any(LocalDate.class), anyString());
    }

    @Test
    void 连续两个周期无回收_升级重点巡查() {
        // 已有一张 WATCH 风险，连续周期=1，窗口截止日早于今天（代表上一扫描周期）
        RiskEntry existing = new RiskEntry();
        existing.setId(99L);
        existing.setStoreId(3L);
        existing.setIndicator("NO_RECYCLING");
        existing.setStatus("OPEN");
        existing.setRiskLevel(RiskLevel.WATCH);
        existing.setConsecutiveNoRecyclingCycles(1);
        existing.setWindowStart(LocalDate.now().minusDays(180));
        existing.setWindowEnd(LocalDate.now().minusDays(90));

        when(manifestRepository.findByStoreIdAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(List.of());
        when(riskEntryRepository.findFirstByStoreIdAndIndicatorAndStatusInOrderByIdDesc(
                eq(3L), eq("NO_RECYCLING"), anyList())).thenReturn(Optional.of(existing));

        analyzer.analyzeStore(store(3L, "15"));

        assertThat(existing.getRiskLevel()).isEqualTo(RiskLevel.KEY_PATROL);
        assertThat(existing.getConsecutiveNoRecyclingCycles()).isEqualTo(2);
        assertThat(existing.getScore()).isEqualTo(90);
        // 升级时再生成一张重点巡查任务
        verify(inspectionTaskService).autoCreate(eq(3L), eq(99L),
                eq(InspectionTrigger.KEY_PATROL), any(LocalDate.class), anyString());
    }

    @Test
    void 恢复第一批回收_重点巡查降为关注() {
        Instant t1 = Instant.now().minusSeconds(3600);
        RiskEntry entry = new RiskEntry();
        entry.setId(99L);
        entry.setStoreId(3L);
        entry.setIndicator("NO_RECYCLING");
        entry.setStatus("OPEN");
        entry.setRiskLevel(RiskLevel.KEY_PATROL);
        entry.setConsecutiveNoRecyclingCycles(3);

        boolean downgraded = analyzer.applyRecoveryStep(entry, 500L, t1);

        assertThat(downgraded).isTrue();
        assertThat(entry.getRiskLevel()).isEqualTo(RiskLevel.WATCH);
        assertThat(entry.getStatus()).isEqualTo("OPEN");
        assertThat(entry.getRecoveredAt()).isEqualTo(t1);
        assertThat(entry.getLastPickupManifestId()).isEqualTo(500L);
        // 仅降级，不解除、不取消任务
        verify(inspectionTaskService, never()).cancelOpenTasksByStore(anyLong(), anyString());
    }

    @Test
    void 关注状态再来一批回收_解除风险并取消任务() {
        Instant t1 = Instant.now().minusSeconds(7200);
        RiskEntry entry = new RiskEntry();
        entry.setId(99L);
        entry.setStoreId(3L);
        entry.setIndicator("NO_RECYCLING");
        entry.setStatus("OPEN");
        entry.setRiskLevel(RiskLevel.WATCH);

        boolean recovered = analyzer.applyRecoveryStep(entry, 501L, t1);

        assertThat(recovered).isTrue();
        assertThat(entry.getStatus()).isEqualTo("RECOVERED");
        assertThat(entry.getScore()).isZero();
        verify(inspectionTaskService).cancelOpenTasksByStore(eq(3L), anyString());
    }

    @Test
    void 同一回收批次不重复降级_逐步下降() {
        RiskEntry entry = new RiskEntry();
        entry.setStoreId(3L);
        entry.setIndicator("NO_RECYCLING");
        entry.setStatus("OPEN");
        entry.setRiskLevel(RiskLevel.KEY_PATROL);
        entry.setLastPickupManifestId(500L);
        entry.setConsecutiveNoRecyclingCycles(2);

        // 同一批次（联单 ID 未更新）再次进入 → 不降级
        boolean changed = analyzer.applyRecoveryStep(entry, 500L, Instant.now());

        assertThat(changed).isFalse();
        assertThat(entry.getRiskLevel()).isEqualTo(RiskLevel.KEY_PATROL);
        verify(inspectionTaskService, never()).cancelOpenTasksByStore(anyLong(), anyString());
    }

    @Test
    void 闭环通知新回收_触发即时降级() {
        RiskEntry entry = new RiskEntry();
        entry.setId(99L);
        entry.setStoreId(3L);
        entry.setIndicator("NO_RECYCLING");
        entry.setStatus("OPEN");
        entry.setRiskLevel(RiskLevel.KEY_PATROL);
        when(riskEntryRepository.findFirstByStoreIdAndIndicatorAndStatusInOrderByIdDesc(
                eq(3L), eq("NO_RECYCLING"), anyList())).thenReturn(Optional.of(entry));

        Manifest manifest = closedManifest(502L, "80", Instant.now());
        manifest.setStoreId(3L);
        boolean changed = analyzer.notifyPickup(manifest);

        assertThat(changed).isTrue();
        assertThat(entry.getRiskLevel()).isEqualTo(RiskLevel.WATCH);
        assertThat(entry.getLastPickupManifestId()).isEqualTo(502L);
    }
}
