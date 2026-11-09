package io.hyperconduit.mod.bridge;

import io.hyperconduit.mod.config.HyperConduitConfig;

/** One discovery answer and at most one connection continuation per attempt. */
public final class TransportConsent {
    private final HyperConduitConfig original;
    private TransportChoicePolicy.Decision decision = TransportChoicePolicy.Decision.CURRENT;
    private boolean discovered;
    private boolean finished;

    public TransportConsent(HyperConduitConfig config) { original = config.copy(); }

    public synchronized boolean discovered(boolean declared, boolean enabled, boolean tcpAllowed) {
        if (finished || discovered) return false;
        discovered = true;
        decision = TransportChoicePolicy.evaluate(original.enabled, declared, enabled, tcpAllowed);
        return true;
    }

    public synchronized TransportChoicePolicy.Decision decision() { return decision; }

    /** A mismatched transport cannot continue without explicit consent from its button. */
    public synchronized HyperConduitConfig proceed(boolean explicitConsent) {
        if (finished || !discovered) return null;
        if (decision != TransportChoicePolicy.Decision.CURRENT && !explicitConsent) return null;
        finished = true;
        HyperConduitConfig config = original.copy();
        if (decision == TransportChoicePolicy.Decision.ASK_TCP) config.enabled = false;
        if (decision == TransportChoicePolicy.Decision.ASK_TUNNEL) config.enabled = true;
        return config;
    }

    public synchronized void cancel() { finished = true; }
}
