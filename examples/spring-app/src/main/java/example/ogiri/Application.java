// SPDX-License-Identifier: Apache-2.0
package example.ogiri;

import java.security.Principal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.web.bind.annotation.*;

@SpringBootApplication
@EnableMethodSecurity
@RestController
public class Application {
    public static void main(String[] args) { SpringApplication.run(Application.class, args); }

    @Bean
    UserDetailsService accounts(PasswordEncoder encoder, @Value("${demo.password}") String password) {
        return new InMemoryUserDetailsManager(User.withUsername("demo").password(encoder.encode(password)).roles("USER").build());
    }

    @GetMapping("/me") String me(Principal principal) { return principal.getName(); }
    @GetMapping("/admin") @PreAuthorize("hasRole('ADMIN')") String admin() { return "admin"; }
}
