package com.wasteoil.kafka;

/**
 * Kafka 主题常量。领域事件总线：核心状态在数据库事务内保证一致，
 * Kafka 承载审计流水、合规分析与风险分析等异步消费。
 */
public final class KafkaTopics {

    public static final String APPOINTMENT_EVENTS = "wasteoil.appointment.events";
    public static final String DISPATCH_EVENTS = "wasteoil.dispatch.events";
    public static final String WEIGHING_EVENTS = "wasteoil.weighing.events";
    public static final String TRACK_EVENTS = "wasteoil.track.events";
    public static final String MANIFEST_EVENTS = "wasteoil.manifest.events";
    public static final String EXCEPTION_EVENTS = "wasteoil.exception.events";
    public static final String COMPLIANCE_EVENTS = "wasteoil.compliance.events";
    /** 监管：风险升级/恢复与现场检查任务 */
    public static final String SUPERVISION_EVENTS = "wasteoil.supervision.events";

    private KafkaTopics() {
    }
}
