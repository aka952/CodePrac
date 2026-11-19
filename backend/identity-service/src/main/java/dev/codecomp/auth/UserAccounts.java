package dev.codecomp.auth;

import dev.codecomp.platform.SignedInUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserAccounts {

    private final UserRepository users;

    UserAccounts(UserRepository users) {
        this.users = users;
    }

    /**
     * Finds the account for this identity or creates it on first sign-in.
     *
     * @param subject the provider's stable id for the person (Google's "sub"), never the email, which can change
     */
    @Transactional
    public SignedInUser signIn(String provider, String subject, String email, String name, String picture) {
        var user = users.findByProviderAndSubject(provider, subject).orElseGet(() -> new User(provider, subject));
        user.signedIn(email, name, picture);
        return users.save(user).toSignedInUser();
    }
}
