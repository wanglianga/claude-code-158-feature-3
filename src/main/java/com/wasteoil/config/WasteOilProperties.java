package com.wasteoil.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "wasteoil")
public class WasteOilProperties {

    /** 结算单价（元/公斤） */
    private BigDecimal unitPricePerKg = new BigDecimal("3.20");

    /** 运输损耗率阈值 */
    private BigDecimal maxLossRate = new BigDecimal("0.03");

    /** 称重设备离线判定（分钟） */
    private int deviceOfflineMinutes = 5;

    /** 司机迟到宽限（分钟） */
    private int driverLateGraceMinutes = 30;

    /** 运输途中轨迹缺失判定（分钟） */
    private int trackMissingMinutes = 10;

    /** 风险分析窗口（天） */
    private int riskWindowDays = 90;

    /** 产量/预期比值阈值 */
    private BigDecimal riskYieldRatio = new BigDecimal("0.3");

    /** 连续多少个扫描周期无回收记录，从"关注"升级为"重点巡查" */
    private int noRecyclingEscalateCycles = 2;

    /** 现场检查任务默认办理期限（天） */
    private int inspectionDueDays = 7;

    /** 恢复正常回收后，风险逐步下降：每个扫描周期回收量达标即降一级（关注→关闭） */
    private int riskRecoveryDecay = 1;
}
