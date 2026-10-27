package com.mercatus.order.domain.order;

import java.util.Objects;
import java.util.UUID;

public record CustomerSnapshot(UUID id, String name, String email) {

    public CustomerSnapshot {
        Objects.requireNonNull(id);
        SnapshotValidation.requireText(name, email);
    }
}
