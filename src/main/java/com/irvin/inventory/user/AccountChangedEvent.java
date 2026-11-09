package com.irvin.inventory.user;

// A change the account's owner must hear about; after it commits, AccountChangeNotifier emails `recipient`,
// and for an admin's email, phone or password change NotificationListener also notifies `userId` in the app.
// adminId is whoever made the change: the owner themself for PASSWORD_CHANGED and PASSWORD_RECOVERED.
public record AccountChangedEvent(Kind kind, Long userId, Long adminId, String name, String recipient,
        String newValue) {

    public enum Kind {
        // To the old address; newValue is the new email.
        EMAIL_CHANGED,
        // To the account's email; newValue is the new phone, or null when it was removed.
        PHONE_CHANGED,
        // To the account's email; no newValue.
        PASSWORD_RESET,
        // Email only, to the account's email. newValue is the Role name.
        ACCOUNT_CREATED,
        // Email only. newValue is the new Role name.
        ROLE_CHANGED,
        // Email only; no newValue.
        DEACTIVATED,
        REACTIVATED,
        // Email only, after the owner changed their password or recovered it with a code; no newValue.
        PASSWORD_CHANGED,
        PASSWORD_RECOVERED
    }
}
