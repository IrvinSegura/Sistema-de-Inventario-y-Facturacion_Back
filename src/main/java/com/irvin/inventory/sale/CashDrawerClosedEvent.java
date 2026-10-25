package com.irvin.inventory.sale;

import java.math.BigDecimal;
import java.time.LocalDate;

// Published on every closing (the responsible closing their own drawer included); heard after commit.
// difference is counted minus expected cash.
public record CashDrawerClosedEvent(Long drawerId, Long responsibleId, Long closedById, LocalDate businessDate,
        BigDecimal difference) {
}
