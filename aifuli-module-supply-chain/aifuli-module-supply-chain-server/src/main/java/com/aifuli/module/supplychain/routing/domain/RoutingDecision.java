package com.aifuli.module.supplychain.routing.domain;

import java.util.List;

/** 纯路由决策；orderedCandidates 是后续路由审计的唯一候选顺序。 */
public record RoutingDecision(RoutingCandidate selectedCandidate,
                              List<RoutingCandidate> orderedCandidates,
                              String reasonCode) {

    public RoutingDecision {
        orderedCandidates = List.copyOf(orderedCandidates);
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("Routing decision reason is required");
        }
        if (selectedCandidate != null
                && (!selectedCandidate.eligible() || !orderedCandidates.contains(selectedCandidate))) {
            throw new IllegalArgumentException("Selected routing candidate must be eligible and ordered");
        }
    }

    public boolean selected() {
        return selectedCandidate != null;
    }
}
