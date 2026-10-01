package io.hyperconduit.conn;

/** Raised when a known server endpoint presents a different Noise static identity. */
public final class IdentityChangedException extends RuntimeException {
    private final String endpoint;
    private final String previousFingerprint;
    private final String presentedFingerprint;
    private final byte[] presentedPublicKey;

    public IdentityChangedException(String endpoint, String previousFingerprint,
                                    String presentedFingerprint, byte[] presentedPublicKey) {
        super("[HyperConduit] Server identity changed for " + endpoint);
        this.endpoint = endpoint;
        this.previousFingerprint = previousFingerprint;
        this.presentedFingerprint = presentedFingerprint;
        this.presentedPublicKey = presentedPublicKey.clone();
    }

    public String endpoint() { return endpoint; }
    public String previousFingerprint() { return previousFingerprint; }
    public String presentedFingerprint() { return presentedFingerprint; }
    public byte[] presentedPublicKey() { return presentedPublicKey.clone(); }
}
