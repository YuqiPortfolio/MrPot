package com.example.datalake.mrpot.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TestRowDto(Long id, @JsonProperty("Name") String name) {}
