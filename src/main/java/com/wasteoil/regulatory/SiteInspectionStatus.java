package com.wasteoil.regulatory;

/**
 * 现场检查任务状态。
 */
public enum SiteInspectionStatus {
    /** 待现场检查 */
    PENDING,
    /** 现场认定违规，责令整改中（已下达整改期限） */
    RECTIFYING,
    /** 现场核实合规，任务直接完成 */
    COMPLETED,
    /** 整改期满复核通过，任务关闭 */
    VERIFIED_CLOSED
}
