package com.example.datalake.mrpot.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Wraps the {@link PrepareResponse} payload under the {@code why} property expected by clients.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrepareEnvelope {

  private PrepareResponse why;

  public static PrepareEnvelope of(PrepareResponse response) {
    return PrepareEnvelope.builder().why(response).build();
  }
}
