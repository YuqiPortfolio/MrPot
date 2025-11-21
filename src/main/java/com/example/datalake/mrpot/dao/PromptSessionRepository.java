package com.example.datalake.mrpot.dao;

import com.example.datalake.mrpot.model.PromptSession;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PromptSessionRepository extends JpaRepository<PromptSession, Long> {
}
