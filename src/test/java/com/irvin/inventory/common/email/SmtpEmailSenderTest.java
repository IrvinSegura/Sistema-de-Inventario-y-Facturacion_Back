package com.irvin.inventory.common.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessagePreparator;

class SmtpEmailSenderTest {

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final SmtpEmailSender sender = new SmtpEmailSender(mailSender,
            new EmailProperties("Inventario <no-reply@example.com>", ""));

    private MimeMessage sent(EmailMessage message) throws Exception {
        sender.send(message);
        ArgumentCaptor<MimeMessagePreparator> preparator = ArgumentCaptor.forClass(MimeMessagePreparator.class);
        verify(mailSender).send(preparator.capture());
        MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()));
        preparator.getValue().prepare(mime);
        mime.saveChanges();
        // Through the wire format, as a client would read it.
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        mime.writeTo(wire);
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(wire.toByteArray()));
    }

    private static void collectLeaves(Part part, List<Part> leaves) throws Exception {
        if (part.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart child = multipart.getBodyPart(i);
                collectLeaves(child, leaves);
            }
        } else {
            leaves.add(part);
        }
    }

    @Test
    void sendsBothFormsWithTheirAccentsIntact() throws Exception {
        MimeMessage mime = sent(new EmailMessage("ana@example.com", "Tu código", "Hola, Ana López", "<p>Hola, Ana López</p>"));

        assertThat(mime.getFrom()[0]).hasToString("Inventario <no-reply@example.com>");
        assertThat(mime.getAllRecipients()[0]).hasToString("ana@example.com");
        assertThat(mime.getSubject()).isEqualTo("Tu código");

        List<Part> leaves = new ArrayList<>();
        collectLeaves(mime, leaves);
        assertThat(leaves).hasSize(2);
        assertThat(leaves.get(0).isMimeType("text/plain")).isTrue();
        assertThat(leaves.get(0).getContent()).isEqualTo("Hola, Ana López");
        assertThat(leaves.get(1).isMimeType("text/html")).isTrue();
        assertThat(leaves.get(1).getContent()).isEqualTo("<p>Hola, Ana López</p>");
    }
}
