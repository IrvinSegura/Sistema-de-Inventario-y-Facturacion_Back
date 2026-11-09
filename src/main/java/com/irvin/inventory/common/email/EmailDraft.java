package com.irvin.inventory.common.email;

import java.util.ArrayList;
import java.util.List;

// What one email says, before EmailLayout gives it its forms. Every value is plain text, including what
// users wrote such as their names: the layout escapes it.
final class EmailDraft {

    // Sets the colors of the icon and the security notice.
    enum Tone {
        INFO,
        SUCCESS,
        WARNING
    }

    record Detail(String label, String value) {
    }

    record Action(String label, String url) {
    }

    final String language;
    final Tone tone;
    final String icon;
    final String kicker;
    final String brand;
    final String brandMark;
    final String signOff;
    final String subject;
    final String preheader;
    final String title;
    final String greeting;
    final List<String> paragraphs = new ArrayList<>();
    final List<Detail> details = new ArrayList<>();
    final List<String> footer = new ArrayList<>();
    String highlightLabel;
    String highlight;
    // A sum of money shown large, above the details; never together with the highlight.
    String amountLabel;
    String amount;
    Action action;
    String linkHint;
    String calloutTitle;
    String callout;

    // The icon is a typographic mark, never an emoji: emoji look different in every client and some show
    // them as empty boxes. The kicker is the short label above the title that says what kind of email it is.
    EmailDraft(EmailTexts texts, Tone tone, String icon, String kicker, String subject, String preheader,
            String title, String greeting) {
        this.language = texts.language();
        this.tone = tone;
        this.icon = icon;
        this.kicker = kicker;
        this.brand = texts.get("brand");
        this.brandMark = texts.get("brand-mark");
        this.signOff = texts.get("sign-off");
        this.subject = subject;
        this.preheader = preheader;
        this.title = title;
        this.greeting = greeting;
    }
}
