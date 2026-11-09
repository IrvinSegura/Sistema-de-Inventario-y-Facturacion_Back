package com.irvin.inventory.common.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.irvin.inventory.auth.EmailPasswordResetSender;
import com.irvin.inventory.user.EmailAccountNoticeSender;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSender;

// Integration tests run without an SMTP host, so they never create these beans.
class EmailSendersActivationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(JavaMailSender.class, () -> mock(JavaMailSender.class))
            .withBean(AccountEmails.class, () -> mock(AccountEmails.class))
            .withBean(EmailProperties.class, () -> new EmailProperties("Inventory <no-reply@example.com>", ""))
            .withUserConfiguration(SmtpEmailSender.class, EmailPasswordResetSender.class,
                    EmailAccountNoticeSender.class);

    @Test
    void withAnSmtpHostEmailsAreSent() {
        runner.withPropertyValues("spring.mail.host=smtp.example.com").run(context -> {
            assertThat(context).hasSingleBean(SmtpEmailSender.class);
            assertThat(context).hasSingleBean(EmailPasswordResetSender.class);
            assertThat(context).hasSingleBean(EmailAccountNoticeSender.class);
        });
    }

    @Test
    void withABlankOrMissingHostNoEmailSenderExists() {
        ApplicationContextRunner blank = runner.withPropertyValues("spring.mail.host=");
        for (ApplicationContextRunner withoutHost : new ApplicationContextRunner[] {runner, blank}) {
            withoutHost.run(context -> {
                assertThat(context).doesNotHaveBean(SmtpEmailSender.class);
                assertThat(context).doesNotHaveBean(EmailPasswordResetSender.class);
                assertThat(context).doesNotHaveBean(EmailAccountNoticeSender.class);
            });
        }
    }
}
