package com.irvin.inventory.common.email;

import java.nio.charset.StandardCharsets;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

// Sends over SMTP (spring.mail.*). Each email carries both forms, and the client shows the best one it
// can. Active only when an SMTP host is configured.
@Component
@ConditionalOnExpression("!'${spring.mail.host:}'.isBlank()")
@RequiredArgsConstructor
public class SmtpEmailSender {

    private final JavaMailSender mailSender;
    private final EmailProperties properties;

    public void send(EmailMessage message) {
        mailSender.send(mime -> {
            MimeMessageHelper mail = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
            mail.setFrom(properties.from());
            mail.setTo(message.to());
            mail.setSubject(message.subject());
            mail.setText(message.body(), message.html());
        });
    }
}
