package io.hyperconduit.conn;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Mutable, session-lock-owned rolling 10-second traffic and RTT tracker. */
final class RollingMetricsTracker {

    private static final long SECOND_NANOS = 1_000_000_000L;
    private static final int BUCKET_COUNT = 10;
    private static final long RATE_BUCKET_NANOS = 100_000_000L;
    private static final int RATE_BUCKET_COUNT = (int) (SECOND_NANOS / RATE_BUCKET_NANOS);
    private static final int MAX_RTT_SAMPLES = 256;

    private final Bucket[] buckets = new Bucket[BUCKET_COUNT];
    private final RateBucket[] rateBuckets = new RateBucket[RATE_BUCKET_COUNT];
    private final ArrayDeque<RttSample> rttSamples = new ArrayDeque<>();
    private long lastRttNanos = -1;
    private long jitterSum;
    private int jitterCount;

    RollingMetricsTracker(long nowNanos) {
        long second = nowNanos / SECOND_NANOS;
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = new Bucket(second - i);
        }
        long rateBucket = nowNanos / RATE_BUCKET_NANOS;
        for (int i = 0; i < rateBuckets.length; i++) {
            rateBuckets[i] = new RateBucket(rateBucket - i);
        }
    }

    void sent(long nowNanos, long bytes) {
        bucket(nowNanos).txBytes += bytes;
        rateBucket(nowNanos).txBytes += bytes;
    }

    void received(long nowNanos, long bytes) {
        bucket(nowNanos).rxBytes += bytes;
        rateBucket(nowNanos).rxBytes += bytes;
    }

    void acknowledged(long nowNanos) { bucket(nowNanos).acknowledgedPackets++; }
    void lost(long nowNanos) { bucket(nowNanos).lostPackets++; }

    void retransmitted(long nowNanos) {
        bucket(nowNanos).retransmittedPackets++;
        rateBucket(nowNanos).retransmittedPackets++;
    }

    void rttSample(long nowNanos, long rttNanos) {
        if (rttNanos <= 0) return;
        expire(nowNanos);
        rttSamples.addLast(new RttSample(nowNanos, rttNanos));
        while (rttSamples.size() > MAX_RTT_SAMPLES) rttSamples.removeFirst();
        if (lastRttNanos >= 0) {
            jitterSum += Math.abs(lastRttNanos - rttNanos);
            jitterCount++;
        }
        lastRttNanos = rttNanos;
    }

    RollingMetrics snapshot(long nowNanos) {
        expire(nowNanos);
        long acknowledged = 0, lost = 0;
        for (Bucket bucket : buckets) {
            acknowledged += bucket.acknowledgedPackets;
            lost += bucket.lostPackets;
        }
        long tx = 0;
        long rx = 0;
        long retransmitted = 0;
        long currentRateBucket = nowNanos / RATE_BUCKET_NANOS;
        for (RateBucket bucket : rateBuckets) {
            if (currentRateBucket - bucket.index >= RATE_BUCKET_COUNT) {
                continue;
            }
            tx += bucket.txBytes;
            rx += bucket.rxBytes;
            retransmitted += bucket.retransmittedPackets;
        }
        long[] samples = rttSamples.stream().mapToLong(RttSample::rttNanos).toArray();
        long p95 = 0;
        if (samples.length > 0) {
            Arrays.sort(samples);
            p95 = samples[(int) Math.ceil(samples.length * 0.95) - 1];
        }
        double lossRate = lost == 0 ? 0 : (double) lost / (lost + acknowledged);
        return new RollingMetrics(tx, rx, lossRate, retransmitted, p95,
                jitterCount == 0 ? 0 : jitterSum / jitterCount);
    }

    private Bucket bucket(long nowNanos) {
        long second = nowNanos / SECOND_NANOS;
        expire(nowNanos);
        int index = (int) Math.floorMod(second, BUCKET_COUNT);
        Bucket bucket = buckets[index];
        if (bucket.second != second) {
            bucket.reset(second);
        }
        return bucket;
    }

    private RateBucket rateBucket(long nowNanos) {
        long index = nowNanos / RATE_BUCKET_NANOS;
        int slot = (int) Math.floorMod(index, RATE_BUCKET_COUNT);
        RateBucket bucket = rateBuckets[slot];
        if (bucket.index != index) {
            bucket.reset(index);
        }
        return bucket;
    }

    private void expire(long nowNanos) {
        long currentSecond = nowNanos / SECOND_NANOS;
        for (Bucket bucket : buckets) {
            if (currentSecond - bucket.second >= BUCKET_COUNT) bucket.reset(currentSecond - BUCKET_COUNT + 1);
        }
        long cutoff = nowNanos - BUCKET_COUNT * SECOND_NANOS;
        while (!rttSamples.isEmpty() && rttSamples.peekFirst().timeNanos < cutoff) rttSamples.removeFirst();
        if (rttSamples.isEmpty()) {
            lastRttNanos = -1;
            jitterSum = 0;
            jitterCount = 0;
        }
    }

    private static final class Bucket {
        long second;
        long txBytes, rxBytes, acknowledgedPackets, lostPackets, retransmittedPackets;
        Bucket(long second) { this.second = second; }
        void reset(long second) {
            this.second = second;
            txBytes = rxBytes = acknowledgedPackets = lostPackets = retransmittedPackets = 0;
        }
    }

    private static final class RateBucket {
        long index;
        long txBytes, rxBytes, retransmittedPackets;
        RateBucket(long index) { this.index = index; }
        void reset(long index) {
            this.index = index;
            txBytes = rxBytes = retransmittedPackets = 0;
        }
    }

    private record RttSample(long timeNanos, long rttNanos) {}
}
