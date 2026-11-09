package io.hyperconduit.mod.probe;

/** Transient list-entry state, not persisted to servers.dat. */
public interface CapabilityEntry {
    Capability hyperconduit$capability();
    void hyperconduit$vanilla(Capability capability);
    void hyperconduit$probe(Capability capability);
    void hyperconduit$reset();
    long hyperconduit$generation();
}
