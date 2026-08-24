package com.mediatheque.media_svc.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class MediaEventPublisher {

    private static final String TOPIC = "media-decision";

    private final KafkaTemplate<String, MediaStatusEvent> kafkaTemplate;

    public MediaEventPublisher(
            @Autowired(required = false) KafkaTemplate<String, MediaStatusEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishStatusEvent(MediaStatusEvent event) {
        if (kafkaTemplate == null) {
            log.warn("Kafka désactivé — événement ignoré pour mediaId: {}", event.getMediaId());
            return;
        }
        kafkaTemplate.send(TOPIC, String.valueOf(event.getMediaId()), event);
    }
}