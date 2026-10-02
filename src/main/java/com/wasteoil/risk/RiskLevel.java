package com.wasteoil.risk;

/**
 * 风险等级。随长期无回收持续而升级，随商户恢复正常回收而逐步下降。
 */
public enum RiskLevel {
    /** 关注（疑似违规）：长期无回收首个周期，自动生成现场检查任务 */
    WATCH,
    /** 重点巡查：连续多个周期仍无回收记录 */
    KEY_PATROL
}
