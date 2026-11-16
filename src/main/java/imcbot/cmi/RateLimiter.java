package imcbot.cmi;

import java.util.concurrent.TimeUnit;

/** Spaces requests evenly so we never exceed a set rate. */
final class RateLimiter {

    private volatile long intervalNanos;
    private long next = System.nanoTime();

    RateLimiter(double perSecond) {
        setRate(perSecond);
    }

    void setRate(double perSecond) {
        intervalNanos = (long) (1_000_000_000L / perSecond);
    }

    synchronized void acquire() {
        long now = System.nanoTime();
        if (next > now) {
            try {
                TimeUnit.NANOSECONDS.sleep(next - now);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            now = next;
        }
        next = now + intervalNanos;
    }
}
