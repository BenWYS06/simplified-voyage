package com.ben.services.service.impl;

import com.ben.common_lib.enums.SeatAvailabilityStatus;
import com.ben.common_lib.enums.SeatType;
import com.ben.common_lib.payload.request.SeatHoldRequest;
import com.ben.common_lib.payload.response.SeatHoldResponse;
import com.ben.services.model.Seat;
import com.ben.services.model.SeatInstance;
import com.ben.services.model.SeatMap;
import com.ben.services.repository.FlightInstanceCabinRepository;
import com.ben.services.repository.SeatInstanceRepository;
import com.ben.services.repository.SeatRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// The rules around a seat hold; the races themselves are proven on a real database in SeatHoldConcurrencyTest
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SeatInstanceServiceImplTest {

    private static final Long BOOKING_ID = 9L;
    private static final Long FLIGHT_INSTANCE_ID = 50L;

    @Mock
    private SeatInstanceRepository seatInstanceRepository;

    @Mock
    private SeatRepository seatRepository;

    @Mock
    private FlightInstanceCabinRepository flightInstanceCabinRepository;

    @InjectMocks
    private SeatInstanceServiceImpl seatInstanceService;

    @BeforeEach
    void holdLastsFifteenMinutes() {
        ReflectionTestUtils.setField(seatInstanceService, "holdMinutes", 15L);
    }

    @Test
    void holdsEverySeatInOneUpdateSortedAndWithoutDuplicates() {
        allSeatsInTheFlight();
        when(seatInstanceRepository.holdAll(anyList(), eq(BOOKING_ID), any(), any())).thenReturn(2);

        SeatHoldResponse result = seatInstanceService.holdSeats(holdRequest(7L, 3L, 7L));

        // Sorted ids = rows locked in one order, so two holds can't deadlock each other
        assertThat(result.getSeatInstanceIds()).containsExactly(3L, 7L);
        verify(seatInstanceRepository).holdAll(eq(List.of(3L, 7L)), eq(BOOKING_ID), any(), any());
    }

    @Test
    void theHoldEndsFifteenMinutesFromNow() {
        allSeatsInTheFlight();
        when(seatInstanceRepository.holdAll(anyList(), anyLong(), any(), any())).thenReturn(1);
        Instant before = Instant.now();

        SeatHoldResponse result = seatInstanceService.holdSeats(holdRequest(3L));

        ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
        verify(seatInstanceRepository).holdAll(anyList(), anyLong(), any(), now.capture());
        assertThat(Duration.between(now.getValue(), result.getHeldUntil())).isEqualTo(Duration.ofMinutes(15));
        assertThat(now.getValue()).isAfterOrEqualTo(before);
    }

    @Test
    void rejectsSeatsFromAnotherFlightBeforeTouchingAnyRow() {
        when(seatInstanceRepository.countInFlightInstance(List.of(3L, 7L), FLIGHT_INSTANCE_ID)).thenReturn(1L);

        assertThatThrownBy(() -> seatInstanceService.holdSeats(holdRequest(3L, 7L)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(seatInstanceRepository, never()).holdAll(anyList(), anyLong(), any(), any());
    }

    @Test
    void oneTakenSeatFailsTheWholeHoldWith409() {
        allSeatsInTheFlight();
        when(seatInstanceRepository.holdAll(anyList(), anyLong(), any(), any())).thenReturn(1);

        // Throwing rolls back the seat the UPDATE did take: all or nothing
        assertThatThrownBy(() -> seatInstanceService.holdSeats(holdRequest(3L, 7L)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void confirmBooksAllSeatsOfTheBooking() {
        when(seatInstanceRepository.confirmForBooking(eq(BOOKING_ID), eq(List.of(3L, 7L)), any())).thenReturn(2);

        assertThat(seatInstanceService.confirmSeats(BOOKING_ID, List.of(3L, 7L, 3L))).isEqualTo(2);
    }

    @Test
    void aLostSeatFailsTheConfirmWith409() {
        when(seatInstanceRepository.confirmForBooking(eq(BOOKING_ID), anyList(), any())).thenReturn(1);

        // booking-service turns this 409 into REFUND_REQUIRED
        assertThatThrownBy(() -> seatInstanceService.confirmSeats(BOOKING_ID, List.of(3L, 7L)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void releaseReturnsHowManyHeldSeatsWereFreed() {
        when(seatInstanceRepository.releaseHeld(BOOKING_ID)).thenReturn(0);

        // Running it again after the seats are free is fine: nothing left to release
        assertThat(seatInstanceService.releaseSeats(BOOKING_ID)).isZero();
    }

    @Test
    void anAdminStatusChangeDetachesTheSeatFromItsBooking() {
        SeatInstance held = SeatInstance.builder()
                .id(3L)
                .seat(Seat.builder().id(1L).seatNumber("1A").seatType(SeatType.WINDOW).seatMap(SeatMap.builder().id(60L).build()).build())
                .status(SeatAvailabilityStatus.HELD)
                .bookingId(BOOKING_ID)
                .heldUntil(Instant.now())
                .build();
        when(seatInstanceRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(held));
        when(seatInstanceRepository.save(any(SeatInstance.class))).thenAnswer(invocation -> invocation.getArgument(0));

        seatInstanceService.updateSeatInstanceStatus(3L, SeatAvailabilityStatus.BLOCKED);

        assertThat(held.getStatus()).isEqualTo(SeatAvailabilityStatus.BLOCKED);
        assertThat(held.getBookingId()).isNull();
        assertThat(held.getHeldUntil()).isNull();
    }

    @Test
    void seatPriceAddsTheSurchargesAndTreatsMissingAsZero() {
        when(seatInstanceRepository.findAllById(List.of(3L, 7L))).thenReturn(List.of(
                SeatInstance.builder().id(3L).premiumSurcharge(150_000.0).build(),
                SeatInstance.builder().id(7L).build()));

        assertThat(seatInstanceService.calculateSeatPrice(List.of(3L, 7L))).isEqualTo(150_000.0);
    }

    @Test
    void missingSeatInstanceGivesNotFound() {
        when(seatInstanceRepository.findById(3L)).thenReturn(Optional.empty());
        when(seatInstanceRepository.findByIdForUpdate(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> seatInstanceService.getSeatInstanceById(3L))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> seatInstanceService.updateSeatInstanceStatus(3L, SeatAvailabilityStatus.AVAILABLE))
                .isInstanceOf(EntityNotFoundException.class);
    }

    private void allSeatsInTheFlight() {
        when(seatInstanceRepository.countInFlightInstance(anyList(), eq(FLIGHT_INSTANCE_ID)))
                .thenAnswer(invocation -> (long) invocation.<List<Long>>getArgument(0).size());
    }

    private static SeatHoldRequest holdRequest(Long... seatIds) {
        return SeatHoldRequest.builder()
                .bookingId(BOOKING_ID)
                .flightInstanceId(FLIGHT_INSTANCE_ID)
                .seatInstanceIds(List.of(seatIds))
                .build();
    }
}
