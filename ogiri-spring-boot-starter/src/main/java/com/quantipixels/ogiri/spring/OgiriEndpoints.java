// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Quanti Pixels
package com.quantipixels.ogiri.spring;


import com.quantipixels.ogiri.*;
import java.util.List;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Optional JSON endpoints. Management requires an authenticated Ogiri session, not an arbitrary user-supplied owner. */
@RestController
@RequestMapping("${ogiri.base-path:/auth}")
public final class OgiriEndpoints {
    private final JdbcSessions sessions;
    private final OgiriAccounts accounts;
    private final AuthenticationManager authentication;

    public OgiriEndpoints(JdbcSessions sessions, OgiriAccounts accounts, AuthenticationManager authentication) {
        this.sessions = sessions; this.accounts = accounts; this.authentication = authentication;
    }

    public record Login(String username, String password, String client) {
        @Override public String toString() { return "Login[redacted]"; }
    }

    @PostMapping(value = "/sign-in", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Session> signIn(@RequestBody Login login) {
        if (!valid(login.username(), 255) || !valid(login.password(), 1024) || !valid(login.client(), 255))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var credentials = UsernamePasswordAuthenticationToken.unauthenticated(login.username(), login.password());
        try {
            Authentication account = authentication.authenticate(credentials);
            if (account == null || !account.isAuthenticated()) throw new BadCredentialsException("Authentication required");
            var issued = sessions.issue(accounts.subject(account), login.client());
            return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + issued.token()).body(issued.session());
        } finally { credentials.eraseCredentials(); }
    }

    @GetMapping("/session")
    public ResponseEntity<Session> current(Authentication authentication) { return noStore(currentSession(authentication)); }

    @GetMapping("/sessions")
    public ResponseEntity<List<Session>> list(Authentication authentication) {
        return noStore(sessions.list(currentSession(authentication).subject()));
    }

    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<Void> revoke(Authentication authentication, @PathVariable UUID id) {
        boolean removed = sessions.revoke(currentSession(authentication).subject(), id);
        return ResponseEntity.status(removed ? HttpStatus.NO_CONTENT : HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore()).build();
    }

    @DeleteMapping("/sign-out")
    public ResponseEntity<Void> signOut(Authentication authentication) {
        Session current = currentSession(authentication);
        sessions.revoke(current.subject(), current.id());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @DeleteMapping("/sessions")
    public ResponseEntity<Void> revokeAll(Authentication authentication) {
        sessions.revokeAll(currentSession(authentication).subject());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @ExceptionHandler({AuthenticationException.class, SessionStoreException.class, SessionLimitException.class})
    public ResponseEntity<ProblemDetail> failure(RuntimeException failure) {
        HttpStatus status = failure instanceof SessionLimitException ? HttpStatus.CONFLICT
                : failure instanceof AuthenticationServiceException || failure instanceof SessionStoreException
                    ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.UNAUTHORIZED;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(ProblemDetail.forStatus(status));
    }

    private static Session currentSession(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof OAuth2AuthenticatedPrincipal principal
                && principal.getAttribute("ogiri_session") instanceof Session session) return session;
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    private static <T> ResponseEntity<T> noStore(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
    private static boolean valid(String value, int maximum) {
        return value != null && !value.isBlank() && value.length() <= maximum && value.indexOf(0) < 0;
    }
}
