package com.ben.service.listener;

import com.ben.common_lib.event.BookingConfirmedEvent;
import com.ben.service.service.EmailService;
import com.ben.service.service.NotificationDedupe;
import com.ben.service.service.SmsService;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// booking.confirmed arrives at least once: the traveller gets one email and one SMS, and a failed send is retried
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BookingNotificationListenerTest {

    private static final Long BOOKING_ID = 42L;

    @Mock
    private EmailService emailService;

    @Mock
    private SmsService smsService;

    @Mock
    private NotificationDedupe dedupe;

    @InjectMocks
    private BookingNotificationListener listener;

    @BeforeEach
    void everyClaimWins() {
        when(dedupe.claim(anyString(), anyLong())).thenReturn(true);
    }

    @Test
    void sendsTheEmailAndTheSms() throws Exception {
        BookingConfirmedEvent event = event("a@b.com", "+84900000000");

        listener.handleBookingConfirmed(event);

        verify(emailService).sendBookingConfirmation(event);
        verify(smsService).sendBookingConfirmation(event);
    }

    @Test
    void aRedeliveredEventSendsNothingAgain() {
        when(dedupe.claim(anyString(), anyLong())).thenReturn(false);

        listener.handleBookingConfirmed(event("a@b.com", "+84900000000"));

        verifyNoInteractions(emailService, smsService);
    }

    @Test
    void onlyTheChannelNotYetSentGoesOut() throws Exception {
        when(dedupe.claim("email", BOOKING_ID)).thenReturn(false);
        BookingConfirmedEvent event = event("a@b.com", "+84900000000");

        listener.handleBookingConfirmed(event);

        verify(emailService, never()).sendBookingConfirmation(any());
        verify(smsService).sendBookingConfirmation(event);
    }

    @Test
    void aFailedEmailFreesItsClaimAndFailsSoKafkaRetries() throws Exception {
        BookingConfirmedEvent event = event("a@b.com", "+84900000000");
        doThrow(new MessagingException("smtp down")).when(emailService).sendBookingConfirmation(event);

        assertThatThrownBy(() -> listener.handleBookingConfirmed(event))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(MessagingException.class);
        verify(dedupe).release("email", BOOKING_ID);
    }

    @Test
    void aFailedSmsKeepsTheEmailClaim() {
        BookingConfirmedEvent event = event("a@b.com", "+84900000000");
        doThrow(new IllegalStateException("twilio down")).when(smsService).sendBookingConfirmation(event);

        assertThatThrownBy(() -> listener.handleBookingConfirmed(event)).isInstanceOf(IllegalStateException.class);
        // On the retry the email key is still there, so only the SMS goes out again
        verify(dedupe).release("sms", BOOKING_ID);
        verify(dedupe, never()).release("email", BOOKING_ID);
    }

    @Test
    void noEmailAddressSkipsOnlyTheEmail() throws Exception {
        BookingConfirmedEvent event = event(" ", "+84900000000");

        listener.handleBookingConfirmed(event);

        verify(emailService, never()).sendBookingConfirmation(any());
        verify(dedupe, never()).claim("email", BOOKING_ID);
        verify(smsService).sendBookingConfirmation(event);
    }

    @Test
    void noPhoneSkipsOnlyTheSms() throws Exception {
        BookingConfirmedEvent event = event("a@b.com", null);

        listener.handleBookingConfirmed(event);

        verify(emailService).sendBookingConfirmation(event);
        verifyNoInteractions(smsService);
    }

    private static BookingConfirmedEvent event(String email, String phone) {
        return BookingConfirmedEvent.builder()
                .bookingId(BOOKING_ID)
                .bookingReference("BEN42")
                .contactEmail(email)
                .contactPhone(phone)
                .build();
    }
}
