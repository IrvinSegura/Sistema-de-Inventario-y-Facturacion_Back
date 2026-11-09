package com.irvin.inventory.common.email;

// An email with its plain-text and HTML forms. It may hold a reset code, so it's built right before
// sending and never stored or logged.
public record EmailMessage(String to, String subject, String body, String html) {
}
