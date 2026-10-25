package com.irvin.inventory.notification;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import com.irvin.inventory.purchasing.PurchaseCancelledEvent;
import com.irvin.inventory.sale.CashDrawerAssignedEvent;
import com.irvin.inventory.sale.CashDrawerClosedEvent;
import com.irvin.inventory.sale.SaleCancelledEvent;
import com.irvin.inventory.user.AccountChangedEvent;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

// Saves notifications in the background once the change has committed: a rolled-back change tells no
// one, the request never waits for them, and a failure is only logged.
@Slf4j
@Component
public class NotificationListener {

    // Saves at once out of the pool's 10 connections, so a burst never starves the requests.
    private static final int MAX_CONCURRENT_SAVES = 2;

    private final NotificationService notificationService;
    private final Clock clock;
    // In the background because the request's own connection is released only after the after-commit
    // callbacks: saving here would hold a second one.
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore saves = new Semaphore(MAX_CONCURRENT_SAVES);

    public NotificationListener(NotificationService notificationService, Clock clock) {
        this.notificationService = notificationService;
        this.clock = clock;
    }

    @TransactionalEventListener
    public void onSaleCancelled(SaleCancelledEvent event) {
        notifyUsers(List.of(event.sellerId()), event.cancelledById(), NotificationType.SALE_CANCELLED,
                NotificationContent.saleCancelled(event.saleId(), event.saleNumber(), event.total(), event.reason()));
    }

    @TransactionalEventListener
    public void onPurchaseCancelled(PurchaseCancelledEvent event) {
        NotificationContent content = NotificationContent.purchaseCancelled(event.purchaseId(), event.supplierName(),
                event.total(), event.reason());
        Instant committedAt = now();
        deliver(NotificationType.PURCHASE_CANCELLED, () -> notificationService.notifyManagers(event.cancelledById(),
                NotificationType.PURCHASE_CANCELLED, content, committedAt));
    }

    @TransactionalEventListener
    public void onCashDrawerAssigned(CashDrawerAssignedEvent event) {
        notifyUsers(event.memberIds(), event.openedById(), NotificationType.CASH_DRAWER_ASSIGNED,
                NotificationContent.cashDrawerAssigned(event.drawerId(), event.responsibleName(), event.openingFloat()));
    }

    // The responsible closing their own drawer is the actor, so only a manager's closing notifies.
    @TransactionalEventListener
    public void onCashDrawerClosed(CashDrawerClosedEvent event) {
        notifyUsers(List.of(event.responsibleId()), event.closedById(), NotificationType.CASH_DRAWER_CLOSED,
                NotificationContent.cashDrawerClosed(event.drawerId(), event.businessDate(), event.difference()));
    }

    // No values: the owner knows their new email or phone, and the email already masks them.
    @TransactionalEventListener
    public void onAccountChanged(AccountChangedEvent event) {
        NotificationType type = switch (event.kind()) {
            case EMAIL_CHANGED -> NotificationType.EMAIL_CHANGED;
            case PHONE_CHANGED -> NotificationType.PHONE_CHANGED;
            case PASSWORD_RESET -> NotificationType.PASSWORD_RESET;
        };
        notifyUsers(List.of(event.userId()), event.adminId(), type, NotificationContent.NONE);
    }

    // Nobody is notified of what they did themselves; with no one left, nothing is queued.
    private void notifyUsers(List<Long> userIds, Long actorId, NotificationType type, NotificationContent content) {
        List<Long> recipients = userIds.stream().filter(id -> !id.equals(actorId)).distinct().toList();
        if (!recipients.isEmpty()) {
            Instant committedAt = now();
            deliver(type, () -> notificationService.notifyUsers(recipients, actorId, type, content, committedAt));
        }
    }

    // Logs the type only: account events carry emails and phones.
    private void deliver(NotificationType type, Runnable save) {
        executor.execute(() -> {
            try {
                saves.acquire();
                try {
                    save.run();
                } finally {
                    saves.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Shut down before saving the {} notifications", type);
            } catch (RuntimeException e) {
                log.error("Could not save the {} notifications", type, e);
            }
        });
    }

    // PostgreSQL stores microseconds; truncating keeps responses equal to later reads.
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    @PreDestroy
    void shutdown() {
        executor.close();
    }
}
