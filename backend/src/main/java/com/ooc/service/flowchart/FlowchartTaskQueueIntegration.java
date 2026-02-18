package com.ooc.service.flowchart;

import com.ooc.entity.BotTaskQueue;
import com.ooc.entity.flowchart.FlowchartInstance;
import com.ooc.repository.FlowchartInstanceRepository;
import com.ooc.service.PersistentTaskQueueService;
import com.ooc.websocket.ChatWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 流程图任务队列集成服务
 * 将流程图执行集成到现有任务队列系统中
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlowchartTaskQueueIntegration {

    private final PersistentTaskQueueService taskQueueService;
    private final FlowchartEngine flowchartEngine;
    private final FlowchartTemplateService templateService;
    private final FlowchartInstanceRepository instanceRepository;

    /**
     * 添加流程图任务到队列（HTTP API 版本）
     *
     * @param roomId 房间ID
     * @param templateId 模板ID
     * @param variables 变量值
     * @param userId 用户ID
     * @param userName 用户名
     * @return 实例ID
     */
    public String enqueueFlowchart(String roomId, String templateId,
                                   Map<String, Object> variables,
                                   String userId, String userName) {
        // 1. 创建流程图实例
        FlowchartInstance instance = templateService.createInstance(
                templateId, roomId, userId, variables
        );

        // 2. 添加到任务队列
        String taskId = addFlowchartTaskToQueue(roomId, instance, userId, userName);

        log.info("Flowchart task enqueued: taskId={}, instanceId={}, templateId={}",
                taskId, instance.getInstanceId(), templateId);

        return instance.getInstanceId();
    }

    /**
     * 添加流程图任务到队列（WebSocket 版本）
     *
     * @param roomId 房间ID
     * @param templateId 模板ID
     * @param variables 变量值
     * @param userInfo 用户信息
     * @param sourceMessageId 源消息ID
     * @return 任务ID
     */
    public String enqueueFlowchart(String roomId, String templateId,
                                   Map<String, Object> variables,
                                   ChatWebSocketHandler.WebSocketUserInfo userInfo,
                                   String sourceMessageId) {
        // 1. 创建流程图实例
        FlowchartInstance instance = templateService.createInstance(
                templateId, roomId, userInfo.getUserId(), variables
        );

        // 2. 添加到任务队列
        String taskId = addFlowchartTaskToQueue(roomId, instance, userInfo, sourceMessageId);

        log.info("Flowchart task enqueued: taskId={}, instanceId={}, templateId={}",
                taskId, instance.getInstanceId(), templateId);

        return taskId;
    }

    /**
     * 添加流程图任务到队列（HTTP API 版本，内部方法）
     */
    private String addFlowchartTaskToQueue(String roomId, FlowchartInstance instance,
                                           String userId, String userName) {
        String taskId = java.util.UUID.randomUUID().toString();

        // 计算队列位置
        int position = taskQueueService.getQueueSize(roomId, BotTaskQueue.BotType.OPENCLAW);

        // 创建用户信息
        BotTaskQueue.UserInfo userInfo = BotTaskQueue.UserInfo.builder()
                .userId(userId)
                .userName(userName)
                .roomId(roomId)
                .build();

        // 创建数据库记录
        BotTaskQueue dbTask = BotTaskQueue.builder()
                .taskId(taskId)
                .roomId(roomId)
                .botType(BotTaskQueue.BotType.OPENCLAW)
                .taskType(BotTaskQueue.TaskType.FLOWCHART)
                .content("流程图: " + instance.getTemplateName())
                .flowchartInstanceId(instance.getInstanceId())
                .userInfo(userInfo)
                .status(BotTaskQueue.TaskStatus.PENDING)
                .position(position)
                .build();

        // 保存到数据库
        taskQueueService.saveTask(dbTask);

        // 创建内存任务包装器
        ChatWebSocketHandler.OpenClawTask task = ChatWebSocketHandler.OpenClawTask.builder()
                .taskId(taskId)
                .roomId(roomId)
                .content(instance.getTemplateName())
                .userInfo(ChatWebSocketHandler.WebSocketUserInfo.builder()
                        .userId(userId)
                        .userName(userName)
                        .roomId(roomId)
                        .build())
                .createdAt(java.time.Instant.now())
                .status(ChatWebSocketHandler.OpenClawTask.TaskStatus.PENDING)
                .taskType(ChatWebSocketHandler.OpenClawTask.TaskType.FLOWCHART)
                .flowchartInstanceId(instance.getInstanceId())
                .build();

        // 添加到内存队列
        taskQueueService.addTaskToMemoryQueue(roomId, BotTaskQueue.BotType.OPENCLAW,
                new PersistentTaskQueueService.TaskWrapper(taskId, task, BotTaskQueue.BotType.OPENCLAW));

        log.info("Flowchart task {} added to queue for room {} (position={}, instanceId={})",
                taskId, roomId, position, instance.getInstanceId());

        // 触发队列处理
        taskQueueService.tryProcessNext(roomId, BotTaskQueue.BotType.OPENCLAW);

        return taskId;
    }

    /**
     * 添加流程图任务到队列（内部方法）
     */
    private String addFlowchartTaskToQueue(String roomId, FlowchartInstance instance,
                                           ChatWebSocketHandler.WebSocketUserInfo userInfo,
                                           String sourceMessageId) {
        String taskId = java.util.UUID.randomUUID().toString();

        // 计算队列位置
        int position = taskQueueService.getQueueSize(roomId, BotTaskQueue.BotType.OPENCLAW);

        // 创建数据库记录
        BotTaskQueue dbTask = BotTaskQueue.builder()
                .taskId(taskId)
                .roomId(roomId)
                .botType(BotTaskQueue.BotType.OPENCLAW)
                .taskType(BotTaskQueue.TaskType.FLOWCHART)
                .content("流程图: " + instance.getTemplateName())
                .flowchartInstanceId(instance.getInstanceId())
                .userInfo(convertUserInfo(userInfo))
                .sourceMessageId(sourceMessageId)
                .status(BotTaskQueue.TaskStatus.PENDING)
                .position(position)
                .build();

        // 保存到数据库
        taskQueueService.saveTask(dbTask);

        // 创建内存任务包装器
        ChatWebSocketHandler.OpenClawTask task = ChatWebSocketHandler.OpenClawTask.builder()
                .taskId(taskId)
                .roomId(roomId)
                .content(instance.getTemplateName())
                .userInfo(userInfo)
                .sourceMessageId(sourceMessageId)
                .createdAt(java.time.Instant.now())
                .status(ChatWebSocketHandler.OpenClawTask.TaskStatus.PENDING)
                .taskType(ChatWebSocketHandler.OpenClawTask.TaskType.FLOWCHART)
                .flowchartInstanceId(instance.getInstanceId())
                .build();

        // 添加到内存队列
        taskQueueService.addTaskToMemoryQueue(roomId, BotTaskQueue.BotType.OPENCLAW,
                new PersistentTaskQueueService.TaskWrapper(taskId, task, BotTaskQueue.BotType.OPENCLAW));

        log.info("Flowchart task {} added to queue for room {} (position={}, instanceId={})",
                taskId, roomId, position, instance.getInstanceId());

        // 触发队列处理
        taskQueueService.tryProcessNext(roomId, BotTaskQueue.BotType.OPENCLAW);

        return taskId;
    }

    /**
     * 处理流程图任务（通过 instanceId）
     *
     * @param task 任务
     */
    public void executeFlowchartTask(ChatWebSocketHandler.OpenClawTask task) {
        if (task.getFlowchartInstanceId() == null) {
            log.error("Flowchart task {} has no instanceId", task.getTaskId());
            taskQueueService.markTaskFailed(task.getTaskId());
            taskQueueService.onTaskComplete(task.getRoomId(), BotTaskQueue.BotType.OPENCLAW);
            return;
        }

        instanceRepository.findByInstanceId(task.getFlowchartInstanceId())
                .ifPresentOrElse(
                        instance -> executeFlowchartTask(task, instance),
                        () -> {
                            log.error("Flowchart instance not found: {}", task.getFlowchartInstanceId());
                            taskQueueService.markTaskFailed(task.getTaskId());
                            taskQueueService.onTaskComplete(task.getRoomId(), BotTaskQueue.BotType.OPENCLAW);
                        }
                );
    }

    /**
     * 处理流程图任务
     *
     * @param task 任务
     * @param instance 流程图实例
     */
    public void executeFlowchartTask(ChatWebSocketHandler.OpenClawTask task,
                                     FlowchartInstance instance) {
        log.info("Executing flowchart task: taskId={}, instanceId={}",
                task.getTaskId(), instance.getInstanceId());

        try {
            // 绑定事件监听器用于 WebSocket 通知
            FlowchartEngine.FlowchartEventListener listener = createWebSocketListener(task);
            flowchartEngine.addListener(instance.getInstanceId(), listener);

            // 更新实例的任务队列ID
            instance.setTaskQueueId(task.getTaskId());
            instanceRepository.save(instance);

            // 开始执行流程图
            flowchartEngine.startExecution(instance.getInstanceId());

            // 等待执行完成（这里简化为异步执行，实际应该等待回调）
            // 注意：实际流程是异步的，完成通知通过事件监听器的 onEvent 回调

        } catch (Exception e) {
            log.error("Error executing flowchart task: taskId={}, instanceId={}",
                    task.getTaskId(), instance.getInstanceId(), e);
            taskQueueService.markTaskFailed(task.getTaskId());
            taskQueueService.onTaskComplete(task.getRoomId(), BotTaskQueue.BotType.OPENCLAW);
        }
    }

    /**
     * 创建 WebSocket 事件监听器
     */
    private FlowchartEngine.FlowchartEventListener createWebSocketListener(
            ChatWebSocketHandler.OpenClawTask task) {
        // 使用数组来持有监听器引用，以便在lambda内部访问
        final FlowchartEngine.FlowchartEventListener[] listenerHolder = new FlowchartEngine.FlowchartEventListener[1];

        listenerHolder[0] = event -> {
            // 根据事件类型发送 WebSocket 通知
            if (event instanceof FlowchartEngine.NodeStartedEvent startedEvent) {
                sendNodeStartedNotification(task, startedEvent);
            } else if (event instanceof FlowchartEngine.NodeCompletedEvent completedEvent) {
                sendNodeCompletedNotification(task, completedEvent);
            } else if (event instanceof FlowchartEngine.FlowchartCompletedEvent completedEvent) {
                // 任务完成
                taskQueueService.markTaskCompleted(task.getTaskId());
                taskQueueService.onTaskComplete(task.getRoomId(), BotTaskQueue.BotType.OPENCLAW);
                flowchartEngine.removeListener(completedEvent.getInstanceId(), listenerHolder[0]);
            } else if (event instanceof FlowchartEngine.FlowchartFailedEvent failedEvent) {
                // 任务失败
                taskQueueService.markTaskFailed(task.getTaskId());
                taskQueueService.onTaskComplete(task.getRoomId(), BotTaskQueue.BotType.OPENCLAW);
                flowchartEngine.removeListener(failedEvent.getInstanceId(), listenerHolder[0]);
            }
        };

        return listenerHolder[0];
    }

    /**
     * 发送节点开始通知
     */
    private void sendNodeStartedNotification(ChatWebSocketHandler.OpenClawTask task,
                                             FlowchartEngine.NodeStartedEvent event) {
        // TODO: 通过 WebSocket 发送通知到房间
        log.info("[Flowchart:{}] Node started: {}",
                event.getInstanceId(),
                event.getNode().getId());
    }

    /**
     * 发送节点完成通知
     */
    private void sendNodeCompletedNotification(ChatWebSocketHandler.OpenClawTask task,
                                               FlowchartEngine.NodeCompletedEvent event) {
        // TODO: 通过 WebSocket 发送通知到房间
        log.info("[Flowchart:{}] Node completed: {} = {}",
                event.getInstanceId(),
                event.getNode().getId(),
                event.getResult().getOutput());
    }

    /**
     * 转换用户信息
     */
    private BotTaskQueue.UserInfo convertUserInfo(ChatWebSocketHandler.WebSocketUserInfo userInfo) {
        if (userInfo == null) return null;
        return BotTaskQueue.UserInfo.builder()
                .userId(userInfo.getUserId())
                .userName(userInfo.getUserName())
                .roomId(userInfo.getRoomId())
                .avatar(userInfo.getAvatar())
                .build();
    }
}
