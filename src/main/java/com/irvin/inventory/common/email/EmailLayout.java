package com.irvin.inventory.common.email;

import java.util.Map;
import java.util.StringJoiner;

// Turns an EmailDraft into the email's two forms. The HTML is designed as a printed business document, in
// the web client's black and white: a letterhead over a heavy rule, the facts in a table ruled like an
// invoice, and codes in a monospaced font so each character can be read apart.
//
// It follows what mail clients actually support: nested tables, inline styles and system fonts. Nothing
// depends on an image, because most clients block them by default; icons are typographic marks, which
// unlike emoji look the same everywhere. Light only, because clients that invert colors on their own would
// undo a dark version anyway.
//
// Every value is escaped on its way in, so a name such as <b>Ana</b> shows up as written and can't add
// markup or links to an email sent from our address.
final class EmailLayout {

    private record Colors(String accent, String soft) {
    }

    private static final Map<EmailDraft.Tone, Colors> TONES = Map.of(
            EmailDraft.Tone.INFO, new Colors("#171717", "#f5f5f5"),
            EmailDraft.Tone.SUCCESS, new Colors("#047857", "#d1fae5"),
            EmailDraft.Tone.WARNING, new Colors("#b45309", "#fef3c7"));

    private static final String FONT = "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";
    private static final String CODE_FONT = "ui-monospace,SFMono-Regular,Menlo,Consolas,'Liberation Mono',monospace";
    private static final String INK = "#171717";
    private static final String TEXT = "#0a0a0a";
    private static final String BODY = "#404040";
    private static final String MUTED = "#737373";
    private static final String LINE = "#e5e5e5";
    private static final String PAGE = "#f4f4f5";
    private static final String PANEL = "#fafafa";

    private EmailLayout() {
    }

    static EmailMessage message(String to, EmailDraft draft) {
        return new EmailMessage(to, draft.subject, text(draft), html(draft));
    }

    private static String text(EmailDraft draft) {
        StringJoiner blocks = new StringJoiner("\n\n", "", "\n");
        blocks.add(draft.greeting);
        draft.paragraphs.forEach(blocks::add);
        if (draft.highlight != null) {
            blocks.add(draft.highlightLabel + ": " + draft.highlight);
        }
        if (draft.amount != null) {
            blocks.add(draft.amountLabel + ": " + draft.amount);
        }
        if (!draft.details.isEmpty()) {
            StringJoiner rows = new StringJoiner("\n");
            draft.details.forEach(detail -> rows.add(detail.label() + ": " + detail.value()));
            blocks.add(rows.toString());
        }
        if (draft.action != null) {
            blocks.add(draft.action.label() + ":\n" + draft.action.url());
        }
        blocks.add(draft.calloutTitle + " " + draft.callout);
        blocks.add(draft.signOff);
        blocks.add("--\n" + draft.brand + "\n" + String.join("\n", draft.footer));
        return blocks.toString();
    }

    private static String html(EmailDraft draft) {
        Colors colors = TONES.get(draft.tone);
        StringBuilder card = new StringBuilder();
        card.append(kickerBlock(draft, colors));
        card.append(block("<h1 class=\"headline\" style=\"margin:0;font-family:%s;font-size:28px;line-height:35px;font-weight:700;letter-spacing:-0.5px;color:%s;\">%s</h1>"
                .formatted(FONT, TEXT, escape(draft.title)), 16));
        card.append(paragraph(draft.greeting, TEXT, 600, 22));
        draft.paragraphs.forEach(text -> card.append(paragraph(text, BODY, 400, 10)));
        if (draft.highlight != null) {
            card.append(codeBlock(draft));
        }
        if (draft.amount != null) {
            card.append(amountBlock(draft));
        }
        if (!draft.details.isEmpty()) {
            card.append(detailsBlock(draft));
        }
        if (draft.action != null) {
            card.append(button(draft.action));
            card.append(linkFallback(draft));
        }
        card.append(calloutBlock(draft, colors));
        card.append(block("<p style=\"margin:0;font-family:%s;font-size:15px;line-height:22px;color:%s;\">%s</p>"
                .formatted(FONT, BODY, escape(draft.signOff)), 30));
        return """
                <!doctype html>
                <html lang="%s" xmlns:o="urn:schemas-microsoft-com:office:office">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <meta name="x-apple-disable-message-reformatting">
                <meta name="color-scheme" content="light">
                <meta name="supported-color-schemes" content="light">
                <title>%s</title>
                <style>
                  @media only screen and (max-width: 600px) {
                    .page { padding: 0 !important; }
                    .card { border-radius: 0 !important; border-left: 0 !important; border-right: 0 !important; }
                    .pad { padding-left: 24px !important; padding-right: 24px !important; }
                    .headline { font-size: 24px !important; line-height: 31px !important; }
                    .code { font-size: 30px !important; letter-spacing: 6px !important; }
                    .amount { font-size: 30px !important; line-height: 38px !important; }
                    .button { width: 100%% !important; }
                    .button a { display: block !important; text-align: center !important; }
                  }
                </style>
                </head>
                <body style="margin:0;padding:0;background-color:%s;-webkit-text-size-adjust:100%%;">
                <div style="display:none;max-height:0;overflow:hidden;opacity:0;color:%s;">%s</div>
                <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0" bgcolor="%s" style="background-color:%s;">
                <tr><td align="center" class="page" style="padding:40px 16px;">
                <!--[if mso]><table role="presentation" width="600" cellspacing="0" cellpadding="0" border="0"><tr><td><![endif]-->
                <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0" style="max-width:600px;">
                <tr><td class="card" bgcolor="#ffffff" style="background-color:#ffffff;border:1px solid %s;border-top:6px solid %s;border-radius:6px;">
                <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0">
                %s%s<tr><td style="height:36px;line-height:36px;font-size:0;">&nbsp;</td></tr>
                </table>
                </td></tr>
                %s</table>
                <!--[if mso]></td></tr></table><![endif]-->
                </td></tr>
                </table>
                </body>
                </html>
                """.formatted(escape(draft.language), escape(draft.subject), PAGE, PAGE, escape(draft.preheader), PAGE,
                PAGE, LINE, INK, header(draft), card, footer(draft));
    }

