<template>
  <div class="voice-input-container">
    <!-- 模型加载进度 -->
    <div v-if="isModelLoading" class="model-loading">
      <div class="progress-bar">
        <div class="progress-fill" :style="{ width: loadingProgress + '%' }" />
      </div>
      <span class="loading-text">加载语音识别模型 {{ loadingProgress }}%</span>
    </div>

    <!-- 录音中状态 -->
    <div v-else-if="isRecording" class="recording-panel">
      <!-- 波形可视化 -->
      <div class="waveform">
        <div
          v-for="i in 7"
          :key="i"
          class="wave-bar"
          :style="{ animationDelay: (i * 0.08) + 's' }"
        />
      </div>

      <span class="recording-time">{{ formatRecordingTime(recordingTime) }}</span>

      <button class="btn-cancel" @click="cancelRecording" title="取消">
        ✕
      </button>
      <button class="btn-done" @click="stopAndSend" title="完成">
        ✓
      </button>
    </div>

    <!-- 识别结果预览 -->
    <div v-else-if="transcript" class="preview-panel">
      <p class="preview-text">🎤 {{ transcript }}</p>
      <div class="preview-actions">
        <button class="btn-send" @click="sendTranscript">
          发送
        </button>
        <button class="btn-retry" @click="retryRecording">
          重录
        </button>
      </div>
    </div>

    <!-- 默认状态：录音按钮 -->
    <button
      v-else
      class="voice-btn"
      @click="startRecording"
      :disabled="isModelLoading"
      title="语音输入"
    >
      <svg class="mic-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"></path>
        <path d="M19 10v2a7 7 0 0 1-14 0v-2"></path>
        <line x1="12" y1="19" x2="12" y2="23"></line>
        <line x1="8" y1="23" x2="16" y2="23"></line>
      </svg>
    </button>

    <!-- 错误提示 -->
    <div v-if="error" class="error-toast" @click="error = null">
      {{ error }}
    </div>
  </div>
</template>

<script setup lang="ts">
import { useSpeechRecognition } from '@/composables/useSpeechRecognition'

const emit = defineEmits<{
  send: [text: string]
}>()

const {
  isRecording,
  isModelLoading,
  loadingProgress,
  transcript,
  recordingTime,
  error,
  startRecording,
  stopRecording,
  cancelRecording,
  clearTranscript,
  formatRecordingTime,
  preloadModel
} = useSpeechRecognition({
  onResult: (text) => {
    console.log('[VoiceInput] 识别结果:', text)
  },
  onError: (err) => {
    console.error('[VoiceInput] 错误:', err)
  }
})

// 停止录音并发送
async function stopAndSend(): Promise<void> {
  await stopRecording()
}

// 发送识别结果
function sendTranscript(): void {
  if (transcript.value.trim()) {
    emit('send', transcript.value.trim())
    clearTranscript()
  }
}

// 重新录音
function retryRecording(): void {
  clearTranscript()
  startRecording()
}

// 暴露预加载方法给父组件
defineExpose({
  preloadModel
})
</script>

<style scoped>
.voice-input-container {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* 录音按钮 */
.voice-btn {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  border: 1px solid var(--border-color);
  background: var(--bg-color);
  color: var(--text-secondary);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s ease;
  padding: 0;
}

.voice-btn:hover {
  background: var(--surface-color);
  color: var(--primary-color);
  border-color: var(--primary-color);
  transform: scale(1.05);
}

.voice-btn:active {
  transform: scale(0.95);
}

.mic-icon {
  width: 18px;
  height: 18px;
}

/* 模型加载进度 */
.model-loading {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 12px;
  background: var(--bg-color);
  border-radius: 18px;
  border: 1px solid var(--border-color);
}

.progress-bar {
  width: 80px;
  height: 4px;
  background: var(--border-color);
  border-radius: 2px;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: linear-gradient(90deg, var(--primary-color), var(--primary-light, #64b5f6));
  border-radius: 2px;
  transition: width 0.3s ease;
}

.loading-text {
  font-size: 12px;
  color: var(--text-secondary);
  white-space: nowrap;
}

/* 录音中面板 */
.recording-panel {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 6px 12px;
  background: linear-gradient(135deg, #ff4444, #ff6b6b);
  border-radius: 20px;
  color: white;
  animation: pulse-bg 2s ease-in-out infinite;
}

@keyframes pulse-bg {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.9; }
}

/* 波形动画 */
.waveform {
  display: flex;
  align-items: center;
  gap: 3px;
  height: 24px;
}

.wave-bar {
  width: 3px;
  height: 8px;
  background: rgba(255, 255, 255, 0.9);
  border-radius: 1.5px;
  animation: wave 0.8s ease-in-out infinite;
}

@keyframes wave {
  0%, 100% {
    height: 8px;
    opacity: 0.7;
  }
  50% {
    height: 24px;
    opacity: 1;
  }
}

.recording-time {
  font-size: 14px;
  font-weight: 500;
  min-width: 40px;
  text-align: center;
  font-variant-numeric: tabular-nums;
}

.btn-cancel,
.btn-done {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  border: none;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  transition: all 0.2s;
}

.btn-cancel {
  background: rgba(255, 255, 255, 0.2);
  color: white;
}

.btn-cancel:hover {
  background: rgba(255, 255, 255, 0.3);
}

.btn-done {
  background: rgba(255, 255, 255, 0.9);
  color: #ff4444;
}

.btn-done:hover {
  background: white;
  transform: scale(1.1);
}

/* 识别结果预览 */
.preview-panel {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  background: var(--bg-color);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  max-width: 400px;
}

.preview-text {
  margin: 0;
  font-size: 14px;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 200px;
}

.preview-actions {
  display: flex;
  gap: 6px;
}

.btn-send,
.btn-retry {
  padding: 4px 12px;
  border-radius: 4px;
  border: none;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-send {
  background: var(--primary-color);
  color: white;
}

.btn-send:hover {
  background: var(--primary-hover, #1976d2);
}

.btn-retry {
  background: var(--surface-color);
  color: var(--text-secondary);
  border: 1px solid var(--border-color);
}

.btn-retry:hover {
  background: var(--border-color);
}

/* 错误提示 */
.error-toast {
  position: fixed;
  bottom: 100px;
  left: 50%;
  transform: translateX(-50%);
  padding: 10px 20px;
  background: #ff4444;
  color: white;
  border-radius: 20px;
  font-size: 14px;
  cursor: pointer;
  animation: slide-up 0.3s ease;
  z-index: 1000;
}

@keyframes slide-up {
  from {
    opacity: 0;
    transform: translateX(-50%) translateY(20px);
  }
  to {
    opacity: 1;
    transform: translateX(-50%) translateY(0);
  }
}

/* 响应式适配 */
@media (max-width: 640px) {
  .preview-panel {
    max-width: 280px;
  }

  .preview-text {
    max-width: 120px;
  }

  .model-loading {
    padding: 4px 8px;
  }

  .progress-bar {
    width: 60px;
  }
}
</style>
