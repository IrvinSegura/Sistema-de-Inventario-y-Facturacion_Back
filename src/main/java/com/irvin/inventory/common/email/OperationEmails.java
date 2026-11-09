package com.irvin.inventory.common.email;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.irvin.inventory.config.StoreProperties;
import org.springframework.stereotype.Component;

// Builds the emails that repeat a business notice from the app (a cancelled sale or purchase, a cash
// drawer assigned or closed), for whoever isn't signed in when it happens. Keys per reason in
// emails_<language>.properties, as in AccountEmails. actor is who made the change.
@Component
public class OperationEmails {

    private static final String CANCELLED_ICON = "×";
    private static final String ASSIGNED_ICON = "+";
    private static final String CLOSED_ICON = "=";
    private static final String CLOSED_OFF_ICON = "±";

    private final String appUrl;
    private final StoreProperties store;
    private final Clock clock;

    public OperationEmails(EmailProperties properties, StoreProperties store, Clock clock) {
        String url = properties.appUrl() == null ? "" : properties.appUrl().strip();
        this.appUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.store = store;
        this.clock = clock;
    }

    // To the seller who made the sale.
    public EmailMessage saleCancelled(String to, String name, String actor, String saleNumber, BigDecimal total,
            String reason, Locale locale) {
        EmailTexts texts = texts(locale);
        EmailDraft draft = start(texts, "sale-cancelled", EmailDraft.Tone.WARNING, CANCELLED_ICON, name,
                values(texts, actor, Map.of("number", saleNumber)));
        draft.amountLabel = texts.get("label.sale-total");
        draft.amount = texts.money(total);
        draft.details.add(new EmailDraft.Detail(texts.get("label.sale"), "#" + saleNumber));
        addReason(draft, texts, reason);
        draft.details.add(new EmailDraft.Detail(texts.get("label.cancelled-at"), texts.dateTime(clock.instant())));
        addAction(draft, texts, "action.cash-drawer-history", "/cash-drawer/history");
        return EmailLayout.message(to, draft);
    }

    // To the other admins and supervisors.
    public EmailMessage purchaseCancelled(String to, String name, String actor, String supplier, BigDecimal total,
            String reason, Locale locale) {
        EmailTexts texts = texts(locale);
        EmailDraft draft = start(texts, "purchase-cancelled", EmailDraft.Tone.WARNING, CANCELLED_ICON, name,
                values(texts, actor, Map.of("supplier", supplier)));
        draft.amountLabel = texts.get("label.purchase-total");
        draft.amount = texts.money(total);
        draft.details.add(new EmailDraft.Detail(texts.get("label.supplier"), supplier));
        addReason(draft, texts, reason);
        draft.details.add(new EmailDraft.Detail(texts.get("label.cancelled-at"), texts.dateTime(clock.instant())));
        addAction(draft, texts, "action.purchases", "/purchases");
        return EmailLayout.message(to, draft);
    }

    // To each seller assigned to the drawer.
    public EmailMessage cashDrawerAssigned(String to, String name, String actor, String responsible,
            BigDecimal openingFloat, Locale locale) {
        EmailTexts texts = texts(locale);
        EmailDraft draft = start(texts, "cash-drawer-assigned", EmailDraft.Tone.INFO, ASSIGNED_ICON, name,
                values(texts, actor, Map.of("responsible", responsible)));
        draft.amountLabel = texts.get("label.opening-float");
        draft.amount = texts.money(openingFloat);
        draft.details.add(new EmailDraft.Detail(texts.get("label.responsible"), responsible));
        draft.details.add(new EmailDraft.Detail(texts.get("label.opened-by"), actorName(texts, actor)));
        draft.details.add(new EmailDraft.Detail(texts.get("label.opened-at"), texts.dateTime(clock.instant())));
        addAction(draft, texts, "action.cash-drawer", "/cash-drawer");
        return EmailLayout.message(to, draft);
    }

    // To the drawer's responsible, when a manager closed it. difference is counted minus expected cash.
    public EmailMessage cashDrawerClosed(String to, String name, String actor, LocalDate businessDate,
            BigDecimal difference, Locale locale) {
        EmailTexts texts = texts(locale);
        boolean balanced = difference.signum() == 0;
        String reason = balanced ? "cash-drawer-closed" : "cash-drawer-closed-off";
        EmailDraft draft = start(texts, reason, balanced ? EmailDraft.Tone.SUCCESS : EmailDraft.Tone.WARNING,
                balanced ? CLOSED_ICON : CLOSED_OFF_ICON, name,
                values(texts, actor, Map.of("date", texts.date(businessDate))));
        draft.amountLabel = texts.get("label.difference");
        draft.amount = texts.signedMoney(difference);
        draft.details.add(new EmailDraft.Detail(texts.get("label.business-date"), texts.date(businessDate)));
        draft.details.add(new EmailDraft.Detail(texts.get("label.closed-by"), actorName(texts, actor)));
        draft.details.add(new EmailDraft.Detail(texts.get("label.closed-at"), texts.dateTime(clock.instant())));
        addAction(draft, texts, "action.cash-drawer-history", "/cash-drawer/history");
        return EmailLayout.message(to, draft);
    }

    private EmailTexts texts(Locale locale) {
        return new EmailTexts(locale, store.timeZone());
    }

    private static Map<String, String> values(EmailTexts texts, String actor, Map<String, String> others) {
        Map<String, String> values = new HashMap<>(others);
        values.put("actor", actorName(texts, actor));
        return values;
    }

    // An actor deleted meanwhile is still someone.
    private static String actorName(EmailTexts texts, String actor) {
        return actor == null ? texts.get("someone") : actor;
    }

    private static EmailDraft start(EmailTexts texts, String reason, EmailDraft.Tone tone, String icon, String name,
            Map<String, String> values) {
        Map<String, String> all = new HashMap<>(values);
        all.put("name", name);
        EmailDraft draft = new EmailDraft(texts, tone, icon, texts.get(reason + ".kicker"),
                texts.get(reason + ".subject", all), texts.get(reason + ".preheader", all),
                texts.get(reason + ".title", all), texts.get("greeting", all));
        draft.paragraphs.addAll(texts.paragraphs(reason + ".body", all));
        draft.calloutTitle = texts.get(reason + ".callout-title");
        draft.callout = texts.get(reason + ".callout");
        draft.footer.add(texts.get("footer.reason"));
        draft.footer.add(texts.get("footer.operations"));
        return draft;
    }

    private static void addReason(EmailDraft draft, EmailTexts texts, String reason) {
        if (reason != null && !reason.isBlank()) {
            draft.details.add(new EmailDraft.Detail(texts.get("label.reason"), reason));
        }
    }

    private void addAction(EmailDraft draft, EmailTexts texts, String labelKey, String path) {
        if (!appUrl.isEmpty()) {
            draft.action = new EmailDraft.Action(texts.get(labelKey), appUrl + path);
            draft.linkHint = texts.get("link-hint");
        }
    }
}
