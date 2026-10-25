package com.irvin.inventory.sale;

import java.math.BigDecimal;

// Published by SaleService.cancel; heard after commit, so a rolled-back cancellation tells no one.
public record SaleCancelledEvent(Long saleId, Long saleNumber, Long sellerId, Long cancelledById, BigDecimal total,
        String reason) {
}
