package com.example.datalake.mrpot.dao;

import com.example.datalake.mrpot.model.TestRow;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestRowRepository extends JpaRepository<TestRow, Long> {
}
