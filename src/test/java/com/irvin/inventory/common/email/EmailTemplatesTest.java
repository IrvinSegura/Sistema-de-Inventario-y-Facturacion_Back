package com.irvin.inventory.common.email;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

import com.irvin.inventory.config.StoreProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EmailTemplatesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-04T18:30:00Z"), ZoneOffset.UTC);
    private static final StoreProperties STORE = new StoreProperties(ZoneId.of("America/Mexico_City"));
    private static final String APP = "https://inventory.example.com";
    private static final String TO = "ana@example.com";
    private static final String NAME = "Ana López";
    private static final Locale SPANISH = Locale.of("es");
    private static final Locale ENGLISH = Locale.of("en");
    private static final Pattern VALUE = Pattern.compile("\\{[a-z-]+}");

    private static final String ACTOR = "Luis Pérez";
    private static final LocalDate DAY = LocalDate.of(2026, 11, 3);

    private final AccountEmails emails = withAppUrl(APP + "/");
    private final OperationEmails operations = operationsWithAppUrl(APP + "/");

    private static AccountEmails withAppUrl(String appUrl) {
        return new AccountEmails(new EmailProperties("Inventory <no-reply@example.com>", appUrl), STORE, CLOCK);
    }

    private static OperationEmails operationsWithAppUrl(String appUrl) {
        return new OperationEmails(new EmailProperties("Inventory <no-reply@example.com>", appUrl), STORE, CLOCK);
    }

    private Map<String, EmailMessage> everyEmail(Locale locale) {
        Map<String, EmailMessage> all = new LinkedHashMap<>();
        all.put("reset-code", emails.resetCode(TO, NAME, "123456", Duration.ofMinutes(10), locale));
        all.put("email-changed", emails.emailChanged(TO, NAME, "n***@example.com", locale));
        all.put("phone-changed", emails.phoneChanged(TO, NAME, "***5678", locale));
        all.put("phone-removed", emails.phoneRemoved(TO, NAME, locale));
        all.put("password-reset", emails.passwordReset(TO, NAME, locale));
        all.put("account-created", emails.accountCreated(TO, NAME, "SELLER", locale));
        all.put("role-changed", emails.roleChanged(TO, NAME, "SUPERVISOR", locale));
        all.put("account-deactivated", emails.accountDeactivated(TO, NAME, locale));
        all.put("account-reactivated", emails.accountReactivated(TO, NAME, locale));
        all.put("password-changed", emails.passwordChanged(TO, NAME, locale));
        all.put("password-recovered", emails.passwordRecovered(TO, NAME, locale));
        all.put("sale-cancelled", operations.saleCancelled(TO, NAME, ACTOR, "1042", new BigDecimal("1250.50"),
                "El cliente devolvió todo", locale));
        all.put("purchase-cancelled", operations.purchaseCancelled(TO, NAME, ACTOR, "Abarrotes del Norte",
                new BigDecimal("18400.00"), "Llegó incompleta", locale));
        all.put("cash-drawer-assigned", operations.cashDrawerAssigned(TO, NAME, ACTOR, "Marta Ruiz",
                new BigDecimal("500.00"), locale));
        all.put("cash-drawer-closed", operations.cashDrawerClosed(TO, NAME, ACTOR, DAY, BigDecimal.ZERO, locale));
        all.put("cash-drawer-closed-off", operations.cashDrawerClosed(TO, NAME, ACTOR, DAY,
                new BigDecimal("-35.50"), locale));
        return all;
    }

    private static Properties bundle(String language) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = EmailTemplatesTest.class.getResourceAsStream("emails_" + language + ".properties")) {
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }

    private static Map<String, List<String>> valuesPerText(Properties texts) {
        Map<String, List<String>> values = new TreeMap<>();
        texts.stringPropertyNames().forEach(key -> values.put(key,
                VALUE.matcher(texts.getProperty(key)).results().map(MatchResult::group).sorted().toList()));
        return values;
    }

    @Test
    void bothLanguagesHaveTheSameTextsWithTheSameValues() throws IOException {
        Properties spanish = bundle("es");
        Properties english = bundle("en");

        assertThat(spanish.stringPropertyNames()).isEqualTo(english.stringPropertyNames());
        assertThat(valuesPerText(spanish)).isEqualTo(valuesPerText(english));
    }

    @ParameterizedTest
    @ValueSource(strings = {"es", "en"})
    void everyEmailHasItsWholeStructureWithNoValueLeftUnfilled(String language) {
        everyEmail(Locale.of(language)).forEach((reason, email) -> {
            assertThat(email.to()).as(reason).isEqualTo(TO);
            assertThat(email.subject()).as(reason).isNotBlank().doesNotContain("{", "null");
            assertThat(email.body()).as(reason).contains(NAME).doesNotContain("{", "null");
            assertThat(email.html()).as(reason)
                    .contains("<html lang=\"" + language + "\"", "role=\"presentation\"", "border-left:4px solid")
                    .contains("<title>" + email.subject() + "</title>", NAME)
                    .doesNotContain("null", "<img", "<svg");
        });
    }

    // Emoji look different in every client and some show them as empty boxes, so icons are typographic marks.
    @ParameterizedTest
    @ValueSource(strings = {"es", "en"})
    void everyEmailIsSignedLabeledAndFreeOfEmoji(String language) throws IOException {
        Properties texts = bundle(language);
        everyEmail(Locale.of(language)).forEach((reason, email) -> {
            assertThat(email.html()).as(reason)
                    .contains(texts.getProperty(reason + ".kicker"), texts.getProperty("sign-off"));
            assertThat(email.body()).as(reason).contains(texts.getProperty("sign-off"));
            assertThat(email.html().codePoints().filter(EmailTemplatesTest::isEmoji)).as(reason).isEmpty();
        });
    }

    private static boolean isEmoji(int codePoint) {
        return codePoint >= 0x1F000 || codePoint == 0xFE0F
                || Character.UnicodeBlock.of(codePoint) == Character.UnicodeBlock.MISCELLANEOUS_SYMBOLS
                || codePoint == 0x2709 || codePoint == 0x2714;
    }

    @Test
    void theEmailIsWrittenInTheRequestedLanguage() {
        EmailMessage spanish = emails.resetCode(TO, NAME, "123456", Duration.ofMinutes(10), SPANISH);
        EmailMessage english = emails.resetCode(TO, NAME, "123456", Duration.ofMinutes(10), ENGLISH);

        assertThat(spanish.subject()).isEqualTo("Tu código para restablecer la contraseña");
        assertThat(spanish.body()).startsWith("Hola, Ana López:").contains("de noviembre de 2026", "10 min")
                .contains("Inventario y Facturación").doesNotContain("Your password");
        assertThat(english.subject()).isEqualTo("Your password reset code");
        assertThat(english.body()).startsWith("Hi Ana López,").contains("November 4, 2026", "10 min")
                .contains("Inventory Billing").doesNotContain("Tu código");
    }

    @Test
    void timesAreInTheStoresTimeZone() {
        assertThat(emails.passwordReset(TO, NAME, SPANISH).body()).contains("12:30");
    }

    @Test
    void anyOtherLanguageReadsInEnglish() {
        assertThat(emails.passwordReset(TO, NAME, Locale.of("fr")).subject()).isEqualTo("Your password was reset");
    }

    @Test
    void theCodeReachesBothFormsButNeverTheSubjectOrPreview() {
        EmailMessage email = emails.resetCode(TO, NAME, "123456", Duration.ofMinutes(10), SPANISH);

        assertThat(email.body()).contains("Tu código: 123456");
        assertThat(email.html()).containsOnlyOnce("123456");
        assertThat(email.subject()).doesNotContain("123456");
    }

    // Opening a link restarts the web's reset, and a new code would cancel the one in this email.
    @Test
    void theResetCodeHasNoButton() {
        EmailMessage email = emails.resetCode(TO, NAME, "123456", Duration.ofMinutes(10), SPANISH);

        assertThat(email.html()).doesNotContain("href=");
        assertThat(email.body()).doesNotContain(APP);
    }

    @Test
    void noticesLinkToSignInWithAFallbackInBothForms() {
        EmailMessage email = emails.passwordReset(TO, NAME, SPANISH);

        assertThat(email.html()).contains("href=\"" + APP + "/login\"", "Iniciar sesión", "copia y pega");
        assertThat(email.body()).contains("Iniciar sesión:\n" + APP + "/login");
    }

    @Test
    void withoutAnAppUrlNoticesGoOutWithNoButton() {
        EmailMessage email = withAppUrl("").passwordReset(TO, NAME, SPANISH);

        assertThat(email.html()).doesNotContain("href=", "copia y pega");
        assertThat(email.body()).doesNotContain("Iniciar sesión");
    }

    @Test
    void newValuesAppearInTheDataTable() {
        assertThat(emails.emailChanged(TO, NAME, "n***@example.com", SPANISH).body())
                .contains("Correo nuevo: n***@example.com");
        assertThat(emails.phoneChanged(TO, NAME, "***5678", SPANISH).body()).contains("Celular nuevo: ***5678");
        assertThat(emails.phoneRemoved(TO, NAME, SPANISH).body()).doesNotContain("Celular nuevo");
    }

    // The admin hands the password over in person; the email only says where to sign in and as what.
    @Test
    void theWelcomeSaysTheEmailAndRoleButNeverAPassword() {
        EmailMessage email = emails.accountCreated(TO, NAME, "SELLER", SPANISH);

        assertThat(email.subject()).isEqualTo("Tu cuenta en Inventario y Facturación está lista");
        assertThat(email.body()).contains("Correo: " + TO, "Rol: Vendedor", "Iniciar sesión:\n" + APP + "/login");
        assertThat(emails.roleChanged(TO, NAME, "ADMIN", ENGLISH).body()).contains("New role: Admin");
    }

    // A deactivated account can't sign in, so its email has nothing to open.
    @Test
    void theDeactivationHasNoButton() {
        assertThat(emails.accountDeactivated(TO, NAME, SPANISH).html()).doesNotContain("href=");
        assertThat(emails.accountReactivated(TO, NAME, SPANISH).html()).contains("href=\"" + APP + "/login\"");
    }

    @Test
    void operationEmailsLeadWithTheAmountAndOpenTheirPage() {
        EmailMessage sale = operations.saleCancelled(TO, NAME, ACTOR, "1042", new BigDecimal("1250.5"), "Error",
                SPANISH);
        assertThat(sale.subject()).isEqualTo("Se canceló la venta #1042");
        assertThat(sale.body()).contains("Luis Pérez canceló la venta #1042", "Total de la venta: $1,250.50",
                "Motivo: Error", "Ver historial de caja:\n" + APP + "/cash-drawer/history");
        assertThat(sale.html()).contains("class=\"amount\"");

        assertThat(operations.purchaseCancelled(TO, NAME, ACTOR, "Abarrotes", BigDecimal.TEN, null, SPANISH).body())
                .contains(APP + "/purchases").doesNotContain("Motivo");
        assertThat(operations.cashDrawerAssigned(TO, NAME, ACTOR, "Marta", new BigDecimal("500"), SPANISH).body())
                .contains("Fondo inicial: $500.00", "Responsable: Marta", APP + "/cash-drawer\n");
    }

    @Test
    void aClosedDrawerSaysWhichWayTheDifferenceGoes() {
        EmailMessage balanced = operations.cashDrawerClosed(TO, NAME, ACTOR, DAY, BigDecimal.ZERO, SPANISH);
        EmailMessage short_ = operations.cashDrawerClosed(TO, NAME, ACTOR, DAY, new BigDecimal("-35.50"), SPANISH);
        EmailMessage over = operations.cashDrawerClosed(TO, NAME, ACTOR, DAY, new BigDecimal("20"), ENGLISH);

        assertThat(balanced.subject()).isEqualTo("Se cerró tu caja del 3 de noviembre de 2026");
        assertThat(balanced.body()).contains("Diferencia: $0.00", "coincidió");
        assertThat(short_.subject()).endsWith("con diferencia");
        assertThat(short_.body()).contains("Diferencia: -$35.50");
        assertThat(over.body()).contains("Difference: +$20.00", "November 3, 2026");
    }

    @Test
    void withoutAnAppUrlOperationEmailsGoOutWithNoButton() {
        assertThat(operationsWithAppUrl("").saleCancelled(TO, NAME, ACTOR, "1", BigDecimal.ONE, null, SPANISH).html())
                .doesNotContain("href=");
    }

    // A user deleted since the change has no name to show.
    @Test
    void anUnknownActorIsSomeone() {
        assertThat(operations.saleCancelled(TO, NAME, null, "1", BigDecimal.ONE, null, SPANISH).body())
                .contains("Alguien canceló la venta #1");
    }

    @Test
    void whatUsersWroteCannotAddMarkupToTheEmail() {
        String evilName = "<img src=x onerror=alert(1)> \"quoted\" 100%s <a href='https://evil.example'>Soporte</a>";

        everyEmailFor(evilName).values().forEach(email ->
                assertThat(email.html())
                        .doesNotContain("<img src=x", "<a href='https://evil.example'", "\"quoted\"")
                        .contains("&lt;img src=x", "&quot;quoted&quot; 100%s", "&lt;a href=&#39;https://evil.example&#39;"));
    }

    @Test
    void valuesThatLookLikeOtherValuesStayAsWritten() {
        assertThat(emails.resetCode(TO, "{code} $1 {name}", "123456", Duration.ofMinutes(10), SPANISH).body())
                .startsWith("Hola, {code} $1 {name}:");
    }

    private Map<String, EmailMessage> everyEmailFor(String name) {
        return Map.of(
                "reset-code", emails.resetCode(TO, name, "123456", Duration.ofMinutes(10), SPANISH),
                "email-changed", emails.emailChanged(TO, name, "***", SPANISH),
                "phone-changed", emails.phoneChanged(TO, name, "***", SPANISH),
                "phone-removed", emails.phoneRemoved(TO, name, SPANISH),
                "password-reset", emails.passwordReset(TO, name, SPANISH),
                "account-created", emails.accountCreated(TO, name, "SELLER", SPANISH),
                "sale-cancelled", operations.saleCancelled(TO, name, name, "1", BigDecimal.ONE, name, SPANISH),
                "purchase-cancelled", operations.purchaseCancelled(TO, name, name, name, BigDecimal.ONE, name, SPANISH),
                "cash-drawer-assigned", operations.cashDrawerAssigned(TO, name, name, name, BigDecimal.ONE, SPANISH),
                "cash-drawer-closed", operations.cashDrawerClosed(TO, name, name, DAY, BigDecimal.ONE, SPANISH));
    }

    // Not an assertion: leaves every design in target/email-previews, with index.html showing them all at
    // desktop and phone width, to open in a browser while working on them.
    @Test
    void writesPreviews() throws IOException {
        Path folder = Path.of("target", "email-previews");
        Files.createDirectories(folder);
        StringBuilder gallery = new StringBuilder("""
                <!doctype html>
                <html lang="es"><head><meta charset="utf-8"><title>Inventario y Facturación: correos</title>
                <style>
                body{margin:0;padding:32px;background:#e5e5e5;font:14px -apple-system,'Segoe UI',Roboto,sans-serif;color:#0a0a0a}
                h2{margin:40px 0 12px;font-size:16px}
                .row{display:flex;gap:24px;align-items:flex-start;flex-wrap:wrap}
                iframe{border:1px solid #d4d4d4;background:#fff;border-radius:6px}
                </style></head><body><h1>Inventario y Facturación: correos</h1>
                """);
        for (Locale locale : List.of(SPANISH, ENGLISH)) {
            for (Map.Entry<String, EmailMessage> email : everyEmail(locale).entrySet()) {
                String file = email.getKey() + "." + locale.getLanguage();
                Files.writeString(folder.resolve(file + ".html"), email.getValue().html());
                Files.writeString(folder.resolve(file + ".txt"), email.getValue().body());
                gallery.append("""
                        <h2>%s · %s</h2><div class="row">
                        <iframe src="%s.html" width="680" height="1000" title="%s"></iframe>
                        <iframe src="%s.html" width="375" height="1000" title="%s"></iframe></div>
                        """.formatted(email.getValue().subject(), locale.getLanguage(), file, file, file, file));
            }
        }
        Files.writeString(folder.resolve("index.html"), gallery.append("</body></html>\n").toString());
    }
}
