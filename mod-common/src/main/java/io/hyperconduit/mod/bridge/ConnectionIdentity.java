package io.hyperconduit.mod.bridge;

/** Set at the server-list/ConnectScreen boundary before address resolution. */
public interface ConnectionIdentity {
    void hyperconduit$endpoint(String endpoint);
    String hyperconduit$endpoint();
}
