package com.adcausalrisk.backend.auth.dto;

/** Current-password confirmation + new password; the server stores only a BCrypt hash. */
public record ChangePasswordRequest(String currentPassword, String newPassword) {}
