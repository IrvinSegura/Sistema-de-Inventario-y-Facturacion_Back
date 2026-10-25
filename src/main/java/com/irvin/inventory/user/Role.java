package com.irvin.inventory.user;

import java.util.Set;

public enum Role {
    // Everything, including user accounts.
    ADMIN,
    // Runs the store like an admin (catalog, stock, purchases, every seller's sales, cancellations and
    // reports) but can't manage user accounts.
    SUPERVISOR,
    // Sells and sees only their own sales and drawer.
    SELLER;

    // Admins and supervisors see every seller's data and may change what sellers can't.
    public static final Set<Role> MANAGERS = Set.of(ADMIN, SUPERVISOR);

    public boolean isManager() {
        return MANAGERS.contains(this);
    }
}
