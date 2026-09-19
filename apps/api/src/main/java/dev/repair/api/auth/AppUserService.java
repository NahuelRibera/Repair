package dev.repair.api.auth;

import org.springframework.stereotype.Service;

/**
 * Find-or-create by Google `sub` — called exactly once per successful
 * OAuth2 login (see OAuth2LoginSuccessHandler), never per request. The
 * first successful login for a given sub creates the Repair user; every
 * subsequent login with the same sub reuses it and refreshes the cached
 * profile fields (name/email/picture, in case they changed on Google's
 * side since last time).
 */
@Service
public class AppUserService {

    private final AppUserRepository repository;

    public AppUserService(AppUserRepository repository) {
        this.repository = repository;
    }

    public AppUserDto findOrCreate(String googleSub, String email, String displayName, String googlePictureUrl) {
        return repository.findByGoogleSub(googleSub)
                .map(existing -> {
                    repository.updateProfile(existing.id(), email, displayName, googlePictureUrl);
                    return repository.findById(existing.id()).orElseThrow();
                })
                .orElseGet(() -> repository.create(googleSub, email, displayName, googlePictureUrl));
    }
}
