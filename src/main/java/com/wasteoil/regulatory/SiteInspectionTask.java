package com.wasteoil.regulatory;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 现场检查任务：长期无回收记录的疑似违规商户由巡查扫描自动生成，
 * 监管人员现场核查后回填检查照片、约谈结果与整改期限；整改期满复核通过后关闭。
 */
@Getter
@Setter
@Entity
@Table(name = "site_inspection_tasks")
public class SiteInspectionTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 任务编号，如 SI20261002-0001 */
    @Column(name = "task_no", nullable = false, unique = true)
    private String taskNo;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** RISK_SCAN 巡查扫描自动生成 / MANUAL 监管手工发起 */
    @Column(nullable = false)
    private String source;

    /** 疑似违规事由 */
    @Column(nullable = false)
    private String reason;

    /** PENDING / RECTIFYING / COMPLETED / VERIFIED_CLOSED，见 {@link SiteInspectionStatus} */
    @Column(nullable = false)
    private String status = SiteInspectionStatus.PENDING.name();

    /** 指派监管人员 */
    private String inspector;

    /** COMPLIANT 合规 / NON_COMPLIANT 违规 / RECTIFYING 责令整改 */
    @Column(name = "check_result")
    private String checkResult;

    /** 现场检查照片（多个 URL 逗号分隔） */
    @Column(name = "photo_urls")
    private String photoUrls;

    /** 约谈结果 */
    @Column(name = "interview_result")
    private String interviewResult;

    /** 整改期限 */
    @Column(name = "rectify_deadline")
    private LocalDate rectifyDeadline;

    @Column(name = "inspected_by")
    private String inspectedBy;

    @Column(name = "inspected_at")
    private Instant inspectedAt;

    /** 整改复核结论 */
    @Column(name = "close_note")
    private String closeNote;

    @Column(name = "closed_by")
    private String closedBy;

    @Column(name = "closed_at")
    private Instant closedAt;

    /** 生成任务时该商户最近一次合规回收时间 */
    @Column(name = "last_recycling_at")
    private Instant lastRecyclingAt;

    /** 生成任务时已连续无回收天数 */
    @Column(name = "days_no_recycling")
    private Integer daysNoRecycling;

    @Version
    @Column(nullable = false)
    private Integer version = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
