package com.ooc.repository;

import com.ooc.entity.ChatRoom;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatRoomRepository extends MongoRepository<ChatRoom, String> {

    List<ChatRoom> findByMemberIdsContaining(String userId);

    List<ChatRoom> findByCreatorId(String creatorId);
}
