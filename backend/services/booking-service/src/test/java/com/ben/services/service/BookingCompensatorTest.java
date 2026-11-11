package com.ben.services.service;

import com.ben.common_lib.enums.BookingStatus;
import com.ben.services.clients.SeatClient;
import com.ben.services.event.publisher.BookingEventProducer;
import com.ben.services.repository.BookingRepository;
import com.ben.services.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// The one undo every failure path runs: close once, cancel tickets, queue booking.cancelled, free the seats
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BookingCompensatorTest {

    private static final Long BOOKING_ID = 42L;

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private BookingEventProducer bookingEventProducer;

    @Mock
    private SeatClient seatClient;

    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private BookingCompensator compensator;

    @BeforeEach
    void transactionsRunTheCallback() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
    }

    @Test
    void closingALiveBookingCancelsTicketsQueuesTheEventAndFreesTheSeats() {
        when(bookingRepository.closeIfAlive(BOOKING_ID, BookingStatus.EXPIRED)).thenReturn(1);

        assertThat(compensator.close(BOOKING_ID, BookingStatus.EXPIRED)).isTrue();

        // The event is queued inside the transaction; the fast release only runs after it
        InOrder order = inOrder(ticketRepository, bookingEventProducer, seatClient);
        order.verify(ticketRepository).cancelByBooking(BOOKING_ID);
        order.verify(bookingEventProducer).sendBookingCancelled(BOOKING_ID, "EXPIRED");
        order.verify(seatClient).releaseSeats(BOOKING_ID);
    }

    @Test
    void aBookingSomeoneElseClosedIsLeftAlone() {
        when(bookingRepository.closeIfAlive(BOOKING_ID, BookingStatus.CANCELLED)).thenReturn(0);

        assertThat(compensator.close(BOOKING_ID, BookingStatus.CANCELLED)).isFalse();
        // Whoever closed it already released its seats: no second event, no second release
        verifyNoInteractions(ticketRepository, bookingEventProducer, seatClient);
    }

    @Test
    void seatServiceDownDoesNotFailTheClose() {
        when(bookingRepository.closeIfAlive(BOOKING_ID, BookingStatus.CANCELLED)).thenReturn(1);
        doThrow(new IllegalStateException("seat-service down")).when(seatClient).releaseSeats(BOOKING_ID);

        // The booking.cancelled event in the outbox releases the seats once seat-service is back
        assertThat(compensator.close(BOOKING_ID, BookingStatus.CANCELLED)).isTrue();
        verify(bookingEventProducer).sendBookingCancelled(BOOKING_ID, "CANCELLED");
    }

    @Test
    void moneyThatCannotBeHonouredFlagsARefundAndFreesTheSeats() {
        when(bookingRepository.markRefundRequired(BOOKING_ID, 7L)).thenReturn(1);

        assertThat(compensator.refundRequired(BOOKING_ID, 7L)).isTrue();

        verify(ticketRepository).cancelByBooking(BOOKING_ID);
        verify(bookingEventProducer).sendBookingCancelled(BOOKING_ID, "REFUND_REQUIRED");
        verify(seatClient).releaseSeats(BOOKING_ID);
    }

    @Test
    void aDuplicateRefundEventChangesNothing() {
        when(bookingRepository.markRefundRequired(BOOKING_ID, 7L)).thenReturn(0);

        assertThat(compensator.refundRequired(BOOKING_ID, 7L)).isFalse();
        verify(bookingEventProducer, never()).sendBookingCancelled(anyLong(), anyString());
        verifyNoInteractions(ticketRepository, seatClient);
    }
}
