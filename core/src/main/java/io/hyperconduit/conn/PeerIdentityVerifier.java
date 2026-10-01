package io.hyperconduit.conn;

/** Verifies the responder's Noise static public key before the initiator sends message 3. */
@FunctionalInterface
public interface PeerIdentityVerifier {

    PeerIdentityVerifier ACCEPT_ANY = publicKey -> true;

    boolean verify(byte[] publicKey);
}
