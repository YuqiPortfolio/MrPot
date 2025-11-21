package com.example.datalake.mrpot.dao;

import java.util.Map;
import java.util.Set;

public interface KeywordsLexiconDao {

    Map<String, Set<String>> loadLexicon();
}
