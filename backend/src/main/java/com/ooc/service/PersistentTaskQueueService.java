package com.ooc.service;

import com.ooc.entity.BotTaskQueue;
import com.ooc.repository.BotTaskQueueRepository;
import com.ooc.websocket.ChatWebSocketHandler;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 持久化机器人任务队列服务
 * 将任务队列存储在 MongoDB 中，确保服务重启后任务不丢失
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersistentTaskQueueService {

    private final BotTaskQueueRepository taskQueueRepository;

    // 内存中的队列缓存（每个机器人独立）
    // OpenClaw
    private final ConcurrentHashMap<String, LinkedBlockingQueue<TaskWrapper>> openclawQueues = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> openclawProcessingFlags = new ConcurrentHashMap<>();
    // Kimi
    private final ConcurrentHashMap<String, LinkedBlockingQueue<TaskWrapper>> kimiQueues = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> kimiProcessingFlags = new ConcurrentHashMap<>();
    // Claude
    private final ConcurrentHashMap<String, LinkedBlockingQueue<TaskWrapper>> claudeQueues = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> claudeProcessingFlags = new ConcurrentHashMap<>();

    // 任务处理器回调
    private final ConcurrentHashMap<String, TaskProcessor> taskProcessors = new ConcurrentHashMap<>();

    // 正在执行的任务取消标志 (taskId -> AtomicBoolean)
    private final ConcurrentHashMap<String, AtomicBoolean> taskCancellationFlags = new ConcurrentHashMap<>();

    // 当前正在执行的任务ID (roomId_botType -> taskId)
    private final ConcurrentHashMap<String, String> currentProcessingTasks = new ConcurrentHashMap<>();

    /**
     * 任务包装器，关联内存队列和数据库记录
     */
    public record TaskWrapper(
            String taskId,
            ChatWebSocketHandler.OpenClawTask task,
            BotTaskQueue.BotType botType
    ) {}

    /**
     * 任务处理器接口
     */
    @FunctionalInterface
    public interface TaskProcessor {
        void process(ChatWebSocketHandler.OpenClawTask task);
    }

    /**
     * 服务启动时恢复未完成的任务队列
     */
    @PostConstruct
    public void restoreQueuesOnStartup() {
        log.info("Restoring task queues from database...");

        // 查找所有待处理或处理中的任务
        List<BotTaskQueue> pendingTasks = taskQueueRepository.findByStatusIn(
                List.of(BotTaskQueue.TaskStatus.PENDING, BotTaskQueue.TaskStatus.PROCESSING)
        );

        if (pendingTasks.isEmpty()) {
            log.info("No pending tasks found in database");
            return;
        }

        log.info("Found {} pending tasks to restore", pendingTasks.size());

        // 将处理中的任务重置为待处理状态（因为服务重启，需要重新处理）
        for (BotTaskQueue dbTask : pendingTasks) {
            if (dbTask.getStatus() == BotTaskQueue.TaskStatus.PROCESSING) {
                dbTask.setStatus(BotTaskQueue.TaskStatus.PENDING);
                dbTask.setUpdatedAt(Instant.now());
                taskQueueRepository.save(dbTask);
                log.info("Reset PROCESSING task {} to PENDING (room={}, bot={})",
                        dbTask.getTaskId(), dbTask.getRoomId(), dbTask.getBotType());
            }

            // 恢复任务到内存队列
            restoreTaskToQueue(dbTask);
        }

        log.info("Task queues restored successfully");
    }

    /**
     * 恢复单个任务到内存队列
     */
    private void restoreTaskToQueue(BotTaskQueue dbTask) {
        ChatWebSocketHandler.OpenClawTask task = convertToTask(dbTask);
        TaskWrapper wrapper = new TaskWrapper(dbTask.getTaskId(), task, dbTask.getBotType());

        LinkedBlockingQueue<TaskWrapper> queue = getOrCreateQueue(dbTask.getRoomId(), dbTask.getBotType());
        queue.offer(wrapper);

        log.info("Restored task {} to {} queue for room {} (position={})",
                dbTask.getTaskId(), dbTask.getBotType(), dbTask.getRoomId(), dbTask.getPosition());
    }

    /**
     * 注册任务处理器
     */
    public void registerTaskProcessor(BotTaskQueue.BotType botType, TaskProcessor processor) {
        taskProcessors.put(botType.name(), processor);
        log.info("Registered task processor for bot type: {}", botType);
    }

    /**
     * 添加任务到队列
     */
    public String addTask(String roomId, String content,
                          List<ChatWebSocketHandler.Attachment> attachments,
                          ChatWebSocketHandler.WebSocketUserInfo userInfo,
                          String sourceMessageId,
                          BotTaskQueue.BotType botType) {
        String taskId = UUID.randomUUID().toString();

        // 计算队列位置
        LinkedBlockingQueue<TaskWrapper> queue = getOrCreateQueue(roomId, botType);
        int position = queue.size();

        // 创建数据库记录
        BotTaskQueue dbTask = BotTaskQueue.builder()
                .taskId(taskId)
                .roomId(roomId)
                .botType(botType)
                .content(content)
                .attachments(convertAttachments(attachments))
                .userInfo(convertUserInfo(userInfo))
                .sourceMessageId(sourceMessageId)
                .status(BotTaskQueue.TaskStatus.PENDING)
                .position(position)
                .build();

        taskQueueRepository.save(dbTask);

        // 创建内存任务
        ChatWebSocketHandler.OpenClawTask task = ChatWebSocketHandler.OpenClawTask.builder()
                .taskId(taskId)
                .roomId(roomId)
                .content(content)
                .attachments(attachments)
                .userInfo(userInfo)
                .sourceMessageId(sourceMessageId)
                .createdAt(Instant.now())
                .status(ChatWebSocketHandler.OpenClawTask.TaskStatus.PENDING)
                .build();

        // 添加到内存队列
        TaskWrapper wrapper = new TaskWrapper(taskId, task, botType);
        queue.offer(wrapper);

        log.info("Task {} added to {} queue for room {} (position={})",
                taskId, botType, roomId, position);

        return taskId;
    }

    /**
     * 获取队列大小
     */
    public int getQueueSize(String roomId, BotTaskQueue.BotType botType) {
        LinkedBlockingQueue<TaskWrapper> queue = getQueue(roomId, botType);
        return queue != null ? queue.size() : 0;
    }

    /**
     * 尝试处理队列中的下一个任务
     */
    public void tryProcessNext(String roomId, BotTaskQueue.BotType botType) {
        LinkedBlockingQueue<TaskWrapper> queue = getQueue(roomId, botType);
        AtomicBoolean isProcessing = getProcessingFlag(roomId, botType);

        if (queue == null || isProcessing == null) {
            return;
        }

        // 使用 CAS 操作确保只有一个线程能开始处理
        if (!isProcessing.compareAndSet(false, true)) {
            log.debug("Room {} is already processing a {} task, skipping", roomId, botType);
            return;
        }

        TaskWrapper wrapper = queue.poll();
        if (wrapper == null) {
            // 队列为空，重置处理标志
            isProcessing.set(false);
            log.debug("Room {} {} queue is empty, resetting processing flag", roomId, botType);
            return;
        }

        // 更新数据库状态为处理中
        updateTaskStatus(wrapper.taskId(), BotTaskQueue.TaskStatus.PROCESSING);

        // 更新内存任务状态
        wrapper.task().setStatus(ChatWebSocketHandler.OpenClawTask.TaskStatus.PROCESSING);

        // 记录当前正在执行的任务
        currentProcessingTasks.put(roomId + "_" + botType.name(), wrapper.taskId());

        // 清理之前的取消标志（如果有）
        taskCancellationFlags.remove(wrapper.taskId());

        // 调用处理器
        TaskProcessor processor = taskProcessors.get(botType.name());
        if (processor != null) {
            log.info("Processing {} task {} for room {}", botType, wrapper.taskId(), roomId);
            try {
                processor.process(wrapper.task());
            } catch (Exception e) {
                log.error("Error processing {} task {}: {}", botType, wrapper.taskId(), e.getMessage(), e);
                markTaskFailed(wrapper.taskId());
                onTaskComplete(roomId, botType);
            }
        } else {
            log.error("No task processor registered for bot type: {}", botType);
            markTaskFailed(wrapper.taskId());
            onTaskComplete(roomId, botType);
        }
    }

    /**
     * 任务完成回调
     */
    public void onTaskComplete(String roomId, BotTaskQueue.BotType botType) {
        onTaskComplete(roomId, botType, null);
    }

    /**
     * 任务完成回调（带任务ID）
     */
    public void onTaskComplete(String roomId, BotTaskQueue.BotType botType, String taskId) {
        log.info("Task {} completed for room {}, checking {} queue for next task", taskId, roomId, botType);

        // 清理当前任务标记
        String processingKey = roomId + "_" + botType.name();
        if (taskId != null) {
            currentProcessingTasks.remove(processingKey, taskId);
            taskCancellationFlags.remove(taskId);
        } else {
            // 如果没有指定taskId，清除该房间的所有记录
            String currentTask = currentProcessingTasks.get(processingKey);
            if (currentTask != null) {
                currentProcessingTasks.remove(processingKey);
                taskCancellationFlags.remove(currentTask);
            }
        }

        AtomicBoolean isProcessing = getProcessingFlag(roomId, botType);
        if (isProcessing != null) {
            isProcessing.set(false);
        }

        // 延迟一小段时间再处理下一个任务，确保资源释放
        Executors.newSingleThreadScheduledExecutor().schedule(() -> {
            tryProcessNext(roomId, botType);
        }, 300, TimeUnit.MILLISECONDS);
    }

    /**
     * 标记任务完成
     */
    public void markTaskCompleted(String taskId) {
        updateTaskStatus(taskId, BotTaskQueue.TaskStatus.COMPLETED);
        log.info("Task {} marked as COMPLETED", taskId);
    }

    /**
     * 标记任务失败
     */
    public void markTaskFailed(String taskId) {
        updateTaskStatus(taskId, BotTaskQueue.TaskStatus.FAILED);
        log.info("Task {} marked as FAILED", taskId);
    }

    /**
     * 取消任务（支持取消正在执行的任务）
     */
    public boolean cancelTask(String roomId, String taskId, BotTaskQueue.BotType botType) {
        String processingKey = roomId + "_" + botType.name();
        String currentTaskId = currentProcessingTasks.get(processingKey);

        // 检查是否是当前正在执行的任务
        if (taskId.equals(currentTaskId)) {
            // 设置取消标志
            AtomicBoolean cancelFlag = taskCancellationFlags.computeIfAbsent(taskId, k -> new AtomicBoolean(true));
            cancelFlag.set(true);

            // 更新数据库状态
            updateTaskStatus(taskId, BotTaskQueue.TaskStatus.CANCELLED);
            log.info("Processing task {} marked for cancellation in room {}", taskId, roomId);

            // 清理当前任务标记（让 onTaskComplete 能正确处理下一个任务）
            currentProcessingTasks.remove(processingKey);

            // 重置处理标志并触发下一个任务
            AtomicBoolean isProcessing = getProcessingFlag(roomId, botType);
            if (isProcessing != null) {
                isProcessing.set(false);
            }

            // 延迟后触发下一个任务
            Executors.newSingleThreadScheduledExecutor().schedule(() -> {
                tryProcessNext(roomId, botType);
            }, 100, TimeUnit.MILLISECONDS);

            return true;
        }

        // 否则尝试从队列中移除
        LinkedBlockingQueue<TaskWrapper> queue = getQueue(roomId, botType);
        if (queue == null) {
            return false;
        }

        // 从内存队列中移除
        boolean removed = queue.removeIf(wrapper -> wrapper.taskId().equals(taskId));

        if (removed) {
            // 更新数据库状态
            updateTaskStatus(taskId, BotTaskQueue.TaskStatus.CANCELLED);
            log.info("Pending task {} cancelled in room {}", taskId, roomId);
            return true;
        }

        return false;
    }

    /**
     * 检查任务是否被取消
     */
    public boolean isTaskCancelled(String taskId) {
        AtomicBoolean flag = taskCancellationFlags.get(taskId);
        return flag != null && flag.get();
    }

    /**
     * 清理任务的取消标志
     */
    public void clearCancellationFlag(String taskId) {
        taskCancellationFlags.remove(taskId);
    }

    /**
     * 获取当前正在执行的任务ID
     */
    public String getCurrentProcessingTask(String roomId, BotTaskQueue.BotType botType) {
        return currentProcessingTasks.get(roomId + "_" + botType.name());
    }

    /**
     * 重排序任务队列
     */
    public boolean reorderQueue(String roomId, BotTaskQueue.BotType botType, List<String> taskIds) {
        LinkedBlockingQueue<TaskWrapper> queue = getQueue(roomId, botType);
        if (queue == null || queue.isEmpty()) {
            return false;
        }

        // 获取当前队列中的所有任务
        List<TaskWrapper> currentTasks = new ArrayList<>(queue);

        // 过滤出待处理的任务（处理中的不能重排序）
        List<TaskWrapper> pendingTasks = currentTasks.stream()
                .filter(w -> w.task().getStatus() == ChatWebSocketHandler.OpenClawTask.TaskStatus.PENDING)
                .collect(Collectors.toList());

        // 按新的顺序重建队列
        LinkedBlockingQueue<TaskWrapper> newQueue = new LinkedBlockingQueue<>();

        // 首先添加正在处理的任务（保持在最前面）
        currentTasks.stream()
                .filter(w -> w.task().getStatus() == ChatWebSocketHandler.OpenClawTask.TaskStatus.PROCESSING)
                .forEach(newQueue::offer);

        // 然后按传入的顺序添加待处理任务
        int position = 0;
        for (String taskId : taskIds) {
            pendingTasks.stream()
                    .filter(w -> w.taskId().equals(taskId))
                    .findFirst()
                    .ifPresent(wrapper -> {
                        newQueue.offer(wrapper);
                        // 更新数据库中的位置
                        taskQueueRepository.findByTaskId(taskId).ifPresent(dbTask -> {
                            dbTask.setPosition(position);
                            taskQueueRepository.save(dbTask);
                        });
                    });
        }

        // 替换原队列
        switch (botType) {
            case OPENCLAW -> openclawQueues.put(roomId, newQueue);
            case KIMI -> kimiQueues.put(roomId, newQueue);
            case CLAUDE -> claudeQueues.put(roomId, newQueue);
        }

        log.info("{} task queue reordered for room {}, new size: {}", botType, roomId, newQueue.size());
        return true;
    }

    /**
     * 获取房间任务队列（包括 PENDING 和 PROCESSING 状态的任务）
     */
    public List<ChatWebSocketHandler.OpenClawTask> getRoomTaskQueue(String roomId, BotTaskQueue.BotType botType) {
        List<ChatWebSocketHandler.OpenClawTask> result = new ArrayList<>();

        // 1. 从数据库获取所有 PENDING 和 PROCESSING 状态的任务
        List<BotTaskQueue> dbTasks = taskQueueRepository.findByRoomIdAndBotTypeOrderByPositionAsc(roomId, botType);

        // 2. 获取当前正在执行的任务ID
        String currentTaskId = getCurrentProcessingTask(roomId, botType);

        // 3. 先添加正在执行的任务（如果有）
        if (currentTaskId != null) {
            dbTasks.stream()
                    .filter(t -> t.getStatus() == BotTaskQueue.TaskStatus.PROCESSING)
                    .filter(t -> t.getTaskId().equals(currentTaskId))
                    .findFirst()
                    .ifPresent(processingTask -> {
                        result.add(convertToTask(processingTask));
                    });
        }

        // 4. 添加所有待处理任务（按位置排序）
        dbTasks.stream()
                .filter(t -> t.getStatus() == BotTaskQueue.TaskStatus.PENDING)
                .sorted(Comparator.comparingInt(BotTaskQueue::getPosition))
                .forEach(t -> result.add(convertToTask(t)));

        return result;
    }

    /**
     * 获取房间中所有机器人类型的任务队列
     */
    public Map<String, List<ChatWebSocketHandler.OpenClawTask>> getRoomAllTaskQueues(String roomId) {
        Map<String, List<ChatWebSocketHandler.OpenClawTask>> result = new HashMap<>();

        for (BotTaskQueue.BotType botType : BotTaskQueue.BotType.values()) {
            List<ChatWebSocketHandler.OpenClawTask> tasks = getRoomTaskQueue(roomId, botType);
            if (!tasks.isEmpty()) {
                result.put(botType.name(), tasks);
            }
        }

        return result;
    }

    /**
     * 检查房间是否正在处理任务
     */
    public boolean isRoomProcessing(String roomId, BotTaskQueue.BotType botType) {
        AtomicBoolean flag = getProcessingFlag(roomId, botType);
        return flag != null && flag.get();
    }

    // ========== 新增：流程图任务支持 ==========

    /**
     * 保存任务到数据库（用于外部直接保存）
     */
    public void saveTask(BotTaskQueue dbTask) {
        taskQueueRepository.save(dbTask);
    }

    /**
     * 添加任务包装器到内存队列（用于外部直接添加）
     */
    public void addTaskToMemoryQueue(String roomId, BotTaskQueue.BotType botType, TaskWrapper wrapper) {
        LinkedBlockingQueue<TaskWrapper> queue = getOrCreateQueue(roomId, botType);
        queue.offer(wrapper);
    }

    // ========== 私有辅助方法 ==========

    private LinkedBlockingQueue<TaskWrapper> getOrCreateQueue(String roomId, BotTaskQueue.BotType botType) {
        return switch (botType) {
            case OPENCLAW -> openclawQueues.computeIfAbsent(roomId, k -> new LinkedBlockingQueue<>());
            case KIMI -> kimiQueues.computeIfAbsent(roomId, k -> new LinkedBlockingQueue<>());
            case CLAUDE -> claudeQueues.computeIfAbsent(roomId, k -> new LinkedBlockingQueue<>());
        };
    }

    private LinkedBlockingQueue<TaskWrapper> getQueue(String roomId, BotTaskQueue.BotType botType) {
        return switch (botType) {
            case OPENCLAW -> openclawQueues.get(roomId);
            case KIMI -> kimiQueues.get(roomId);
            case CLAUDE -> claudeQueues.get(roomId);
        };
    }

    private AtomicBoolean getProcessingFlag(String roomId, BotTaskQueue.BotType botType) {
        return switch (botType) {
            case OPENCLAW -> openclawProcessingFlags.computeIfAbsent(roomId, k -> new AtomicBoolean(false));
            case KIMI -> kimiProcessingFlags.computeIfAbsent(roomId, k -> new AtomicBoolean(false));
            case CLAUDE -> claudeProcessingFlags.computeIfAbsent(roomId, k -> new AtomicBoolean(false));
        };
    }

    private void updateTaskStatus(String taskId, BotTaskQueue.TaskStatus status) {
        taskQueueRepository.findByTaskId(taskId).ifPresent(dbTask -> {
            dbTask.setStatus(status);
            dbTask.setUpdatedAt(Instant.now());
            taskQueueRepository.save(dbTask);
        });
    }

    private ChatWebSocketHandler.OpenClawTask convertToTask(BotTaskQueue dbTask) {
        return ChatWebSocketHandler.OpenClawTask.builder()
                .taskId(dbTask.getTaskId())
                .roomId(dbTask.getRoomId())
                .content(dbTask.getContent())
                .attachments(convertAttachmentsBack(dbTask.getAttachments()))
                .userInfo(convertUserInfoBack(dbTask.getUserInfo()))
                .sourceMessageId(dbTask.getSourceMessageId())
                .createdAt(dbTask.getCreatedAt())
                .status(ChatWebSocketHandler.OpenClawTask.TaskStatus.valueOf(dbTask.getStatus().name()))
                .build();
    }

    private List<BotTaskQueue.Attachment> convertAttachments(List<ChatWebSocketHandler.Attachment> attachments) {
        if (attachments == null) return null;
        return attachments.stream()
                .map(att -> BotTaskQueue.Attachment.builder()
                        .type(att.getType())
                        .mimeType(att.getMimeType())
                        .url(att.getUrl())
                        .content(att.getContent())
                        .build())
                .collect(Collectors.toList());
    }

    private List<ChatWebSocketHandler.Attachment> convertAttachmentsBack(List<BotTaskQueue.Attachment> attachments) {
        if (attachments == null) return null;
        return attachments.stream()
                .map(att -> {
                    ChatWebSocketHandler.Attachment result = new ChatWebSocketHandler.Attachment();
                    result.setType(att.getType());
                    result.setMimeType(att.getMimeType());
                    result.setUrl(att.getUrl());
                    result.setContent(att.getContent());
                    return result;
                })
                .collect(Collectors.toList());
    }

    private BotTaskQueue.UserInfo convertUserInfo(ChatWebSocketHandler.WebSocketUserInfo userInfo) {
        if (userInfo == null) return null;
        return BotTaskQueue.UserInfo.builder()
                .userId(userInfo.getUserId())
                .userName(userInfo.getUserName())
                .roomId(userInfo.getRoomId())
                .avatar(userInfo.getAvatar())
                .build();
    }

    private ChatWebSocketHandler.WebSocketUserInfo convertUserInfoBack(BotTaskQueue.UserInfo userInfo) {
        if (userInfo == null) return null;
        return ChatWebSocketHandler.WebSocketUserInfo.builder()
                .userId(userInfo.getUserId())
                .userName(userInfo.getUserName())
                .roomId(userInfo.getRoomId())
                .avatar(userInfo.getAvatar())
                .build();
    }
}
