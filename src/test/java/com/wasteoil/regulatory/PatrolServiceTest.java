package com.wasteoil.regulatory;

import com.wasteoil.config.WasteOilProperties;
import com.wasteoil.kafka.EventPublisher;
import com.wasteoil.merchant.Merchant;
import com.wasteoil.merchant.MerchantRepository;
import com.wasteoil.merchant.Store;
import com.wasteoil.merchant.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 监管巡查单元测试：长期无回收疑似违规生成现场检查任务、持续无回收进入重点巡查、
 * 恢复正常回收后风险分逐步下降，以及现场回填与整改复核。
 */
@ExtendWith(MockitoExtension.class)
class PatrolServiceTest {

    @Mock StoreRepository storeRepository;
    @Mock MerchantRepository merchantRepository;
    @Mock SiteInspectionTaskRepository taskRepository;
    @Mock ChainTraceService chainTraceService;
    @Mock EventPublisher eventPublisher;
    @Mock JdbcTemplate jdbcTemplate;

    final Map<Long, StoreRiskProfile> profiles = new HashMap<>();
    final AtomicLong taskSeq = new AtomicLong(100);

    PatrolService service;

    @BeforeEach
    void setUp() {
        WasteOilProperties props = new WasteOilProperties(); // 30 / 60 / 2 天默认阈值
        StoreRiskProfileRepository profileRepository = mock(StoreRiskProfileRepository.class);
        lenient().when(profileRepository.findById(anyLong())).thenAnswer(
                inv -> Optional.ofNullable(profiles.get(inv.getArgument(0))));
        lenient().when(profileRepository.save(any())).thenAnswer(inv -> {
            StoreRiskProfile p = inv.getArgument(0);
            profiles.put(p.getStoreId(), p);
            return p;
        });

        service = new PatrolService(storeRepository, merchantRepository, profileRepository,
                taskRepository, chainTraceService, eventPublisher, jdbcTemplate, props);

        lenient().when(jdbcTemplate.queryForObject(any(String.class), eq(Long.class)))
                .thenAnswer(inv -> taskSeq.incrementAndGet());
        lenient().when(taskRepository.save(any())).thenAnswer(inv -> {
            SiteInspectionTask t = inv.getArgument(0);
            if (t.getId() == null) {
                t.setId(taskSeq.incrementAndGet());
            }
            return t;
        });
        lenient().when(merchantRepository.findById(anyLong())).thenAnswer(inv -> {
            Merchant m = new Merchant();
            m.setId(inv.getArgument(0));
            m.setName("测试商户");
            return Optional.of(m);
        });
        // 默认不存在未结现场检查任务，由各用例覆盖
        lenient().when(taskRepository.findByStoreIdAndStatusIn(anyLong(), anyList()))
                .thenReturn(List.of());
    }

    private Store store40DaysNoRecycling() {
        Store s = new Store();
        s.setId(4L);
        s.setMerchantId(2L);
        s.setStatus("ACTIVE");
        s.setCreatedAt(Instant.now().minus(40, ChronoUnit.DAYS));
        return s;
    }

    @Test
    void 首轮巡查_无回收四十天_疑似违规并生成现场检查任务() {
        when(chainTraceService.latestRecyclingAt(4L)).thenReturn(null);

        int created = service.scanStore(store40DaysNoRecycling(), "TEST");

        assertThat(created).isEqualTo(1);
        StoreRiskProfile p = profiles.get(4L);
        assertThat(p.getRiskLevel()).isEqualTo(StoreRiskProfile.WATCH);
        assertThat(p.getRiskScore()).isEqualTo(50);
        assertThat(p.getKeyPatrol()).isFalse();
        assertThat(p.getConsecutiveNoRecyclingScans()).isEqualTo(1);
        verify(taskRepository).save(argThat(t ->
                "RISK_SCAN".equals(t.getSource())
                        && SiteInspectionStatus.PENDING.name().equals(t.getStatus())
                        && t.getDaysNoRecycling() >= 30));
    }

    @Test
    void 连续两轮巡查无回收_进入重点巡查名单且不重复生成任务() {
        when(chainTraceService.latestRecyclingAt(4L)).thenReturn(null);
        SiteInspectionTask existing = new SiteInspectionTask();
        existing.setId(9L);
        existing.setStoreId(4L);
        existing.setStatus(SiteInspectionStatus.PENDING.name());
        when(taskRepository.findByStoreIdAndStatusIn(eq(4L), anyList()))
                .thenReturn(List.of(existing));

        service.scanStore(store40DaysNoRecycling(), "TEST");
        int createdSecond = service.scanStore(store40DaysNoRecycling(), "TEST");

        assertThat(createdSecond).isZero(); // 已有未结任务，不重复生成
        StoreRiskProfile p = profiles.get(4L);
        assertThat(p.getRiskLevel()).isEqualTo(StoreRiskProfile.KEY);
        assertThat(p.getKeyPatrol()).isTrue();
        assertThat(p.getRiskScore()).isEqualTo(80);
    }

