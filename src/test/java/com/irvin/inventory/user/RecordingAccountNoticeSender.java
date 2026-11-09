package com.irvin.inventory.user;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.irvin.inventory.common.email.EmailMessage;

// Keeps account notices instead of emailing them, so tests can read them. They're sent in the
// background after the change commits, hence the waits.
public class RecordingAccountNoticeSender implements AccountNoticeSender {

    private final Map<String, BlockingQueue<EmailMessage>> byEmail = new ConcurrentHashMap<>();

    @Override
    public void send(EmailMessage notice) {
        queue(notice.to()).add(notice);
    }

    public EmailMessage next(String email) throws InterruptedException {
        EmailMessage notice = queue(email).poll(5, TimeUnit.SECONDS);
        if (notice == null) {
            throw new AssertionError("No account notice was sent to " + email);
        }
        return notice;
    }

    public boolean nothingSentTo(String email) throws InterruptedException {
        return queue(email).poll(500, TimeUnit.MILLISECONDS) == null;
    }

    private BlockingQueue<EmailMessage> queue(String email) {
        return byEmail.computeIfAbsent(email, key -> new LinkedBlockingQueue<>());
    }
}
