package com.irvin.inventory.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.irvin.inventory.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

class PasswordResetIT extends AbstractIntegrationTest {

    private static final String NEW_PASSWORD = "new-password-123";

    @Autowired
    private RecordingPasswordResetSender sender;

    @Test
    void emailCodeResetsPasswordAndSignsOutEverySession() throws Exception {
        CreatedUser seller = createSeller();
        Tokens session = loginOk(seller.email(), DEFAULT_PASSWORD);

        requestCode(seller.email(), "EMAIL", "es").andExpect(status().isAccepted());
        ResetMessage message = sender.next(seller.email());
        assertThat(message.channel()).isEqualTo(ResetChannel.EMAIL);
        assertThat(message.code()).matches("\\d{6}");
        assertThat(message.locale().getLanguage()).isEqualTo("es");

        reset(seller.email(), message.code(), NEW_PASSWORD).andExpect(status().isNoContent());

        login(seller.email(), DEFAULT_PASSWORD).andExpect(status().isUnauthorized());
        login(seller.email(), NEW_PASSWORD).andExpect(status().isOk());
        refresh(session.refreshToken()).andExpect(status().isUnauthorized());
        // A code works once.
        reset(seller.email(), message.code(), "another-password").andExpect(status().isBadRequest());
    }

    @Test
    void aForgottenPasswordResetClearsTheRequiredChange() throws Exception {
        CreatedUser seller = createSeller();
        putJson("/api/v1/users/" + seller.id() + "/password", """
                {"temporaryPassword": "temporary-pass-1"}
                """, adminAccessToken()).andExpect(status().isNoContent());

        requestCode(seller.email(), "EMAIL", "es").andExpect(status().isAccepted());
        reset(seller.email(), sender.next(seller.email()).code(), NEW_PASSWORD).andExpect(status().isNoContent());

        String token = loginOk(seller.email(), NEW_PASSWORD).accessToken();
        getJson("/api/v1/users/me", token).andExpect(jsonPath("$.passwordChangeRequired").value(false));
        getJson("/api/v1/products", token).andExpect(status().isOk());
        getJson("/api/v1/users/" + seller.id() + "/events", adminAccessToken())
                .andExpect(jsonPath("$.content[0].type").value("PASSWORD_RECOVERED"))
                .andExpect(jsonPath("$.content[0].actor.id").value(seller.id()));
    }

    @Test
    void aRecoveredPasswordEndsOpenSessionsAtOnce() throws Exception {
        CreatedUser seller = createSeller();
        String openSession = loginOk(seller.email(), DEFAULT_PASSWORD).accessToken();

        requestCode(seller.email(), "EMAIL", "es").andExpect(status().isAccepted());
        reset(seller.email(), sender.next(seller.email()).code(), NEW_PASSWORD).andExpect(status().isNoContent());

        getJson("/api/v1/users/me", openSession).andExpect(status().isUnauthorized());
    }

