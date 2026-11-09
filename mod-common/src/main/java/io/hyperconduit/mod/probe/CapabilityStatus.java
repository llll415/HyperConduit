package io.hyperconduit.mod.probe;

/** Attached to the decoded status object; no global "last response" state. */
public interface CapabilityStatus {
    Capability hyperconduit$capability();

    void hyperconduit$capability(Capability capability);

    default CapabilityDetails hyperconduit$details() {
        return CapabilityDetails.of(hyperconduit$capability());
    }

    default void hyperconduit$details(CapabilityDetails details) {
        hyperconduit$capability(details == null ? Capability.UNDECLARED : details.state());
    }
}
