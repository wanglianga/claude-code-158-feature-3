package com.wasteoil.supervision;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 监管抽查链路 DTO：按 商户 / 日期 / 回收批次（联单）组织的废油去向完整链路。
 */
public class ChainDtos {

    private ChainDtos() {
    }

    /** 某商户某日的回收批次链路集合 */
    public record StreetChain(Long storeId, String storeName, String address, String district,
                              String merchantName, String date, int batchCount,
                              List<PickupBatch> batches) {
    }

    /** 单个回收批次（一张电子联单）的完整链路：预约→称重→司机车辆→到厂联单→结算 */
    public record PickupBatch(
            Long manifestId,
            String manifestNo,
            String manifestStatus,
            String complianceResult,
            BigDecimal exitWeightKg,
            BigDecimal arrivalWeightKg,
            BigDecimal lossKg,
            BigDecimal lossRate,
            Integer barrelCountExit,
            Integer barrelCountArrival,
            AppointmentInfo appointment,
            DispatchInfo dispatch,
            List<WeighingInfo> weighings,
            int trackPointCount,
            PlantReceiptInfo plantReceipt,
            SettlementInfo settlement,
            List<String> exceptionTypes,
            List<InspectionInfo> inspections) {
    }

    public record AppointmentInfo(Long id, Instant windowStart, Instant windowEnd,
                                  Integer barrelCount, BigDecimal estimatedWeightKg,
                                  String contactName, String storageLocation, String status) {
    }

    public record DriverInfo(Long id, String name, String phone) {
    }

    public record VehicleInfo(Long id, String plateNo, BigDecimal capacityKg) {
    }

    public record PlantInfo(Long id, String name, String address) {
    }

    public record DispatchInfo(Long id, String status, Instant plannedStart, Instant plannedEnd,
                               Instant departedAt, Instant arrivedAt, Instant loadDoneAt,
                               Instant plantArrivedAt, DriverInfo driver, VehicleInfo vehicle,
                               PlantInfo plant) {
    }

    public record WeighingInfo(String barrelCode, BigDecimal weightKg, String source,
                               boolean deviceOffline, String handoverName, String photoUrl,
                               Instant weighedAt) {
    }

    public record PlantReceiptInfo(BigDecimal arrivalWeightKg, Integer barrelCount,
                                   String qualityCheck, String decision, String rejectReason,
                                   String receivedBy, Instant receivedAt) {
    }

    public record SettlementInfo(BigDecimal weightKg, BigDecimal unitPrice, BigDecimal amount,
                                 String status) {
    }

    public record InspectionInfo(Long id, String inspector, String result, String note,
                                 Instant createdAt) {
    }
}
