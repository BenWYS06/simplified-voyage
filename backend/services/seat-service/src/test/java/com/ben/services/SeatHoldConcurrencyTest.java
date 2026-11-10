package com.ben.services;

import com.ben.common_lib.enums.SeatAvailabilityStatus;
import com.ben.common_lib.payload.request.SeatHoldRequest;
import com.ben.common_lib.payload.response.SeatHoldResponse;
import com.ben.services.model.SeatInstance;
import com.ben.services.repository.SeatInstanceRepository;
import com.ben.services.service.SeatInstanceService;
import com.ben.services.support.Concurrently;
import com.ben.services.support.IntegrationTest;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Build step 2 (DESIGN.md §3): seat hold = one atomic UPDATE ... WHERE id IN (...) AND free.
 * Repeated so the threads hit many different interleavings.
 */
class SeatHoldConcurrencyTest extends IntegrationTest {

    private static final long FLIGHT_INSTANCE_ID = 100L;

    @Autowired
    private SeatInstanceService seatInstanceService;

    @Autowired
    private SeatInstanceRepository seatInstanceRepository;

    @Autowired
    private JdbcTemplate jdbc;

    // Race condition: 10 bookings click the same seat at the same moment → exactly one gets it, nine get 409
    @RepeatedTest(10)
    void sameSeatAtTheSameMoment_exactlyOneBookingGetsIt() throws Exception {
        long seatId = newSeats(1).get(0);

        List<Callable<?>> tasks = new ArrayList<>();
        LongStream.rangeClosed(1, 10).forEach(bookingId -> tasks.add(() -> hold(bookingId, seatId)));
        List<Object> outcomes = Concurrently.run(tasks);

        List<SeatHoldResponse> winners = outcomes.stream()
                .filter(SeatHoldResponse.class::isInstance).map(SeatHoldResponse.class::cast).toList();
        assertThat(winners).hasSize(1);
        assertThat(outcomes).filteredOn(SeatHoldConcurrencyTest::isConflict).hasSize(9);

        SeatInstance seat = seatInstanceRepository.findById(seatId).orElseThrow();
        assertThat(seat.getStatus()).isEqualTo(SeatAvailabilityStatus.HELD);
        assertThat(seat.getBookingId()).isEqualTo(winners.get(0).getBookingId());
    }

    // Deadlock: A wants (s1, s2), B wants (s2, s1). One UPDATE locks rows in primary-key order, so they queue
    // instead of deadlocking: one gets both seats, the other a clean 409 (never a deadlock error, never half a set)
    @RepeatedTest(10)
    void overlappingSeatsInOppositeOrder_noDeadlockAndNoPartialHold() throws Exception {
        List<Long> seats = newSeats(2);
        long s1 = seats.get(0);
        long s2 = seats.get(1);

        List<Object> outcomes = Concurrently.run(List.of(
                () -> hold(1L, s1, s2),
                () -> hold(2L, s2, s1)));

        assertThat(outcomes).filteredOn(SeatHoldResponse.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(SeatHoldConcurrencyTest::isConflict).hasSize(1);   // nothing else (no deadlock)

        Long winner = outcomes.stream().filter(SeatHoldResponse.class::isInstance)
                .map(o -> ((SeatHoldResponse) o).getBookingId()).findFirst().orElseThrow();
        assertThat(seatInstanceRepository.findAllById(seats))
                .allSatisfy(seat -> assertThat(seat.getBookingId()).isEqualTo(winner));
    }

    private SeatHoldResponse hold(long bookingId, Long... seatIds) {
        return seatInstanceService.holdSeats(SeatHoldRequest.builder()
                .bookingId(bookingId)
                .flightInstanceId(FLIGHT_INSTANCE_ID)
                .seatInstanceIds(List.of(seatIds))
                .build());
    }

    private static boolean isConflict(Object outcome) {
        return outcome instanceof ResponseStatusException e && e.getStatusCode() == HttpStatus.CONFLICT;
    }

    /**
     * Fresh AVAILABLE seats on one cabin of FLIGHT_INSTANCE_ID. Fixture shortcut: foreign key checks are switched
     * off on this one connection, so the cabin class / seat map parents (not part of the hold logic) aren't needed.
     */
    private List<Long> newSeats(int count) {
        return jdbc.execute((ConnectionCallback<List<Long>>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET FOREIGN_KEY_CHECKS = 0");
                try {
                    long cabinId = insert(connection.prepareStatement(
                            "INSERT INTO flight_instance_cabins (flight_instance_id, cabin_class_id, total_seats) "
                                    + "VALUES (" + FLIGHT_INSTANCE_ID + ", 1, " + count + ")",
                            Statement.RETURN_GENERATED_KEYS));
                    List<Long> seatIds = new ArrayList<>();
                    for (int i = 0; i < count; i++) {
                        seatIds.add(insert(connection.prepareStatement(
                                "INSERT INTO seat_instances (flight_instance_cabin_id, seat_id, status, version) "
                                        + "VALUES (" + cabinId + ", 1, 'AVAILABLE', 0)",
                                Statement.RETURN_GENERATED_KEYS)));
                    }
                    return seatIds;
                } finally {
                    statement.execute("SET FOREIGN_KEY_CHECKS = 1");   // the connection goes back to the pool
                }
            }
        });
    }

    private static long insert(PreparedStatement statement) throws java.sql.SQLException {
        try (statement) {
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }
}
