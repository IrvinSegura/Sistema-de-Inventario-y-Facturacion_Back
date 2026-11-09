package com.irvin.inventory.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

import com.irvin.inventory.auth.dto.PasswordResetCodeRequest;
import com.irvin.inventory.auth.dto.ResetPasswordRequest;
import com.irvin.inventory.common.exception.BadRequestException;
import com.irvin.inventory.user.AccountChangedEvent;
import com.irvin.inventory.user.AccountEventRecorder;
import com.irvin.inventory.user.AccountEventType;
import com.irvin.inventory.user.User;
import com.irvin.inventory.user.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

// Forgotten password: a 6-digit code sent by email or SMS, then the code plus a new password.
// Neither step reveals whether an account exists. Lock order: user row -> its codes -> its refresh
// tokens (same as AuthService and UserService).
@Service
public class PasswordResetService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int CODE_RANGE = 1_000_000;
    private static final Duration RATE_WINDOW = Duration.ofHours(1);
    private static final Duration RETENTION = Duration.ofDays(1);
    private static final String INVALID_CODE = "Invalid or expired code";

    private final UserRepository userRepository;
    private final PasswordResetCodeRepository codeRepository;
    private final AuthService authService;
    private final PasswordResetDispatcher dispatcher;
    private final PasswordEncoder passwordEncoder;
    private final PasswordResetProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final AccountEventRecorder accountEvents;
    private final ApplicationEventPublisher eventPublisher;

    public PasswordResetService(UserRepository userRepository, PasswordResetCodeRepository codeRepository,
            AuthService authService, PasswordResetDispatcher dispatcher, PasswordEncoder passwordEncoder,
            PasswordResetProperties properties, Clock clock, TransactionTemplate transactionTemplate,
            AccountEventRecorder accountEvents, ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.codeRepository = codeRepository;
        this.authService = authService;
        this.dispatcher = dispatcher;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.clock = clock;
        this.transactionTemplate = transactionTemplate;
        this.accountEvents = accountEvents;
        this.eventPublisher = eventPublisher;
    }

    // Silently does nothing for unknown or inactive accounts, SMS without a phone, or when the
    // user asks too often: the client always gets the same answer.
    public void requestCode(PasswordResetCodeRequest request) {
        User user = userRepository.findByEmail(request.email()).orElse(null);
        if (user == null || !user.isActive()) {
            return;
        }
        if (request.channel() == ResetChannel.SMS && user.getPhone() == null) {
            return;
        }
        String code = transactionTemplate.execute(status -> issueCode(user.getId(), request.email(), request.channel()));
        if (code != null) {
            dispatcher.dispatch(new ResetMessage(request.channel(), user.getId(), user.getName(), user.getEmail(),
                    user.getPhone(), code, properties.codeTtl(), Locale.forLanguageTag(request.language())));
        }
    }

    // BCrypt runs before the transaction so it doesn't hold a pool connection. Wrong attempts are
    // counted and committed even though the request fails.
    public void resetPassword(ResetPasswordRequest request) {
        String newPasswordHash = passwordEncoder.encode(request.newPassword());
        User user = userRepository.findByEmail(request.email()).orElse(null);
        boolean reset = user != null && Boolean.TRUE.equals(transactionTemplate.execute(
                status -> applyReset(user.getId(), request.code(), newPasswordHash)));
        if (!reset) {
            throw new BadRequestException(INVALID_CODE);
        }
    }

    @Scheduled(cron = "0 15 3 * * *")
    @Transactional
    public void deleteOldCodes() {
        codeRepository.deleteCreatedBefore(clock.instant().minus(RETENTION));
    }

    // After the user's password or email changes outside this flow. Caller must hold the user row lock.
    @Transactional(propagation = Propagation.MANDATORY)
    public void invalidateCodes(Long userId) {
        codeRepository.invalidateAllByUserId(userId, clock.instant());
    }

    // Returns the raw code, or null when the user must wait before asking again. Also null when an admin
    // changed the email since it was looked up: the code would go to the old address.
    private String issueCode(Long userId, String email, ResetChannel channel) {
        User user = userRepository.findByIdForUpdate(userId).orElse(null);
        if (user == null || !user.isActive() || !user.getEmail().equals(email)) {
            return null;
        }
        Instant now = clock.instant();
        boolean tooSoon = codeRepository.findFirstByUserIdOrderByCreatedAtDescIdDesc(userId)
                .map(latest -> latest.getCreatedAt().plus(properties.resendCooldown()).isAfter(now))
                .orElse(false);
        if (tooSoon || codeRepository.countByUserIdAndCreatedAtAfter(userId, now.minus(RATE_WINDOW))
                >= properties.maxCodesPerHour()) {
            return null;
        }
        // Only the newest code works.
        codeRepository.invalidateAllByUserId(userId, now);

        String rawCode = "%06d".formatted(RANDOM.nextInt(CODE_RANGE));
        PasswordResetCode code = new PasswordResetCode();
        code.setUser(user);
        code.setCodeHash(AuthService.hash(rawCode));
        code.setChannel(channel);
        code.setExpiresAt(now.plus(properties.codeTtl()));
        codeRepository.save(code);
        return rawCode;
    }

    private boolean applyReset(Long userId, String rawCode, String newPasswordHash) {
        User user = userRepository.findByIdForUpdate(userId).orElse(null);
        if (user == null || !user.isActive()) {
            return false;
        }
        Instant now = clock.instant();
        PasswordResetCode code = codeRepository.findFirstByUserIdOrderByCreatedAtDescIdDesc(userId)
                .filter(latest -> latest.isUsable(now))
                .orElse(null);
        if (code == null) {
            return false;
        }
        if (!matches(rawCode, code.getCodeHash())) {
            code.setAttempts(code.getAttempts() + 1);
            if (code.getAttempts() >= properties.maxAttempts()) {
                code.invalidate(now);
            }
            return false;
        }
        code.invalidate(now);
        user.setPasswordHash(newPasswordHash);
        // The user chose it, so a temporary one an admin set no longer has to be changed.
        user.setPasswordChangeRequired(false);
        user.invalidateIssuedTokens();
        accountEvents.record(userId, userId, AccountEventType.PASSWORD_RECOVERED, null, null);
        // Sent after commit, so a failed reset tells no one.
        eventPublisher.publishEvent(new AccountChangedEvent(AccountChangedEvent.Kind.PASSWORD_RECOVERED, userId,
                userId, user.getName(), user.getEmail(), null));
        // Whoever knew the old password loses their sessions.
        authService.revokeAllSessions(userId);
        return true;
    }

    // Constant-time comparison, so response time doesn't leak how many characters matched.
    private static boolean matches(String rawCode, String codeHash) {
        return MessageDigest.isEqual(
                AuthService.hash(rawCode).getBytes(StandardCharsets.UTF_8), codeHash.getBytes(StandardCharsets.UTF_8));
    }
}
