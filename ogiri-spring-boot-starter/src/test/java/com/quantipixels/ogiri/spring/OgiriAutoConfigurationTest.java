// SPDX-License-Identifier: Apache-2.0
package com.quantipixels.ogiri.spring;

import static org.assertj.core.api.Assertions.assertThat;
import com.quantipixels.ogiri.*;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.authentication.*;
import org.springframework.security.crypto.password.*;

class OgiriAutoConfigurationTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OgiriAutoConfiguration.class))
            .withBean(DataSource.class, () -> new DriverManagerDataSource(System.getenv("OGIRI_TEST_JDBC_URL"),
                    System.getenv("OGIRI_TEST_JDBC_USER"), System.getenv("OGIRI_TEST_JDBC_PASSWORD")))
            .withBean(UserDetailsService.class, () -> new InMemoryUserDetailsManager(
                    User.withUsername("test").password("{noop}test").roles("USER").build()));

    @Test void disabledStarterAddsNoSecurityOrStorageBeans() {
        runner.withPropertyValues("ogiri.enabled=false").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(JdbcSessions.class).doesNotHaveBean(OgiriSecurity.class);
        });
    }

    @Test void boundPolicyAndCustomAuthenticationBeansWin() {
        var manager = (org.springframework.security.authentication.AuthenticationManager) authentication -> authentication;
        var encoder = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        runner.withPropertyValues("ogiri.lifetime=12h", "ogiri.maximum-sessions=3", "ogiri.base-path=/api/login")
                .withBean(org.springframework.security.authentication.AuthenticationManager.class, () -> manager)
                .withBean(PasswordEncoder.class, () -> encoder).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(SessionPolicy.class)).isEqualTo(new SessionPolicy(Duration.ofHours(12), 3));
                    assertThat(context.getBean(PasswordEncoder.class)).isSameAs(encoder);
                    assertThat(context.getBean(org.springframework.security.authentication.AuthenticationManager.class)).isSameAs(manager);
                });
    }

    @Test void invalidEndpointPathsFailAtBindingRatherThanOpenUnintendedRoutes() {
        runner.withPropertyValues("ogiri.base-path=/auth/**").run(context -> assertThat(context).hasFailed());
    }
}
