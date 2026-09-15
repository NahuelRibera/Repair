package dev.repair.api.garage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.repair.api.common.VisitorContext;
import dev.repair.api.motorcycle.ModelDetailDto;
import dev.repair.api.motorcycle.MotorcycleCatalogRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * Unit tests (mocked repositories) for the garage-vehicle dedup contract
 * (QA pass section 6/35): the normal "choose your bike" flow reuses an
 * existing garage vehicle; the explicit "+ Add another bike" flow
 * (allowDuplicate=true) always creates a new one, since owning two
 * identical motorcycles is legitimate.
 */
@ExtendWith(MockitoExtension.class)
class GarageVehicleControllerTest {

    private static final UUID VISITOR_ID = UUID.randomUUID();
    private static final long MODEL_ID = 3L;
    private static final int YEAR = 2021;

    @Mock
    private GarageVehicleRepository garageVehicleRepository;
    @Mock
    private MotorcycleCatalogRepository catalogRepository;

    private GarageVehicleController controller;

    @BeforeEach
    void setUp() {
        VisitorContext visitor = new VisitorContext();
        visitor.setVisitorId(VISITOR_ID);
        controller = new GarageVehicleController(garageVehicleRepository, catalogRepository, visitor);

        when(catalogRepository.findModelDetail(MODEL_ID))
                .thenReturn(Optional.of(new ModelDetailDto(MODEL_ID, 1L, "Yamaha", "Tenere 700", "tenere-700")));
        when(catalogRepository.hasKnowledgeCoverage(MODEL_ID, YEAR)).thenReturn(true);
    }

    private GarageVehicleDto sampleDto(long id) {
        return new GarageVehicleDto(id, MODEL_ID, "Yamaha", "Tenere 700", YEAR, null, null, null,
                OffsetDateTime.now(), OffsetDateTime.now());
    }

    @Test
    void normalSelectionReusesAnExistingGarageVehicle() {
        when(garageVehicleRepository.findExisting(VISITOR_ID, MODEL_ID, YEAR)).thenReturn(Optional.of(42L));
        when(garageVehicleRepository.find(VISITOR_ID, 42L)).thenReturn(Optional.of(sampleDto(42L)));

        var response = controller.create(new CreateGarageVehicleRequest(MODEL_ID, YEAR, null, false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().id()).isEqualTo(42L);
        verify(garageVehicleRepository, never()).create(eq(VISITOR_ID), eq(MODEL_ID), eq(YEAR), any(), any());
    }

    @Test
    void normalSelectionWithNoExistingMatchCreatesOne() {
        when(garageVehicleRepository.findExisting(VISITOR_ID, MODEL_ID, YEAR)).thenReturn(Optional.empty());
        when(garageVehicleRepository.create(eq(VISITOR_ID), eq(MODEL_ID), eq(YEAR), any(), any())).thenReturn(7L);
        when(garageVehicleRepository.find(VISITOR_ID, 7L)).thenReturn(Optional.of(sampleDto(7L)));

        var response = controller.create(new CreateGarageVehicleRequest(MODEL_ID, YEAR, null, false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().id()).isEqualTo(7L);
    }

    @Test
    void explicitAddAnotherBikeAlwaysCreatesANewOneEvenIfOneAlreadyExists() {
        when(garageVehicleRepository.create(eq(VISITOR_ID), eq(MODEL_ID), eq(YEAR), any(), any())).thenReturn(9L);
        when(garageVehicleRepository.find(VISITOR_ID, 9L)).thenReturn(Optional.of(sampleDto(9L)));

        var response = controller.create(new CreateGarageVehicleRequest(MODEL_ID, YEAR, "Second bike", true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().id()).isEqualTo(9L);
        // allowDuplicate=true must never even consult findExisting — the
        // rider explicitly asked to add another one.
        verify(garageVehicleRepository, never()).findExisting(any(), anyLong(), anyInt());
    }
}
