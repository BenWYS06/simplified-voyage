package com.ben.service.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// The dedupe decides whether a traveller gets one email or two, and whether Redis being down blocks emails
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationDedupeTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> values;

    @InjectMocks
    private NotificationDedupe dedupe;

    @BeforeEach
    void redisAnswers() {
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void theFirstClaimWins() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        assertThat(dedupe.claim("email", 42L)).isTrue();
    }

    @Test
    void aSecondClaimForTheSameBookingAndChannelLoses() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertThat(dedupe.claim("email", 42L)).isFalse();
    }

    @Test
    void eachChannelHasItsOwnKeyThatOutlivesTheRetries() {
        dedupe.claim("email", 42L);
        dedupe.claim("sms", 42L);

        // One key per channel: "email sent, SMS failed" must resend only the SMS
        verify(values).setIfAbsent("notif:email:42", "sent", Duration.ofDays(1));
        verify(values).setIfAbsent("notif:sms:42", "sent", Duration.ofDays(1));
    }

    @Test
    void aNullAnswerFromRedisIsNotAWin() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(null);

        assertThat(dedupe.claim("email", 42L)).isFalse();
    }

    @Test
    void redisDownStillSendsTheConfirmation() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("down"));

        // Fail open: a duplicate email is better than a missing one
        assertThat(dedupe.claim("email", 42L)).isTrue();
    }

    @Test
    void releaseFreesTheKeySoTheRetryCanSend() {
        dedupe.release("sms", 42L);

        verify(redis).delete("notif:sms:42");
    }

    @Test
    void releaseWithRedisDownDoesNotHideTheOriginalFailure() {
        when(redis.delete(eq("notif:sms:42"))).thenThrow(new RedisConnectionFailureException("down"));

        assertThatCode(() -> dedupe.release("sms", 42L)).doesNotThrowAnyException();
    }
}
