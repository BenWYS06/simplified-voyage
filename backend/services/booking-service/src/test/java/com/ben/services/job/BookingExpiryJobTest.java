package com.ben.services.job;

import com.ben.common_lib.enums.BookingStatus;
import com.ben.services.model.Booking;
import com.ben.services.repository.BookingRepository;
import com.ben.services.service.BookingCompensator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Saga recovery from DB state: crashed creates are cancelled, unpaid bookings expire, one failure never stops the run
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BookingExpiryJobTest {

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private BookingCompensator bookingCompensator;

    @InjectMocks
    private BookingExpiryJob job;

    @Test
    void aCreateStuckInPendingIsCancelled() {
        when(bookingRepository.findTop100ByStatusAndBookingDateBefore(eq(BookingStatus.PENDING), any()))
                .thenReturn(List.of(booking(1L)));

        job.closeStuckBookings();

        verify(bookingCompensator).close(1L, BookingStatus.CANCELLED);
    }

    @Test
    void anUnpaidBookingPastTheLinkAndGraceExpires() {
        when(bookingRepository.findTop100ByStatusAndPaymentExpiresAtBefore(eq(BookingStatus.AWAITING_PAYMENT), any()))
                .thenReturn(List.of(booking(2L)));

        job.closeStuckBookings();

        verify(bookingCompensator).close(2L, BookingStatus.EXPIRED);
    }

    @Test
    void theCutoffsLeaveALiveCreateAndALatePaymentTheirTime() {
        job.closeStuckBookings();

        // PENDING only after 1 minute (a create takes ~30 s at most); unpaid only 2 minutes after the link expired
        ArgumentCaptor<LocalDateTime> pendingCutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<Instant> paymentCutoff = ArgumentCaptor.forClass(Instant.class);
        verify(bookingRepository).findTop100ByStatusAndBookingDateBefore(eq(BookingStatus.PENDING), pendingCutoff.capture());
        verify(bookingRepository).findTop100ByStatusAndPaymentExpiresAtBefore(eq(BookingStatus.AWAITING_PAYMENT), paymentCutoff.capture());
        assertThat(pendingCutoff.getValue()).isCloseTo(LocalDateTime.now().minusMinutes(1), within(Duration.ofSeconds(5)));
        assertThat(paymentCutoff.getValue()).isCloseTo(Instant.now().minus(Duration.ofMinutes(2)), within(Duration.ofSeconds(5)));
    }

    @Test
    void oneBookingThatFailsToCloseDoesNotStopTheOthers() {
        when(bookingRepository.findTop100ByStatusAndPaymentExpiresAtBefore(eq(BookingStatus.AWAITING_PAYMENT), any()))
                .thenReturn(List.of(booking(2L), booking(3L)));
        when(bookingCompensator.close(2L, BookingStatus.EXPIRED)).thenThrow(new IllegalStateException("db hiccup"));

        job.closeStuckBookings();

        // Booking 2 is picked up again on the next run
        verify(bookingCompensator).close(3L, BookingStatus.EXPIRED);
    }

    private static Booking booking(Long id) {
        return Booking.builder().id(id).build();
    }
}