    @Test
    void 恢复正常回收_风险分两轮巡查逐步下降至正常() {
        when(chainTraceService.latestRecyclingAt(4L)).thenReturn(null);
        service.scanStore(store40DaysNoRecycling(), "TEST");
        service.scanStore(store40DaysNoRecycling(), "TEST");
        assertThat(profiles.get(4L).getRiskLevel()).isEqualTo(StoreRiskProfile.KEY);

        // 商户恢复正常回收（最近回收为当下），每轮巡查风险分下降一档
        when(chainTraceService.latestRecyclingAt(4L)).thenReturn(Instant.now());
        service.scanStore(store40DaysNoRecycling(), "TEST");
        StoreRiskProfile afterFirst = profiles.get(4L);
        assertThat(afterFirst.getRiskScore()).isEqualTo(40);
        assertThat(afterFirst.getRiskLevel()).isEqualTo(StoreRiskProfile.WATCH);
        assertThat(afterFirst.getKeyPatrol()).isFalse();

        service.scanStore(store40DaysNoRecycling(), "TEST");
        StoreRiskProfile afterSecond = profiles.get(4L);
        assertThat(afterSecond.getRiskScore()).isZero();
        assertThat(afterSecond.getRiskLevel()).isEqualTo(StoreRiskProfile.NORMAL);
        assertThat(afterSecond.getKeyPatrol()).isFalse();
        assertThat(afterSecond.getNormalRecyclingStreak()).isZero();
    }

    @Test
    void 回填现场检查_认定违规_责令整改并升级重点巡查() {
        when(chainTraceService.latestRecyclingAt(4L)).thenReturn(null);
        service.scanStore(store40DaysNoRecycling(), "TEST");
        long taskId = taskSeq.get();
        SiteInspectionTask task = new SiteInspectionTask();
        task.setId(taskId);
        task.setStoreId(4L);
        task.setStatus(SiteInspectionStatus.PENDING.name());
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        // 缺少检查照片 → 拒绝
        assertThatThrownBy(() -> service.completeInspection(taskId, "NON_COMPLIANT",
                List.of(), "已约谈老板", LocalDate.now().plusDays(7)))
                .hasMessageContaining("照片");
        // 认定违规缺少整改期限 → 拒绝
        assertThatThrownBy(() -> service.completeInspection(taskId, "NON_COMPLIANT",
                List.of("https://img.example.com/site.jpg"), "已约谈老板", null))
                .hasMessageContaining("整改期限");

        SiteInspectionTask saved = service.completeInspection(taskId, "NON_COMPLIANT",
                List.of("https://img.example.com/site1.jpg", "https://img.example.com/site2.jpg"),
                "现场约谈：老板承认废油卖给无资质收运人，责令限期整改",
                LocalDate.now().plusDays(7));

        assertThat(saved.getStatus()).isEqualTo(SiteInspectionStatus.RECTIFYING.name());
        assertThat(saved.getPhotoUrls()).contains("site1.jpg").contains("site2.jpg");
        assertThat(saved.getRectifyDeadline()).isEqualTo(LocalDate.now().plusDays(7));
        assertThat(profiles.get(4L).getKeyPatrol()).isTrue();
        assertThat(profiles.get(4L).getRiskLevel()).isEqualTo(StoreRiskProfile.KEY);
    }

    @Test
    void 整改复核通过_任务关闭() {
        SiteInspectionTask task = new SiteInspectionTask();
        task.setId(5L);
        task.setStoreId(4L);
        task.setStatus(SiteInspectionStatus.RECTIFYING.name());
        when(taskRepository.findById(5L)).thenReturn(Optional.of(task));

        SiteInspectionTask closed = service.verifyRectification(5L, true, "已更换合规收运合同，复核通过");

        assertThat(closed.getStatus()).isEqualTo(SiteInspectionStatus.VERIFIED_CLOSED.name());
        assertThat(closed.getCloseNote()).contains("复核通过");
        assertThat(closed.getClosedAt()).isNotNull();
    }

    @Test
    void 现场核实合规_任务直接完成() {
        SiteInspectionTask task = new SiteInspectionTask();
        task.setId(6L);
        task.setStoreId(4L);
        task.setStatus(SiteInspectionStatus.PENDING.name());
        when(taskRepository.findById(6L)).thenReturn(Optional.of(task));

        SiteInspectionTask saved = service.completeInspection(6L, "COMPLIANT",
                List.of("https://img.example.com/ok.jpg"), null, null);

        assertThat(saved.getStatus()).isEqualTo(SiteInspectionStatus.COMPLETED.name());
        assertThat(saved.getCheckResult()).isEqualTo("COMPLIANT");
        assertThat(saved.getClosedAt()).isNotNull();
    }
}
