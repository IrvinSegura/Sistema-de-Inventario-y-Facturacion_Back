package com.irvin.inventory.notification;

import java.time.Instant;
import java.util.Collection;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    // Uses ix_notification_recipient_date; id breaks ties between notifications of the same instant.
    @EntityGraph(attributePaths = "actor")
    Page<Notification> findByRecipientIdAndTypeInOrderByCreatedAtDescIdDesc(Long recipientId,
            Collection<NotificationType> types, Pageable pageable);

    // Uses ix_notification_recipient_unread.
    @EntityGraph(attributePaths = "actor")
    Page<Notification> findByRecipientIdAndReadAtIsNullAndTypeInOrderByCreatedAtDescIdDesc(Long recipientId,
            Collection<NotificationType> types, Pageable pageable);

    long countByRecipientIdAndReadAtIsNullAndTypeIn(Long recipientId, Collection<NotificationType> types);

    // Keeps the first read time, so marking twice still finds the row; 0 when it isn't the recipient's.
    @Modifying
    @Query("update Notification n set n.readAt = coalesce(n.readAt, :now) where n.id = :id and n.recipient.id = :recipientId")
    int markRead(Long id, Long recipientId, Instant now);

    @Modifying
    @Query("update Notification n set n.readAt = :now where n.recipient.id = :recipientId and n.readAt is null")
    int markAllRead(Long recipientId, Instant now);

    @Modifying
    @Query("delete from Notification n where n.readAt < :readBefore or n.createdAt < :createdBefore")
    int deleteOld(Instant readBefore, Instant createdBefore);
}
