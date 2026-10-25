package com.irvin.inventory.sale;

import java.math.BigDecimal;
import java.util.List;

// Published when a manager opens a drawer for sellers; heard after commit.
public record CashDrawerAssignedEvent(Long drawerId, List<Long> memberIds, String responsibleName, Long openedById,
        BigDecimal openingFloat) {
}
