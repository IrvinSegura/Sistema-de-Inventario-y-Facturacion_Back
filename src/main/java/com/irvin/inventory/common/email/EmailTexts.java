package com.irvin.inventory.common.email;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// The wording of the emails in one language, from emails_<language>.properties next to this class.
// Texts name their values, as in "Hola {name},".
final class EmailTexts {

    private static final String SPANISH = "es";
    private static final String ENGLISH = "en";
    private static final Locale MEXICO = Locale.of(SPANISH, "MX");
    private static final String BUNDLE = EmailTexts.class.getPackageName() + ".emails";
    private static final Pattern VALUE = Pattern.compile("\\{([a-z-]+)}");

    private final String language;
    private final ResourceBundle bundle;
    private final DateTimeFormatter dateTime;
    private final DateTimeFormatter date;
    private final NumberFormat money;

    // Anything but Spanish reads in English, as the API's default language.
    EmailTexts(Locale locale, ZoneId zone) {
        this.language = SPANISH.equals(locale.getLanguage()) ? SPANISH : ENGLISH;
        boolean spanish = SPANISH.equals(language);
        // No fallback, so a text missing from one language fails instead of going out in another one.
        this.bundle = ResourceBundle.getBundle(BUNDLE, Locale.of(language),
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
        this.dateTime = DateTimeFormatter
                .ofPattern(spanish ? "d 'de' MMMM 'de' yyyy, HH:mm z" : "MMMM d, yyyy, h:mm a z",
                        spanish ? MEXICO : Locale.US)
                .withZone(zone);
        this.date = DateTimeFormatter.ofPattern(spanish ? "d 'de' MMMM 'de' yyyy" : "MMMM d, yyyy",
                spanish ? MEXICO : Locale.US);
        this.money = NumberFormat.getCurrencyInstance(spanish ? MEXICO : Locale.US);
    }

    String language() {
        return language;
    }

    String get(String key) {
        return bundle.getString(key);
    }

    // One pass over the text, so a value that looks like another one (a name such as "{code}") stays as written.
    String get(String key, Map<String, String> values) {
        Matcher names = VALUE.matcher(bundle.getString(key));
        return names.replaceAll(name -> Matcher.quoteReplacement(values.getOrDefault(name.group(1), name.group())));
    }

    // Null if this email doesn't have the text.
    String optional(String key, Map<String, String> values) {
        return bundle.containsKey(key) ? get(key, values) : null;
    }

    // The paragraphs of an email: key, then key.2 and so on while they exist.
    List<String> paragraphs(String key, Map<String, String> values) {
        List<String> paragraphs = new ArrayList<>();
        paragraphs.add(get(key, values));
        for (int i = 2; bundle.containsKey(key + "." + i); i++) {
            paragraphs.add(get(key + "." + i, values));
        }
        return paragraphs;
    }

    String dateTime(Instant instant) {
        return dateTime.format(instant);
    }

    String date(LocalDate day) {
        return date.format(day);
    }

    // In the store's currency, pesos: "$1,250.50".
    String money(BigDecimal amount) {
        return money.format(amount);
    }

    // A difference says which way it goes: "+$20.00", "-$20.00", "$0.00".
    String signedMoney(BigDecimal amount) {
        return amount.signum() > 0 ? "+" + money(amount) : money(amount);
    }
}
