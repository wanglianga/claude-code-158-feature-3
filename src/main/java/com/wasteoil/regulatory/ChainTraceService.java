package com.wasteoil.regulatory;

import com.wasteoil.appointment.Appointment;
import com.wasteoil.common.BizException;
import com.wasteoil.dispatch.DispatchOrder;
import com.wasteoil.dispatch.Driver;
import com.wasteoil.dispatch.DriverRepository;
import com.wasteoil.dispatch.Vehicle;
import com.wasteoil.dispatch.VehicleRepository;
import com.wasteoil.manifest.Manifest;
import com.wasteoil.manifest.ManifestDetailService;
import com.wasteoil.manifest.ManifestRepository;
import com.wasteoil.manifest.ManifestStatus;
import com.wasteoil.merchant.Merchant;
import com.wasteoil.merchant.MerchantRepository;
import com.wasteoil.merchant.Store;
import com.wasteoil.merchant.StoreRepository;
import com.wasteoil.plant.Plant;
import com.wasteoil.plant.PlantRepository;
import com.wasteoil.plant.PlantReceipt;
import com.wasteoil.settlement.Settlement;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 监管链路追踪：按商户、日期与回收批次组装完整链路。
 * 每个批次（一张电子联单）串起 预约 → 派单 → 司机/车辆 → 门店称重 →
 * 运输 → 到厂收料联单 → 结算，数据取自联单全链路详情这一个口径。
 */
@Service
public class ChainTraceService {

    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 计入"合规回收"的联单状态（与风险分析口径一致；拒收/取消/异常挂起不计） */
    private static final Set<ManifestStatus> COMPLIANT = EnumSet.of(
            ManifestStatus.VERIFIED, ManifestStatus.CLOSED, ManifestStatus.DISPUTED);

    private final StoreRepository storeRepository;
    private final MerchantRepository merchantRepository;
    private final ManifestRepository manifestRepository;
    private final ManifestDetailService detailService;
    private final DriverRepository driverRepository;
    private final VehicleRepository vehicleRepository;
    private final PlantRepository plantRepository;

    public ChainTraceService(StoreRepository storeRepository,
                             MerchantRepository merchantRepository,
                             ManifestRepository manifestRepository,
                             ManifestDetailService detailService,
                             DriverRepository driverRepository,
                             VehicleRepository vehicleRepository,
                             PlantRepository plantRepository) {
        this.storeRepository = storeRepository;
        this.merchantRepository = merchantRepository;
        this.manifestRepository = manifestRepository;
        this.detailService = detailService;
        this.driverRepository = driverRepository;
        this.vehicleRepository = vehicleRepository;
        this.plantRepository = plantRepository;
    }

    /**
     * 生成链路视图。storeId / merchantId / district 至少给出一个范围；
     * date 为空时默认当天（Asia/Shanghai）。
     */
    @Transactional(readOnly = true)
    public ChainView trace(Long storeId, Long merchantId, String district, LocalDate date) {
        LocalDate day = date == null ? LocalDate.now(ZONE) : date;
        Instant from = day.atStartOfDay(ZONE).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(ZONE).toInstant();

        List<Store> stores = pickStores(storeId, merchantId, district);
        List<StoreChain> storeChains = new ArrayList<>();
        for (Store store : stores) {
            List<Manifest> manifests = manifestRepository
                    .findByStoreIdAndCreatedAtBetween(store.getId(), from, to).stream()
                    .sorted(Comparator.comparing(Manifest::getCreatedAt))
                    .toList();
            if (manifests.isEmpty()) {
                continue;
            }
            Merchant merchant = merchantRepository.findById(store.getMerchantId()).orElse(null);
            List<BatchChain> batches = manifests.stream().map(this::toBatch).toList();
            BigDecimal totalArrival = batches.stream()
                    .map(b -> b.arrivalWeightKg() == null ? BigDecimal.ZERO : b.arrivalWeightKg())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            storeChains.add(new StoreChain(store.getId(), store.getName(), store.getAddress(),
                    store.getDistrict(), store.getMerchantId(),
                    merchant == null ? null : merchant.getName(), day,
                    batches.size(), totalArrival, batches));
        }
        return new ChainView(day, storeId, merchantId, district,
                storeChains.size(), storeChains);
    }

    private List<Store> pickStores(Long storeId, Long merchantId, String district) {
        if (storeId != null) {
            Store store = storeRepository.findById(storeId)
                    .orElseThrow(() -> BizException.notFound("门店"));
            return List.of(store);
        }
        List<Store> all = storeRepository.findAll();
        if (merchantId != null) {
            all = all.stream().filter(s -> s.getMerchantId().equals(merchantId)).toList();
        }
        if (district != null && !district.isBlank()) {
            all = all.stream().filter(s -> district.equals(s.getDistrict())).toList();
        }
        return all;
    }

