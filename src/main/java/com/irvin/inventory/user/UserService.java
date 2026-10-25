package com.irvin.inventory.user;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.irvin.inventory.auth.AuthService;
import com.irvin.inventory.auth.PasswordResetService;
import com.irvin.inventory.common.Sorting;
import com.irvin.inventory.common.exception.BadRequestException;
import com.irvin.inventory.common.exception.ConflictException;
import com.irvin.inventory.common.exception.NotFoundException;
import com.irvin.inventory.user.AccountChangedEvent.Kind;
import com.irvin.inventory.user.dto.AccountEventResponse;
import com.irvin.inventory.user.dto.ChangePasswordRequest;
import com.irvin.inventory.user.dto.CreateUserRequest;
import com.irvin.inventory.user.dto.ResetUserPasswordRequest;
import com.irvin.inventory.user.dto.SellerResponse;
import com.irvin.inventory.user.dto.UpdateUserRequest;
import com.irvin.inventory.user.dto.UserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Lock order: active admins (by id) -> target user -> its reset codes -> its refresh tokens.
// Every change to an account is recorded (AccountEventRecorder) in the same transaction.
@Service
@RequiredArgsConstructor
public class UserService {

    // Only the SORTABLE fields can be sorted by: anything else could order by sensitive columns.
    private static final Sort DEFAULT_SORT = Sort.by("name", "id");
    // Orders a client may ask for (API name -> entity path); the default one breaks ties.
    private static final Map<String, String> SORTABLE = Map.of(
            "name", "name",
            "email", "email",
            "role", "role",
            "createdAt", "createdAt");
    // From V1: two requests can both pass existsByEmail, and then this unique constraint decides.
    private static final String EMAIL_CONSTRAINT = "uk_app_user_email";
    // old_value of a PASSWORD_CHANGED that replaced an admin's temporary password: whoever knew that one
    // (the admin too) could have made it, so the history says so.
    static final String REPLACED_TEMPORARY = "TEMPORARY";

