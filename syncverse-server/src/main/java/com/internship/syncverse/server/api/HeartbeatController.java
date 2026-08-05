package com.internship.syncverse.server.api;

import com.internship.syncverse.common.dto.HeartbeatRequest;
import com.internship.syncverse.common.protocol.MessageType;
import com.internship.syncverse.server.session.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public final class HeartbeatController {

    private final SessionService sessions;

    public HeartbeatController(SessionService sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/heartbeat")
    ResponseEntity<Void> heartbeat(@RequestBody HeartbeatRequest request) {
        if (request.messageType() != MessageType.HEARTBEAT) {
            throw new InvalidRequestException("Expected messageType HEARTBEAT");
        }
        sessions.heartbeat(request.sessionId());
        return ResponseEntity.noContent().build();
    }
}
