package com.wasteoil.compliance;

import com.wasteoil.config.WasteOilProperties;
import com.wasteoil.dispatch.DispatchOrder;
import com.wasteoil.dispatch.DispatchOrderRepository;
import com.wasteoil.exception.ExceptionService;
import com.wasteoil.exception.ExceptionType;
import com.wasteoil.kafka.EventPublisher;
import com.wasteoil.kafka.KafkaTopics;
import com.wasteoil.manifest.Manifest;
import com.wasteoil.manifest.ManifestRepository;
import com.wasteoil.manifest.ManifestService;
import com.wasteoil.manifest.ManifestStatus;
import com.wasteoil.plant.PlantReceipt;
import com.wasteoil.risk.RiskAnalyzer;
import com.wasteoil.settlement.SettlementService;
import com.wasteoil.track.TrackPointRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 合规闭环服务：比对出店重量、运输损耗、到厂重量与油桶数量，
 * 结合运输轨迹完整性与质检结果，判定联单合规闭环或进入异常处理。
 */
@Slf4j
@Service
public class ComplianceService {

    private final ManifestRepository manifestRepository;
    private final ManifestService manifestService;
    private final DispatchOrderRepository dispatchRepository;
    private final TrackPointRepository trackPointRepository;
    private final ExceptionService exceptionService;
    private final SettlementService settlementService;
    private final RiskAnalyzer riskAnalyzer;
    private final EventPublisher eventPublisher;
    private final WasteOilProperties properties;

    public ComplianceService(ManifestRepository manifestRepository,
                             ManifestService manifestService,
                             DispatchOrderRepository dispatchRepository,
                             TrackPointRepository trackPointRepository,
                             ExceptionService exceptionService,
                             SettlementService settlementService,
                             RiskAnalyzer riskAnalyzer,
                             EventPublisher eventPublisher,
                             WasteOilProperties properties) {
        this.manifestRepository = manifestRepository;
        this.manifestService = manifestService;
        this.dispatchRepository = dispatchRepository;
        this.trackPointRepository = trackPointRepository;
        this.exceptionService = exceptionService;
        this.settlementService = settlementService;
        this.riskAnalyzer = riskAnalyzer;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
    }

