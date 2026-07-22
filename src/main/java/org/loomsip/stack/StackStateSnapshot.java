package org.loomsip.stack;

import java.util.List;
import java.util.Optional;

/** Immutable runtime diagnostics without SIP message bodies or authentication data. */
public record StackStateSnapshot(SipStackState state, List<StackTransportSnapshot> transports,
                                 int inviteClientTransactions, int inviteServerTransactions,
                                 int nonInviteClientTransactions, int nonInviteServerTransactions,
                                 Optional<String> lastFailure) { }