    private BatchChain toBatch(Manifest manifest) {
        var d = detailService.detail(manifest.getId());
        Appointment a = d.appointment();
        DispatchOrder dis = d.dispatch();

        Driver driver = null;
        Vehicle vehicle = null;
        Plant plant = plantRepository.findById(manifest.getPlantId()).orElse(null);
        if (dis != null) {
            driver = driverRepository.findById(dis.getDriverId()).orElse(null);
            vehicle = vehicleRepository.findById(dis.getVehicleId()).orElse(null);
        }

        long manualCount = d.weighings().stream().filter(w -> "MANUAL".equals(w.getSource())).count();
        long offlineCount = d.weighings().stream().filter(w -> Boolean.TRUE.equals(w.getDeviceOffline())).count();
        long openExceptions = d.exceptions().stream().filter(t -> "OPEN".equals(t.getStatus())).count();

        return new BatchChain(
                manifest.getId(), manifest.getManifestNo(), manifest.getStatus().name(),
                manifest.getCreatedAt(), manifest.getComplianceResult(),
                manifest.getExitWeightKg(), manifest.getArrivalWeightKg(),
                manifest.getLossKg(), manifest.getLossRate(),
                manifest.getBarrelCountExit(), manifest.getBarrelCountArrival(),
                manifest.getTrackMissing(), manifest.getQualityIssue(),
                a == null ? null : new AppointmentInfo(a.getId(), a.getStatus().name(),
                        a.getBarrelCount(), a.getEstimatedWeightKg(), a.getStorageLocation(),
                        a.getContactName(), a.getContactPhone(), a.getWindowStart(), a.getWindowEnd()),
                dis == null ? null : new DispatchInfo(dis.getId(), dis.getStatus().name(),
                        dis.getPlannedStart(), dis.getPlannedEnd(), dis.getDepartedAt(),
                        dis.getArrivedAt(), dis.getLoadDoneAt(), dis.getPlantArrivedAt(),
                        dis.getCompletedAt(), dis.getRouteNote()),
                driver == null ? null : new DriverInfo(driver.getId(), driver.getName(), driver.getPhone()),
                vehicle == null ? null : new VehicleInfo(vehicle.getId(), vehicle.getPlateNo(),
                        vehicle.getCapacityKg()),
                plant == null ? null : new PlantInfo(plant.getId(), plant.getName(), plant.getAddress()),
                d.weighings().size(), manualCount, offlineCount, d.tracks().size(),
                toReceiptInfo(d.receipt()),
                d.settlement() == null ? null : new SettlementInfo(
                        d.settlement().getWeightKg(), d.settlement().getUnitPrice(),
                        d.settlement().getAmount(), d.settlement().getStatus()),
                d.exceptions().size(), openExceptions);
    }

    private ReceiptInfo toReceiptInfo(PlantReceipt r) {
        if (r == null) {
            return null;
        }
        return new ReceiptInfo(r.getArrivalWeightKg(), r.getBarrelCount(), r.getQualityCheck(),
                r.getDecision(), r.getRejectReason(), r.getReceivedBy(), r.getReceivedAt());
    }

    /** 某门店最近一次合规回收时间（到厂收料且联单 VERIFIED/CLOSED/DISPUTED）。 */
    @Transactional(readOnly = true)
    public Instant latestRecyclingAt(Long storeId) {
        return manifestRepository.findByStoreIdOrderByCreatedAtDesc(storeId).stream()
                .filter(m -> m.getArrivalWeightKg() != null && COMPLIANT.contains(m.getStatus()))
                .map(Manifest::getCreatedAt)
                .findFirst().orElse(null);
    }

    // ---------- 链路 DTO ----------

    public record ChainView(LocalDate date, Long storeId, Long merchantId, String district,
                            int storeCount, List<StoreChain> stores) {
    }

    public record StoreChain(Long storeId, String storeName, String address, String district,
                             Long merchantId, String merchantName, LocalDate date,
                             int batchCount, BigDecimal totalArrivalWeightKg,
                             List<BatchChain> batches) {
    }

    public record BatchChain(Long manifestId, String manifestNo, String status, Instant createdAt,
                             String complianceResult,
                             BigDecimal exitWeightKg, BigDecimal arrivalWeightKg,
                             BigDecimal lossKg, BigDecimal lossRate,
                             Integer barrelCountExit, Integer barrelCountArrival,
                             boolean trackMissing, boolean qualityIssue,
                             AppointmentInfo appointment, DispatchInfo dispatch,
                             DriverInfo driver, VehicleInfo vehicle, PlantInfo plant,
                             int weighingCount, long manualWeighCount, long offlineWeighCount,
                             int trackCount, ReceiptInfo receipt, SettlementInfo settlement,
                             int exceptionCount, long openExceptionCount) {
    }

    public record AppointmentInfo(Long id, String status, Integer barrelCount,
                                  BigDecimal estimatedWeightKg, String storageLocation,
                                  String contactName, String contactPhone,
                                  Instant windowStart, Instant windowEnd) {
    }

    public record DispatchInfo(Long id, String status, Instant plannedStart, Instant plannedEnd,
                               Instant departedAt, Instant arrivedAt, Instant loadDoneAt,
                               Instant plantArrivedAt, Instant completedAt, String routeNote) {
    }

    public record DriverInfo(Long id, String name, String phone) {
    }

    public record VehicleInfo(Long id, String plateNo, BigDecimal capacityKg) {
    }

    public record PlantInfo(Long id, String name, String address) {
    }

    public record ReceiptInfo(BigDecimal arrivalWeightKg, Integer barrelCount, String qualityCheck,
                              String decision, String rejectReason, String receivedBy,
                              Instant receivedAt) {
    }

    public record SettlementInfo(BigDecimal weightKg, BigDecimal unitPrice, BigDecimal amount,
                                 String status) {
    }
}
