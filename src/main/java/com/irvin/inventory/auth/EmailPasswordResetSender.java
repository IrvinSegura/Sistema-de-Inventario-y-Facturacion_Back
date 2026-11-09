package com.irvin.inventory.auth;

import com.irvin.inventory.common.email.AccountEmails;
import com.irvin.inventory.common.email.SmtpEmailSender;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

// Emails the code in the language of the request. Active only when an SMTP host is configured.
@Component
@ConditionalOnExpression("!'${spring.mail.host:}'.isBlank()")
@RequiredArgsConstructor
public class EmailPasswordResetSender implements PasswordResetSender {

    private final AccountEmails emails;
    private final SmtpEmailSender emailSender;

    @Override
    public boolean supports(ResetChannel channel) {
        return channel == ResetChannel.EMAIL;
    }

    @Override
    public void send(ResetMessage message) {
        emailSender.send(emails.resetCode(message.email(), message.name(), message.code(), message.ttl(),
                message.locale()));
    }
}