    @Test
    void codesSentBeforeAnAdminChangeStopWorking() throws Exception {
        String adminToken = adminAccessToken();
        CreatedUser emailed = createSeller();
        CreatedUser resetUser = createSeller();
        requestCode(emailed.email(), "EMAIL", "es").andExpect(status().isAccepted());
        requestCode(resetUser.email(), "EMAIL", "es").andExpect(status().isAccepted());
        String emailedCode = sender.next(emailed.email()).code();
        String resetUserCode = sender.next(resetUser.email()).code();
        String newEmail = "moved-" + emailed.id() + "-" + System.nanoTime() + "@test.com";

        // The code went to the old address.
        putJson("/api/v1/users/" + emailed.id(), """
                {"name": "Test User", "email": "%s", "role": "SELLER", "active": true}
                """.formatted(newEmail), adminToken).andExpect(status().isOk());
        putJson("/api/v1/users/" + resetUser.id() + "/password", """
                {"temporaryPassword": "temporary-pass-1"}
                """, adminToken).andExpect(status().isNoContent());

        reset(newEmail, emailedCode, NEW_PASSWORD).andExpect(status().isBadRequest());
        reset(resetUser.email(), resetUserCode, NEW_PASSWORD).andExpect(status().isBadRequest());
        login(newEmail, DEFAULT_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void unknownEmailGetsTheSameAnswerAndNothingIsSent() throws Exception {
        requestCode("nobody-reset@test.com", "EMAIL", "en").andExpect(status().isAccepted());
        assertThat(sender.nothingSentTo("nobody-reset@test.com")).isTrue();

        reset("nobody-reset@test.com", "123456", NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid or expired code"));
    }

    @Test
    void smsGoesToThePhoneOnlyWhenTheAccountHasOne() throws Exception {
        CreatedUser seller = createSeller();
        requestCode(seller.email(), "SMS", "en").andExpect(status().isAccepted());
        assertThat(sender.nothingSentTo(seller.email())).isTrue();

        putJson("/api/v1/users/" + seller.id(), """
                {"name": "Test User", "role": "SELLER", "active": true, "phone": "+52 (55) 1234-5678"}
                """, adminAccessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("+525512345678"));

        requestCode(seller.email(), "SMS", "en").andExpect(status().isAccepted());
        ResetMessage message = sender.next(seller.email());
        assertThat(message.channel()).isEqualTo(ResetChannel.SMS);
        assertThat(message.phone()).isEqualTo("+525512345678");
        assertThat(message.smsBody()).contains(message.code());
    }

    @Test
    void tooManyWrongAttemptsInvalidateTheCode() throws Exception {
        CreatedUser seller = createSeller();
        requestCode(seller.email(), "EMAIL", "en").andExpect(status().isAccepted());
        String code = sender.next(seller.email()).code();

        for (int attempt = 0; attempt < 5; attempt++) {
            reset(seller.email(), otherCode(code), NEW_PASSWORD).andExpect(status().isBadRequest());
        }
        reset(seller.email(), code, NEW_PASSWORD).andExpect(status().isBadRequest());
        login(seller.email(), DEFAULT_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void aNewCodeReplacesThePreviousOneButNotWithinTheCooldown() throws Exception {
        CreatedUser seller = createSeller();
        requestCode(seller.email(), "EMAIL", "en").andExpect(status().isAccepted());
        String first = sender.next(seller.email()).code();

        requestCode(seller.email(), "EMAIL", "en").andExpect(status().isAccepted());
        assertThat(sender.nothingSentTo(seller.email())).isTrue();

        // Past the cooldown.
        jdbcTemplate.update("UPDATE password_reset_code SET created_at = created_at - INTERVAL '2 minutes' "
                + "WHERE user_id = ?", seller.id());
        requestCode(seller.email(), "EMAIL", "en").andExpect(status().isAccepted());
        String second = sender.next(seller.email()).code();

        if (!first.equals(second)) {
            reset(seller.email(), first, NEW_PASSWORD).andExpect(status().isBadRequest());
        }
        reset(seller.email(), second, NEW_PASSWORD).andExpect(status().isNoContent());
    }

    @Test
    void expiredCodeIsRejected() throws Exception {
        CreatedUser seller = createSeller();
        requestCode(seller.email(), "EMAIL", "en").andExpect(status().isAccepted());
        String code = sender.next(seller.email()).code();
        jdbcTemplate.update("UPDATE password_reset_code SET expires_at = now() - INTERVAL '1 second' "
                + "WHERE user_id = ?", seller.id());

        reset(seller.email(), code, NEW_PASSWORD).andExpect(status().isBadRequest());
    }

    @Test
    void phoneMustBeInInternationalFormat() throws Exception {
        CreatedUser seller = createSeller();
        putJson("/api/v1/users/" + seller.id(), """
                {"name": "Test User", "role": "SELLER", "active": true, "phone": "55 1234 5678"}
                """, adminAccessToken())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.phone").exists());
    }

    private ResultActions requestCode(String email, String channel, String language) throws Exception {
        return postJson("/api/v1/auth/password-reset/code", """
                {"email": "%s", "channel": "%s", "language": "%s"}
                """.formatted(email, channel, language));
    }

    private ResultActions reset(String email, String code, String newPassword) throws Exception {
        return postJson("/api/v1/auth/password-reset", """
                {"email": "%s", "code": "%s", "newPassword": "%s"}
                """.formatted(email, code, newPassword));
    }

    private static String otherCode(String code) {
        return "%06d".formatted((Integer.parseInt(code) + 1) % 1_000_000);
    }
}
