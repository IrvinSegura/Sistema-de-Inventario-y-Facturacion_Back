package com.irvin.inventory.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.irvin.inventory.AbstractIntegrationTest;
import com.irvin.inventory.auth.RecordingPasswordResetSender;
import com.irvin.inventory.common.email.EmailMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// The account audit trail, the notices an admin's changes send the owner, and the sellers list
// supervisors use for filters.
class AccountHistoryIT extends AbstractIntegrationTest {

    @Autowired
    private RecordingAccountNoticeSender notices;
    @Autowired
    private RecordingPasswordResetSender codes;

    @Test
    void everyAccountChangeIsRecordedWithWhoMadeIt() throws Exception {
        String token = adminAccessToken();
        CreatedUser seller = createSeller();
        String newEmail = "history-" + UUID.randomUUID() + "@test.com";

        update(seller.id(), newEmail, "SUPERVISOR", true, token);
        update(seller.id(), null, "SUPERVISOR", false, token);
        update(seller.id(), null, "SUPERVISOR", true, token);
        putJson("/api/v1/users/" + seller.id() + "/password", """
                {"temporaryPassword": "temporary-pass-1"}
                """, token).andExpect(status().isNoContent());

        // Newest first; the email and role changes of one save are both there.
        getJson("/api/v1/users/" + seller.id() + "/events", token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(6))
                .andExpect(jsonPath("$.content[0].type").value("PASSWORD_RESET"))
                .andExpect(jsonPath("$.content[1].type").value("REACTIVATED"))
                .andExpect(jsonPath("$.content[2].type").value("DEACTIVATED"))
                .andExpect(jsonPath("$.content[?(@.type == 'ROLE_CHANGED')].oldValue").value("SELLER"))
                .andExpect(jsonPath("$.content[?(@.type == 'ROLE_CHANGED')].newValue").value("SUPERVISOR"))
                .andExpect(jsonPath("$.content[?(@.type == 'EMAIL_CHANGED')].oldValue").value(seller.email()))
                .andExpect(jsonPath("$.content[?(@.type == 'EMAIL_CHANGED')].newValue").value(newEmail))
                .andExpect(jsonPath("$.content[5].type").value("CREATED"))
                .andExpect(jsonPath("$.content[5].actor.name").exists());
    }

    @Test
    void theUsersOwnPasswordChangeNamesThemAsTheActor() throws Exception {
        CreatedUser seller = createSeller();
        String sellerToken = loginOk(seller.email(), DEFAULT_PASSWORD).accessToken();

        putJson("/api/v1/users/me/password", """
                {"currentPassword": "%s", "newPassword": "brand-new-pass-1"}
                """.formatted(DEFAULT_PASSWORD), sellerToken).andExpect(status().isNoContent());

        getJson("/api/v1/users/" + seller.id() + "/events", adminAccessToken())
                .andExpect(jsonPath("$.content[0].type").value("PASSWORD_CHANGED"))
                .andExpect(jsonPath("$.content[0].actor.id").value(seller.id()));
    }

    @Test
    void onlyAdminsReadTheHistory() throws Exception {
        CreatedUser seller = createSeller();
        String supervisorToken = loginOk(createUser("SUPERVISOR").email(), DEFAULT_PASSWORD).accessToken();

        getJson("/api/v1/users/" + seller.id() + "/events", supervisorToken).andExpect(status().isForbidden());
        getJson("/api/v1/users/999999/events", adminAccessToken()).andExpect(status().isNotFound());
    }

    // Created by an admin, so welcomed; the welcome is read here and the tests below start after it.
    private CreatedUser welcomedSeller() throws Exception {
        CreatedUser seller = createSeller();
        EmailMessage welcome = notices.next(seller.email());
        assertThat(welcome.subject()).isEqualTo("Tu cuenta en Inventario y Facturación está lista");
        assertThat(welcome.body()).contains("Rol: Vendedor").doesNotContain(DEFAULT_PASSWORD);
        return seller;
    }

