package com.example.datalake.mrpot.config;

import com.google.cloud.translate.Translate;
import com.google.cloud.translate.TranslateOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TranslateConfig {

    /**
     * Uses Application Default Credentials (ADC) or GOOGLE_API_KEY if present.
     * See: TranslateOptions.getDefaultInstance().getService()
     */
    @Bean
    public Translate translate() {
        return TranslateOptions.getDefaultInstance().getService();
    }
}
