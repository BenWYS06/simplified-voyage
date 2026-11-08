package com.ben.service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Idempotent consumer for side effects outside our DB (DESIGN.md §10, D3): booking.confirmed can arrive more
 * than once (Kafka at-least-once, outbox resend), but the traveller must get ONE email and ONE SMS.
 * Redis SET NX = "claim the right to send"; only the first delivery wins. One key per booking AND channel,
 * so a retry after "email sent, SMS failed" resends only the SMS.
 * Redis is the right tool here (not for money / seats): losing a key costs at most one extra email.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDedupe {

    // Longer than any redelivery window (Kafka retries ≈ 5 min, outbox resends within seconds)
    private static final Duration KEY_TTL = Duration.ofDays(1);

    private final StringRedisTemplate redis;

    /** @return true = first time for this booking + channel, go ahead and send; false = already sent */
    public boolean claim(String channel, Long bookingId) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(channel, bookingId), "sent", KEY_TTL));
        } catch (DataAccessException e) {
            // Fail open: with Redis down a duplicate email is better than a missing confirmation
            log.warn("Redis unavailable, sending {} for booking {} without dedupe: {}", channel, bookingId, e.getMessage());
            return true;
        }
    }

    /** The send failed: free the key so Kafka's retry can try again. */
    public void release(String channel, Long bookingId) {
        try {
            redis.delete(key(channel, bookingId));
        } catch (DataAccessException e) {
            log.warn("Could not release dedupe key {} for booking {}: {}", channel, bookingId, e.getMessage());
        }
    }

    private static String key(String channel, Long bookingId) {
        return "notif:" + channel + ":" + bookingId;
    }
}
