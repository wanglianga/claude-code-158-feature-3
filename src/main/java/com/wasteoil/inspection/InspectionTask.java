package com.wasteoil.inspection;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 现场检查任务。某商户长期无回收记录被标记疑似违规后自动生成，
 * 监管人员可回填现场检查照片、约谈结果、整改期限；
 * 商户恢复正常回收后任务随风险下降关闭，持续无记录则进入重点巡查名单。
 */
@Getter
@Setter
@Entity
@Table(name = "inspection_tasks")
public class InspectionTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 检查任务编号，如 INS20261002-0001 */
    @Column(name = "task_no", nullable = false, unique = true)
    private String taskNo;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    /** 触发本任务的风险条目（人工发起可为空） */
    @Column(name = "risk_entry_id")
    private Long riskEntryId;

    /** NO_RECYCLING / KEY_PATROL / MANUAL */
    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_reason", nullable = false)
    private InspectionTrigger triggerReason;

    /** OPEN / IN_PROGRESS / COMPLETED / OVERDUE / CANCELLED */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskStatus status = TaskStatus.OPEN;

    /** 承办监管人员 */
    private String inspector;

    /** 要求完成现场检查的期限 */
    @Column(name = "due_date")
    private LocalDate dueDate;

    /** 现场检查照片（多个 URL 逗号分隔） */
    @Column(name = "inspection_photo_urls")
    private String inspectionPhotoUrls;

    /** 约谈结果 */
    @Column(name = "interview_result")
    private String interviewResult;

    /** 责令整改期限 */
    @Column(name = "rectification_deadline")
    private LocalDate rectificationDeadline;

    /** QUALIFIED 合格 / UNQUALIFIED 不合格 */
    @Column(name = "inspection_result")
    private String inspectionResult;

    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;
}
