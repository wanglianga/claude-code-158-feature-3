package com.wasteoil.config;

import com.wasteoil.kafka.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic appointmentEvents() {
        return TopicBuilder.name(KafkaTopics.APPOINTMENT_EVENTS).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic dispatchEvents() {
        return TopicBuilder.name(KafkaTopics.DISPATCH_EVENTS).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic weighingEvents() {
        return TopicBuilder.name(KafkaTopics.WEIGHING_EVENTS).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic trackEvents() {
        return TopicBuilder.name(KafkaTopics.TRACK_EVENTS).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic manifestEvents() {
        return TopicBuilder.name(KafkaTopics.MANIFEST_EVENTS).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic exceptionEvents() {
        return TopicBuilder.name(KafkaTopics.EXCEPTION_EVENTS).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic complianceEvents() {
        return TopicBuilder.name(KafkaTopics.COMPLIANCE_EVENTS).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic supervisionEvents() {
        return TopicBuilder.name(KafkaTopics.SUPERVISION_EVENTS).partitions(3).replicas(1).build();
    }
}
