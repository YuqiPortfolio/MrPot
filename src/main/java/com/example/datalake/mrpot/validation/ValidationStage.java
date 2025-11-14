package com.example.datalake.mrpot.validation;

/** Identifies where in the pipeline a validator is executed. */
public enum ValidationStage {
  /** Validations that occur before any orchestration or chain execution. */
  PRE_CHAIN,
  /** Validations that run immediately before the LLM is invoked. */
  PRE_LLM
}
