// SPDX-License-Identifier: Apache-2.0
package example.ogiri;

import static org.junit.jupiter.api.Assertions.*;
import com.quantipixels.ogiri.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=${OGIRI_TEST_JDBC_URL}",
        "spring.datasource.username=${OGIRI_TEST_JDBC_USER}",
        "spring.datasource.password=${OGIRI_TEST_JDBC_PASSWORD}",
        "spring.datasource.hikari.maximum-pool-size=1",
        "demo.password=test-password"
})
@org.springframework.context.annotation.Import(ConsumerTest.OutageConfiguration.class)
class ConsumerTest {
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class OutageConfiguration {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        org.springframework.security.core.userdetails.UserDetailsService faultableAccounts(
                @org.springframework.beans.factory.annotation.Qualifier("accounts") org.springframework.security.core.userdetails.UserDetailsService delegate) {
            return username -> {
                if (username.equals("offline")) throw new org.springframework.security.authentication.InternalAuthenticationServiceException("Injected directory outage");
                return delegate.loadUserByUsername(username);
            };
        }
    }

    @Value("${local.server.port}") private int port;
    @Autowired private PostgresSessions sessions;
    @Autowired private DataSource dataSource;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @BeforeEach void emptyDatabase() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) { statement.execute("TRUNCATE ogiri_sessions"); }
    }

    @Test void installedArtifactsServeTheFullSessionLifecycleThroughTheNativeSecurityChain() throws Exception {
        assertEquals(401, request("GET", "/me", null, null).statusCode());
        var login = login("test-password");
        assertEquals(201, login.statusCode());
        assertTrue(login.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        String bearer = login.headers().firstValue("Authorization").orElseThrow();
        assertFalse(login.body().contains(bearer.substring(7)), "Response JSON must not contain the credential");
        UUID id = sessions.list(new Subject("demo", "", "demo")).get(0).id();
        assertEquals(200, request("GET", "/me", null, bearer).statusCode());
        assertEquals(200, request("GET", "/sessions", null, bearer).statusCode());
        assertEquals(403, request("GET", "/admin", null, bearer).statusCode());
        var foreign = sessions.issue(new Subject("demo", "another-tenant", "demo"), "foreign");
        assertEquals(404, request("DELETE", "/sessions/" + foreign.session().id(), null, bearer).statusCode());
        assertTrue(sessions.authenticate(foreign.token()).isPresent());
        assertEquals(204, request("DELETE", "/sessions/" + id, null, bearer).statusCode());
        assertEquals(401, request("GET", "/me", null, bearer).statusCode());
    }

    @Test void invalidLoginSimpleCrossSitePostsAndAmbiguousBearerTransportAreRejected() throws Exception {
        assertEquals(401, login("wrong-password").statusCode());
        assertEquals(403, request("POST", "/sessions", "text/plain", null).statusCode());
        assertEquals(403, request("POST", "/sessions", "application/json", null).statusCode());
        var invalid = request("GET", "/me", null, "Bearer og1_" + "A".repeat(43));
        assertEquals(401, invalid.statusCode());
        var issued = sessions.issue(new Subject("demo", "", "demo"), "phone");
        assertEquals(401, request("GET", "/me?access_token=" + issued.token(), null, null).statusCode());
        var duplicate = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/me"))
                .header("Authorization", "Bearer " + issued.token()).header("Authorization", "Bearer " + issued.token()).GET().build();
        assertEquals(400, HTTP.send(duplicate, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(1, sessions.list(new Subject("demo", "", "demo")).size());
    }

    @Test void accountDirectoryOutagesRemainServerFailuresRatherThanInvalidCredentials() throws Exception {
        var login = login("offline", "irrelevant");
        assertEquals(503, login.statusCode());
        assertFalse(login.body().contains("Injected directory outage"));
        var token = sessions.issue(new Subject("demo", "", "offline"), "test fault").token();
        var request = request("GET", "/me", null, "Bearer " + token);
        assertEquals(500, request.statusCode());
        assertFalse(request.body().contains("Injected directory outage"));
    }

    private HttpResponse<String> login(String password) throws Exception { return login("demo", password); }

    private HttpResponse<String> login(String username, String password) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/sessions"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .header("X-Requested-With", "ogiri-demo")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"" + username + "\",\"password\":\"" + password + "\",\"client\":\"browser\"}")).build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> request(String method, String path, String contentType, String bearer) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(10));
        if (contentType != null) request.header("Content-Type", contentType);
        if (bearer != null) request.header("Authorization", bearer);
        request.method(method, contentType == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString("{}"));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
