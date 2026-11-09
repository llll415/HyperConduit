package io.hyperconduit.mod.probe;

/** Vanilla declarations take precedence; silence and absent fields are not declarations. */
public final class CapabilityEvidence {
    private Capability vanilla;
    private Capability quic = Capability.CHECKING;
    public synchronized void vanilla(Capability value) { vanilla = value; }
    public synchronized void quic(Capability value) { quic = value; }
    public synchronized void reset() { vanilla = null; quic = Capability.CHECKING; }
    public synchronized Capability state() {
        if (vanilla != null && vanilla.declared()) return vanilla;
        if (quic.declared()) return quic;
        if (quic == Capability.CHECKING) return Capability.CHECKING;
        if (vanilla == Capability.UNDECLARED || quic == Capability.UNDECLARED) return Capability.UNDECLARED;
        return Capability.UNKNOWN;
    }
}
