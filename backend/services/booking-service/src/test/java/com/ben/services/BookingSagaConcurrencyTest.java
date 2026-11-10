package com.ben.services;

import com.ben.common_lib.enums.BookingStatus;
import com.ben.common_lib.enums.Gender;
import com.ben.common_lib.enums.TicketStatus;
import com.ben.common_lib.event.PaymentCompletedEvent;
import com.ben.common_lib.payload.request.BookingRequest;
import com.ben.common_lib.payload.request.PassengerRequest;
import com.ben.common_lib.payload.response.AirlineResponse;
import com.ben.common_lib.payload.response.FareResponse;
import com.ben.common_lib.payload.response.FlightResponse;
import com.ben.common_lib.payload.response.PaymentInitiateResponse;
import com.ben.common_lib.payload.response.SeatHoldResponse;
import com.ben.services.clients.*;
import com.ben.services.event.listener.PaymentEventListener;
import com.ben.services.job.BookingExpiryJob;
import com.ben.services.model.Booking;
import com.ben.services.model.Ticket;
import com.ben.services.outbox.OutboxRepository;
import com.ben.services.repository.BookingRepository;
import com.ben.services.repository.TicketRepository;
import com.ben.services.service.BookingService;
import com.ben.services.support.Concurrently;
import com.ben.services.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Booking's own saga logic under concurrency, on a real PostgreSQL (partial unique index, state guards,
 * outbox in the same transaction). Other services are mocked: these tests are about booking's DB rules.
 */
class BookingSagaConcurrencyTest extends IntegrationTest {

    private static final long USER_ID = 42L;
    private static final long SEAT_ID = 501L;

    @MockitoBean private SeatClient seatClient;
    @MockitoBean private PaymentClient paymentClient;
    @MockitoBean private FlightClient flightClient;
    @MockitoBean private PricingClient pricingClient;
    @MockitoBean private AncillaryClient ancillaryClient;
    @MockitoBean private UserClient userClient;

    @Autowired private BookingService bookingService;
    @Autowired private PaymentEventListener paymentEventListener;
    @Autowired private BookingExpiryJob bookingExpiryJob;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private OutboxRepository outboxRepository;

    @BeforeEach
    void stubOtherServices() {
        when(flightClient.getFlightById(anyLong())).thenReturn(
                FlightResponse.builder().id(1L).airline(AirlineResponse.builder().id(1L).build()).build());
        when(pricingClient.getFareById(anyLong())).thenReturn(FareResponse.builder().id(1L).baseFare(100.0).build());
        when(seatClient.calculateSeatPrice(anyList())).thenReturn(0.0);
        when(ancillaryClient.calculateAncillariesPrice(any())).thenReturn(0.0);
        when(ancillaryClient.calculateMealPrice(any())).thenReturn(0.0);
        when(seatClient.confirmSeats(anyLong(), anyList())).thenReturn(1);
        // a slow hold keeps the first request in PENDING while the duplicate arrives
        when(seatClient.holdSeats(any())).thenAnswer(call -> {
            Thread.sleep(300);
            return SeatHoldResponse.builder().build();
        });
        when(paymentClient.initiatePayment(any(), anyLong())).thenAnswer(call -> PaymentInitiateResponse.builder()
                .paymentId(7L)
                .checkoutUrl("https://sandbox.vnpayment.vn/pay")
                .expiresAt(Instant.now().plus(Duration.ofMinutes(10)))
                .success(true)
                .build());
    }

    // Idempotent create (step 3): the same request twice at once → ONE booking; the copy gets that booking back
    @RepeatedTest(5)
    void sameBookingRequestTwiceAtTheSameMoment_createsOneBooking() throws Exception {
        BookingRequest request = bookingRequest(newFlightInstanceId());

        List<Object> outcomes = Concurrently.run(List.of(
                () -> bookingService.createBooking(request, USER_ID),
                () -> bookingService.createBooking(request, USER_ID)));

        assertThat(outcomes).allMatch(PaymentInitiateResponse.class::isInstance);   // no error for the duplicate
        List<PaymentInitiateResponse> answers = outcomes.stream().map(PaymentInitiateResponse.class::cast).toList();
        assertThat(answers).filteredOn(a -> !Boolean.TRUE.equals(a.getReused())).hasSize(1);   // one created it
        assertThat(answers).filteredOn(a -> Boolean.TRUE.equals(a.getReused())).hasSize(1);    // one was answered from it
        assertThat(answers).extracting(PaymentInitiateResponse::getBookingId).containsOnly(answers.get(0).getBookingId());
        assertThat(bookingRepository.countByFlightInstanceId(request.getFlightInstanceId())).isEqualTo(1);
    }

