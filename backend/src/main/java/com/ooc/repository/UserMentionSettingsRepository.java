package com.ooc.repository;

import com.ooc.entity.UserMentionSettings;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserMentionSettingsRepository extends MongoRepository<UserMentionSettings, String> {

    Optional<UserMentionSettings> findByUserId(String userId);

    void deleteByUserId(String userId);
}
