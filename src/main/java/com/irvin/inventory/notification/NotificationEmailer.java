package com.irvin.inventory.notification;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import com.irvin.inventory.common.email.EmailMessage;
import com.irvin.inventory.common.email.OperationEmails;
import com.irvin.inventory.common.email.SmtpEmailSender;
import com.irvin.inventory.user.User;
import com.irvin.inventory.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

// Emails the business notices to the same people the app notifies, so they hear about them while signed
// out. Account notices have their own emails (AccountChangeNotifier). Without an SMTP server, nothing.
@Slf4j
@Component
class NotificationEmailer {

    private static final Set<NotificationType> EMAILED = Set.of(NotificationType.SALE_CANCELLED,
            NotificationType.PURCHASE_CANCELLED, NotificationType.CASH_DRAWER_ASSIGNED,
            NotificationType.CASH_DRAWER_CLOSED);

    // Users have no language setting (the web keeps it in the browser); Spanish is the web's default.
    private static final Locale NOTICE_LOCALE = Locale.of("es");

    private final UserRepository userRepository;
    private final OperationEmails emails;
    private final ObjectProvider<SmtpEmailSender> smtp;

    NotificationEmailer(UserRepository userRepository, OperationEmails emails, ObjectProvider<SmtpEmailSender> smtp) {
        this.userRepository = userRepository;
        this.emails = emails;
        this.smtp = smtp;
    }

    // Reads the addresses, so the caller builds them while it still holds its database slot and sends
    // them after. Someone deactivated since the change gets nothing.
    List<EmailMessage> messages(NotificationType type, Collection<Long> recipientIds, Long actorId,
            NotificationContent content) {
        if (!EMAILED.contains(type) || recipientIds.isEmpty() || smtp.getIfAvailable() == null) {
            return List.of();
        }
        String actor = actorId == null ? null : userRepository.findById(actorId).map(User::getName).orElse(null);
        return userRepository.findAllById(recipientIds).stream()
                .filter(User::isActive)
                .map(user -> message(type, user, actor, content))
                .filter(Objects::nonNull)
                .toList();
    }

    private EmailMessage message(NotificationType type, User to, String actor, NotificationContent content) {
        String email = to.getEmail();
        String name = to.getName();
        return switch (type) {
            case SALE_CANCELLED -> emails.saleCancelled(email, name, actor, content.subjectLabel(), content.amount(),
                    content.reason(), NOTICE_LOCALE);
            case PURCHASE_CANCELLED -> emails.purchaseCancelled(email, name, actor, content.subjectLabel(),
                    content.amount(), content.reason(), NOTICE_LOCALE);
            case CASH_DRAWER_ASSIGNED -> emails.cashDrawerAssigned(email, name, actor, content.subjectLabel(),
                    content.amount(), NOTICE_LOCALE);
            case CASH_DRAWER_CLOSED -> emails.cashDrawerClosed(email, name, actor,
                    LocalDate.parse(content.subjectLabel()), content.amount(), NOTICE_LOCALE);
            default -> null;
        };
    }

    // One by one, so a rejected address doesn't stop the rest. Logs no address.
    void send(List<EmailMessage> messages) {
        SmtpEmailSender sender = smtp.getIfAvailable();
        if (sender == null) {
            return;
        }
        for (EmailMessage message : messages) {
            try {
                sender.send(message);
            } catch (RuntimeException e) {
                log.error("Could not email a notification", e);
            }
        }
    }
}
