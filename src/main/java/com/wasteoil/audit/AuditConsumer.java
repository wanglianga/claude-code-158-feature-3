package com.wasteoil.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wasteoil.kafka.KafkaTopics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * 审计消费者：订阅全部领域事件主题，事件落库形成不可抵赖的审计流水，
 * 支撑监管追溯与"各方同一口径"的存证要求。
 */
@Slf4j
@Component
public class AuditConsumer {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AuditConsumer(AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = {
            KafkaTopics.APPOINTMENT_EVENTS,
            KafkaTopics.DISPATCH_EVENTS,
            KafkaTopics.WEIGHING_EVENTS,
            KafkaTopics.TRACK_EVENTS,
            KafkaTopics.MANIFEST_EVENTS,
            KafkaTopics.EXCEPTION_EVENTS,
            KafkaTopics.COMPLIANCE_EVENTS,
            KafkaTopics.SUPERVISION_EVENTS
    }, groupId = "waste-oil-audit")
    public void onEvent(@Payload String message,
                        @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        try {
            JsonNode node = objectMapper.readTree(message);
            AuditLog log_ = new AuditLog();
            log_.setTopic(topic);
            log_.setEventId(node.path("eventId").asText());
            log_.setEventType(node.path("type").asText());
            log_.setPayload(node.path("payload").toString());
            auditLogRepository.save(log_);
        } catch (Exception e) {
            log.error("审计事件落库失败 topic={} message={}", topic, message, e);
            throw new IllegalStateException("审计落库失败", e);
        }
    }
}
