package com.irvin.inventory.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

import com.irvin.inventory.AbstractIntegrationTest;
import com.irvin.inventory.config.StoreProperties;
import com.irvin.inventory.sale.SaleCancelledEvent;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

// Notifications are saved in the background after the change commits, hence the waits. Every recipient
// of one event is saved in one transaction, so once one arrives the others' absence is final.
class NotificationIT extends AbstractIntegrationTest {

    private static final String NOTIFICATIONS = "/api/v1/notifications";

    @Autowired
    private StoreProperties storeProperties;

    @Autowired
    private NotificationCleanup notificationCleanup;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void aCancelledSaleNotifiesItsSellerWhoReadsIt() throws Exception {
        CreatedUser seller = createSeller();
        String sellerToken = sellerToken(seller);
        String sale = sell(saleBody(), sellerToken).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long saleId = ((Number) JsonPath.read(sale, "$.id")).longValue();
        Number saleNumber = JsonPath.read(sale, "$.saleNumber");

        cancelSale(saleId, "Wrong product");

        awaitCount(() -> notifications(seller.id(), "SALE_CANCELLED", saleId), 1);
        getJson(NOTIFICATIONS + "/unread-count", sellerToken).andExpect(jsonPath("$.count").value(1));
        String list = getJson(NOTIFICATIONS, sellerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].type").value("SALE_CANCELLED"))
                .andExpect(jsonPath("$.content[0].subjectId").value(saleId))
                .andExpect(jsonPath("$.content[0].subjectLabel").value(saleNumber.toString()))
                .andExpect(jsonPath("$.content[0].amount").value(10.0))
                .andExpect(jsonPath("$.content[0].reason").value("Wrong product"))
                .andExpect(jsonPath("$.content[0].actor.name").exists())
                .andExpect(jsonPath("$.content[0].readAt").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        Long notificationId = ((Number) JsonPath.read(list, "$.content[0].id")).longValue();
        // The admin who cancelled it isn't told.
        assertThat(notifications(adminId(), "SALE_CANCELLED", saleId)).isZero();

        // Someone else's notification, or a missing one, answers 404.
        String otherToken = loginOk(createSeller().email(), DEFAULT_PASSWORD).accessToken();
        postJson(readReceiptUrl(notificationId), "", otherToken).andExpect(status().isNotFound());
        postJson(readReceiptUrl(Long.MAX_VALUE), "", sellerToken).andExpect(status().isNotFound());
        getJson(NOTIFICATIONS + "/unread-count", otherToken).andExpect(jsonPath("$.count").value(0));

        postJson(readReceiptUrl(notificationId), "", sellerToken).andExpect(status().isNoContent());
        String readAt = JsonPath.read(getJson(NOTIFICATIONS, sellerToken)
                .andExpect(jsonPath("$.content[0].readAt").exists())
                .andReturn().getResponse().getContentAsString(), "$.content[0].readAt");
        getJson(NOTIFICATIONS + "/unread-count", sellerToken).andExpect(jsonPath("$.count").value(0));
        getJson(NOTIFICATIONS + "?unreadOnly=true", sellerToken).andExpect(jsonPath("$.content.length()").value(0));
        // Marking again keeps the first read time.
        postJson(readReceiptUrl(notificationId), "", sellerToken).andExpect(status().isNoContent());
        getJson(NOTIFICATIONS, sellerToken).andExpect(jsonPath("$.content[0].readAt").value(readAt));
    }

    @Test
    void aRejectedSecondCancellationAddsNoNotification() throws Exception {
        CreatedUser seller = createSeller();
        String sellerToken = sellerToken(seller);
        Long saleId = readId(sell(saleBody(), sellerToken).andExpect(status().isCreated()));
        cancelSale(saleId, "Returned");
        awaitCount(() -> notifications(seller.id(), "SALE_CANCELLED", saleId), 1);

        postJson("/api/v1/sales/" + saleId + "/cancellation", "{\"reason\": \"Again\"}", adminAccessToken())
                .andExpect(status().isConflict());

        assertStaysAt(() -> notifications(seller.id(), "SALE_CANCELLED", saleId), 1);
    }

    @Test
    void anEventOfARolledBackTransactionNotifiesNoOne() throws Exception {
        CreatedUser seller = createSeller();
        Long adminId = adminId();

        transactionTemplate.executeWithoutResult(tx -> {
            eventPublisher.publishEvent(new SaleCancelledEvent(0L, 1L, seller.id(), adminId, BigDecimal.TEN, "Test"));
            tx.setRollbackOnly();
        });

        assertStaysAt(() -> notifications(seller.id(), "SALE_CANCELLED", 0L), 0);
    }

    @Test
    void readReceiptsMarkEveryNotificationAndTheListIsNewestFirst() throws Exception {
        CreatedUser seller = createSeller();
        String sellerToken = sellerToken(seller);
        CreatedUser other = createSeller();
        insertNotification(other.id(), "1 hour", null);
        Long first = readId(sell(saleBody(), sellerToken).andExpect(status().isCreated()));
        Long second = readId(sell(saleBody(), sellerToken).andExpect(status().isCreated()));
        cancelSale(first, "One");
        awaitCount(() -> notifications(seller.id(), "SALE_CANCELLED", first), 1);
        cancelSale(second, "Two");
        awaitCount(() -> notifications(seller.id(), "SALE_CANCELLED", second), 1);
        getJson(NOTIFICATIONS + "?unreadOnly=true", sellerToken)
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].subjectId").value(second))
                .andExpect(jsonPath("$.content[1].subjectId").value(first));

        postJson(NOTIFICATIONS + "/read-receipts", "", sellerToken).andExpect(status().isNoContent());

        getJson(NOTIFICATIONS + "/unread-count", sellerToken).andExpect(jsonPath("$.count").value(0));
        getJson(NOTIFICATIONS, sellerToken).andExpect(jsonPath("$.content.length()").value(2));
        // Only the caller's.
        String otherToken = loginOk(other.email(), DEFAULT_PASSWORD).accessToken();
        getJson(NOTIFICATIONS + "/unread-count", otherToken).andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void aCancelledPurchaseNotifiesTheOtherActiveManagers() throws Exception {
        CreatedUser supervisor = createUser("SUPERVISOR");
        String supervisorToken = loginOk(supervisor.email(), DEFAULT_PASSWORD).accessToken();
        CreatedUser inactiveSupervisor = createUser("SUPERVISOR");
        putJson("/api/v1/users/" + inactiveSupervisor.id(), """
                {"name": "Test User", "role": "SUPERVISOR", "active": false}
                """, adminAccessToken()).andExpect(status().isOk());
        CreatedUser seller = createSeller();
        Long purchaseId = purchase();

        postJson("/api/v1/purchases/" + purchaseId + "/cancellation", "{\"reason\": \"Duplicated\"}",
                supervisorToken)
                .andExpect(status().isOk());

        awaitCount(() -> notifications(adminId(), "PURCHASE_CANCELLED", purchaseId), 1);
        assertThat(notifications(supervisor.id(), "PURCHASE_CANCELLED", purchaseId)).isZero();
        assertThat(notifications(inactiveSupervisor.id(), "PURCHASE_CANCELLED", purchaseId)).isZero();
        assertThat(notifications(seller.id(), "PURCHASE_CANCELLED", purchaseId)).isZero();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT subject_label, amount, reason FROM notification
                WHERE recipient_id = ? AND type = 'PURCHASE_CANCELLED' AND subject_id = ?""", adminId(), purchaseId))
                .containsEntry("reason", "Duplicated")
                .hasEntrySatisfying("amount", amount -> assertThat(amount.toString()).isEqualTo("10.00"))
                .hasEntrySatisfying("subject_label", label -> assertThat(label.toString()).startsWith("Supplier "));
    }

    @Test
    void aManagerTurnedSellerNoLongerReadsThePurchaseNotifications() throws Exception {
        CreatedUser supervisor = createUser("SUPERVISOR");
        String adminToken = adminAccessToken();
        insertNotification(supervisor.id(), "1 hour", null);
        Long purchaseId = purchase();
        postJson("/api/v1/purchases/" + purchaseId + "/cancellation", "{\"reason\": \"Duplicated\"}", adminToken)
                .andExpect(status().isOk());
        awaitCount(() -> notifications(supervisor.id(), "PURCHASE_CANCELLED", purchaseId), 1);
        String supervisorToken = loginOk(supervisor.email(), DEFAULT_PASSWORD).accessToken();
        getJson(NOTIFICATIONS + "/unread-count", supervisorToken).andExpect(jsonPath("$.count").value(2));

        putJson("/api/v1/users/" + supervisor.id(), """
                {"name": "Test User", "role": "SELLER", "active": true}
                """, adminToken).andExpect(status().isOk());

        // The role change ended the old session; the new one reads as a seller.
        String sellerToken = loginOk(supervisor.email(), DEFAULT_PASSWORD).accessToken();
        getJson(NOTIFICATIONS + "/unread-count", sellerToken).andExpect(jsonPath("$.count").value(1));
        getJson(NOTIFICATIONS, sellerToken)
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].type").value("PASSWORD_RESET"));
        getJson(NOTIFICATIONS + "?unreadOnly=true", sellerToken).andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void anAssignedDrawerNotifiesItsSellersAndAManagersClosingItsResponsible() throws Exception {
        CreatedUser supervisor = createUser("SUPERVISOR");
        String supervisorToken = loginOk(supervisor.email(), DEFAULT_PASSWORD).accessToken();
        CreatedUser responsible = createSeller();
        CreatedUser member = createSeller();
        String responsibleToken = loginOk(responsible.email(), DEFAULT_PASSWORD).accessToken();
        Long drawerId = readId(postJson(CASH_DRAWERS + "/assigned", """
                {"openingFloat": 300, "responsibleId": %d, "memberIds": [%d, %d]}
                """.formatted(responsible.id(), responsible.id(), member.id()), supervisorToken)
                .andExpect(status().isCreated()));

        awaitCount(() -> notifications(responsible.id(), "CASH_DRAWER_ASSIGNED", drawerId), 1);
        getJson(NOTIFICATIONS, responsibleToken)
                .andExpect(jsonPath("$.content[0].type").value("CASH_DRAWER_ASSIGNED"))
                .andExpect(jsonPath("$.content[0].subjectId").value(drawerId))
                .andExpect(jsonPath("$.content[0].subjectLabel").value("Test User"))
                .andExpect(jsonPath("$.content[0].amount").value(300.0))
                .andExpect(jsonPath("$.content[0].actor.id").value(supervisor.id()));
        assertThat(notifications(member.id(), "CASH_DRAWER_ASSIGNED", drawerId)).isOne();

        postJson(CASH_DRAWERS + "/" + drawerId + "/closing", "{\"countedCash\": 299}", supervisorToken)
                .andExpect(status().isOk());

        awaitCount(() -> notifications(responsible.id(), "CASH_DRAWER_CLOSED", drawerId), 1);
        getJson(NOTIFICATIONS, responsibleToken)
                .andExpect(jsonPath("$.content[0].type").value("CASH_DRAWER_CLOSED"))
                .andExpect(jsonPath("$.content[0].subjectLabel").value(LocalDate.now(storeProperties.timeZone()).toString()))
                .andExpect(jsonPath("$.content[0].amount").value(-1.0));
        assertThat(notifications(member.id(), "CASH_DRAWER_CLOSED", drawerId)).isZero();
    }

    @Test
    void closingYourOwnDrawerNotifiesNoOne() throws Exception {
        CreatedUser seller = createSeller();
        String token = loginOk(seller.email(), DEFAULT_PASSWORD).accessToken();
        Long drawerId = openDrawer(token, "0");

        postJson(CASH_DRAWERS + "/" + drawerId + "/closing", "{\"countedCash\": 0}", token).andExpect(status().isOk());

        assertStaysAt(() -> notifications(seller.id(), "CASH_DRAWER_CLOSED", drawerId), 0);
    }

    @Test
    void accountChangesByAnAdminNotifyTheOwnerWithoutValues() throws Exception {
        CreatedUser seller = createSeller();
        String adminToken = adminAccessToken();

        putJson("/api/v1/users/" + seller.id(), """
                {"name": "Test User", "email": "renamed-%s@test.com", "role": "SELLER", "active": true,
                 "phone": "+525512345678"}
                """.formatted(UUID.randomUUID()), adminToken).andExpect(status().isOk());
        putJson("/api/v1/users/" + seller.id() + "/password", """
                {"temporaryPassword": "temporary-pass-1"}
                """, adminToken).andExpect(status().isNoContent());

        for (String type : new String[] {"EMAIL_CHANGED", "PHONE_CHANGED", "PASSWORD_RESET"}) {
            awaitCount(() -> jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM notification
                    WHERE recipient_id = ? AND type = ? AND actor_id = ?
                      AND subject_id IS NULL AND subject_label IS NULL AND amount IS NULL AND reason IS NULL""",
                    Integer.class, seller.id(), type, adminId()), 1);
        }
    }

    @Test
    void cleanupDeletesReadNotificationsAfter30DaysAndAnyAfter90() throws Exception {
        CreatedUser seller = createSeller();
        insertNotification(seller.id(), "31 days", "31 days");
        insertNotification(seller.id(), "2 days", "1 day");
        insertNotification(seller.id(), "31 days", null);
        insertNotification(seller.id(), "91 days", null);

        notificationCleanup.deleteOldNotifications();

        assertThat(jdbcTemplate.queryForList("""
                SELECT read_at IS NULL FROM notification WHERE recipient_id = ? ORDER BY created_at""",
                Boolean.class, seller.id())).containsExactly(true, false);
    }

    private void cancelSale(Long saleId, String reason) throws Exception {
        postJson("/api/v1/sales/" + saleId + "/cancellation", """
                {"reason": "%s"}
                """.formatted(reason), adminAccessToken())
                .andExpect(status().isOk());
    }

    // A 10.00 purchase from a new supplier of a product nothing has sold, so it can be cancelled.
    private Long purchase() throws Exception {
        return readId(postJson("/api/v1/purchases", """
                {"supplierId": %d, "items": [{"productId": %d, "quantity": 2, "unitCost": 5}]}
                """.formatted(createSupplier(), createProduct(categoryId("Pantry"), 0)), adminAccessToken())
                .andExpect(status().isCreated()));
    }

    private int notifications(Long recipientId, String type, Long subjectId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM notification WHERE recipient_id = ? AND type = ? AND subject_id = ?""",
                Integer.class, recipientId, type, subjectId);
    }

    private static void awaitCount(IntSupplier count, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (count.getAsInt() != expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Expected " + expected + " notifications, found " + count.getAsInt());
            }
            Thread.sleep(50);
        }
    }

    // For notifications that must not arrive: long enough for a background save to have happened.
    private static void assertStaysAt(IntSupplier count, int expected) throws InterruptedException {
        Thread.sleep(500);
        assertThat(count.getAsInt()).isEqualTo(expected);
    }

    private void insertNotification(Long recipientId, String age, String readAge) {
        jdbcTemplate.update("""
                INSERT INTO notification (recipient_id, type, created_at, read_at)
                VALUES (?, 'PASSWORD_RESET', now() - CAST(? AS interval), now() - CAST(? AS interval))""",
                recipientId, age, readAge);
    }

    private Long adminId() {
        return jdbcTemplate.queryForObject("SELECT id FROM app_user WHERE email = ?", Long.class, ADMIN_EMAIL);
    }

    // One unit of a new 10.00 product with no tax, stocked for the sale.
    private String saleBody() throws Exception {
        Long product = createPricedProduct("10.00", "0");
        addStock(product, 1);
        return """
                {"paymentMethod": "CASH", "items": [{"productId": %d, "quantity": 1}]}
                """.formatted(product);
    }

    private static String readReceiptUrl(Long notificationId) {
        return NOTIFICATIONS + "/" + notificationId + "/read-receipt";
    }
}
