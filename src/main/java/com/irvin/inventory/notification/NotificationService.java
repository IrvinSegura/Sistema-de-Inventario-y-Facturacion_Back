package com.irvin.inventory.notification;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import com.irvin.inventory.common.exception.NotFoundException;
import com.irvin.inventory.notification.dto.NotificationResponse;
import com.irvin.inventory.notification.dto.UnreadCountResponse;
import com.irvin.inventory.security.CurrentUser;
import com.irvin.inventory.user.Role;
import com.irvin.inventory.user.User;
import com.irvin.inventory.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    // Exactly these recipients: NotificationListener has already left the actor out. createdAt is when
    // the change committed, so notifications saved out of order still list in the order things happened.
    @Transactional
    public void notifyUsers(Collection<Long> recipientIds, Long actorId, NotificationType type,
            NotificationContent content, Instant createdAt) {
        save(recipientIds, actorId, type, content, createdAt);
    }

    // Every active admin and supervisor but the actor. One demoted meanwhile may still get it, but
    // never reads it: findForUser filters by the reader's current role.
    @Transactional
    public void notifyManagers(Long actorId, NotificationType type, NotificationContent content,
            Instant createdAt) {
        List<Long> recipients = userRepository.findActiveIdsByRoleIn(Role.MANAGERS).stream()
                .filter(id -> !id.equals(actorId))
                .toList();
        save(recipients, actorId, type, content, createdAt);
    }

    // Always newest first: a client sort would break the list's order (or name a missing field).
    // The token's role is current: a role change invalidates the tokens issued before it.
    @Transactional(readOnly = true)
    public Page<NotificationResponse> findForUser(CurrentUser reader, boolean unreadOnly, Pageable pageable) {
        Pageable unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        Set<NotificationType> types = NotificationType.visibleTo(reader.role());
        Page<Notification> page = unreadOnly
                ? notificationRepository.findByRecipientIdAndReadAtIsNullAndTypeInOrderByCreatedAtDescIdDesc(
                        reader.id(), types, unsorted)
                : notificationRepository.findByRecipientIdAndTypeInOrderByCreatedAtDescIdDesc(reader.id(), types,
                        unsorted);
        return page.map(NotificationResponse::from);
    }

    @Transactional(readOnly = true)
    public UnreadCountResponse countUnread(CurrentUser reader) {
        return new UnreadCountResponse(notificationRepository.countByRecipientIdAndReadAtIsNullAndTypeIn(reader.id(),
                NotificationType.visibleTo(reader.role())));
    }

    // Another user's notification answers like a missing one.
    @Transactional
    public void markRead(Long id, Long userId) {
        if (notificationRepository.markRead(id, userId, now()) == 0) {
            throw new NotFoundException("Notification not found");
        }
    }

    @Transactional
    public void markAllRead(Long userId) {
        notificationRepository.markAllRead(userId, now());
    }

    private void save(Collection<Long> recipientIds, Long actorId, NotificationType type, NotificationContent content,
            Instant createdAt) {
        User actor = actorId == null ? null : userRepository.getReferenceById(actorId);
        notificationRepository.saveAll(recipientIds.stream()
                .map(recipientId -> new Notification(userRepository.getReferenceById(recipientId), actor, type,
                        content, createdAt))
                .toList());
    }

    // PostgreSQL stores microseconds; truncating keeps responses equal to later reads.
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
