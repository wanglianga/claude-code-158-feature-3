package com.wasteoil.regulatory;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 门店动态风险画像（一店一行）。
 * 长期无回收时风险逐级上升（NORMAL → WATCH 疑似违规 → KEY 重点巡查），
 * 恢复正常回收后经连续巡查确认风险分逐步下降，直至解除重点巡查。
 */
@Getter
@Setter
@Entity
@Table(name = "store_risk_profiles")
public class StoreRiskProfile {

    public static final String NORMAL = "NORMAL";
    /** 关注：疑似违规（长期无回收） */
    public static final String WATCH = "WATCH";
    /** 重点巡查 */
    public static final String KEY = "KEY";

    @Id
    @Column(name = "store_id")
    private Long storeId;

    /** NORMAL / WATCH / KEY */
    @Column(name = "risk_level", nullable = false)
    private String riskLevel = NORMAL;

    /** 动态风险分 0-100 */
    @Column(name = "risk_score", nullable = false)
    private Integer riskScore = 0;

    /** 是否列入重点巡查名单 */
    @Column(name = "key_patrol", nullable = false)
    private Boolean keyPatrol = false;

    /** 连续无回收巡查扫描命中次数 */
    @Column(name = "consecutive_no_recycling_scans", nullable = false)
    private Integer consecutiveNoRecyclingScans = 0;

    /** 恢复正常回收后的连续巡查确认次数 */
    @Column(name = "normal_recycling_streak", nullable = false)
    private Integer normalRecyclingStreak = 0;

    /** 最近一次合规回收（到厂收料）时间 */
    @Column(name = "last_recycling_at")
    private Instant lastRecyclingAt;

    @Column(name = "last_scanned_at")
    private Instant lastScannedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
