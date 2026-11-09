package io.hyperconduit.mod.probe;

/** Transient list-entry state, not persisted to servers.dat. */
public interface CapabilityEntry {
    Capability hyperconduit$capability();

    /** Full declaration for transport choice; never persisted. */
    CapabilityDetails hyperconduit$details();

    void hyperconduit$vanilla(Capability capability);

    void hyperconduit$probe(Capability capability);

    void hyperconduit$vanillaDetails(CapabilityDetails details);

    void hyperconduit$probeDetails(CapabilityDetails details);

    void hyperconduit$reset();

    long hyperconduit$generation();
}
