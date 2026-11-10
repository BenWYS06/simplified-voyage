package com.ben.services.service.impl;

import com.ben.common_lib.enums.SeatType;
import com.ben.services.model.Seat;
import com.ben.services.model.SeatMap;
import com.ben.services.repository.SeatMapRepository;
import com.ben.services.repository.SeatRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Seat generation turns a layout into numbered seats; window and aisle types drive seat choice and pricing
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SeatServiceImplTest {

    @Mock
    private SeatRepository seatRepository;

    @Mock
    private SeatMapRepository seatMapRepository;

    @InjectMocks
    private SeatServiceImpl seatService;

    @Test
    void generatesOneSeatPerRowAndColumnWithLetterNumbers() throws Exception {
        List<Seat> seats = generate(2, 3, 3);

        assertThat(seats).hasSize(12);
        assertThat(seats).extracting(Seat::getSeatNumber).startsWith("1A", "1B", "1C", "1D", "1E", "1F", "2A");
        assertThat(seats.get(6).getSeatRow()).isEqualTo(2);
        assertThat(seats.get(6).getColumnLetter()).isEqualTo('A');
    }

    @Test
    void threeAndThreeGivesWindowMiddleAisleOnEachSide() throws Exception {
        List<Seat> row = generate(1, 3, 3);

        assertThat(row).extracting(Seat::getSeatType).containsExactly(
                SeatType.WINDOW, SeatType.MIDDLE, SeatType.AISLE,
                SeatType.AISLE, SeatType.MIDDLE, SeatType.WINDOW);
    }

    @Test
    void twoAndTwoHasNoMiddleSeat() throws Exception {
        List<Seat> row = generate(1, 2, 2);

        assertThat(row).extracting(Seat::getSeatType).containsExactly(
                SeatType.WINDOW, SeatType.AISLE, SeatType.AISLE, SeatType.WINDOW);
    }

    @Test
    void moreThan26ColumnsContinueWithDoubleLetters() throws Exception {
        List<Seat> row = generate(1, 14, 14);

        assertThat(row.get(25).getSeatNumber()).isEqualTo("1Z");
        assertThat(row.get(26).getSeatNumber()).isEqualTo("1AA");
    }

    @Test
    void refusesToGenerateTwiceForTheSameSeatMap() {
        when(seatRepository.existsBySeatMapId(60L)).thenReturn(true);

        assertThatThrownBy(() -> seatService.generateSeats(60L)).hasMessageContaining("already created");
        verify(seatRepository, never()).saveAll(anyList());
    }

    @Test
    void aMissingSeatMapGeneratesNothing() {
        when(seatMapRepository.findById(60L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> seatService.generateSeats(60L)).hasMessageContaining("seat map not found");
        verify(seatRepository, never()).saveAll(anyList());
    }

    @Test
    void missingSeatGivesNotFound() {
        when(seatRepository.findById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> seatService.getSeatById(5L)).isInstanceOf(EntityNotFoundException.class);
        verify(seatRepository, never()).save(any());
    }

    @SuppressWarnings("unchecked")
    private List<Seat> generate(int rows, int left, int right) throws Exception {
        SeatMap seatMap = SeatMap.builder().id(60L).totalRows(rows).leftSeatsPerRow(left).rightSeatsPerRow(right).build();
        when(seatMapRepository.findById(60L)).thenReturn(Optional.of(seatMap));

        seatService.generateSeats(60L);

        ArgumentCaptor<List<Seat>> saved = ArgumentCaptor.forClass(List.class);
        verify(seatRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).allSatisfy(seat -> assertThat(seat.getSeatMap()).isSameAs(seatMap));
        return saved.getValue();
    }
}
