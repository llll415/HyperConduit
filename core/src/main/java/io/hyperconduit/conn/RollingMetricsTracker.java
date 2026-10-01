package io.hyperconduit.conn;

import java.util.ArrayDeque;
import java.util.Arrays;

/** Mutable, session-lock-owned recent traffic and RTT tracker. */
final class RollingMetricsTracker {

    private static final long SECOND_NANOS = 1_000_000_000L;
    private static final int BUCKET_COUNT = 10;
    private static final long WINDOW_NANOS = BUCKET_COUNT * SECOND_NANOS;
    private static final long RATE_TIME_CONSTANT_NANOS = 500_000_000L;

    private final Bucket[] buckets = new Bucket[BUCKET_COUNT];
    private final ArrayDeque<RttSample> rttSamples = new ArrayDeque<>();

    private long rateUpdatedAtNanos;
    private double txBytesPerSecond;
    private double rxBytesPerSecond;
    private double retransmitsPerSecond;

    RollingMetricsTracker(long nowNanos) {
        long second = nowNanos / SECOND_NANOS;
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = new Bucket(second - i);
        }
        rateUpdatedAtNanos = nowNanos;
    }

    void sent(long nowNanos, long bytes) {
        advanceRates(nowNanos);
        txBytesPerSecond += rateImpulse(bytes);
    }

    void received(long nowNanos, long bytes) {
        advanceRates(nowNanos);
        rxBytesPerSecond += rateImpulse(bytes);
    }

    void acknowledged(long nowNanos, long count) {
        if (count > 0) {
            bucket(nowNanos).acknowledgedPackets += count;
        }
    }

    void lost(long nowNanos, long count) {
        if (count > 0) {
            bucket(nowNanos).lostPackets += count;
        }
    }

    void retransmitted(long nowNanos) {
        advanceRates(nowNanos);
        retransmitsPerSecond += rateImpulse(1);
    }

    void rttSample(long nowNanos, long rttNanos) {
        if (rttNanos <= 0) {
            return;
        }
        expire(nowNanos);
        rttSamples.addLast(new RttSample(nowNanos, rttNanos));
    }

    RollingMetrics snapshot(long nowNanos) {
        expire(nowNanos);
        advanceRates(nowNanos);

        long acknowledged = 0;
        long lost = 0;
        for (Bucket bucket : buckets) {
            acknowledged += bucket.acknowledgedPackets;
            lost += bucket.lostPackets;
        }

        long[] samples = rttSamples.stream().mapToLong(RttSample::rttNanos).toArray();
        long p95 = 0;
        long jitterSum = 0;
        int jitterCount = 0;
        if (samples.length > 0) {
            Arrays.sort(samples);
            p95 = samples[(int) Math.ceil(samples.length * 0.95) - 1];

            long previous = -1;
            for (RttSample sample : rttSamples) {
                if (previous >= 0) {
                    jitterSum += Math.abs(previous - sample.rttNanos);
                    jitterCount++;
                }
                previous = sample.rttNanos;
            }
        }

        double lossRate = lost == 0 ? 0 : (double) lost / (lost + acknowledged);
        return new RollingMetrics(Math.round(txBytesPerSecond), Math.round(rxBytesPerSecond), lossRate,
                displayRate(retransmitsPerSecond), p95, jitterCount == 0 ? 0 : jitterSum / jitterCount);
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

    private void advanceRates(long nowNanos) {
        long elapsedNanos = Math.max(0, nowNanos - rateUpdatedAtNanos);
        if (elapsedNanos == 0) {
            return;
        }
        double decay = Math.exp(-(double) elapsedNanos / RATE_TIME_CONSTANT_NANOS);
        txBytesPerSecond *= decay;
        rxBytesPerSecond *= decay;
        retransmitsPerSecond *= decay;
        rateUpdatedAtNanos = nowNanos;
    }

    private void expire(long nowNanos) {
        long currentSecond = nowNanos / SECOND_NANOS;
        for (Bucket bucket : buckets) {
            if (currentSecond - bucket.second >= BUCKET_COUNT) {
                bucket.reset(currentSecond - BUCKET_COUNT + 1);
            }
        }
        long cutoff = nowNanos - WINDOW_NANOS;
        while (!rttSamples.isEmpty() && rttSamples.peekFirst().timeNanos < cutoff) {
            rttSamples.removeFirst();
        }
    }

    private static double rateImpulse(long quantity) {
        return (double) quantity * SECOND_NANOS / RATE_TIME_CONSTANT_NANOS;
    }

    private static double displayRate(double rate) {
        return rate < 0.05 ? 0 : rate;
    }

    private static final class Bucket {
        long second;
        long acknowledgedPackets;
        long lostPackets;

        Bucket(long second) {
            this.second = second;
        }

        void reset(long second) {
            this.second = second;
            acknowledgedPackets = 0;
            lostPackets = 0;
        }
    }

    private record RttSample(long timeNanos, long rttNanos) {
    }
}
