package com.irvin.inventory.notification;

import com.irvin.inventory.notification.dto.NotificationResponse;
import com.irvin.inventory.notification.dto.UnreadCountResponse;
import com.irvin.inventory.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Notifications", description = "The signed-in user's own notifications, any role")
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "The user's notifications, newest first",
            description = "Read notifications are kept 30 days; any notification, 90. PURCHASE_CANCELLED is "
                    + "listed to managers only. Values by type (the rest are null): SALE_CANCELLED and "
                    + "PURCHASE_CANCELLED: subjectId (sale or purchase), subjectLabel (sale number or supplier), "
                    + "amount (total), reason. CASH_DRAWER_ASSIGNED: subjectId (drawer), subjectLabel "
                    + "(responsible's name), amount (opening float). CASH_DRAWER_CLOSED: subjectId (drawer), "
                    + "subjectLabel (business date), amount (counted minus expected cash). EMAIL_CHANGED, "
                    + "PHONE_CHANGED, PASSWORD_RESET: none.")
    @GetMapping
    public Page<NotificationResponse> findMine(
            @Parameter(description = "Only notifications not read yet")
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @ParameterObject Pageable pageable,
            @AuthenticationPrincipal Jwt jwt) {
        return notificationService.findForUser(CurrentUser.from(jwt), unreadOnly, pageable);
    }

    @Operation(summary = "How many of the user's notifications are unread")
    @GetMapping("/unread-count")
    public UnreadCountResponse countUnread(@AuthenticationPrincipal Jwt jwt) {
        return notificationService.countUnread(CurrentUser.from(jwt));
    }

    @Operation(summary = "Mark one notification as read",
            description = "Marking it again keeps the first read time. 404 if it isn't the user's.")
    @PostMapping("/{id}/read-receipt")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        notificationService.markRead(id, Long.valueOf(jwt.getSubject()));
    }

    @Operation(summary = "Mark all of the user's notifications as read")
    @PostMapping("/read-receipts")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(@AuthenticationPrincipal Jwt jwt) {
        notificationService.markAllRead(Long.valueOf(jwt.getSubject()));
    }
}
