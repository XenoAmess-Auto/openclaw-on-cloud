package com.ooc.repository;

import com.ooc.entity.OocSession;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OocSessionRepository extends MongoRepository<OocSession, String> {

    Optional<OocSession> findByChatRoomIdAndArchivedFalse(String chatRoomId);

    List<OocSession> findByChatRoomIdOrderByCreatedAtDesc(String chatRoomId);

    List<OocSession> findByArchivedTrue();
}
