package com.wasteoil.supervision;

import com.wasteoil.appointment.Appointment;
import com.wasteoil.appointment.AppointmentRepository;
import com.wasteoil.barrel.Barrel;
import com.wasteoil.barrel.BarrelRepository;
import com.wasteoil.common.BizException;
import com.wasteoil.dispatch.DispatchOrder;
import com.wasteoil.dispatch.DispatchOrderRepository;
import com.wasteoil.dispatch.Driver;
import com.wasteoil.dispatch.DriverRepository;
import com.wasteoil.dispatch.Vehicle;
import com.wasteoil.dispatch.VehicleRepository;
import com.wasteoil.exception.ExceptionTicket;
import com.wasteoil.exception.ExceptionTicketRepository;
import com.wasteoil.inspection.Inspection;
import com.wasteoil.inspection.InspectionRepository;
import com.wasteoil.manifest.Manifest;
import com.wasteoil.manifest.ManifestRepository;
import com.wasteoil.merchant.Merchant;
import com.wasteoil.merchant.MerchantRepository;
import com.wasteoil.merchant.Store;
import com.wasteoil.merchant.StoreRepository;
import com.wasteoil.plant.Plant;
import com.wasteoil.plant.PlantReceipt;
import com.wasteoil.plant.PlantReceiptRepository;
import com.wasteoil.plant.PlantRepository;
import com.wasteoil.settlement.Settlement;
import com.wasteoil.settlement.SettlementRepository;
import com.wasteoil.track.TrackPointRepository;
import com.wasteoil.weighing.WeighingRecord;
import com.wasteoil.weighing.WeighingRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 监管抽查链路服务。监管人员抽查某条街餐饮商户废油去向时，
 * 按 <b>商户（门店）+ 日期 + 回收批次（电子联单）</b> 组装完整链路：
 * 预约 → 称重 → 司机 → 车辆 → 到厂联单 → 结算，并附带异常与抽查记录。
 *
 * <p>所有数据以电子联单为单一口径，监管端与司机、商户、处置厂看到的是同一份数据。
 */
