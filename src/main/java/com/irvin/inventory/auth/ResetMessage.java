package com.irvin.inventory.auth;

import java.time.Duration;
import java.util.Locale;

// A code ready to be delivered, so the user reads it in the app's language. The email's texts are in
// common/email; the SMS is here because it's a single line.
public record ResetMessage(
        ResetChannel channel, Long userId, String name, String email, String phone, String code, Duration ttl,
        Locale locale) {

    private static final Locale SPANISH = Locale.of("es");

    public String smsBody() {
        long minutes = ttl.toMinutes();
        return isSpanish()
                ? "Tu código para restablecer la contraseña es %s. Vence en %d min.".formatted(code, minutes)
                : "Your password reset code is %s. It expires in %d min.".formatted(code, minutes);
    }

    private boolean isSpanish() {
        return SPANISH.getLanguage().equals(locale.getLanguage());
    }
}
