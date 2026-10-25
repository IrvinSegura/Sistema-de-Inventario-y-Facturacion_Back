package com.irvin.inventory.notification;

import java.math.BigDecimal;
import java.time.LocalDate;

// The values a notification shows, one factory per type so they match NotificationType's contract.
public record NotificationContent(Long subjectId, String subjectLabel, BigDecimal amount, String reason) {

    public static final NotificationContent NONE = new NotificationContent(null, null, null, null);

    public static NotificationContent saleCancelled(Long saleId, Long saleNumber, BigDecimal total, String reason) {
        return new NotificationContent(saleId, String.valueOf(saleNumber), total, reason);
    }

    public static NotificationContent purchaseCancelled(Long purchaseId, String supplierName, BigDecimal total,
            String reason) {
        return new NotificationContent(purchaseId, supplierName, total, reason);
    }

    public static NotificationContent cashDrawerAssigned(Long drawerId, String responsibleName,
            BigDecimal openingFloat) {
        return new NotificationContent(drawerId, responsibleName, openingFloat, null);
    }

    // ISO date, which the web parses.
    public static NotificationContent cashDrawerClosed(Long drawerId, LocalDate businessDate, BigDecimal difference) {
        return new NotificationContent(drawerId, businessDate.toString(), difference, null);
    }
}
