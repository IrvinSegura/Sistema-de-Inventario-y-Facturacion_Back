package com.irvin.inventory.notification.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.irvin.inventory.notification.Notification;
import com.irvin.inventory.notification.NotificationType;

public record NotificationResponse(
        Long id,
        NotificationType type,
        // Which of these a type fills is documented on NotificationType; the rest are null.
        Long subjectId,
        String subjectLabel,
        BigDecimal amount,
        String reason,
        // Null when no person caused it.
        Actor actor,
        Instant createdAt,
        // Null while unread.
        Instant readAt) {

    public record Actor(Long id, String name) {
    }

    public static NotificationResponse from(Notification notification) {
        Actor actor = notification.getActor() == null
                ? null
                : new Actor(notification.getActor().getId(), notification.getActor().getName());
        return new NotificationResponse(notification.getId(), notification.getType(), notification.getSubjectId(),
                notification.getSubjectLabel(), notification.getAmount(), notification.getReason(), actor,
                notification.getCreatedAt(), notification.getReadAt());
    }
}