@Service
public class SupervisionChainService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final StoreRepository storeRepository;
    private final MerchantRepository merchantRepository;
    private final ManifestRepository manifestRepository;
    private final DispatchOrderRepository dispatchRepository;
    private final AppointmentRepository appointmentRepository;
    private final WeighingRecordRepository weighingRecordRepository;
    private final TrackPointRepository trackPointRepository;
    private final PlantReceiptRepository receiptRepository;
    private final SettlementRepository settlementRepository;
    private final ExceptionTicketRepository ticketRepository;
    private final InspectionRepository inspectionRepository;
    private final DriverRepository driverRepository;
    private final VehicleRepository vehicleRepository;
    private final PlantRepository plantRepository;
    private final BarrelRepository barrelRepository;

    public SupervisionChainService(StoreRepository storeRepository,
                                   MerchantRepository merchantRepository,
                                   ManifestRepository manifestRepository,
                                   DispatchOrderRepository dispatchRepository,
                                   AppointmentRepository appointmentRepository,
                                   WeighingRecordRepository weighingRecordRepository,
                                   TrackPointRepository trackPointRepository,
                                   PlantReceiptRepository receiptRepository,
                                   SettlementRepository settlementRepository,
                                   ExceptionTicketRepository ticketRepository,
                                   InspectionRepository inspectionRepository,
                                   DriverRepository driverRepository,
                                   VehicleRepository vehicleRepository,
                                   PlantRepository plantRepository,
                                   BarrelRepository barrelRepository) {
        this.storeRepository = storeRepository;
        this.merchantRepository = merchantRepository;
        this.manifestRepository = manifestRepository;
        this.dispatchRepository = dispatchRepository;
        this.appointmentRepository = appointmentRepository;
        this.weighingRecordRepository = weighingRecordRepository;
        this.trackPointRepository = trackPointRepository;
        this.receiptRepository = receiptRepository;
        this.settlementRepository = settlementRepository;
        this.ticketRepository = ticketRepository;
        this.inspectionRepository = inspectionRepository;
        this.driverRepository = driverRepository;
        this.vehicleRepository = vehicleRepository;
        this.plantRepository = plantRepository;
        this.barrelRepository = barrelRepository;
    }

    /**
     * 查询某门店某天（或某日期区间）的全部回收批次链路。
     */
    @Transactional(readOnly = true)
    public ChainDtos.StreetChain chainByStore(Long storeId, LocalDate date,
                                              LocalDate from, LocalDate to) {
        Store store = storeRepository.findById(storeId).orElseThrow(() -> BizException.notFound("门店"));
        Merchant merchant = merchantRepository.findById(store.getMerchantId()).orElse(null);

        LocalDate start;
        LocalDate end;
        String dateLabel;
        if (date != null) {
            start = date;
            end = date;
            dateLabel = date.toString();
        } else if (from != null && to != null) {
            start = from;
            end = to;
            dateLabel = from + "~" + to;
        } else {
            // 默认今天
            start = LocalDate.now();
            end = start;
            dateLabel = start.toString();
        }
        Instant fromInstant = start.atStartOfDay(ZONE).toInstant();
        Instant toInstant = end.plusDays(1).atStartOfDay(ZONE).toInstant();

        List<Manifest> manifests = manifestRepository
                .findByStoreIdAndCreatedAtBetween(storeId, fromInstant, toInstant);

        List<ChainDtos.PickupBatch> batches = manifests.stream()
                .map(this::toBatch)
                .toList();
        return new ChainDtos.StreetChain(store.getId(), store.getName(), store.getAddress(),
                store.getDistrict(), merchant == null ? null : merchant.getName(),
                dateLabel, batches.size(), batches);
    }

    /**
     * 查询某条街道（按商圈/地址片段匹配门店）某天的全部商户回收链路。
     */
    @Transactional(readOnly = true)
    public List<ChainDtos.StreetChain> chainByStreet(String street, LocalDate date) {
        LocalDate day = date == null ? LocalDate.now() : date;
        Instant fromInstant = day.atStartOfDay(ZONE).toInstant();
        Instant toInstant = day.plusDays(1).atStartOfDay(ZONE).toInstant();

        List<ChainDtos.StreetChain> result = new ArrayList<>();
        for (Store store : storeRepository.findAll()) {
            if (!"ACTIVE".equals(store.getStatus())) {
                continue;
            }
            boolean matched = street == null || street.isBlank()
                    || store.getDistrict().contains(street)
                    || store.getAddress().contains(street)
                    || store.getName().contains(street);
            if (!matched) {
                continue;
            }
            List<Manifest> manifests = manifestRepository
                    .findByStoreIdAndCreatedAtBetween(store.getId(), fromInstant, toInstant);
            List<ChainDtos.PickupBatch> batches = manifests.stream().map(this::toBatch).toList();
            Merchant merchant = merchantRepository.findById(store.getMerchantId()).orElse(null);
            result.add(new ChainDtos.StreetChain(store.getId(), store.getName(), store.getAddress(),
                    store.getDistrict(), merchant == null ? null : merchant.getName(),
                    day.toString(), batches.size(), batches));
        }
        return result;
    }

    /** 单个回收批次（联单）链路 */
    @Transactional(readOnly = true)
    public ChainDtos.PickupBatch batchByManifest(Long manifestId) {
        Manifest manifest = manifestRepository.findById(manifestId)
                .orElseThrow(() -> BizException.notFound("电子联单"));
        return toBatch(manifest);
    }

    private ChainDtos.PickupBatch toBatch(Manifest manifest) {
        DispatchOrder dispatch = dispatchRepository.findById(manifest.getDispatchId()).orElse(null);
        Appointment appointment = null;
        ChainDtos.DriverInfo driver = null;
        ChainDtos.VehicleInfo vehicle = null;
        ChainDtos.PlantInfo plant = null;
        int trackCount = 0;
        if (dispatch != null) {
            appointment = appointmentRepository.findById(dispatch.getAppointmentId()).orElse(null);
            driver = driverRepository.findById(dispatch.getDriverId())
                    .map(d -> new ChainDtos.DriverInfo(d.getId(), d.getName(), d.getPhone()))
                    .orElse(null);
            vehicle = vehicleRepository.findById(dispatch.getVehicleId())
                    .map(v -> new ChainDtos.VehicleInfo(v.getId(), v.getPlateNo(), v.getCapacityKg()))
                    .orElse(null);
            plant = plantRepository.findById(manifest.getPlantId())
                    .map(p -> new ChainDtos.PlantInfo(p.getId(), p.getName(), p.getAddress()))
                    .orElse(null);
            trackCount = (int) trackPointRepository.countByDispatchId(dispatch.getId());
        }

        ChainDtos.AppointmentInfo apptInfo = null;
        if (appointment != null) {
            apptInfo = new ChainDtos.AppointmentInfo(appointment.getId(),
                    appointment.getWindowStart(), appointment.getWindowEnd(),
                    appointment.getBarrelCount(), appointment.getEstimatedWeightKg(),
                    appointment.getContactName(), appointment.getStorageLocation(),
                    appointment.getStatus().name());
        }
        ChainDtos.DispatchInfo dispatchInfo = null;
        if (dispatch != null) {
            dispatchInfo = new ChainDtos.DispatchInfo(dispatch.getId(), dispatch.getStatus().name(),
                    dispatch.getPlannedStart(), dispatch.getPlannedEnd(),
                    dispatch.getDepartedAt(), dispatch.getArrivedAt(), dispatch.getLoadDoneAt(),
                    dispatch.getPlantArrivedAt(), driver, vehicle, plant);
        }

        // 称重记录（补充桶码）
        List<WeighingRecord> records = weighingRecordRepository.findByManifestId(manifest.getId());
        Map<Long, String> barrelCodes = new HashMap<>();
        for (WeighingRecord r : records) {
            barrelCodes.computeIfAbsent(r.getBarrelId(), id -> barrelRepository.findById(id)
                    .map(Barrel::getCode).orElse("BAR-?"));
        }
        List<ChainDtos.WeighingInfo> weighingInfos = records.stream()
                .map(r -> new ChainDtos.WeighingInfo(barrelCodes.get(r.getBarrelId()),
                        r.getWeightKg(), r.getSource(), Boolean.TRUE.equals(r.getDeviceOffline()),
                        r.getHandoverName(), r.getPhotoUrl(), r.getWeighedAt()))
                .toList();

        PlantReceipt receipt = receiptRepository.findByManifestId(manifest.getId()).orElse(null);
        ChainDtos.PlantReceiptInfo receiptInfo = receipt == null ? null
                : new ChainDtos.PlantReceiptInfo(receipt.getArrivalWeightKg(), receipt.getBarrelCount(),
                        receipt.getQualityCheck(), receipt.getDecision(), receipt.getRejectReason(),
                        receipt.getReceivedBy(), receipt.getReceivedAt());

        Settlement settlement = settlementRepository.findByManifestId(manifest.getId()).orElse(null);
        ChainDtos.SettlementInfo settlementInfo = settlement == null ? null
                : new ChainDtos.SettlementInfo(settlement.getWeightKg(), settlement.getUnitPrice(),
                        settlement.getAmount(), settlement.getStatus());

        List<String> exceptionTypes = ticketRepository
                .findByRefTypeAndRefId("MANIFEST", manifest.getId()).stream()
                .map(t -> t.getType().name()).toList();

        List<ChainDtos.InspectionInfo> inspectionInfos = new ArrayList<>();
        for (Inspection ins : inspectionRepository.findByManifestId(manifest.getId())) {
            inspectionInfos.add(new ChainDtos.InspectionInfo(ins.getId(), ins.getInspector(),
                    ins.getResult(), ins.getNote(), ins.getCreatedAt()));
        }

        return new ChainDtos.PickupBatch(
                manifest.getId(), manifest.getManifestNo(), manifest.getStatus().name(),
                manifest.getComplianceResult(), manifest.getExitWeightKg(),
                manifest.getArrivalWeightKg(), manifest.getLossKg(), manifest.getLossRate(),
                manifest.getBarrelCountExit(), manifest.getBarrelCountArrival(),
                apptInfo, dispatchInfo, weighingInfos, trackCount,
                receiptInfo, settlementInfo,
                Collections.unmodifiableList(exceptionTypes), inspectionInfos);
    }
}
