package com.example.datalake.mrpot.config;

import com.pgvector.PGvector;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

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
            String driverName = "";
            try {
                driverName = conn.getMetaData().getDriverName();
            } catch (SQLException ignored) {
                // best-effort detection
            }

            boolean isPostgres = driverName != null && driverName.toLowerCase(Locale.ROOT).contains("postgresql");

            if (isPostgres) {
                PGvector.registerTypes(conn);
            } else {
                log.debug("Skip pgvector type registration for non-PostgreSQL connection: {}", conn.getClass());
            }
        }
    }
}
