package com.ben.services.service.impl;

import com.ben.common_lib.enums.BookingStatus;
import com.ben.common_lib.exception.ForbiddenException;
import com.ben.common_lib.payload.request.BookingRequest;
import com.ben.common_lib.payload.request.PassengerRequest;
import com.ben.common_lib.payload.request.PaymentInitiateRequest;
import com.ben.common_lib.payload.request.SeatHoldRequest;
import com.ben.common_lib.payload.response.AirlineResponse;
import com.ben.common_lib.payload.response.FlightResponse;
import com.ben.common_lib.payload.response.PaymentInitiateResponse;
import com.ben.services.clients.AirlineClient;
import com.ben.services.clients.AncillaryClient;
import com.ben.services.clients.FlightClient;
import com.ben.services.clients.PaymentClient;
import com.ben.services.clients.PricingClient;
import com.ben.services.clients.SeatClient;
import com.ben.services.integration.PricingIntegrationService;
import com.ben.services.model.Booking;
import com.ben.services.model.Passenger;
import com.ben.services.model.Ticket;
import com.ben.services.repository.BookingRepository;
import com.ben.services.service.BookingCompensator;
import com.ben.services.service.PassengerService;
import com.ben.services.service.TicketService;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// The create saga's forward steps, its one compensation and the idempotent answer to a duplicate request.
// Two identical requests at the same moment are proven on a real PostgreSQL in BookingSagaConcurrencyTest.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BookingServiceImplTest {

    private static final Long USER_ID = 3L;
    private static final Long BOOKING_ID = 42L;

    @Mock private BookingRepository bookingRepository;
    @Mock private PassengerService passengerService;
    @Mock private TicketService ticketService;
    @Mock private PricingIntegrationService pricingIntegrationService;
    @Mock private PricingClient pricingClient;
    @Mock private AncillaryClient ancillaryClient;
    @Mock private PaymentClient paymentClient;
    @Mock private SeatClient seatClient;
    @Mock private FlightClient flightClient;
    @Mock private AirlineClient airlineClient;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private BookingCompensator bookingCompensator;

    @InjectMocks
    private BookingServiceImpl bookingService;

    @BeforeEach
    void everyStepSucceeds() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
        when(flightClient.getFlightById(10L)).thenReturn(
                FlightResponse.builder().airline(AirlineResponse.builder().id(1L).build()).build());
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> {
            Booking booking = invocation.getArgument(0);
            booking.setId(BOOKING_ID);
            return booking;
        });
        when(passengerService.createPassengerForBooking(any(), anyLong(), any())).thenAnswer(i -> mock(Passenger.class));
        when(ticketService.issueTicket(any(), any(), anyLong())).thenAnswer(i -> mock(Ticket.class));
        when(pricingIntegrationService.calculateFareTotal(5L)).thenReturn(1_000_000.0);
        when(seatClient.calculateSeatPrice(anyList())).thenReturn(200_000.0);
        when(ancillaryClient.calculateAncillariesPrice(any())).thenReturn(50_000.0);
        when(ancillaryClient.calculateMealPrice(any())).thenReturn(30_000.0);
        when(paymentClient.initiatePayment(any(), eq(USER_ID))).thenReturn(PaymentInitiateResponse.builder()
                .paymentId(7L).checkoutUrl("https://vnpay/pay?signed").expiresAt(Instant.now().plusSeconds(600)).build());
        when(bookingRepository.markAwaitingPayment(eq(BOOKING_ID), anyString(), any(), eq(7L))).thenReturn(1);
    }

    // ── Create ──────────────────────────────────────────────────────────

    @Test
    void createHoldsTheSeatsThenAsksForMoneyAndWaitsForPayment() {
        PaymentInitiateResponse result = bookingService.createBooking(request(101L, 102L), USER_ID);

        assertThat(result.getBookingId()).isEqualTo(BOOKING_ID);
        assertThat(result.getBookingStatus()).isEqualTo(BookingStatus.AWAITING_PAYMENT);
        assertThat(result.getReused()).isFalse();
        assertThat(result.getCheckoutUrl()).isEqualTo("https://vnpay/pay?signed");

        ArgumentCaptor<SeatHoldRequest> hold = ArgumentCaptor.forClass(SeatHoldRequest.class);
        verify(seatClient).holdSeats(hold.capture());
        assertThat(hold.getValue().getBookingId()).isEqualTo(BOOKING_ID);
        assertThat(hold.getValue().getFlightInstanceId()).isEqualTo(50L);
        assertThat(hold.getValue().getSeatInstanceIds()).containsExactly(101L, 102L);
        verifyNoInteractions(bookingCompensator);
    }

    @Test
    void theAmountIsPricedServerSideFromTheIds() {
        bookingService.createBooking(request(101L, 102L), USER_ID);

        // fare × 2 passengers + seats + ancillaries + meals
        ArgumentCaptor<PaymentInitiateRequest> payment = ArgumentCaptor.forClass(PaymentInitiateRequest.class);
        verify(paymentClient).initiatePayment(payment.capture(), eq(USER_ID));
        assertThat(payment.getValue().getAmount()).isEqualTo(2_280_000.0);
        assertThat(payment.getValue().getBookingId()).isEqualTo(BOOKING_ID);
    }

    @Test
    void twoPassengersOnOneSeatIsA400BeforeAnythingIsSaved() {
        assertThatThrownBy(() -> bookingService.createBooking(request(101L, 101L), USER_ID))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(flightClient, seatClient, paymentClient);
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void aPassengerWithoutASeatIsA400() {
        BookingRequest request = request(101L);
        request.getPassengers().add(PassengerRequest.builder().firstName("No seat").build());

        assertThatThrownBy(() -> bookingService.createBooking(request, USER_ID))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void flightOpsDownIsA503WithNothingToUndo() {
        when(flightClient.getFlightById(10L)).thenReturn(null);

        assertThatThrownBy(() -> bookingService.createBooking(request(101L), USER_ID))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verify(bookingRepository, never()).save(any());
        verifyNoInteractions(bookingCompensator);
    }

    @Test
    void aTakenSeatCancelsTheBookingAndPassesThe409On() {
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT)).when(seatClient).holdSeats(any());

        assertThatThrownBy(() -> bookingService.createBooking(request(101L), USER_ID))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(bookingCompensator).close(BOOKING_ID, BookingStatus.CANCELLED);
        verifyNoInteractions(paymentClient);
    }

    @Test
    void paymentServiceDownRunsTheSameCompensation() {
        when(paymentClient.initiatePayment(any(), eq(USER_ID)))
                .thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> bookingService.createBooking(request(101L), USER_ID))
                .isInstanceOf(ResponseStatusException.class);
        verify(bookingCompensator).close(BOOKING_ID, BookingStatus.CANCELLED);
    }

    @Test
    void aBookingClosedWhileCreatingNeverHandsOutALink() {
        when(bookingRepository.markAwaitingPayment(eq(BOOKING_ID), anyString(), any(), eq(7L))).thenReturn(0);

        assertThatThrownBy(() -> bookingService.createBooking(request(101L), USER_ID))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verify(bookingCompensator).close(BOOKING_ID, BookingStatus.CANCELLED);
    }

    // ── Duplicate request (idempotent create) ───────────────────────────

    @Test
    void aDuplicateRequestGetsTheSameLinkBack() {
        duplicateKeyOnInsert();
        Booking existing = live(BookingStatus.AWAITING_PAYMENT, Instant.now().plus(5, ChronoUnit.MINUTES));
        existing.setCheckoutUrl("https://vnpay/pay?first");
        when(bookingRepository.findLiveByKey(eq(USER_ID), eq(50L), anyString())).thenReturn(Optional.of(existing));

        PaymentInitiateResponse result = bookingService.createBooking(request(101L), USER_ID);

        assertThat(result.getReused()).isTrue();
        assertThat(result.getBookingId()).isEqualTo(99L);
        assertThat(result.getCheckoutUrl()).isEqualTo("https://vnpay/pay?first");
        verifyNoInteractions(seatClient, paymentClient);
    }

    @Test
    void aDuplicateWhileTheFirstIsStillRunningIsToldToWait() {
        duplicateKeyOnInsert();
        when(bookingRepository.findLiveByKey(eq(USER_ID), eq(50L), anyString()))
                .thenReturn(Optional.of(live(BookingStatus.PENDING, null)));

        PaymentInitiateResponse result = bookingService.createBooking(request(101L), USER_ID);

        // The controller answers 202 for success = false
        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getCheckoutUrl()).isNull();
    }

    @Test
    void aDuplicateAfterTheLinkAndGraceRanOutExpiresTheOldBookingAndBooksAgain() {
        when(bookingRepository.save(any(Booking.class)))
                .thenThrow(bookingKeyViolation())
                .thenAnswer(invocation -> {
                    Booking booking = invocation.getArgument(0);
                    booking.setId(BOOKING_ID);
                    return booking;
                });
        when(bookingRepository.findLiveByKey(eq(USER_ID), eq(50L), anyString()))
                .thenReturn(Optional.of(live(BookingStatus.AWAITING_PAYMENT, Instant.now().minus(10, ChronoUnit.MINUTES))));

        PaymentInitiateResponse result = bookingService.createBooking(request(101L), USER_ID);

        verify(bookingCompensator).close(99L, BookingStatus.EXPIRED);
        assertThat(result.getBookingId()).isEqualTo(BOOKING_ID);
        assertThat(result.getReused()).isFalse();
    }

    @Test
    void anotherConstraintViolationIsNotMistakenForADuplicate() {
        when(bookingRepository.save(any(Booking.class)))
                .thenThrow(new DataIntegrityViolationException("value too long"));

        assertThatThrownBy(() -> bookingService.createBooking(request(101L), USER_ID))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(bookingRepository, never()).findLiveByKey(anyLong(), anyLong(), anyString());
    }

    @Test
    void theSameSeatsInAnotherOrderAreTheSameRequest() {
        duplicateKeyOnInsert();
        when(bookingRepository.findLiveByKey(eq(USER_ID), eq(50L), anyString()))
                .thenReturn(Optional.of(live(BookingStatus.PENDING, null)));

        bookingService.createBooking(request(101L, 102L), USER_ID);
        bookingService.createBooking(request(102L, 101L), USER_ID);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(bookingRepository, times(2)).findLiveByKey(eq(USER_ID), eq(50L), keys.capture());
        assertThat(keys.getAllValues().get(0)).isEqualTo(keys.getAllValues().get(1)).hasSize(64);
    }

    // ── Cancel and view ─────────────────────────────────────────────────

    @Test
    void onlyTheOwnerCanCancel() {
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(owned(99L, BookingStatus.AWAITING_PAYMENT)));

        assertThatThrownBy(() -> bookingService.cancelBooking(BOOKING_ID, USER_ID))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(bookingCompensator);
    }

    @Test
    void aPaidBookingCannotBeCancelled() {
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(owned(USER_ID, BookingStatus.CONFIRMED)));
        when(bookingCompensator.close(BOOKING_ID, BookingStatus.CANCELLED)).thenReturn(false);

        assertThatThrownBy(() -> bookingService.cancelBooking(BOOKING_ID, USER_ID))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void anotherTravellerCannotViewTheBooking() {
        when(bookingRepository.findByIdWithDetails(BOOKING_ID)).thenReturn(Optional.of(owned(99L, BookingStatus.CONFIRMED)));

        assertThatThrownBy(() -> bookingService.getBookingById(BOOKING_ID, USER_ID, "ROLE_CUSTOMER"))
                .isInstanceOf(ForbiddenException.class);
    }

    private void duplicateKeyOnInsert() {
        when(bookingRepository.save(any(Booking.class))).thenThrow(bookingKeyViolation());
    }

    // What PostgreSQL's partial unique index raises through Hibernate when a second live booking is inserted
    private static DataIntegrityViolationException bookingKeyViolation() {
        return new DataIntegrityViolationException("duplicate key",
                new ConstraintViolationException("duplicate key", new SQLException("23505"), "uk_booking_active"));
    }

    private static Booking live(BookingStatus status, Instant paymentExpiresAt) {
        return Booking.builder().id(99L).userId(USER_ID).status(status).paymentId(8L)
                .paymentExpiresAt(paymentExpiresAt).build();
    }

    private static Booking owned(Long userId, BookingStatus status) {
        return Booking.builder().id(BOOKING_ID).userId(userId).status(status).build();
    }

    private static BookingRequest request(Long... seatIds) {
        List<PassengerRequest> passengers = new ArrayList<>();
        for (Long seatId : seatIds) {
            passengers.add(PassengerRequest.builder().firstName("P" + seatId).seatInstanceId(seatId).build());
        }
        return BookingRequest.builder()
                .flightId(10L)
                .flightInstanceId(50L)
                .fareId(5L)
                .passengers(passengers)
                .ancillaryIds(List.of(1L))
                .mealIds(List.of(2L))
                .build();
    }
}
