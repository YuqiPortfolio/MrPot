package com.example.datalake.mrpot.config;

import com.pgvector.PGvector;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Register pgvector types with the JDBC driver so that
 * PGvector can be used in PreparedStatement / ResultSet.
 */
@Configuration
@RequiredArgsConstructor
public class PgvectorConfig {

    private final DataSource dataSource;

    @PostConstruct
    public void registerPgvectorTypes() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            PGvector.registerTypes(conn);
        }
    }
}
