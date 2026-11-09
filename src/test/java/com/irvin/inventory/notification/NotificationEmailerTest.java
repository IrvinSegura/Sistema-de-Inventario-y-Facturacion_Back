package com.irvin.inventory.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.irvin.inventory.common.email.EmailMessage;
import com.irvin.inventory.common.email.EmailProperties;
import com.irvin.inventory.common.email.OperationEmails;
import com.irvin.inventory.common.email.SmtpEmailSender;
import com.irvin.inventory.config.StoreProperties;
import com.irvin.inventory.user.User;
import com.irvin.inventory.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class NotificationEmailerTest {

    private static final NotificationContent SALE = NotificationContent.saleCancelled(7L, 1042L,
            new BigDecimal("99.90"), null);

    private final UserRepository users = mock(UserRepository.class);
    private final SmtpEmailSender smtp = mock(SmtpEmailSender.class);
    private final OperationEmails emails = new OperationEmails(new EmailProperties("x@example.com", ""),
            new StoreProperties(ZoneId.of("America/Mexico_City")),
            Clock.fixed(Instant.parse("2026-11-04T18:30:00Z"), ZoneOffset.UTC));

    @SuppressWarnings("unchecked")
    private NotificationEmailer emailer(SmtpEmailSender sender) {
        ObjectProvider<SmtpEmailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        return new NotificationEmailer(users, emails, provider);
    }

    private static User user(Long id, String name, String email, boolean active) {
        User user = new User();
        user.setId(id);
        user.setName(name);
        user.setEmail(email);
        user.setActive(active);
        return user;
    }

    @Test
    void emailsEachActiveRecipientNamingWhoActed() {
        when(users.findById(1L)).thenReturn(Optional.of(user(1L, "Luis", "luis@test.com", true)));
        when(users.findAllById(List.of(2L, 3L))).thenReturn(List.of(user(2L, "Ana", "ana@test.com", true),
                user(3L, "Bea", "bea@test.com", false)));

        List<EmailMessage> messages = emailer(smtp).messages(NotificationType.SALE_CANCELLED, List.of(2L, 3L), 1L,
                SALE);

        assertThat(messages).singleElement().satisfies(message -> {
            assertThat(message.to()).isEqualTo("ana@test.com");
            assertThat(message.body()).contains("Hola, Ana:", "Luis canceló la venta #1042", "$99.90");
        });
    }

    @Test
    void theClosedDrawerReadsItsDayBack() {
        when(users.findAllById(List.of(2L))).thenReturn(List.of(user(2L, "Ana", "ana@test.com", true)));

        List<EmailMessage> messages = emailer(smtp).messages(NotificationType.CASH_DRAWER_CLOSED, List.of(2L), null,
                NotificationContent.cashDrawerClosed(5L, LocalDate.of(2026, 11, 3), BigDecimal.ZERO));

        assertThat(messages).singleElement().extracting(EmailMessage::subject)
                .isEqualTo("Se cerró tu caja del 3 de noviembre de 2026");
    }

    // AccountChangeNotifier emails those, and without SMTP there's nothing to build.
    @Test
    void accountNoticesAndAMissingMailServerSendNothing() {
        assertThat(emailer(smtp).messages(NotificationType.PASSWORD_RESET, List.of(2L), 1L, NotificationContent.NONE))
                .isEmpty();
        assertThat(emailer(null).messages(NotificationType.SALE_CANCELLED, List.of(2L), 1L, SALE)).isEmpty();
        verifyNoInteractions(users);
    }

    @Test
    void oneRejectedAddressDoesNotStopTheRest() {
        EmailMessage first = new EmailMessage("a@test.com", "s", "b", "h");
        EmailMessage second = new EmailMessage("b@test.com", "s", "b", "h");
        doThrow(new IllegalStateException("rejected")).when(smtp).send(first);

        emailer(smtp).send(List.of(first, second));

        verify(smtp, times(2)).send(any());
        verify(smtp).send(second);
    }
}
