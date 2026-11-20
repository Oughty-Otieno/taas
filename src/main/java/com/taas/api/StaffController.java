package com.taas.api;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.taas.common.NotFoundException;
import com.taas.ledger.ActionLedger;
import com.taas.staff.StaffService;

/** Staff users and their roles (admin, staff:manage). */
@RestController
public class StaffController {

    public record StaffDetail(StaffService.StaffUser user, List<ActionView> history, List<ActionView> recentActions) {
    }

    private final StaffService service;
    private final ActionLedger actions;

    public StaffController(StaffService service, ActionLedger actions) {
        this.service = service;
        this.actions = actions;
    }

    @GetMapping("/v1/staff")
    public List<StaffService.StaffUser> list() {
        return service.users();
    }

    /** A user, the changes made to their account, and what they have done recently. */
    @GetMapping("/v1/staff/{userId}")
    public StaffDetail get(@PathVariable String userId) {
        StaffService.StaffUser u = service.user(userId)
                .orElseThrow(() -> new NotFoundException("User " + userId + " not found"));
        ActionLedger.Filter byUser = new ActionLedger.Filter(null, userId, null, null, List.of(), null, null, null);
        return new StaffDetail(u, actions.concerning(userId, 50).stream().map(ActionView::of).toList(),
                actions.list(byUser, null, 20, true).stream().map(ActionView::of).toList());
    }

    @PostMapping("/v1/staff")
    public ResponseEntity<Object> create(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(Http.IDEMPOTENCY_KEY) String key, @Valid @RequestBody StaffService.Create body) {
        return Http.send(service.create(Http.caller(jwt, key), body));
    }

    @PostMapping("/v1/staff/{userId}/role")
    public ResponseEntity<Object> changeRole(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(Http.IDEMPOTENCY_KEY) String key, @PathVariable String userId,
            @Valid @RequestBody StaffService.ChangeRole body) {
        return Http.send(service.changeRole(Http.caller(jwt, key), userId, body));
    }

    @PostMapping("/v1/staff/{userId}/status")
    public ResponseEntity<Object> changeStatus(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(Http.IDEMPOTENCY_KEY) String key, @PathVariable String userId,
            @Valid @RequestBody StaffService.ChangeStatus body) {
        return Http.send(service.changeStatus(Http.caller(jwt, key), userId, body));
    }
}
