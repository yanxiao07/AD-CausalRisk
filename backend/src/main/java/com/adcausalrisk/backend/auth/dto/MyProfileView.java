package com.adcausalrisk.backend.auth.dto;

/** Self-service profile view: no credential material is ever included. */
public record MyProfileView(
        long id,
        String username,
        String displayName,
        String department,
        String phone,
        String officeHours) {}
