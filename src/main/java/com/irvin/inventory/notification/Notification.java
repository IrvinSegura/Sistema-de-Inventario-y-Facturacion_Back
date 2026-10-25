package com.irvin.inventory.notification;

import java.math.BigDecimal;
import java.time.Instant;

import com.irvin.inventory.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.NoArgsConstructor;

// Never edited once written but for read_at, which NotificationRepository's updates set.
@Entity
@Getter
@NoArgsConstructor
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id")
    private User recipient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id")
    private User actor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private NotificationType type;

    private Long subjectId;

    @Column(length = 150)
    private String subjectLabel;

    @Column(precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(length = 255)
    private String reason;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant readAt;

    public Notification(User recipient, User actor, NotificationType type, NotificationContent content,
            Instant createdAt) {
        this.recipient = recipient;
        this.actor = actor;
        this.type = type;
        this.subjectId = content.subjectId();
        this.subjectLabel = content.subjectLabel();
        this.amount = content.amount();
        this.reason = content.reason();
        this.createdAt = createdAt;
    }
}
