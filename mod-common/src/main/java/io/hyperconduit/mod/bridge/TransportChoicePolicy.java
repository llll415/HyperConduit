package io.hyperconduit.mod.bridge;

/** Advisory discovery can request consent, but can never choose a downgrade itself. */
public final class TransportChoicePolicy {
    public enum Decision { CURRENT, ASK_TCP, ASK_TUNNEL }

    private TransportChoicePolicy() {}

    public static Decision evaluate(boolean clientEnabled, boolean declaredCompatible,
                                    boolean serverEnabled, boolean tcpAllowed) {
        if (!declaredCompatible) return Decision.CURRENT;
        if (clientEnabled && !serverEnabled && tcpAllowed) return Decision.ASK_TCP;
        if (!clientEnabled && !tcpAllowed) return Decision.ASK_TUNNEL;
        return Decision.CURRENT;
    }
}
