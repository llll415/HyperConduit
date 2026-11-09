package io.hyperconduit.mod.probe;

/** Attached to the decoded status object; no global "last response" state. */
public interface CapabilityStatus {
    Capability hyperconduit$capability();
    void hyperconduit$capability(Capability capability);
}
