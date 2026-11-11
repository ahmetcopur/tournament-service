package com.example.tournament.search;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Mapping;
import org.springframework.data.elasticsearch.annotations.Setting;
import org.springframework.data.elasticsearch.annotations.WriteTypeHint;

/**
 * Only the fields people search by. Username and country never change, so a player is indexed
 * once at sign up. Level and coins change on every level up; they come from Postgres when a
 * search is answered instead of being re-indexed on the hot path.
 */
@Document(indexName = "players", createIndex = false, writeTypeHint = WriteTypeHint.FALSE)
@Setting(settingPath = "elasticsearch/players-settings.json")
@Mapping(mappingPath = "elasticsearch/players-mapping.json")
public record PlayerDocument(@Id Long id, String username, String country) {
}
