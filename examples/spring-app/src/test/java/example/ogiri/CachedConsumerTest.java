// SPDX-License-Identifier: Apache-2.0
package example.ogiri;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Import;

/** Rerun the installed-artifact HTTP contract with a real, Boot-managed Caffeine provider. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=${OGIRI_TEST_JDBC_URL}",
        "spring.datasource.username=${OGIRI_TEST_JDBC_USER}",
        "spring.datasource.password=${OGIRI_TEST_JDBC_PASSWORD}",
        "spring.datasource.hikari.maximum-pool-size=1", "demo.password=test-password",
        "ogiri.cache.enabled=true", "ogiri.cache.max-age=5s", "ogiri.cache.name=consumer.sessions",
        "spring.cache.type=caffeine", "spring.cache.cache-names=consumer.sessions",
        "spring.cache.caffeine.spec=maximumSize=100,expireAfterWrite=5s"
})
@Import(CachedConsumerTest.Caching.class)
class CachedConsumerTest extends ConsumerTest {
    @TestConfiguration(proxyBeanMethods = false)
    @EnableCaching
    static class Caching {}
}