    private final UserRepository userRepository;
    private final AccountEventRecorder accountEvents;
    private final AuthService authService;
    private final PasswordResetService passwordResetService;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    // adminId is null for the initial admin (AdminInitializer), which is recorded as creating itself.
    @Transactional
    public UserResponse create(CreateUserRequest request, Long adminId) {
        if (userRepository.existsByEmail(request.email())) {
            throw emailTaken();
        }
        User user = new User();
        user.setName(request.name());
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(request.role());
        user.setPhone(request.phone());
        User saved = saveCheckingEmail(user);
        accountEvents.record(saved.getId(), adminId == null ? saved.getId() : adminId, AccountEventType.CREATED,
                null, null);
        return UserResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public Page<UserResponse> findAll(Pageable pageable) {
        Pageable sorted = Sorting.apply(pageable, SORTABLE, DEFAULT_SORT);
        return userRepository.findAll(sorted).map(UserResponse::from);
    }

    @Transactional(readOnly = true)
    public UserResponse findById(Long id) {
        return UserResponse.from(userRepository.findById(id).orElseThrow(UserService::notFound));
    }

    @Transactional(readOnly = true)
    public List<SellerResponse> findSellers() {
        return userRepository.findSellers().stream().map(SellerResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public Page<AccountEventResponse> findEvents(Long userId, Pageable pageable) {
        if (!userRepository.existsById(userId)) {
            throw notFound();
        }
        return accountEvents.findForUser(userId, pageable);
    }

    @Transactional
    public UserResponse update(Long id, UpdateUserRequest request, Long adminId) {
        boolean removesAdminAccess = request.role() != Role.ADMIN || !request.active();
        List<User> activeAdmins = removesAdminAccess ? userRepository.lockActiveByRole(Role.ADMIN) : List.of();
        User user = lockUser(id);

        boolean isActiveAdmin = user.getRole() == Role.ADMIN && user.isActive();
        if (removesAdminAccess && isActiveAdmin && activeAdmins.size() <= 1) {
            throw new ConflictException("At least one active admin is required");
        }

        Role oldRole = user.getRole();
        boolean wasActive = user.isActive();
        String oldPhone = user.getPhone();
        String oldEmail = user.getEmail();
        boolean emailChanges = request.email() != null && !request.email().equals(oldEmail);
        boolean phoneChanges = !Objects.equals(oldPhone, request.phone());
        if (emailChanges && userRepository.existsByEmail(request.email())) {
            throw emailTaken();
        }
        // Role or status changes must not survive in issued tokens: refresh tokens are revoked below and
        // access tokens stop working with the new credentials version.
        boolean endSessions = oldRole != request.role() || (wasActive && !request.active());
        user.setName(request.name());
        user.setRole(request.role());
        user.setPhone(request.phone());
        user.setActive(request.active());
        if (emailChanges) {
            user.setEmail(request.email());
        }
        if (endSessions) {
            user.invalidateIssuedTokens();
        }
        // Writes every field before the @Modifying queries below clear the persistence context.
        saveCheckingEmail(user);
        // Both recover the password: codes sent to the old email or phone must stop working.
        if (emailChanges || phoneChanges) {
            passwordResetService.invalidateCodes(id);
        }
        if (endSessions) {
            authService.revokeAllSessions(id);
        }

        if (oldRole != request.role()) {
            accountEvents.record(id, adminId, AccountEventType.ROLE_CHANGED, oldRole.name(), request.role().name());
        }
        if (wasActive != request.active()) {
            accountEvents.record(id, adminId,
                    request.active() ? AccountEventType.REACTIVATED : AccountEventType.DEACTIVATED, null, null);
        }
        if (emailChanges) {
            accountEvents.record(id, adminId, AccountEventType.EMAIL_CHANGED, oldEmail, request.email());
            eventPublisher.publishEvent(new AccountChangedEvent(Kind.EMAIL_CHANGED, id, adminId, request.name(),
                    oldEmail, request.email()));
        }
        if (phoneChanges) {
            accountEvents.record(id, adminId, AccountEventType.PHONE_CHANGED, oldPhone, request.phone());
            eventPublisher.publishEvent(new AccountChangedEvent(Kind.PHONE_CHANGED, id, adminId, request.name(),
                    user.getEmail(), request.phone()));
        }
        return UserResponse.from(user);
    }

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = lockUser(userId);
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BadRequestException("Current password is incorrect");
        }
        // Otherwise a temporary password an admin set (and knows) could be "changed" to itself.
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new BadRequestException("New password must differ from the current one");
        }
        String replaced = user.isPasswordChangeRequired() ? REPLACED_TEMPORARY : null;
        replacePassword(user, request.newPassword(), false);
        accountEvents.record(userId, userId, AccountEventType.PASSWORD_CHANGED, replaced, null);
    }

    // The admin's own account goes through changePassword, which asks for the current one.
    @Transactional
    public void resetUserPassword(Long id, ResetUserPasswordRequest request, Long adminId) {
        if (id.equals(adminId)) {
            throw new BadRequestException("Change your own password from your account");
        }
        User user = lockUser(id);
        String name = user.getName();
        String email = user.getEmail();
        replacePassword(user, request.temporaryPassword(), true);
        accountEvents.record(id, adminId, AccountEventType.PASSWORD_RESET, null, null);
        eventPublisher.publishEvent(new AccountChangedEvent(Kind.PASSWORD_RESET, id, adminId, name, email, null));
    }

    // Whoever knew the old password, or got a reset code for it, loses access at once: refresh tokens
    // are revoked and access tokens carry an older credentials version. Caller holds the user lock.
    private void replacePassword(User user, String rawPassword, boolean changeRequired) {
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setPasswordChangeRequired(changeRequired);
        user.invalidateIssuedTokens();
        passwordResetService.invalidateCodes(user.getId());
        authService.revokeAllSessions(user.getId());
    }

    private User saveCheckingEmail(User user) {
        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            String cause = ex.getMostSpecificCause().getMessage();
            if (cause != null && cause.contains(EMAIL_CONSTRAINT)) {
                throw emailTaken();
            }
            throw ex;
        }
    }

    private static ConflictException emailTaken() {
        return new ConflictException("Email is already registered");
    }

    private User lockUser(Long id) {
        return userRepository.findByIdForUpdate(id).orElseThrow(UserService::notFound);
    }

    private static NotFoundException notFound() {
        return new NotFoundException("User not found");
    }
}
