package com.irvin.inventory.purchasing;

import java.math.BigDecimal;

// Published by PurchaseService.cancel; heard after commit, so a rolled-back cancellation tells no one.
public record PurchaseCancelledEvent(Long purchaseId, String supplierName, Long cancelledById, BigDecimal total,
        String reason) {
}
