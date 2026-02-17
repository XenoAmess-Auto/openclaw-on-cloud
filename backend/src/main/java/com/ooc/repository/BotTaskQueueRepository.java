package com.ooc.repository;

import com.ooc.entity.BotTaskQueue;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 机器人任务队列 Repository
 */
@Repository
public interface BotTaskQueueRepository extends MongoRepository<BotTaskQueue, String> {

    /**
     * 根据房间ID和机器人类型查找任务，按位置排序
     */
    List<BotTaskQueue> findByRoomIdAndBotTypeOrderByPositionAsc(String roomId, BotTaskQueue.BotType botType);

    /**
     * 根据房间ID、机器人类型和状态查找任务
     */
    List<BotTaskQueue> findByRoomIdAndBotTypeAndStatus(String roomId, BotTaskQueue.BotType botType, BotTaskQueue.TaskStatus status);

    /**
     * 查找所有待处理或处理中的任务（用于服务启动时恢复）
     */
    List<BotTaskQueue> findByStatusIn(List<BotTaskQueue.TaskStatus> statuses);

    /**
     * 根据任务ID查找
     */
    Optional<BotTaskQueue> findByTaskId(String taskId);

    /**
     * 根据任务ID删除
     */
    void deleteByTaskId(String taskId);

    /**
     * 根据房间ID和机器人类型删除所有任务
     */
    void deleteByRoomIdAndBotType(String roomId, BotTaskQueue.BotType botType);

    /**
     * 统计房间中某状态的任务数量
     */
    long countByRoomIdAndBotTypeAndStatus(String roomId, BotTaskQueue.BotType botType, BotTaskQueue.TaskStatus status);
}
