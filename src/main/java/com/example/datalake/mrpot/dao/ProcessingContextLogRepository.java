package com.example.datalake.mrpot.dao;

import com.example.datalake.mrpot.entity.ProcessingContextEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProcessingContextLogRepository extends JpaRepository<ProcessingContextEntity, Long> {
}
