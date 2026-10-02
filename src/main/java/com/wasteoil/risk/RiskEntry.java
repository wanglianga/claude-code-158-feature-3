package com.wasteoil.risk;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 风险名单条目：长期产量/营业规模/回收频次不匹配的商户门店。
 *
 * <p>风险分级：
 * <ul>
 *   <li>{@link RiskLevel#WATCH}：疑似违规，纳入关注；长期无回收首个周期即为此级，并生成现场检查任务。</li>
 *   <li>{@link RiskLevel#KEY_PATROL}：连续多个周期仍无回收记录，进入重点巡查名单。</li>
 * </ul>
 * 商户恢复正常回收后，风险状态随扫描逐步下降（KEY_PATROL → WATCH → 关闭）。
 */
@Getter
@Setter
@Entity
@Table(name = "risk_entries")
public class RiskEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    /** YIELD_MISMATCH / NO_RECYCLING / MANUAL_WEIGH_HEAVY / INSPECTION_FAIL */
    @Column(nullable = false)
    private String indicator;

    @Column(nullable = false)
    private String detail;

    /** 风险分（0-100） */
    @Column(nullable = false)
    private Integer score;

    @Column(name = "window_start", nullable = false)
    private LocalDate windowStart;

    @Column(name = "window_end", nullable = false)
    private LocalDate windowEnd;

    /** OPEN / CONFIRMED / DISMISSED / RECOVERED */
    @Column(nullable = false)
    private String status = "OPEN";

    /** WATCH 疑似违规(关注) / KEY_PATROL 重点巡查 */
    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false)
    private RiskLevel riskLevel = RiskLevel.WATCH;

    /** 连续无回收的扫描周期数（每次扫描窗口无合规回收 +1） */
    @Column(name = "consecutive_no_recycling_cycles", nullable = false)
    private Integer consecutiveNoRecyclingCycles = 0;

    /** 最近一次合规回收时间 */
    @Column(name = "last_pickup_at")
    private Instant lastPickupAt;

    /** 最近一次已计入降级的回收批次（电子联单）ID，用于保证"每批降一级"不重不漏 */
    @Column(name = "last_pickup_manifest_id")
    private Long lastPickupManifestId;

    /** 恢复正常回收的起始时间（用于逐步降级） */
    @Column(name = "recovered_at")
    private Instant recoveredAt;

    @Column(name = "last_evaluated_at")
    private Instant lastEvaluatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
