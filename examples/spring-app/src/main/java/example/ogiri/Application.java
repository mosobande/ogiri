// SPDX-License-Identifier: Apache-2.0
package example.ogiri;

import com.quantipixels.ogiri.*;
import com.quantipixels.ogiri.spring.OgiriOpaqueTokenIntrospector;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.*;
import org.springframework.security.authentication.*;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Runnable integration example. Demo users are not an account-registration system. */
@SpringBootApplication
public class Application {
    public static void main(String[] args) { SpringApplication.run(Application.class, args); }

    @Bean PostgresSessions sessions(DataSource dataSource) { return new PostgresSessions(dataSource); }
    @Bean PasswordEncoder passwords() { return Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8(); }

    @Bean UserDetailsService accounts(PasswordEncoder encoder, @Value("${demo.password}") String password) {
        if (password.isBlank()) throw new IllegalArgumentException("Set OGIRI_DEMO_PASSWORD");
        return new InMemoryUserDetailsManager(User.withUsername("demo").password(encoder.encode(password)).roles("USER").build());
    }

    @Bean AuthenticationManager loginManager(UserDetailsService accounts, PasswordEncoder passwords) {
        var provider = new DaoAuthenticationProvider(accounts);
        provider.setPasswordEncoder(passwords);
        return new ProviderManager(provider);
    }

    @Bean OpaqueTokenIntrospector introspector(PostgresSessions sessions, UserDetailsService accounts) {
        return new OgiriOpaqueTokenIntrospector(sessions, subject -> {
            if (!subject.realm().equals("demo") || !subject.tenantId().isEmpty()) throw new UsernameNotFoundException("Unknown account");
            // This demo's immutable username is its account ID. Real mutable logins need stable IDs.
            return accounts.loadUserByUsername(subject.subjectId());
        });
    }

    @Bean SecurityFilterChain security(HttpSecurity http, OpaqueTokenIntrospector introspector) throws Exception {
        var nativeBearer = new org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver();
        return http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(routes -> routes
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, "/sessions").permitAll()
                        .requestMatchers("/admin").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resource -> resource
                        .bearerTokenResolver(request -> {
                            var values = request.getHeaders(HttpHeaders.AUTHORIZATION);
                            if (values.hasMoreElements()) {
                                values.nextElement();
                                if (values.hasMoreElements()) throw new org.springframework.security.oauth2.core.OAuth2AuthenticationException(
                                        org.springframework.security.oauth2.server.resource.BearerTokenErrors.invalidRequest("Multiple Authorization headers"));
                            }
                            return nativeBearer.resolve(request);
                        })
                        .opaqueToken(opaque -> opaque.introspector(introspector)))
                // Only this JSON, custom-header sign-in is exempt. Do not enable permissive CORS.
                // The native resource server separately handles CSRF for explicit Bearer credentials.
                .csrf(csrf -> csrf.ignoringRequestMatchers(request ->
                        request.getMethod().equals("POST") && request.getServletPath().equals("/sessions")
                        && "ogiri-demo".equals(request.getHeader("X-Requested-With"))
                        && request.getContentType() != null
                        && request.getContentType().split(";", 2)[0].trim().equalsIgnoreCase("application/json")))
                .build();
    }

    @RestController
    static class Endpoints {
        private final PostgresSessions sessions;
        private final AuthenticationManager authentication;
        Endpoints(PostgresSessions sessions, AuthenticationManager authentication) {
            this.sessions = sessions; this.authentication = authentication;
        }

        record Login(String username, String password, String client) {}

        @PostMapping(value = "/sessions", consumes = MediaType.APPLICATION_JSON_VALUE)
        ResponseEntity<Session> signIn(@RequestBody Login login) {
            if (login.username() == null || login.username().isBlank() || login.username().length() > 255
                    || login.password() == null || login.password().isBlank() || login.password().length() > 1024
                    || login.client() == null || login.client().isBlank() || login.client().length() > 255 || login.client().indexOf(0) >= 0)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            final org.springframework.security.core.Authentication account;
            try { account = authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(login.username(), login.password())); }
            catch (AuthenticationServiceException unavailable) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE); }
            catch (AuthenticationException invalid) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED); }
            try {
                var issued = sessions.issue(new Subject("demo", "", account.getName()), login.client());
                return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issued.token()).body(issued.session());
            } catch (SessionLimitException full) { throw new ResponseStatusException(HttpStatus.CONFLICT); }
        }

        @GetMapping("/me") java.util.Map<String, Object> current(@AuthenticationPrincipal OAuth2AuthenticatedPrincipal principal) { return principal.getAttributes(); }

        @GetMapping("/sessions") List<Session> list(@AuthenticationPrincipal OAuth2AuthenticatedPrincipal principal) { return sessions.list(owner(principal)); }

        @DeleteMapping("/sessions/{id}") ResponseEntity<Void> revoke(@AuthenticationPrincipal OAuth2AuthenticatedPrincipal principal, @PathVariable UUID id) {
            return sessions.revoke(owner(principal), id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
        }

        @GetMapping("/admin") String admin() { return "admin"; }

        private static Subject owner(OAuth2AuthenticatedPrincipal principal) {
            return new Subject(principal.getAttribute("realm"), principal.getAttribute("tenant_id"), principal.getName());
        }
    }
}
