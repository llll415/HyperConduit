package io.hyperconduit.mod.probe;

/** Vanilla declarations take precedence; silence and absent fields are not declarations. */
public final class CapabilityEvidence {
    private CapabilityDetails vanilla;
    private CapabilityDetails quic = CapabilityDetails.CHECKING;

    public synchronized void vanilla(Capability value) {
        vanilla = CapabilityDetails.of(value);
    }

    public synchronized void quic(Capability value) {
        quic = CapabilityDetails.of(value);
    }

    public synchronized void vanilla(CapabilityDetails value) {
        vanilla = value == null ? CapabilityDetails.UNDECLARED : value;
    }

    public synchronized void quic(CapabilityDetails value) {
        quic = value == null ? CapabilityDetails.UNKNOWN : value;
    }

    public synchronized void reset() {
        vanilla = null;
        quic = CapabilityDetails.CHECKING;
    }

    public synchronized Capability state() {
        return details().state();
    }

    /** Full declaration, including policy only when the winning side declared compatibly. */
    public synchronized CapabilityDetails details() {
        if (vanilla != null && vanilla.state().declared()) return vanilla;
        if (quic.state().declared()) return quic;
        if (quic.state() == Capability.CHECKING) return CapabilityDetails.CHECKING;
        if ((vanilla != null && vanilla.state() == Capability.UNDECLARED)
                || quic.state() == Capability.UNDECLARED) {
            return CapabilityDetails.UNDECLARED;
        }
        return CapabilityDetails.UNKNOWN;
    }
}
