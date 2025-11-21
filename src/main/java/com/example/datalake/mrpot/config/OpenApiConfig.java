package com.example.datalake.mrpot.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.License;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
    info = @Info(
        title = "MrPot API",
        version = "v1",
        description = "Interactive prompt preparation, streaming demo events, and health endpoints.",
        contact = @Contact(name = "MrPot Team", email = "support@mrpot.local")
    ),
    servers = {
        @Server(url = "/", description = "Default server")
    }
)
public class OpenApiConfig {

  @Bean
  public OpenAPI baseOpenAPI() {
    return new OpenAPI()
        .info(new io.swagger.v3.oas.models.info.Info()
            .title("MrPot API")
            .version("v1")
            .description("Swagger UI for exploring the prompt pipeline and streaming endpoints.")
            .license(new License().name("Apache 2.0")))
        .externalDocs(new ExternalDocumentation()
            .description("Project README")
            .url("https://github.com/"));
  }

  @Bean
  public GroupedOpenApi promptApi() {
    return GroupedOpenApi.builder()
        .group("prompt")
        .packagesToScan("com.example.datalake.mrpot.controller")
        .pathsToMatch("/v1/**")
        .build();
  }

  @Bean
  public GroupedOpenApi actuatorApi() {
    return GroupedOpenApi.builder()
        .group("actuator")
        .pathsToMatch("/actuator/**")
        .build();
  }
}
