package io.hyperconduit.conn;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.TreeMap;

/**
 * Reassembles one ordered byte stream from {@link Frame.StreamData} chunks that may arrive
 * out of order, be duplicated, or overlap.
 *
 * <p><b>Invariant relied upon:</b> chunks at a given offset are either identical or disjoint. This
 * holds because the sender never re-chunks data — a lost packet is re-queued verbatim, and new data
 * is always appended at a strictly increasing offset. Partial overlaps are therefore handled by
 * trimming against what is already held, not by a general merge.
 */
public final class ReceiveBuffer {

    private final int windowBytes;
    private final ArrayDeque<byte[]> ready = new ArrayDeque<>();
    private final TreeMap<Long, byte[]> pending = new TreeMap<>();

    private long nextOffset;
    private int readyBytes;
    private int pendingBytes;

    /** @param windowBytes how much undelivered data to buffer before withholding flow-control credit */
    public ReceiveBuffer(int windowBytes) {
        if (windowBytes <= 0) {
            throw new IllegalArgumentException("window must be positive");
        }
        this.windowBytes = windowBytes;
    }

    /** Offset of the next byte that has not yet been handed to the application. */
    public long nextOffset() {
        return nextOffset;
    }

    /** Bytes available to {@link #read} right now, in order. */
    public int available() {
        return readyBytes;
    }

    /** Bytes held out of order, waiting for the gap ahead of them to fill. */
    public int buffered() {
        return pendingBytes;
    }

    /**
     * Highest exclusive offset the peer may send to. Advancing this is what applies backpressure:
     * it only moves as the application drains the buffer.
     */
    public long maxAcceptableOffset() {
        return nextOffset + windowBytes;
    }

    public void put(long offset, byte[] data) {
        put(offset, data, 0, data.length);
    }

    public void put(long offset, byte[] data, int off, int len) {
        if (len <= 0) {
            return;
        }
        long head = nextOffset + readyBytes;
        if (offset + len <= head) {
            return; // entirely covered by bytes already delivered or buffered contiguously
        }
        if (offset < head) {
            int skip = (int) (head - offset);
            off += skip;
            len -= skip;
            offset = head;
        }
        byte[] chunk = off == 0 && len == data.length ? data : Arrays.copyOfRange(data, off, off + len);

        if (offset == head) {
            ready.addLast(chunk);
            readyBytes += chunk.length;
            promotePending();
            return;
        }

        // Trim against a preceding out-of-order chunk, if the invariant above is ever bent.
        var previous = pending.floorEntry(offset);
        if (previous != null) {
            long previousEnd = previous.getKey() + previous.getValue().length;
            if (previousEnd > offset) {
                if (previousEnd >= offset + chunk.length) {
                    return; // fully covered by an existing chunk
                }
                int skip = (int) (previousEnd - offset);
                chunk = Arrays.copyOfRange(chunk, skip, chunk.length);
                offset = previousEnd;
                if (offset == nextOffset + readyBytes) {
                    ready.addLast(chunk);
                    readyBytes += chunk.length;
                    promotePending();
                    return;
                }
            }
        }
        byte[] replaced = pending.put(offset, chunk);
        if (replaced != null) {
            pendingBytes -= replaced.length;
        }
        pendingBytes += chunk.length;
    }

    /** Copies up to {@code maxLen} in-order bytes out of the buffer; returns how many were copied. */
    public int read(byte[] dst, int dstOff, int maxLen) {
        int copied = 0;
        while (copied < maxLen && !ready.isEmpty()) {
            byte[] head = ready.peekFirst();
            int n = Math.min(head.length, maxLen - copied);
            System.arraycopy(head, 0, dst, dstOff + copied, n);
            copied += n;
            nextOffset += n;
            readyBytes -= n;
            ready.pollFirst();
            if (n < head.length) {
                ready.addFirst(Arrays.copyOfRange(head, n, head.length));
            }
        }
        return copied;
    }

    private void promotePending() {
        while (true) {
            byte[] chunk = pending.remove(nextOffset + readyBytes);
            if (chunk == null) {
                return;
            }
            pendingBytes -= chunk.length;
            ready.addLast(chunk);
            readyBytes += chunk.length;
        }
    }
}
