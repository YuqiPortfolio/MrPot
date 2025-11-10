package com.example.datalake.mrpot;

import com.example.datalake.mrpot.prompt.config.PromptProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(PromptProperties.class)
public class MrPotApplication {

    public static void main(String[] args) {
        SpringApplication.run(MrPotApplication.class, args);
    }

}
