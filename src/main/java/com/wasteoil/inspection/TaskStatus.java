package com.wasteoil.inspection;

/**
 * 现场检查任务状态。
 */
public enum TaskStatus {
    /** 已生成，待分派/待检查 */
    OPEN,
    /** 监管人员已认领并在检查中 */
    IN_PROGRESS,
    /** 已完成（回填照片、约谈结果、整改期限或合格结论） */
    COMPLETED,
    /** 超过检查期限仍未完成 */
    OVERDUE,
    /** 已取消（如风险解除） */
    CANCELLED
}