    // A letterhead: the mark and name over a heavy rule. A square with text stands in for the logo, since
    // most clients block images by default and nearly all block SVG.
    private static String header(EmailDraft draft) {
        return """
                <tr><td class="pad" style="padding:26px 44px 0;">
                <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0"><tr><td style="padding-bottom:18px;border-bottom:2px solid %s;">
                <table role="presentation" cellspacing="0" cellpadding="0" border="0"><tr>
                <td width="34" height="34" align="center" valign="middle" bgcolor="%s" style="width:34px;height:34px;background-color:%s;border-radius:6px;font-family:%s;font-size:13px;line-height:34px;font-weight:800;letter-spacing:0.5px;color:#ffffff;">%s</td>
                <td style="padding-left:12px;font-family:%s;font-size:17px;line-height:22px;font-weight:700;letter-spacing:-0.2px;color:%s;">%s</td>
                </tr></table>
                </td></tr></table>
                </td></tr>
                """.formatted(INK, INK, INK, FONT, escape(draft.brandMark), FONT, INK, escape(draft.brand));
    }

    private static String kickerBlock(EmailDraft draft, Colors colors) {
        return """
                <tr><td class="pad" style="padding:30px 44px 0;">
                <table role="presentation" cellspacing="0" cellpadding="0" border="0"><tr>
                <td width="34" height="34" align="center" valign="middle" bgcolor="%s" style="width:34px;height:34px;background-color:%s;border:1px solid %s;border-radius:6px;font-family:%s;font-size:17px;line-height:34px;font-weight:700;color:%s;">%s</td>
                <td style="padding-left:12px;font-family:%s;font-size:12px;line-height:16px;font-weight:700;letter-spacing:1.6px;text-transform:uppercase;color:%s;">%s</td>
                </tr></table>
                </td></tr>
                """.formatted(colors.soft(), colors.soft(), LINE, FONT, colors.accent(), escape(draft.icon), FONT,
                colors.accent(), escape(draft.kicker));
    }

    private static String block(String content, int top) {
        return "<tr><td class=\"pad\" style=\"padding:%dpx 44px 0;\">%s</td></tr>\n".formatted(top, content);
    }

    private static String paragraph(String text, String color, int weight, int top) {
        return block("<p style=\"margin:0;font-family:%s;font-size:16px;line-height:26px;font-weight:%d;color:%s;\">%s</p>"
                .formatted(FONT, weight, color, escape(text)), top);
    }

    // One string with spacing, not one cell per digit, so that selecting it copies just the code.
    private static String codeBlock(EmailDraft draft) {
        return block("""
                <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0" bgcolor="%s" style="background-color:%s;border:1px solid %s;border-radius:6px;">
                <tr><td align="center" style="padding:22px 12px;">
                <div style="font-family:%s;font-size:11px;line-height:16px;font-weight:700;letter-spacing:1.6px;text-transform:uppercase;color:%s;">%s</div>
                <div class="code" style="padding:8px 0 0 10px;font-family:%s;font-size:38px;line-height:46px;font-weight:700;letter-spacing:10px;color:%s;">%s</div>
                </td></tr>
                </table>""".formatted(PANEL, PANEL, LINE, FONT, MUTED, escape(draft.highlightLabel), CODE_FONT, TEXT,
                escape(draft.highlight)), 26);
    }

