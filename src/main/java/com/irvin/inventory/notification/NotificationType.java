package com.irvin.inventory.notification;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import com.irvin.inventory.user.Role;

// What happened, and which of the notification's values it fills (the rest stay null). Keep in step
// with ck_notification_type and NotificationController's description.
public enum NotificationType {
    // To the sale's seller. subjectId: sale; subjectLabel: sale number; amount: total; reason.
    SALE_CANCELLED,
    // To the other managers. subjectId: purchase; subjectLabel: supplier; amount: total (a cost, which
    // sellers never see); reason.
    PURCHASE_CANCELLED(true),
    // To each assigned seller. subjectId: drawer; subjectLabel: the responsible's name; amount: opening float.
    CASH_DRAWER_ASSIGNED,
    // To the responsible, when a manager closes their drawer. subjectId: drawer; subjectLabel: business
    // date (ISO); amount: counted minus expected cash.
    CASH_DRAWER_CLOSED,
    // To the account's owner, when an admin changes it. No values.
    EMAIL_CHANGED,
    PHONE_CHANGED,
    PASSWORD_RESET;

    private static final Set<NotificationType> ALL = Set.of(values());
    private static final Set<NotificationType> NOT_MANAGER_ONLY = Arrays.stream(values())
            .filter(type -> !type.managerOnly)
            .collect(Collectors.toUnmodifiableSet());

    // Hidden, not deleted, once the recipient stops being a manager: filtering on read can't race with
    // a save in flight.
    private final boolean managerOnly;

    NotificationType() {
        this(false);
    }

    NotificationType(boolean managerOnly) {
        this.managerOnly = managerOnly;
    }

    // What a user with this role may read.
    public static Set<NotificationType> visibleTo(Role role) {
        return role.isManager() ? ALL : NOT_MANAGER_ONLY;
    }
}
