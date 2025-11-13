package com.example.datalake.mrpot.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true, fluent = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Language {
  private String isoCode;
  private String displayName;
  private double confidence;
  private String script;

  public static Language und() {
    return new Language("und", "Undetermined", 0.0, null);
  }

  public boolean is(String code) {
    return code != null && code.equalsIgnoreCase(this.isoCode);
  }
}