    @Test
    void theOldAddressIsToldWhenTheEmailChanges() throws Exception {
        String token = adminAccessToken();
        CreatedUser seller = welcomedSeller();
        String newEmail = "notice-" + UUID.randomUUID() + "@test.com";

        update(seller.id(), newEmail, "SELLER", true, token);

        EmailMessage notice = notices.next(seller.email());
        // Masked: the old address may be the compromised one.
        assertThat(notice.body()).contains("n***@test.com").doesNotContain(newEmail);
        assertThat(notices.nothingSentTo(newEmail)).isTrue();
        // Later changes go to the new address.
        update(seller.id(), null, "SELLER", false, token);
        assertThat(notices.next(newEmail).subject()).isEqualTo("Se desactivó tu cuenta");
        assertThat(notices.nothingSentTo(seller.email())).isTrue();
    }

    @Test
    void roleAndStatusChangesAreToldToTheOwner() throws Exception {
        String token = adminAccessToken();
        CreatedUser seller = welcomedSeller();

        update(seller.id(), null, "SUPERVISOR", true, token);
        assertThat(notices.next(seller.email()).body()).contains("Rol nuevo: Supervisor");
        update(seller.id(), null, "SUPERVISOR", false, token);
        assertThat(notices.next(seller.email()).subject()).isEqualTo("Se desactivó tu cuenta");
        update(seller.id(), null, "SUPERVISOR", true, token);
        assertThat(notices.next(seller.email()).subject()).isEqualTo("Se reactivó tu cuenta");
    }

    @Test
    void theOwnersOwnPasswordChangeIsToldToThem() throws Exception {
        CreatedUser seller = welcomedSeller();
        String sellerToken = loginOk(seller.email(), DEFAULT_PASSWORD).accessToken();

        putJson("/api/v1/users/me/password", """
                {"currentPassword": "%s", "newPassword": "brand-new-pass-1"}
                """.formatted(DEFAULT_PASSWORD), sellerToken).andExpect(status().isNoContent());

        assertThat(notices.next(seller.email()).subject()).isEqualTo("Cambió tu contraseña");
    }

    @Test
    void aPhoneChangeIsRecordedToldAndEndsSmsCodes() throws Exception {
        String token = adminAccessToken();
        CreatedUser seller = welcomedSeller();
        setPhone(seller.id(), "+525500000001", token);
        notices.next(seller.email());
        postJson("/api/v1/auth/password-reset/code", """
                {"email": "%s", "channel": "SMS", "language": "es"}
                """.formatted(seller.email())).andExpect(status().isAccepted());
        String smsCode = codes.next(seller.email()).code();

        setPhone(seller.id(), "+525512345678", token);

        getJson("/api/v1/users/" + seller.id() + "/events", token)
                .andExpect(jsonPath("$.content[0].type").value("PHONE_CHANGED"))
                .andExpect(jsonPath("$.content[0].oldValue").value("+525500000001"))
                .andExpect(jsonPath("$.content[0].newValue").value("+525512345678"));
        assertThat(notices.next(seller.email()).body()).contains("***5678").doesNotContain("+525512345678");
        // The code went to the old phone.
        postJson("/api/v1/auth/password-reset", """
                {"email": "%s", "code": "%s", "newPassword": "brand-new-pass-1"}
                """.formatted(seller.email(), smsCode)).andExpect(status().isBadRequest());
    }

    @Test
    void removingThePhoneIsToldToTheUser() throws Exception {
        String token = adminAccessToken();
        CreatedUser seller = welcomedSeller();
        setPhone(seller.id(), "+525500000001", token);
        notices.next(seller.email());

        putJson("/api/v1/users/" + seller.id(), """
                {"name": "Test User", "role": "SELLER", "active": true}
                """, token).andExpect(status().isOk());

        assertThat(notices.next(seller.email()).subject()).isEqualTo("Se quitó el celular de tu cuenta");
    }

