package com.ben.services.service.impl;

import com.ben.common_lib.enums.CabinClassType;
import com.ben.common_lib.payload.request.SeatMapRequest;
import com.ben.common_lib.payload.response.AirlineResponse;
import com.ben.common_lib.payload.response.SeatMapResponse;
import com.ben.services.clients.AirlineClient;
import com.ben.services.model.CabinClass;
import com.ben.services.model.SeatMap;
import com.ben.services.repository.CabinClassRepository;
import com.ben.services.repository.SeatMapRepository;
import com.ben.services.service.SeatService;
import feign.FeignException;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// A seat map belongs to the owner's airline and generates its seats once; names are unique per airline and cabin
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SeatMapServiceImplTest {

    private static final Long USER_ID = 7L;
    private static final Long AIRLINE_ID = 1L;
    private static final Long CABIN_ID = 30L;

    @Mock
    private SeatMapRepository seatMapRepository;

    @Mock
    private CabinClassRepository cabinClassRepository;

    @Mock
    private AirlineClient airlineClient;

    @Mock
    private SeatService seatService;

    @InjectMocks
    private SeatMapServiceImpl seatMapService;

    private final CabinClass cabin = CabinClass.builder().id(CABIN_ID).name(CabinClassType.ECONOMY).code("Y").build();

    @BeforeEach
    void ownerHasAnAirlineAndTheCabinExists() {
        when(airlineClient.getAirlineByOwner(USER_ID)).thenReturn(AirlineResponse.builder().id(AIRLINE_ID).build());
        when(cabinClassRepository.findById(CABIN_ID)).thenReturn(Optional.of(cabin));
        when(seatMapRepository.save(any(SeatMap.class))).thenAnswer(invocation -> {
            SeatMap seatMap = invocation.getArgument(0);
            seatMap.setId(60L);
            return seatMap;
        });
    }

    @Test
    void createSavesUnderTheOwnersAirlineAndGeneratesTheSeats() throws Exception {
        SeatMapResponse result = seatMapService.createSeatMap(USER_ID, request("Main deck"));

        assertThat(result.getAirlineId()).isEqualTo(AIRLINE_ID);
        assertThat(result.getCabinClassId()).isEqualTo(CABIN_ID);
        verify(seatService).generateSeats(60L);
    }

    @Test
    void createRejectsANameTheAirlineAlreadyUsesInThatCabin() throws Exception {
        when(seatMapRepository.existsByAirlineIdAndCabinClassIdAndName(AIRLINE_ID, CABIN_ID, "Main deck"))
                .thenReturn(true);

        assertThatThrownBy(() -> seatMapService.createSeatMap(USER_ID, request("Main deck")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Main deck");
        verify(seatMapRepository, never()).save(any());
        verify(seatService, never()).generateSeats(anyLong());
    }

    @Test
    void createFailsOnAMissingCabin() throws Exception {
        when(cabinClassRepository.findById(CABIN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> seatMapService.createSeatMap(USER_ID, request("Main deck")))
                .isInstanceOf(EntityNotFoundException.class);
        verify(seatService, never()).generateSeats(anyLong());
    }

    @Test
    void ownerWithoutAnAirlineCannotCreate() {
        FeignException.NotFound notFound = mock(FeignException.NotFound.class);
        when(airlineClient.getAirlineByOwner(USER_ID)).thenThrow(notFound);

        assertThatThrownBy(() -> seatMapService.createSeatMap(USER_ID, request("Main deck")))
                .isInstanceOf(EntityNotFoundException.class);
        verify(seatMapRepository, never()).save(any());
    }

    @Test
    void anOutageOfAirlineCoreIsNotReportedAsNotFound() {
        FeignException.ServiceUnavailable unavailable = mock(FeignException.ServiceUnavailable.class);
        when(airlineClient.getAirlineByOwner(USER_ID)).thenThrow(unavailable);

        assertThatThrownBy(() -> seatMapService.createSeatMap(USER_ID, request("Main deck")))
                .isNotInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("airline-core-service");
    }

    @Test
    void bulkCreateSkipsExistingNamesAndGeneratesSeatsForTheRest() throws Exception {
        when(seatMapRepository.existsByAirlineIdAndCabinClassIdAndName(AIRLINE_ID, CABIN_ID, "Old"))
                .thenReturn(true);
        when(seatMapRepository.saveAll(anyList())).thenAnswer(invocation -> {
            List<SeatMap> maps = invocation.getArgument(0);
            for (int i = 0; i < maps.size(); i++) {
                maps.get(i).setId(70L + i);
            }
            return maps;
        });

        List<SeatMapResponse> result = seatMapService.createSeatMaps(USER_ID,
                List.of(request("Old"), request("New A"), request("New B")));

        assertThat(result).extracting(SeatMapResponse::getName).containsExactly("New A", "New B");
        verify(seatService, times(2)).generateSeats(anyLong());
    }

    @Test
    void updateRejectsANameAnotherSeatMapOfTheAirlineUses() {
        when(seatMapRepository.findById(60L)).thenReturn(Optional.of(seatMap()));
        when(seatMapRepository.existsByAirlineIdAndNameAndIdNot(AIRLINE_ID, "Taken", 60L)).thenReturn(true);

        assertThatThrownBy(() -> seatMapService.updateSeatMap(USER_ID, 60L, request("Taken")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(seatMapRepository, never()).save(any());
    }

    @Test
    void updateChangesTheLayout() {
        SeatMap existing = seatMap();
        when(seatMapRepository.findById(60L)).thenReturn(Optional.of(existing));
        SeatMapRequest request = request("Main deck");
        request.setTotalRows(40);

        seatMapService.updateSeatMap(USER_ID, 60L, request);

        ArgumentCaptor<SeatMap> saved = ArgumentCaptor.forClass(SeatMap.class);
        verify(seatMapRepository).save(saved.capture());
        assertThat(saved.getValue().getTotalRows()).isEqualTo(40);
    }

    @Test
    void aCabinWithoutASeatMapGivesNotFound() {
        when(seatMapRepository.findByCabinClassId(CABIN_ID)).thenReturn(null);

        assertThatThrownBy(() -> seatMapService.getSeatMapsByCabinClass(CABIN_ID))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void deletingAMissingSeatMapFails() {
        when(seatMapRepository.existsById(60L)).thenReturn(false);

        assertThatThrownBy(() -> seatMapService.deleteSeatMap(60L)).hasMessageContaining("60");
        verify(seatMapRepository, never()).deleteById(any());
    }

    private SeatMap seatMap() {
        return SeatMap.builder().id(60L).name("Main deck").totalRows(30)
                .leftSeatsPerRow(3).rightSeatsPerRow(3).airlineId(AIRLINE_ID).cabinClass(cabin).build();
    }

    private static SeatMapRequest request(String name) {
        return SeatMapRequest.builder()
                .name(name)
                .totalRows(30)
                .leftSeatsPerRow(3)
                .rightSeatsPerRow(3)
                .cabinClassId(CABIN_ID)
                .build();
    }
}
