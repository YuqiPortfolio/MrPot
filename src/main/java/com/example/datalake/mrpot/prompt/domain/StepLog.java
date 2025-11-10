package com.example.datalake.mrpot.prompt.domain;

import java.time.Instant;

public record StepLog(String name, String note, Instant at) {
}
