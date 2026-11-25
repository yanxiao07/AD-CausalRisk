package com.adcausalrisk.backend.patient;

import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.Envelope;
import com.adcausalrisk.backend.common.ErrorCode;
import com.adcausalrisk.backend.security.AppUserPrincipal;

import jakarta.validation.Valid;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PatientConsentController {

    private final PatientConsentService consentService;

    public PatientConsentController(PatientConsentService consentService) {
        this.consentService = consentService;
    }

    /**
     * 两个开关都用包装类型：请求体漏字段时应该是「参数无效」，而不是被静默当成 false 把
     * 授权收回去。撤回和授予都是患者主动提交的最终状态。
     */
    public record ConsentRequest(Boolean researchUse, Boolean followUpContact) {}

    @GetMapping("/api/patients/me/consent")
    public Envelope<PatientConsentService.ConsentView> currentConsent(Authentication auth) {
        AppUserPrincipal principal = (AppUserPrincipal) auth.getPrincipal();
        return Envelope.success(consentService.currentConsent(principal));
    }

    @PutMapping("/api/patients/me/consent")
    public Envelope<PatientConsentService.ConsentView> saveConsent(
            @Valid @RequestBody ConsentRequest request, Authentication auth) {
        AppUserPrincipal principal = (AppUserPrincipal) auth.getPrincipal();
        if (request == null || request.researchUse() == null || request.followUpContact() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请同时提交科研使用与随访联系两项授权");
        }
        return Envelope.success(consentService.saveConsent(
                principal, request.researchUse(), request.followUpContact()));
    }
}
