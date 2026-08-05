package com.internship.syncverse.server.api;

import com.internship.syncverse.common.dto.ReconnectRequest;
import com.internship.syncverse.common.dto.RegisterRequest;
import com.internship.syncverse.common.dto.RegisterResponse;
import com.internship.syncverse.common.protocol.MessageType;
import com.internship.syncverse.server.session.ClientSession;
import com.internship.syncverse.server.session.SessionService;
import com.internship.syncverse.server.persistence.ChangeLogRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public final class RegistrationController {

    private final SessionService sessions;
    private final ChangeLogRepository changes;

    public RegistrationController(SessionService sessions, ChangeLogRepository changes) {
        this.sessions = sessions;
        this.changes = changes;
    }

    @PostMapping("/register")
    ResponseEntity<RegisterResponse> register(@RequestBody RegisterRequest request) {
        requireMessageType(request.messageType(), MessageType.HELLO);
        ClientSession session = sessions.register(request.clientName());
        return ResponseEntity.status(HttpStatus.CREATED).body(response(session));
    }

    @PostMapping("/reconnect")
    RegisterResponse reconnect(@RequestBody ReconnectRequest request) {
        requireMessageType(request.messageType(), MessageType.RECONNECT);
        return response(sessions.reconnect(
                request.clientName(), request.lastSeenGlobalVersion()));
    }

    private RegisterResponse response(ClientSession session) {
        return new RegisterResponse(
                session.clientName(), session.sessionId(), changes.maxVersion());
    }

    private static void requireMessageType(MessageType actual, MessageType expected) {
        if (actual != expected) {
            throw new InvalidRequestException("Expected messageType " + expected);
        }
    }
}
