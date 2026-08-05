package com.internship.syncverse.common.dto;

import java.util.List;

public record DeltaResponse(long fromExclusive, long latestGlobalVersion, List<FileRevision> changes) {
}
