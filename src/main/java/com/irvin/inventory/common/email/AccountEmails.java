package com.irvin.inventory.common.email;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

import com.irvin.inventory.config.StoreProperties;
import org.springframework.stereotype.Component;

// Builds the emails about an account; each reason has its keys in emails_<language>.properties, such as
// reset-code.title. New values arrive already masked.
@Component
public class AccountEmails {

    // Typographic marks rather than emoji, which look different in every client.
    private static final String CODE_ICON = "✱";
    private static final String EMAIL_ICON = "@";
    private static final String PHONE_CHANGED_ICON = "⇄";
    private static final String PHONE_REMOVED_ICON = "−";
    private static final String PASSWORD_ICON = "↺";
    private static final String WELCOME_ICON = "✦";
    private static final String ROLE_ICON = "⇅";
    private static final String DEACTIVATED_ICON = "‖";
    private static final String REACTIVATED_ICON = "✓";
    private static final String PASSWORD_CHANGED_ICON = "✱";

    private final String appUrl;
    private final StoreProperties store;
    private final Clock clock;

    public AccountEmails(EmailProperties properties, StoreProperties store, Clock clock) {
        String url = properties.appUrl() == null ? "" : properties.appUrl().strip();
        this.appUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.store = store;
        this.clock = clock;
    }

    // No button: the web keeps the reset in progress in memory, so opening a link starts over at asking for
    // a code, and a new code cancels this one.
    public EmailMessage resetCode(String to, String name, String code, Duration ttl, Locale locale) {
        EmailTexts texts = texts(locale);
        EmailDraft draft = start(texts, "reset-code", EmailDraft.Tone.INFO, CODE_ICON, name);
        draft.highlightLabel = texts.get("reset-code.highlight-label");
        draft.highlight = code;
        draft.details.add(new EmailDraft.Detail(texts.get("label.expires-in"),
                texts.get("label.minutes", Map.of("minutes", String.valueOf(ttl.toMinutes())))));
        draft.details.add(requestedAt(texts));
        return EmailLayout.message(to, draft);
    }

    public EmailMessage emailChanged(String to, String name, String maskedEmail, Locale locale) {
        EmailTexts texts = texts(locale);
        EmailDraft draft = start(texts, "email-changed", EmailDraft.Tone.WARNING, EMAIL_ICON, name);
        draft.details.add(new EmailDraft.Detail(texts.get("label.new-email"), maskedEmail));
        draft.details.add(changedAt(texts));
        addSignIn(draft, texts);
        return EmailLayout.message(to, draft);
    }

    public EmailMessage phoneChanged(String to, String name, String maskedPhone, Locale locale) {
        EmailTexts texts = texts(locale);
        EmailDraft draft = start(texts, "phone-changed", EmailDraft.Tone.WARNING, PHONE_CHANGED_ICON, name);
        draft.details.add(new EmailDraft.Detail(texts.get("label.new-phone"), maskedPhone));
        draft.details.add(changedAt(texts));
        addSignIn(draft, texts);
        return EmailLayout.message(to, draft);
    }

    public EmailMessage phoneRemoved(String to, String name, Locale locale) {
        EmailTexts texts = texts(locale);
        EmailDraft draft = start(texts, "phone-removed", EmailDraft.Tone.WARNING, PHONE_REMOVED_ICON, name);
        draft.details.add(changedAt(texts));
        addSignIn(draft, texts);
        return EmailLayout.message(to, draft);
    }

    public EmailMessage passwordReset(String to, String name, Locale locale) {
        EmailTexts texts = texts(locale);
        EmailDraft draft = start(texts, "password-reset", EmailDraft.Tone.WARNING, PASSWORD_ICON, name);
        draft.details.add(changedAt(texts));
        addSignIn(draft, texts);
        return EmailLayout.message(to, draft);
    }

