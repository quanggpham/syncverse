package com.internship.syncverse.server.api;

import com.internship.syncverse.common.dto.DeltaResponse;
import com.internship.syncverse.server.delta.DeltaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/deltas")
public final class DeltaController {

    private final DeltaService deltaService;

    public DeltaController(DeltaService deltaService) {
        this.deltaService = deltaService;
    }

    @GetMapping
    DeltaResponse deltas(
            @RequestHeader("X-Session-Id") UUID sessionId,
            @RequestParam("since") long since) {
        return deltaService.poll(sessionId, since);
    }
}
