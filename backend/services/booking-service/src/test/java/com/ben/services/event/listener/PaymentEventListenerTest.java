package com.ben.services.event.listener;

import com.ben.common_lib.enums.BookingStatus;
import com.ben.common_lib.event.PaymentCompletedEvent;
import com.ben.common_lib.event.PaymentFailedEvent;
import com.ben.common_lib.payload.response.FlightInstanceResponse;
import com.ben.services.clients.FlightClient;
import com.ben.services.clients.PricingClient;
import com.ben.services.clients.SeatClient;
import com.ben.services.clients.UserClient;
import com.ben.services.event.publisher.BookingEventProducer;
import com.ben.services.model.Booking;
import com.ben.services.repository.BookingRepository;
import com.ben.services.repository.TicketRepository;
import com.ben.services.service.BookingCompensator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// The end of the saga: seats first, then CONFIRMED + booking.confirmed; money that can't be honoured is refunded.
// Duplicate and racing events are proven on a real database in BookingSagaConcurrencyTest.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentEventListenerTest {

    private static final Long BOOKING_ID = 42L;
    private static final Long PAYMENT_ID = 7L;

    @Mock private BookingRepository bookingRepository;
    @Mock private TicketRepository ticketRepository;
    @Mock private BookingEventProducer bookingEventProducer;
    @Mock private BookingCompensator bookingCompensator;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private FlightClient flightClient;
    @Mock private PricingClient pricingClient;
    @Mock private UserClient userClient;
    @Mock private SeatClient seatClient;

    @InjectMocks
    private PaymentEventListener listener;

    @BeforeEach
    void transactionsRunTheCallback() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
        when(bookingRepository.markConfirmed(BOOKING_ID, PAYMENT_ID)).thenReturn(1);
    }

    @Test
    void paymentConfirmsTheSeatsFirstThenTheBooking() {
        bookingIs(BookingStatus.AWAITING_PAYMENT);

        listener.handlePaymentCompleted(completed());

        // Never CONFIRMED while the seats aren't BOOKED
        InOrder order = inOrder(seatClient, bookingRepository, ticketRepository, bookingEventProducer);
        order.verify(seatClient).confirmSeats(eq(BOOKING_ID), anyList());
        order.verify(bookingRepository).markConfirmed(BOOKING_ID, PAYMENT_ID);
        order.verify(ticketRepository).restoreByBooking(BOOKING_ID);
        order.verify(bookingEventProducer).sendBookingConfirmed(any(), any(), any(), any(), any());
        verifyNoInteractions(bookingCompensator);
    }

    @Test
    void lateMoneyAfterTheExpiryJobStillConfirms() {
        bookingIs(BookingStatus.EXPIRED);

        listener.handlePaymentCompleted(completed());

        verify(bookingRepository).markConfirmed(BOOKING_ID, PAYMENT_ID);
        verify(bookingCompensator, never()).refundRequired(anyLong(), anyLong());
    }

    @Test
    void aRedeliveredEventForAFinishedBookingDoesNothing() {
        bookingIs(BookingStatus.CONFIRMED);

        listener.handlePaymentCompleted(completed());

        verifyNoInteractions(seatClient, bookingEventProducer, bookingCompensator);
        verify(bookingRepository, never()).markConfirmed(anyLong(), anyLong());
    }

    @Test
    void moneyForABookingTheUserCancelledIsFlaggedForRefund() {
        bookingIs(BookingStatus.CANCELLED);

        listener.handlePaymentCompleted(completed());

        verify(bookingCompensator).refundRequired(BOOKING_ID, PAYMENT_ID);
        verifyNoInteractions(seatClient);
    }

    @Test
    void seatsTakenByAnotherBookingMeanARefundNotAConfirm() {
        bookingIs(BookingStatus.AWAITING_PAYMENT);
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT)).when(seatClient).confirmSeats(eq(BOOKING_ID), anyList());

        listener.handlePaymentCompleted(completed());

        verify(bookingCompensator).refundRequired(BOOKING_ID, PAYMENT_ID);
        verify(bookingRepository, never()).markConfirmed(anyLong(), anyLong());
    }

    @Test
    void seatServiceDownFailsTheEventSoKafkaRedeliversIt() {
        bookingIs(BookingStatus.AWAITING_PAYMENT);
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE))
                .when(seatClient).confirmSeats(eq(BOOKING_ID), anyList());

        // The money is paid: never cancel or refund on an outage
        assertThatThrownBy(() -> listener.handlePaymentCompleted(completed()))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(bookingCompensator);
    }

    @Test
    void aCancelThatWinsWhileConfirmingTurnsIntoARefund() {
        bookingIs(BookingStatus.AWAITING_PAYMENT);
        when(bookingRepository.markConfirmed(BOOKING_ID, PAYMENT_ID)).thenReturn(0);

        listener.handlePaymentCompleted(completed());

        verify(bookingCompensator).refundRequired(BOOKING_ID, PAYMENT_ID);
        verify(bookingEventProducer, never()).sendBookingConfirmed(any(), any(), any(), any(), any());
    }

    @Test
    void flightOpsDownStillConfirmsWithoutTheFlightDetails() {
        Booking booking = bookingIs(BookingStatus.AWAITING_PAYMENT);
        booking.setFlightInstanceId(50L);
        when(flightClient.getFlightInstanceResponse(50L)).thenThrow(new IllegalStateException("flight-ops down"));

        listener.handlePaymentCompleted(completed());

        // The email is enrichment: it goes out with less detail rather than blocking the confirmation
        verify(bookingEventProducer).sendBookingConfirmed(any(), any(), isNull(FlightInstanceResponse.class), any(), any());
    }

    @Test
    void anEventForAMissingBookingIsDropped() {
        when(bookingRepository.findByIdWithDetails(BOOKING_ID)).thenReturn(Optional.empty());

        listener.handlePaymentCompleted(completed());

        verifyNoInteractions(seatClient, bookingEventProducer, bookingCompensator);
    }

    @Test
    void aFailedPaymentRunsTheSameCompensation() {
        listener.handlePaymentFailed(PaymentFailedEvent.builder().bookingId(BOOKING_ID).paymentId(PAYMENT_ID).build());

        verify(bookingCompensator).close(BOOKING_ID, BookingStatus.CANCELLED);
    }

    private Booking bookingIs(BookingStatus status) {
        Booking booking = Booking.builder().id(BOOKING_ID).status(status).build();
        when(bookingRepository.findByIdWithDetails(BOOKING_ID)).thenReturn(Optional.of(booking));
        return booking;
    }

    private static PaymentCompletedEvent completed() {
        return PaymentCompletedEvent.builder().bookingId(BOOKING_ID).paymentId(PAYMENT_ID).amount(150_000.0).build();
    }
}
