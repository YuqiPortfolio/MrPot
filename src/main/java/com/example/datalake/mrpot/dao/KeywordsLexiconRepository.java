package com.example.datalake.mrpot.dao;

import com.example.datalake.mrpot.model.KeywordsLexicon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface KeywordsLexiconRepository extends JpaRepository<KeywordsLexicon, String> {
}
