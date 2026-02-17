import { ref, onUnmounted } from 'vue'

interface UseSpeechRecognitionOptions {
  onResult?: (text: string) => void
  onError?: (error: Error) => void
}

// 使用浏览器原生 Web Speech API
export function useSpeechRecognition(options: UseSpeechRecognitionOptions = {}) {
  const isRecording = ref(false)
  const isModelLoading = ref(false)
  const loadingProgress = ref(0)
  const transcript = ref('')
  const recordingTime = ref(0)
  const error = ref<string | null>(null)
  const useLocalModel = ref(true)

  let recognition: SpeechRecognition | null = null
  let recordingTimer: number | null = null

  // 检查浏览器支持
  const isSupported = 'webkitSpeechRecognition' in window || 'SpeechRecognition' in window

  // 懒加载/初始化
  async function loadModel(): Promise<boolean> {
    if (!isSupported) {
      error.value = '您的浏览器不支持语音识别功能，请使用 Chrome 浏览器'
      options.onError?.(new Error('Speech recognition not supported'))
      return false
    }
    return true
  }

  // 预加载模型（Web Speech API 不需要预加载）
  async function preloadModel(): Promise<void> {
    // 无需操作
  }

  // 开始录音
  async function startRecording(): Promise<boolean> {
    const ready = await loadModel()
    if (!ready) return false

    try {
      error.value = null
      isModelLoading.value = true
      loadingProgress.value = 0

      // 创建 SpeechRecognition 实例
      const SpeechRecognition = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition
      recognition = new SpeechRecognition()
      
      recognition.lang = 'zh-CN'
      recognition.continuous = false
      recognition.interimResults = true
      recognition.maxAlternatives = 1

      let finalTranscript = ''

      recognition.onstart = () => {
        console.log('[useSpeechRecognition] 录音开始')
        isRecording.value = true
        isModelLoading.value = false
        recordingTime.value = 0
        
        recordingTimer = window.setInterval(() => {
          recordingTime.value++
        }, 1000)
      }

      recognition.onresult = (event: SpeechRecognitionEvent) => {
        let interimTranscript = ''
        
        for (let i = event.resultIndex; i < event.results.length; i++) {
          const result = event.results[i]
          if (result.isFinal) {
            finalTranscript += result[0].transcript
          } else {
            interimTranscript += result[0].transcript
          }
        }
        
        transcript.value = finalTranscript + interimTranscript
        console.log('[useSpeechRecognition] 识别结果:', transcript.value)
      }

      recognition.onerror = (event: SpeechRecognitionErrorEvent) => {
        console.error('[useSpeechRecognition] 识别错误:', event.error)
        
        if (event.error === 'no-speech') {
          error.value = '没有检测到语音'
        } else if (event.error === 'audio-capture') {
          error.value = '无法访问麦克风'
        } else if (event.error === 'not-allowed') {
          error.value = '麦克风权限被拒绝'
        } else {
          error.value = '语音识别错误: ' + event.error
        }
        
        cleanup()
        options.onError?.(new Error(event.error))
      }

      recognition.onend = () => {
        console.log('[useSpeechRecognition] 录音结束')
        
        // 如果有最终结果，触发回调
        if (finalTranscript) {
          transcript.value = finalTranscript
          options.onResult?.(finalTranscript)
        }
        
        cleanup()
      }

      recognition.start()
      return true
    } catch (err) {
      error.value = '启动录音失败: ' + (err as Error).message
      isModelLoading.value = false
      options.onError?.(err as Error)
      return false
    }
  }

  // 停止录音
  async function stopRecording(): Promise<string | null> {
    if (!recognition || !isRecording.value) return null

    return new Promise((resolve) => {
      if (recognition) {
        // 设置一个临时回调来获取最终结果
        const originalOnEnd = recognition.onend
        recognition.onend = (event) => {
          if (originalOnEnd) originalOnEnd.call(recognition!, event as any)
          resolve(transcript.value)
        }
        
        recognition.stop()
      } else {
        resolve(null)
      }
    })
  }

  // 取消录音
  function cancelRecording(): void {
    if (recognition && isRecording.value) {
      recognition.abort()
    }
    cleanup()
  }

  // 清理资源
  function cleanup(): void {
    if (recordingTimer) {
      clearInterval(recordingTimer)
      recordingTimer = null
    }
    isRecording.value = false
    isModelLoading.value = false
    recognition = null
  }

  // 清除识别结果
  function clearTranscript(): void {
    transcript.value = ''
  }

  // 格式化录音时间
  function formatRecordingTime(seconds: number): string {
    const mins = Math.floor(seconds / 60)
    const secs = seconds % 60
    return `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`
  }

  // 组件卸载时清理
  onUnmounted(() => {
    cancelRecording()
  })

  return {
    isRecording,
    isModelLoading,
    loadingProgress,
    transcript,
    recordingTime,
    error,
    useLocalModel,
    startRecording,
    stopRecording,
    cancelRecording,
    clearTranscript,
    formatRecordingTime,
    preloadModel
  }
}

export type UseSpeechRecognitionReturn = ReturnType<typeof useSpeechRecognition>
