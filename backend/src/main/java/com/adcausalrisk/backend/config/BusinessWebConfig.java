package com.adcausalrisk.backend.config;

import com.adcausalrisk.backend.audit.BusinessAuditInterceptor;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class BusinessWebConfig implements WebMvcConfigurer {
    private final BusinessAuditInterceptor audit;

    public BusinessWebConfig(BusinessAuditInterceptor audit) {
        this.audit = audit;
    }

    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(audit).addPathPatterns("/api/**");
    }
}