    private void setPhone(Long id, String phone, String token) throws Exception {
        putJson("/api/v1/users/" + id, """
                {"name": "Test User", "role": "SELLER", "active": true, "phone": "%s"}
                """.formatted(phone), token).andExpect(status().isOk());
    }

    @Test
    void aPasswordResetIsToldToTheUserAndItsReplacementMarked() throws Exception {
        String token = adminAccessToken();
        CreatedUser seller = welcomedSeller();

        putJson("/api/v1/users/" + seller.id() + "/password", """
                {"temporaryPassword": "temporary-pass-1"}
                """, token).andExpect(status().isNoContent());
        assertThat(notices.next(seller.email()).subject()).contains("contraseña");
        String temporarySession = loginOk(seller.email(), "temporary-pass-1").accessToken();
        putJson("/api/v1/users/me/password", """
                {"currentPassword": "temporary-pass-1", "newPassword": "brand-new-pass-1"}
                """, temporarySession).andExpect(status().isNoContent());

        // Whoever knew the temporary password could have made this change: the history says it replaced one.
        getJson("/api/v1/users/" + seller.id() + "/events", token)
                .andExpect(jsonPath("$.content[0].type").value("PASSWORD_CHANGED"))
                .andExpect(jsonPath("$.content[0].oldValue").value("TEMPORARY"));
    }

    @Test
    void theHistoryIgnoresClientSorting() throws Exception {
        String token = adminAccessToken();
        CreatedUser seller = createSeller();
        update(seller.id(), null, "SELLER", false, token);

        getJson("/api/v1/users/" + seller.id() + "/events?sort=type,asc", token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].type").value("DEACTIVATED"));
        getJson("/api/v1/users/" + seller.id() + "/events?sort=nope", token).andExpect(status().isOk());
    }

    @Test
    void aRejectedEmailChangeSendsNoNotice() throws Exception {
        String token = adminAccessToken();
        CreatedUser seller = welcomedSeller();
        CreatedUser other = createSeller();

        putJson("/api/v1/users/" + seller.id(), """
                {"name": "Test User", "email": "%s", "role": "SELLER", "active": true}
                """.formatted(other.email()), token).andExpect(status().isConflict());

        assertThat(notices.nothingSentTo(seller.email())).isTrue();
    }

    @Test
    void managersListEverySellerForTheirFilters() throws Exception {
        CreatedUser seller = createSeller();
        update(seller.id(), null, "SELLER", false, adminAccessToken());
        // Sold, then became a supervisor: their sales must stay filterable.
        CreatedUser promoted = createSeller();
        Long product = createPricedProduct("10", "0");
        addStock(product, 1);
        sell("""
                {"paymentMethod": "CASH", "items": [{"productId": %d, "quantity": 1}]}
                """.formatted(product), sellerToken(promoted)).andExpect(status().isCreated());
        update(promoted.id(), null, "SUPERVISOR", true, adminAccessToken());
        String supervisorToken = loginOk(createUser("SUPERVISOR").email(), DEFAULT_PASSWORD).accessToken();
        String sellerToken = loginOk(createSeller().email(), DEFAULT_PASSWORD).accessToken();

        getJson("/api/v1/sellers", supervisorToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + seller.id() + ")].active").value(false))
                .andExpect(jsonPath("$[?(@.id == " + promoted.id() + ")]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.name == 'Test User')]").isNotEmpty());
        getJson("/api/v1/sellers", sellerToken).andExpect(status().isForbidden());
    }

    private void update(Long id, String email, String role, boolean active, String token) throws Exception {
        String emailField = email == null ? "" : "\"email\": \"%s\", ".formatted(email);
        putJson("/api/v1/users/" + id, """
                {"name": "Test User", %s"role": "%s", "active": %s}
                """.formatted(emailField, role, active), token).andExpect(status().isOk());
    }
}
