package com.irvin.inventory.user;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.irvin.inventory.common.email.AccountEmails;
import com.irvin.inventory.common.email.EmailMessage;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

// Tells the owner about every change to their account: an admin creating it, changing its email, phone
// (both recover the password), role or status, or resetting its password, and the owner's own password
// changes, so a takeover doesn't go unnoticed. After commit and in the background, so a slow or
// failing mail server never holds or undoes the change. New values are masked: the old address may be
// the compromised one.
@Slf4j
@Component
public class AccountChangeNotifier {

    // Users have no language setting (the web keeps it in the browser); Spanish is the web's default.
    private static final Locale NOTICE_LOCALE = Locale.of("es");

    private final List<AccountNoticeSender> senders;
    private final AccountEmails emails;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public AccountChangeNotifier(List<AccountNoticeSender> senders, AccountEmails emails) {
        this.senders = senders;
        this.emails = emails;
    }

    @TransactionalEventListener
    public void onAccountChanged(AccountChangedEvent event) {
        executor.execute(() -> deliver(event));
    }

    private EmailMessage notice(AccountChangedEvent event) {
        String to = event.recipient();
        String name = event.name();
        return switch (event.kind()) {
            case EMAIL_CHANGED -> emails.emailChanged(to, name, maskEmail(event.newValue()), NOTICE_LOCALE);
            case PHONE_CHANGED -> event.newValue() == null
                    ? emails.phoneRemoved(to, name, NOTICE_LOCALE)
                    : emails.phoneChanged(to, name, maskPhone(event.newValue()), NOTICE_LOCALE);
            case PASSWORD_RESET -> emails.passwordReset(to, name, NOTICE_LOCALE);
            case ACCOUNT_CREATED -> emails.accountCreated(to, name, event.newValue(), NOTICE_LOCALE);
            case ROLE_CHANGED -> emails.roleChanged(to, name, event.newValue(), NOTICE_LOCALE);
            case DEACTIVATED -> emails.accountDeactivated(to, name, NOTICE_LOCALE);
            case REACTIVATED -> emails.accountReactivated(to, name, NOTICE_LOCALE);
            case PASSWORD_CHANGED -> emails.passwordChanged(to, name, NOTICE_LOCALE);
            case PASSWORD_RECOVERED -> emails.passwordRecovered(to, name, NOTICE_LOCALE);
        };
    }

    // "jane@example.com" -> "j***@example.com".
    static String maskEmail(String email) {
        int at = email.indexOf('@');
        return at <= 0 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }

    // "+525512345678" -> "***5678".
    static String maskPhone(String phone) {
        return "***" + phone.substring(Math.max(0, phone.length() - 4));
    }

    private void deliver(AccountChangedEvent event) {
        if (senders.isEmpty()) {
            log.warn("No email provider configured; an account change notice was not sent");
            return;
        }
        try {
            EmailMessage notice = notice(event);
            senders.forEach(sender -> sender.send(notice));
        } catch (RuntimeException e) {
            log.error("Could not send an account change notice", e);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.close();
    }
}
