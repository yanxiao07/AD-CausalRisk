package com.adcausalrisk.backend.auth.dto;

/**
 * Self-service profile update. Every field is optional: omitted fields keep their current value.
 * Display name/department/phone are limited to the same lengths as the admin editor; office hours
 * are only meaningful for doctors.
 */
public record UpdateProfileRequest(
        String displayName, String department, String phone, String officeHours) {}
