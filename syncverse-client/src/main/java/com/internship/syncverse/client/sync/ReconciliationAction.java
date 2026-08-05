package com.internship.syncverse.client.sync;

public record ReconciliationAction(Kind kind, String checksum, long fileVersion) {

    public enum Kind {
        NO_OP,
        UPLOAD_LOCAL,
        APPLY_REMOTE,
        UPLOAD_CONFLICT,
        APPLY_DELETE
    }
}
