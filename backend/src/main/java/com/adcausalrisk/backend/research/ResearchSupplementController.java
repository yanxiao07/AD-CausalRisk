package com.adcausalrisk.backend.research;

import com.adcausalrisk.backend.common.Envelope;
import com.adcausalrisk.backend.security.AppUserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/research/supplements")
public class ResearchSupplementController {
    private final ResearchSupplementService supplements;

    public ResearchSupplementController(ResearchSupplementService supplements) {
        this.supplements = supplements;
    }

    record CreateRequest(long originalTaskId, String note) {}

    record ChangeRequest(String action, Long replacementTaskId) {}

    @GetMapping
    public Envelope<?> list(@RequestParam(required = false) Long originalTaskId,
                            @RequestParam(required = false) String dataScope,
                            @RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "20") int pageSize,
                            Authentication authentication) {
        return Envelope.success(supplements.list(originalTaskId, dataScope, page, pageSize, actor(authentication)));
    }

    @PostMapping
    public Envelope<?> create(@RequestBody CreateRequest request, Authentication authentication) {
        return Envelope.success(supplements.create(request.originalTaskId(), request.note(), actor(authentication)));
    }

    @PatchMapping("/{id}")
    public Envelope<?> change(@PathVariable long id, @RequestBody ChangeRequest request, Authentication authentication) {
        return Envelope.success(supplements.change(id, request.action(), request.replacementTaskId(),
                actor(authentication)));
    }

    private static AppUserPrincipal actor(Authentication authentication) {
        return (AppUserPrincipal) authentication.getPrincipal();
    }
}
