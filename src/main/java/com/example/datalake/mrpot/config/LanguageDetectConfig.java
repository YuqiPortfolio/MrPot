package com.example.datalake.mrpot.config;

import com.github.pemistahl.lingua.api.LanguageDetector;
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LanguageDetectConfig {
    @Bean
    public LanguageDetector linguaDetector() {
        return LanguageDetectorBuilder.fromAllLanguages().build();
    }
}
