package dev.repair.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The one-time find-or-create that runs on every successful Google login
 * (see OAuth2LoginSuccessHandler and DevLoginController) — never per
 * request. google_sub is the only identity key; a repeat login with the
 * same sub must reuse the existing row, not create a duplicate.
 */
@ExtendWith(MockitoExtension.class)
class AppUserServiceTest {

    @Mock
    private AppUserRepository repository;

    private AppUserService service;

    private AppUserDto sample(long id, String sub) {
        return new AppUserDto(id, sub, "rider@example.test", "Rider", null, OffsetDateTime.now(), OffsetDateTime.now());
    }

    @Test
    void firstLoginForANewSubCreatesAUser() {
        service = new AppUserService(repository);
        when(repository.findByGoogleSub("sub-1")).thenReturn(Optional.empty());
        when(repository.create(eq("sub-1"), eq("rider@example.test"), eq("Rider"), eq("https://pic")))
                .thenReturn(sample(1L, "sub-1"));

        AppUserDto result = service.findOrCreate("sub-1", "rider@example.test", "Rider", "https://pic");

        assertThat(result.id()).isEqualTo(1L);
        verify(repository, never()).updateProfile(anyLong(), any(), any(), any());
    }

    @Test
    void repeatLoginForTheSameSubReusesTheExistingUserAndRefreshesProfile() {
        service = new AppUserService(repository);
        AppUserDto existing = sample(7L, "sub-2");
        when(repository.findByGoogleSub("sub-2")).thenReturn(Optional.of(existing));
        when(repository.findById(7L)).thenReturn(Optional.of(sample(7L, "sub-2")));

        AppUserDto result = service.findOrCreate("sub-2", "new-email@example.test", "New Name", "https://new-pic");

        assertThat(result.id()).isEqualTo(7L);
        verify(repository).updateProfile(7L, "new-email@example.test", "New Name", "https://new-pic");
        verify(repository, never()).create(any(), any(), any(), any());
    }
}
