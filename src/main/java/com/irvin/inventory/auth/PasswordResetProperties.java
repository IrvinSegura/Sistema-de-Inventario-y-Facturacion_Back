package com.irvin.inventory.auth;

import java.time.Duration;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

// Limits of the forgotten-password flow. logCodes writes codes to the log when no email or SMS
// provider is configured: for local development only, never in production.
@Validated
@ConfigurationProperties("app.password-reset")
public record PasswordResetProperties(
        @NotNull Duration codeTtl,
        @Positive int maxAttempts,
        @NotNull Duration resendCooldown,
        @Positive int maxCodesPerHour,
        boolean logCodes) {
}
