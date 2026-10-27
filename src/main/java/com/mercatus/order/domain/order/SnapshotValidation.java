package com.mercatus.order.domain.order;

import com.mercatus.order.domain.exception.DomainRuleException;

final class SnapshotValidation {

    private SnapshotValidation() {
    }

    static void requireText(String... values) {
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new DomainRuleException("Snapshot contains missing data");
            }
        }
    }
}
