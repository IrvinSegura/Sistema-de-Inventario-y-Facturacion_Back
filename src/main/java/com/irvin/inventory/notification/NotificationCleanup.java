package com.irvin.inventory.notification;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// Notifications only matter for a while; keeping them would grow the table forever. Unread ones get
// longer, for someone back from a break. Keep in step with NotificationController's description.
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationCleanup {

    static final Duration READ_RETENTION = Duration.ofDays(30);
    static final Duration MAX_RETENTION = Duration.ofDays(90);

    private final NotificationRepository notificationRepository;
    private final Clock clock;

    // Half an hour after RefreshTokenCleanup, so the two don't run at once.
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void deleteOldNotifications() {
        Instant now = clock.instant();
        int deleted = notificationRepository.deleteOld(now.minus(READ_RETENTION), now.minus(MAX_RETENTION));
        log.info("Deleted {} old notifications", deleted);
    }
}
