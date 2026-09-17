package com.itemguard.multiserver;

import java.util.List;
import java.util.Objects;

/**
 * The cross-server rule's answer, with the servers named so an operator can go and look.
 *
 * @param status           what was concluded ({@link CrossServerStatus#NONE} or seen on several)
 * @param servers          the distinct server names, sorted, longest-lived first is not implied
 * @param distinctServers  how many of them, which is what the status was decided from
 */
public record CrossServerAssessment(CrossServerStatus status, List<String> servers, int distinctServers) {

    public CrossServerAssessment {
        Objects.requireNonNull(status, "status");
        servers = List.copyOf(Objects.requireNonNull(servers, "servers"));
        if (distinctServers != servers.size()) {
            throw new IllegalArgumentException(
                "distinctServers (" + distinctServers + ") must match the server list ("
                    + servers.size() + ")"
            );
        }
    }

    public boolean isReportable() {
        return status != CrossServerStatus.NONE;
    }

    /**
     * The sentence a staff member would read. It states where the item was seen and never what
     * that means — see {@link CrossServerStatus} for why that distinction is load-bearing.
     */
    public String statement() {
        return switch (status) {
            case NONE -> servers.isEmpty()
                ? "not seen inside the window"
                : "seen on one server inside the window: " + String.join(", ", servers);
            case SEEN_ON_MULTIPLE_SERVERS -> "seen on " + distinctServers + " servers inside the "
                + "window: " + String.join(", ", servers);
        };
    }
}
