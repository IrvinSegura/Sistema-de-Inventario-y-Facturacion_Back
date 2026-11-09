package com.irvin.inventory.user;

import com.irvin.inventory.common.email.EmailMessage;
import com.irvin.inventory.common.email.SmtpEmailSender;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

// Over the same SMTP server and sender as the reset codes (EmailPasswordResetSender).
@Component
@ConditionalOnExpression("!'${spring.mail.host:}'.isBlank()")
@RequiredArgsConstructor
public class EmailAccountNoticeSender implements AccountNoticeSender {

    private final SmtpEmailSender emailSender;

    @Override
    public void send(EmailMessage notice) {
        emailSender.send(notice);
    }
}