    /**
     * 处置厂收料后执行合规比对（与收料同一事务，保证联单口径一致）。
     */
    @Transactional
    public Manifest evaluate(Manifest manifest, PlantReceipt receipt) {
        List<String> issues = new ArrayList<>();
        boolean hasBlocking = false;

        // 1. 运输损耗比对：出店重量 vs 到厂重量
        BigDecimal exit = manifest.getExitWeightKg();
        BigDecimal arrival = receipt.getArrivalWeightKg();
        BigDecimal loss = exit.subtract(arrival);
        BigDecimal lossRate = exit.compareTo(BigDecimal.ZERO) > 0
                ? loss.divide(exit, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        manifest.setLossKg(loss);
        manifest.setLossRate(lossRate);
        if (lossRate.compareTo(properties.getMaxLossRate()) > 0) {
            hasBlocking = true;
            issues.add(String.format("运输损耗率 %.2f%% 超过阈值 %.2f%%",
                    lossRate.multiply(BigDecimal.valueOf(100)),
                    properties.getMaxLossRate().multiply(BigDecimal.valueOf(100))));
            exceptionService.raise(ExceptionType.TRANSPORT_LOSS_ABNORMAL, "MANIFEST", manifest.getId(),
                    String.format("出店 %.1f kg，到厂 %.1f kg，损耗 %.1f kg（%.2f%%）",
                            exit, arrival, loss, lossRate.multiply(BigDecimal.valueOf(100))));
        }

        // 2. 油桶数量比对：装车桶数 vs 到厂桶数
        if (!receipt.getBarrelCount().equals(manifest.getBarrelCountExit())) {
            hasBlocking = true;
            issues.add(String.format("油桶数量不符: 装车 %d 桶，到厂 %d 桶",
                    manifest.getBarrelCountExit(), receipt.getBarrelCount()));
            exceptionService.raise(ExceptionType.BARREL_COUNT_MISMATCH, "MANIFEST", manifest.getId(),
                    String.format("装车 %d 桶，到厂 %d 桶",
                            manifest.getBarrelCountExit(), receipt.getBarrelCount()));
        }

        // 3. 运输轨迹完整性
        DispatchOrder dispatch = dispatchRepository.findById(manifest.getDispatchId())
                .orElseThrow();
        long trackCount = trackPointRepository.countByDispatchId(dispatch.getId());
        if (trackCount == 0) {
            hasBlocking = true;
            manifest.setTrackMissing(true);
            issues.add("运输轨迹缺失");
            exceptionService.raise(ExceptionType.TRACK_MISSING, "MANIFEST", manifest.getId(),
                    "运输全程无轨迹点");
        }

        // 4. 质检结果（混入厨余，记录但不阻断闭环）
        if ("CONTAMINATED".equals(receipt.getQualityCheck())) {
            manifest.setQualityIssue(true);
            issues.add("油桶混入厨余");
            exceptionService.raise(ExceptionType.BARREL_CONTAMINATED, "MANIFEST", manifest.getId(),
                    "处置厂质检发现混入厨余: " + receipt.getRejectReason());
        }

        manifest.setArrivalWeightKg(arrival);
        manifest.setBarrelCountArrival(receipt.getBarrelCount());

        if (hasBlocking) {
            manifest.setComplianceResult("EXCEPTION");
            manifest.setExceptionNote(String.join("; ", issues));
            manifest.setStatus(ManifestStatus.EXCEPTION);
        } else {
            // 非阻断性问题（如混入厨余）记录但不妨碍闭环
            if (!issues.isEmpty()) {
                manifest.setExceptionNote(String.join("; ", issues));
            }
            manifest.setComplianceResult("PASS");
            manifest.setStatus(ManifestStatus.VERIFIED);
        }
        manifest.setUpdatedAt(java.time.Instant.now());
        Manifest saved = manifestRepository.save(manifest);

        if (saved.getStatus() == ManifestStatus.VERIFIED) {
            closeLoop(saved);
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("manifestId", saved.getId());
        payload.put("manifestNo", saved.getManifestNo());
        payload.put("complianceResult", saved.getComplianceResult());
        payload.put("lossKg", saved.getLossKg());
        payload.put("lossRate", saved.getLossRate());
        payload.put("issues", issues);
        eventPublisher.publish(KafkaTopics.COMPLIANCE_EVENTS,
                "manifest-" + saved.getId(), "ComplianceEvaluated", payload);
        return saved;
    }

    /**
     * 阻断性异常处理完毕后重新评估：无未处理阻断异常则闭环。
     */
    @Transactional
    public Manifest reevaluate(Long manifestId) {
        Manifest manifest = manifestRepository.findById(manifestId)
                .orElseThrow(() -> com.wasteoil.common.BizException.notFound("电子联单"));
        if (manifest.getStatus() != ManifestStatus.EXCEPTION) {
            return manifest;
        }
        if (exceptionService.hasBlockingOpen("MANIFEST", manifestId)) {
            return manifest;
        }
        manifest.setStatus(ManifestStatus.VERIFIED);
        manifest.setComplianceResult("PASS");
        manifest.setUpdatedAt(java.time.Instant.now());
        Manifest saved = manifestRepository.save(manifest);
        closeLoop(saved);
        return saved;
    }

    /** 合规通过 → 生成结算单 → 联单闭环 */
    private void closeLoop(Manifest manifest) {
        settlementService.createFor(manifest);
        manifestService.transition(manifest, ManifestStatus.CLOSED, "ManifestClosed");
        // 产生新的合规回收批次：若该门店存在长期无回收风险，风险状态逐步下降
        riskAnalyzer.notifyPickup(manifest);
    }
}
