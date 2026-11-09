package mygrant.support;

import org.junit.jupiter.api.TestInstance;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared real-database fixture for tests that need Flyway and pgvector rather than mocks.
 *
 * <p>The container is skipped on developer machines without Docker, but always runs in the
 * GitHub Actions Docker runner. Test-only AI beans must be supplied by each subclass: external
 * providers are never contacted from these tests.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class PgvectorIntegrationTestBase {

    private static final DockerImageName PGVECTOR = DockerImageName.parse("pgvector/pgvector:pg16")
            .asCompatibleSubstituteFor("postgres");

    @Container
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PGVECTOR)
            .withDatabaseName("policy_impact_test")
            .withUsername("policy_user")
            .withPassword("policy_password");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        // DynamicPropertySource is evaluated while Spring is deciding how to create its
        // datasource. Start explicitly here because JUnit does not initialise an inherited
        // @Container field before that lookup.
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }
}