    // Never the password: the admin who set it hands it over in person.
    public EmailMessage accountCreated(String to, String name, String role, Locale locale) {
        EmailTexts texts = texts(locale);
        EmailDraft draft = start(texts, "account-created", EmailDraft.Tone.SUCCESS, WELCOME_ICON, name);
        draft.details.add(new EmailDraft.Detail(texts.get("label.email"), to));
        draft.details.add(new EmailDraft.Detail(texts.get("label.role"), texts.get("role." + role)));
        addSignIn(draft, texts);
        return EmailLayout.message(to, draft);
    }

    // role is a Role name such as SUPERVISOR. The change ended every session, hence the sign-in button.
    public EmailMessage roleChanged(String to, String name, String role, Locale locale) {
        EmailTexts texts = texts(locale);
        EmailDraft draft = start(texts, "role-changed", EmailDraft.Tone.INFO, ROLE_ICON, name);
        draft.details.add(new EmailDraft.Detail(texts.get("label.new-role"), texts.get("role." + role)));
        draft.details.add(changedAt(texts));
        addSignIn(draft, texts);
        return EmailLayout.message(to, draft);
    }

    // No button: the account can't sign in.
    public EmailMessage accountDeactivated(String to, String name, Locale locale) {
        EmailTexts texts = texts(locale);
        EmailDraft draft = start(texts, "account-deactivated", EmailDraft.Tone.WARNING, DEACTIVATED_ICON, name);
        draft.details.add(changedAt(texts));
        return EmailLayout.message(to, draft);
    }

    public EmailMessage accountReactivated(String to, String name, Locale locale) {
        EmailTexts texts = texts(locale);
        EmailDraft draft = start(texts, "account-reactivated", EmailDraft.Tone.SUCCESS, REACTIVATED_ICON, name);
        draft.details.add(changedAt(texts));
        addSignIn(draft, texts);
        return EmailLayout.message(to, draft);
    }

    // The owner changed it from their account; if they didn't, someone else knew the old one.
    public EmailMessage passwordChanged(String to, String name, Locale locale) {
        return passwordNotice(to, name, "password-changed", locale);
    }

    // Through a code sent to the email or the phone.
    public EmailMessage passwordRecovered(String to, String name, Locale locale) {
        return passwordNotice(to, name, "password-recovered", locale);
    }

    private EmailMessage passwordNotice(String to, String name, String reason, Locale locale) {
        EmailTexts texts = texts(locale);
        EmailDraft draft = start(texts, reason, EmailDraft.Tone.WARNING, PASSWORD_CHANGED_ICON, name);
        draft.details.add(changedAt(texts));
        addSignIn(draft, texts);
        return EmailLayout.message(to, draft);
    }

    private EmailTexts texts(Locale locale) {
        return new EmailTexts(locale, store.timeZone());
    }

    private EmailDraft start(EmailTexts texts, String reason, EmailDraft.Tone tone, String icon, String name) {
        Map<String, String> values = Map.of("name", name);
        EmailDraft draft = new EmailDraft(texts, tone, icon, texts.get(reason + ".kicker"),
                texts.get(reason + ".subject"), texts.get(reason + ".preheader"), texts.get(reason + ".title"),
                texts.get("greeting", values));
        draft.paragraphs.addAll(texts.paragraphs(reason + ".body", values));
        draft.calloutTitle = texts.get(reason + ".callout-title");
        draft.callout = texts.get(reason + ".callout");
        draft.footer.add(texts.get("footer.reason"));
        draft.footer.add(texts.get("footer.security"));
        return draft;
    }

    private EmailDraft.Detail requestedAt(EmailTexts texts) {
        return new EmailDraft.Detail(texts.get("label.requested-at"), texts.dateTime(clock.instant()));
    }

    private EmailDraft.Detail changedAt(EmailTexts texts) {
        return new EmailDraft.Detail(texts.get("label.changed-at"), texts.dateTime(clock.instant()));
    }

    // Only pages the owner can open anyway, never with a secret in the link.
    private void addSignIn(EmailDraft draft, EmailTexts texts) {
        if (!appUrl.isEmpty()) {
            draft.action = new EmailDraft.Action(texts.get("action.sign-in"), appUrl + "/login");
            draft.linkHint = texts.get("link-hint");
        }
    }
}
