package com.example.datalake.mrpot.config;

import com.pgvector.PGvector;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.PGConnection;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Register pgvector types with the JDBC driver so that
 * PGvector can be used in PreparedStatement / ResultSet.
 */
@Slf4j
@Configuration
@Profile("!test")
@RequiredArgsConstructor
public class PgvectorConfig {

    private final DataSource dataSource;

    @PostConstruct
    public void registerPgvectorTypes() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            if (conn.isWrapperFor(PGConnection.class)) {
                PGvector.registerTypes(conn.unwrap(PGConnection.class));
            } else {
                log.debug("Skip pgvector type registration for non-PostgreSQL connection: {}", conn.getClass());
            }
        }
    }
}
