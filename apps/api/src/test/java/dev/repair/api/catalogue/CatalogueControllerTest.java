package dev.repair.api.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.repair.api.common.NotFoundException;
import dev.repair.api.common.PageResult;
import dev.repair.api.config.DemoVehicleProperties;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests (mocked repository) for the page-size clamp that was part of
 * the incomplete-dropdown bug: a request for a very large size must still
 * be clamped to a real, enforced bound (not literally unbounded), and that
 * bound must be big enough to cover the catalogue's real per-manufacturer
 * maximums (~110) — see CatalogueRepositoryIT for the data-level proof and
 * docs/planning/status.md for the full bug writeup.
 */
@ExtendWith(MockitoExtension.class)
class CatalogueControllerTest {

    @Mock
    private CatalogueRepository repository;

    @Test
    void requestingAHugePageSizeIsClampedNotPassedThroughUnbounded() {
        var controller = new CatalogueController(repository, sampleDemoProperties());
        when(repository.searchManufacturers(anyString(), anyInt(), eq(500)))
                .thenReturn(new PageResult<>(List.of(), 0, 500, 0));

        controller.manufacturers("", 0, 1_000_000);

        verify(repository).searchManufacturers(eq(""), eq(0), eq(500));
    }

    @Test
    void theClampCoversTheRealCatalogueMaximum() {
        // Ford has 110 models in the real catalogue (see data-findings.md) —
        // the clamp must not silently drop back below that.
        var controller = new CatalogueController(repository, sampleDemoProperties());
        when(repository.searchModels(anyLong(), anyString(), anyInt(), eq(500)))
                .thenReturn(new PageResult<>(List.of(), 0, 500, 0));

        controller.models(1L, "", 0, 500);

        verify(repository).searchModels(eq(1L), eq(""), eq(0), eq(500));
    }

    @Test
    void demoVehicleEndpointReturns404WhenConfiguredVehicleDoesNotResolve() {
        var controller = new CatalogueController(repository, sampleDemoProperties());
        when(repository.findDemoVariant(anyString(), anyString(), anyString(), eq(2008)))
                .thenReturn(Optional.empty());

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                NotFoundException.class, controller::demoVehicle
        )).hasMessageContaining("demo vehicle");
    }

    private DemoVehicleProperties sampleDemoProperties() {
        return new DemoVehicleProperties("BMW", "BMW 3 Series Sedan", "(E90) 320d 6MT RWD (177 HP)", 2008);
    }
}
