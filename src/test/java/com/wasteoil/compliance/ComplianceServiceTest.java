package com.wasteoil.compliance;

import com.wasteoil.config.WasteOilProperties;
import com.wasteoil.dispatch.DispatchOrder;
import com.wasteoil.dispatch.DispatchOrderRepository;
import com.wasteoil.exception.ExceptionService;
import com.wasteoil.exception.ExceptionType;
import com.wasteoil.kafka.EventPublisher;
import com.wasteoil.manifest.Manifest;
import com.wasteoil.manifest.ManifestRepository;
import com.wasteoil.manifest.ManifestService;
import com.wasteoil.manifest.ManifestStatus;
import com.wasteoil.plant.PlantReceipt;
import com.wasteoil.risk.RiskAnalyzer;
import com.wasteoil.settlement.SettlementService;
import com.wasteoil.track.TrackPointRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 合规闭环比对单元测试：出店重量 / 运输损耗 / 到厂重量 / 桶数 / 轨迹。
 */
@ExtendWith(MockitoExtension.class)
class ComplianceServiceTest {

    @Mock
    ManifestRepository manifestRepository;
    @Mock
    ManifestService manifestService;
    @Mock
    DispatchOrderRepository dispatchRepository;
    @Mock
    TrackPointRepository trackPointRepository;
    @Mock
    ExceptionService exceptionService;
    @Mock
    SettlementService settlementService;
    @Mock
    RiskAnalyzer riskAnalyzer;
    @Mock
    EventPublisher eventPublisher;

    ComplianceService service;

    @BeforeEach
    void setUp() {
        WasteOilProperties props = new WasteOilProperties();
        service = new ComplianceService(manifestRepository, manifestService, dispatchRepository,
                trackPointRepository, exceptionService, settlementService, riskAnalyzer,
                eventPublisher, props);
        lenient().when(manifestRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        DispatchOrder dispatch = new DispatchOrder();
        dispatch.setId(1L);
        lenient().when(dispatchRepository.findById(1L)).thenReturn(Optional.of(dispatch));
    }

    private Manifest manifest(BigDecimal exitWeight, int exitBarrels) {
        Manifest m = new Manifest();
        m.setId(1L);
        m.setManifestNo("M20260926-0001");
        m.setDispatchId(1L);
        m.setStoreId(1L);
        m.setPlantId(1L);
        m.setStatus(ManifestStatus.IN_TRANSIT);
        m.setExitWeightKg(exitWeight);
        m.setBarrelCountExit(exitBarrels);
        return m;
    }

    private PlantReceipt receipt(String weight, int barrels, String quality) {
        PlantReceipt r = new PlantReceipt();
        r.setArrivalWeightKg(new BigDecimal(weight));
        r.setBarrelCount(barrels);
        r.setQualityCheck(quality);
        r.setDecision("ACCEPT");
        return r;
    }

    @Test
    void 正常闭环_损耗在阈值内() {
        when(trackPointRepository.countByDispatchId(1L)).thenReturn(5L);
        Manifest m = service.evaluate(manifest(new BigDecimal("100"), 2),
                receipt("98", 2, "OK"));

        assertThat(m.getComplianceResult()).isEqualTo("PASS");
        assertThat(m.getStatus()).isEqualTo(ManifestStatus.VERIFIED);
        assertThat(m.getLossKg()).isEqualByComparingTo("2");
        assertThat(m.getLossRate()).isEqualByComparingTo("0.0200");
        verify(settlementService).createFor(m);
        verify(manifestService).transition(m, ManifestStatus.CLOSED, "ManifestClosed");
        verify(exceptionService, never()).raise(any(), any(), any(), any());
    }

    @Test
    void 运输损耗超标_进入异常() {
        when(trackPointRepository.countByDispatchId(1L)).thenReturn(5L);
        Manifest m = service.evaluate(manifest(new BigDecimal("100"), 2),
                receipt("80", 2, "OK"));

        assertThat(m.getComplianceResult()).isEqualTo("EXCEPTION");
        assertThat(m.getStatus()).isEqualTo(ManifestStatus.EXCEPTION);
        verify(exceptionService).raise(eq(ExceptionType.TRANSPORT_LOSS_ABNORMAL),
                eq("MANIFEST"), eq(1L), contains("损耗"));
        verify(settlementService, never()).createFor(any());
    }

    @Test
    void 油桶数量不符_进入异常() {
        when(trackPointRepository.countByDispatchId(1L)).thenReturn(5L);
        Manifest m = service.evaluate(manifest(new BigDecimal("100"), 3),
                receipt("99", 2, "OK"));

        assertThat(m.getStatus()).isEqualTo(ManifestStatus.EXCEPTION);
        verify(exceptionService).raise(eq(ExceptionType.BARREL_COUNT_MISMATCH),
                eq("MANIFEST"), eq(1L), anyString());
    }

    @Test
    void 运输轨迹缺失_进入异常() {
        when(trackPointRepository.countByDispatchId(1L)).thenReturn(0L);
        Manifest m = service.evaluate(manifest(new BigDecimal("100"), 2),
                receipt("99", 2, "OK"));

        assertThat(m.getStatus()).isEqualTo(ManifestStatus.EXCEPTION);
        assertThat(m.getTrackMissing()).isTrue();
        verify(exceptionService).raise(eq(ExceptionType.TRACK_MISSING),
                eq("MANIFEST"), eq(1L), anyString());
    }

    @Test
    void 混入厨余_标记质量问题但不阻断闭环() {
        when(trackPointRepository.countByDispatchId(1L)).thenReturn(5L);
        Manifest m = service.evaluate(manifest(new BigDecimal("100"), 2),
                receipt("99", 2, "CONTAMINATED"));

        assertThat(m.getQualityIssue()).isTrue();
        assertThat(m.getStatus()).isEqualTo(ManifestStatus.VERIFIED);
        verify(exceptionService).raise(eq(ExceptionType.BARREL_CONTAMINATED),
                eq("MANIFEST"), eq(1L), anyString());
        verify(settlementService).createFor(m);
    }

    @Test
    void 阻断异常处理完毕后重估闭环() {
        Manifest m = manifest(new BigDecimal("100"), 2);
        m.setStatus(ManifestStatus.EXCEPTION);
        when(manifestRepository.findById(1L)).thenReturn(Optional.of(m));
        when(exceptionService.hasBlockingOpen("MANIFEST", 1L)).thenReturn(false);

        Manifest result = service.reevaluate(1L);

        assertThat(result.getStatus()).isEqualTo(ManifestStatus.VERIFIED);
        verify(settlementService).createFor(m);
    }
}
