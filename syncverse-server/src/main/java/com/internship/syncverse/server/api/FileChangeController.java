package com.internship.syncverse.server.api;

import com.internship.syncverse.common.dto.FileChangeRequest;
import com.internship.syncverse.common.dto.FileChangeResponse;
import com.internship.syncverse.common.protocol.ChangeOutcome;
import com.internship.syncverse.server.sync.SyncService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/files")
public final class FileChangeController {

    private final SyncService syncService;

    public FileChangeController(SyncService syncService) {
        this.syncService = syncService;
    }

    @PostMapping("/changes")
    ResponseEntity<FileChangeResponse> change(@RequestBody FileChangeRequest request) {
        FileChangeResponse response = syncService.apply(request);
        if (response.outcome() == ChangeOutcome.CONFLICT_REJECTED) {
            throw new StaleDeleteException();
        }
        return ResponseEntity.ok(response);
    }
}

final class StaleDeleteException extends RuntimeException {
    StaleDeleteException() {
        super("Delete base version is stale; canonical file was preserved");
    }
}
