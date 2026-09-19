package dev.repair.api.auth;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The one endpoint the frontend uses to check whether it's authenticated
 * (a 401 via ApiAuthenticationEntryPoint means "not signed in"). There is
 * deliberately no larger "profile" API — My Garage is the user's main
 * area; see docs/authentication.md. */
@RestController
public class MeController {

    private final AuthenticatedUserContext currentUser;

    public MeController(AuthenticatedUserContext currentUser) {
        this.currentUser = currentUser;
    }

    @GetMapping("/api/me")
    public AppUserDto me() {
        return currentUser.getUser();
    }
}
