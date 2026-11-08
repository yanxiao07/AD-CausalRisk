package com.adcausalrisk.backend.user;

import com.adcausalrisk.backend.common.Envelope;
import com.adcausalrisk.backend.security.AppUserPrincipal;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
public class UserManagementController {
    private final UserManagementService users;

    public UserManagementController(UserManagementService users) {
        this.users = users;
    }

    @GetMapping("/api/users")
    public Envelope<?> list(@RequestParam Map<String, String> filter, Authentication auth) {
        return Envelope.success(users.list(filter, (AppUserPrincipal) auth.getPrincipal()));
    }

    @PostMapping("/api/users")
    public Envelope<?> create(@RequestBody Map<String, Object> body, Authentication auth) {
        return Envelope.success(
                Map.of("id", users.save(null, body, (AppUserPrincipal) auth.getPrincipal())));
    }

    @PatchMapping("/api/users/{id}")
    public Envelope<?> update(
            @PathVariable long id, @RequestBody Map<String, Object> body, Authentication auth) {
        return Envelope.success(
                Map.of("id", users.save(id, body, (AppUserPrincipal) auth.getPrincipal())));
    }

    @GetMapping("/api/subjects/{id}/permissions")
    public Envelope<?> permissions(@PathVariable long id, Authentication auth) {
        return Envelope.success(users.permissions(id, (AppUserPrincipal) auth.getPrincipal()));
    }

    public record Grant(long userId, String permissionType) {}

    @PostMapping("/api/subjects/{id}/permissions")
    public Envelope<?> grant(@PathVariable long id, @RequestBody Grant body, Authentication auth) {
        users.grant(
                id, body.userId(), body.permissionType(), (AppUserPrincipal) auth.getPrincipal());
        return Envelope.success(Map.of("saved", true));
    }

    @DeleteMapping("/api/subjects/{id}/permissions/{permission}")
    public Envelope<?> revoke(
            @PathVariable long id, @PathVariable long permission, Authentication auth) {
        users.revoke(id, permission, (AppUserPrincipal) auth.getPrincipal());
        return Envelope.success(Map.of("saved", true));
    }
}
