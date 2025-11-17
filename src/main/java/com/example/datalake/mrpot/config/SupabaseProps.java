package com.example.datalake.mrpot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "mrpot.supabase")
public class SupabaseProps {
    private String host;
    private int port;
    private String database;
    private String user;
    private String password;
    private String schema;
    private String table;
    private int embeddingDimension;
}