    // Like the total of an invoice: left aligned, figures of equal width so amounts line up.
    private static String amountBlock(EmailDraft draft) {
        return block("""
                <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0" bgcolor="%s" style="background-color:%s;border:1px solid %s;border-radius:6px;">
                <tr><td style="padding:18px 20px;">
                <div style="font-family:%s;font-size:11px;line-height:16px;font-weight:700;letter-spacing:1.6px;text-transform:uppercase;color:%s;">%s</div>
                <div class="amount" style="padding-top:4px;font-family:%s;font-size:36px;line-height:44px;font-weight:700;letter-spacing:-0.5px;font-variant-numeric:tabular-nums;color:%s;">%s</div>
                </td></tr>
                </table>""".formatted(PANEL, PANEL, LINE, FONT, MUTED, escape(draft.amountLabel), FONT, TEXT,
                escape(draft.amount)), 26);
    }

    // Ruled like an invoice: heavy rules above and below, hairlines between the lines.
    private static String detailsBlock(EmailDraft draft) {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < draft.details.size(); i++) {
            EmailDraft.Detail detail = draft.details.get(i);
            String border = i == 0 ? "" : "border-top:1px solid %s;".formatted(LINE);
            rows.append("""
                    <tr>
                    <td valign="top" style="%spadding:12px 14px 12px 0;font-family:%s;font-size:11px;line-height:20px;font-weight:700;letter-spacing:1px;text-transform:uppercase;white-space:nowrap;color:%s;">%s</td>
                    <td valign="top" align="right" style="%spadding:12px 0;font-family:%s;font-size:14px;line-height:20px;font-weight:600;color:%s;word-break:break-word;">%s</td>
                    </tr>
                    """.formatted(border, FONT, MUTED, escape(detail.label()), border, FONT, TEXT,
                    escape(detail.value())));
        }
        return block("""
                <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0" style="border-top:2px solid %s;border-bottom:2px solid %s;">
                %s</table>""".formatted(INK, INK, rows), 26);
    }

    // Padding on the link, not the cell, so the whole button is clickable everywhere but Outlook, which
    // still gets a working link and a solid background.
    private static String button(EmailDraft.Action action) {
        return block("""
                <table role="presentation" class="button" cellspacing="0" cellpadding="0" border="0"><tr>
                <td align="center" bgcolor="%s" style="background-color:%s;border-radius:6px;mso-padding-alt:15px 30px;">
                <a href="%s" target="_blank" rel="noopener" style="display:inline-block;padding:15px 30px;font-family:%s;font-size:15px;line-height:20px;font-weight:600;color:#ffffff;text-decoration:none;border-radius:6px;">%s&nbsp;&rarr;</a>
                </td></tr></table>""".formatted(INK, INK, escape(action.url()), FONT, escape(action.label())), 30);
    }

    private static String linkFallback(EmailDraft draft) {
        return block("""
                <p style="margin:0;font-family:%s;font-size:12px;line-height:19px;color:%s;">%s<br>
                <a href="%s" target="_blank" rel="noopener" style="color:%s;word-break:break-all;">%s</a></p>"""
                .formatted(FONT, MUTED, escape(draft.linkHint), escape(draft.action.url()), INK,
                        escape(draft.action.url())), 18);
    }

    private static String calloutBlock(EmailDraft draft, Colors colors) {
        boolean calm = draft.tone == EmailDraft.Tone.INFO;
        String background = calm ? PANEL : colors.soft();
        String edge = calm ? "#a3a3a3" : colors.accent();
        return block("""
                <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0" bgcolor="%s" style="background-color:%s;border-left:4px solid %s;">
                <tr><td style="padding:14px 18px;font-family:%s;font-size:14px;line-height:22px;color:%s;">
                <strong style="display:block;color:%s;">%s</strong>%s</td></tr>
                </table>""".formatted(background, background, edge, FONT, BODY, TEXT, escape(draft.calloutTitle),
                escape(draft.callout)), 30);
    }

    private static String footer(EmailDraft draft) {
        StringBuilder lines = new StringBuilder();
        draft.footer.forEach(line -> lines.append("""
                <p style="margin:0 0 8px;font-family:%s;font-size:12px;line-height:18px;color:%s;">%s</p>
                """.formatted(FONT, MUTED, escape(line))));
        return """
                <tr><td class="pad" align="center" style="padding:28px 44px 8px;text-align:center;">
                <p style="margin:0 0 10px;font-family:%s;font-size:13px;line-height:18px;font-weight:700;color:%s;">%s</p>
                %s</td></tr>
                """.formatted(FONT, INK, escape(draft.brand), lines);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