    // Idempotent consumer (steps 3 + 6): payment.completed delivered twice at once → CONFIRMED once,
    // ONE booking.confirmed event in the outbox (one e-ticket email)
    @RepeatedTest(5)
    void paymentCompletedTwiceAtTheSameMoment_confirmsOnce() throws Exception {
        Booking booking = awaitingPaymentBooking(Instant.now().plus(Duration.ofMinutes(10)));
        PaymentCompletedEvent event = paymentCompleted(booking);

        List<Object> outcomes = Concurrently.run(List.of(
                () -> { paymentEventListener.handlePaymentCompleted(event); return "done"; },
                () -> { paymentEventListener.handlePaymentCompleted(event); return "done"; }));

        assertThat(outcomes).containsOnly("done");
        assertThat(bookingRepository.findById(booking.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.CONFIRMED);
        assertThat(outboxRepository.findAll()).filteredOn(e ->
                e.getAggregateId().equals(String.valueOf(booking.getId())) && e.getTopic().equals("booking.confirmed"))
                .hasSize(1);
    }

    // Saga recovery vs late money (step 4): the expiry job and the payment result hit an expired, unpaid booking
    // at the same moment. Whichever runs first, the paying user ends CONFIRMED with valid tickets.
    @RepeatedTest(5)
    void expiryJobAndPaymentAtTheSameMoment_paidBookingEndsConfirmed() throws Exception {
        Booking booking = awaitingPaymentBooking(Instant.now().minus(Duration.ofMinutes(5)));   // link + grace over
        PaymentCompletedEvent event = paymentCompleted(booking);

        Concurrently.run(List.of(
                () -> { bookingExpiryJob.closeStuckBookings(); return "job"; },
                () -> { paymentEventListener.handlePaymentCompleted(event); return "payment"; }));

        assertThat(bookingRepository.findById(booking.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.CONFIRMED);
        assertThat(ticketRepository.findByBookingId(booking.getId()))
                .allSatisfy(ticket -> assertThat(ticket.getStatus()).isEqualTo(TicketStatus.BOOKED));
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private static long newFlightInstanceId() {
        return ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE);
    }

    private static BookingRequest bookingRequest(long flightInstanceId) {
        PassengerRequest passenger = PassengerRequest.builder()
                .firstName("Minh")
                .lastName("Tran")
                .dateOfBirth(LocalDate.of(2000, 1, 1))
                .gender(Gender.MALE)
                .seatInstanceId(SEAT_ID)
                .build();
        return BookingRequest.builder()
                .flightId(1L)
                .flightInstanceId(flightInstanceId)
                .fareId(1L)
                .passengers(List.of(passenger))
                .build();
    }

    // An unpaid booking with one ticket, as createBooking leaves it once the VNPay link is ready
    private Booking awaitingPaymentBooking(Instant paymentExpiresAt) {
        Booking booking = bookingRepository.save(Booking.builder()
                .bookingReference("BK" + UUID.randomUUID().toString().substring(0, 8))
                .userId(USER_ID)
                .flightId(1L)
                .flightInstanceId(newFlightInstanceId())
                .airlineId(1L)
                .status(BookingStatus.AWAITING_PAYMENT)
                .checkoutUrl("https://sandbox.vnpayment.vn/pay")
                .paymentExpiresAt(paymentExpiresAt)
                .build());
        ticketRepository.save(Ticket.builder()
                .ticketNumber("T-" + UUID.randomUUID())
                .status(TicketStatus.BOOKED)
                .issuedAt(LocalDateTime.now())
                .booking(booking)
                .seatInstanceId(SEAT_ID)
                .build());
        return booking;
    }

    private static PaymentCompletedEvent paymentCompleted(Booking booking) {
        return PaymentCompletedEvent.builder()
                .paymentId(7L)
                .bookingId(booking.getId())
                .userId(USER_ID)
                .amount(100.0)
                .build();
    }
}
