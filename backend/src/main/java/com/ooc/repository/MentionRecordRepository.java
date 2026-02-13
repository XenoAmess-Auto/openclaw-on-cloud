package com.ooc.repository;

import com.ooc.entity.MentionRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface MentionRecordRepository extends MongoRepository<MentionRecord, String> {

    List<MentionRecord> findByMentionedUserIdAndIsReadFalse(String mentionedUserId);

    Page<MentionRecord> findByMentionedUserIdOrderByCreatedAtDesc(String mentionedUserId, Pageable pageable);

    long countByMentionedUserIdAndIsReadFalse(String mentionedUserId);

    @Query("{ 'mentionedUserId': ?0, 'createdAt': { $gt: ?1 } }")
    List<MentionRecord> findRecentMentions(String mentionedUserId, Instant since);

    @Query("{ 'mentionerUserId': ?0, 'mentionedUserId': ?1, 'createdAt': { $gt: ?2 } }")
    List<MentionRecord> findRecentMentionsByUser(String mentionerUserId, String mentionedUserId, Instant since);

    void deleteByMessageId(String messageId);
}
