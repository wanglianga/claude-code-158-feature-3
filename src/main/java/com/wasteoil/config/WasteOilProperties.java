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

    /** 巡查：连续多少天无合规回收即标记疑似违规（WATCH）并生成现场检查任务 */
    private int patrolWatchDays = 30;

    /** 巡查：连续多少天无合规回收即列入重点巡查名单（KEY） */
    private int patrolKeyDays = 60;

    /** 巡查：恢复正常回收后连续多少次巡查确认即解除风险状态 */
    private int patrolRecoverScans = 2;
}
