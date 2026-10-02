package com.wasteoil.inspection;

/**
 * 现场检查任务触发来源。
 */
public enum InspectionTrigger {
    /** 长期无回收记录，疑似违规（首个风险周期） */
    NO_RECYCLING,
    /** 连续多周期无回收，进入重点巡查名单 */
    KEY_PATROL,
    /** 监管人员人工发起 */
    MANUAL
}
