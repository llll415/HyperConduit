package io.hyperconduit.mod.client;

import io.hyperconduit.sidecar.SidecarException;

/**
 * Carries one LAN publish attempt's safe Chinese failure text from the server-listener bind hook
 * to the client publish hook.
 *
 * <p>The raw {@link SidecarException}, its code, cause, token and paths are deliberately not
 * retained. Only the already-safe Chinese message is copied, and it is normalized to the single
 * user-facing form {@code [HyperConduit] 隧道程序启动失败。\n原因：...}.
 *
 * <p>{@link #begin()} starts a fresh attempt on the calling thread and discards any older attempt
 * on that thread. {@link #finish()} ends the attempt and removes the thread-local state, so a
 * later LAN attempt can never inherit an earlier failure. The publish path is synchronous on its
 * caller thread, so a thread-local attempt is sufficient and also isolates concurrent clients.
 */
public final class LanStartupFailure {
    public static final String PREFIX = "[HyperConduit] ";
    private static final String TITLE = "隧道程序启动失败。";
    private static final String REASON_PREFIX = "原因：";
    private static final String FALLBACK_REASON = "无法启动服务器隧道监听，请检查端口与服务器配置。";

    private static final ThreadLocal<Attempt> CURRENT = new ThreadLocal<>();

    private LanStartupFailure() {
    }

    /** Starts a new attempt, discarding any stale failure left on this thread. */
    public static void begin() {
        CURRENT.set(new Attempt());
    }

    /**
     * Records the safe Chinese message of the failure for the current attempt. Calls outside an
     * active LAN attempt (for example on a dedicated server) are ignored.
     */
    public static void record(SidecarException failure) {
        if (failure == null) return;
        Attempt attempt = CURRENT.get();
        if (attempt == null || attempt.message != null) return;
        attempt.message = format(failure.getMessage());
    }

    /**
     * Ends the current attempt and returns its normalized message, or {@code null} when this
     * attempt did not fail. The thread-local state is removed before returning.
     */
    public static String finish() {
        Attempt attempt = CURRENT.get();
        CURRENT.remove();
        return attempt == null ? null : attempt.message;
    }

    /** Removes any attempt state from the calling thread. */
    public static void clear() {
        CURRENT.remove();
    }

    static String format(String message) {
        return PREFIX + TITLE + "\n" + REASON_PREFIX + reason(message);
    }

    private static String reason(String message) {
        if (message == null) return FALLBACK_REASON;
        String reason = message.strip();
        while (reason.startsWith(PREFIX)) reason = reason.substring(PREFIX.length()).strip();
        if (reason.startsWith(TITLE)) {
            String remainder = reason.substring(TITLE.length()).strip();
            if (remainder.startsWith(REASON_PREFIX)) {
                reason = remainder.substring(REASON_PREFIX.length()).strip();
            }
        }
        return reason.isEmpty() ? FALLBACK_REASON : reason;
    }

    /** Mutable per-attempt state; only a safe String is kept, never the original exception. */
    private static final class Attempt {
        private String message;
    }
}
