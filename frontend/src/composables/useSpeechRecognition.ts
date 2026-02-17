import { ref, onUnmounted } from 'vue'

interface UseSpeechRecognitionOptions {
  onResult?: (text: string) => void
  onError?: (error: Error) => void
}

// TypeScript 类型声明
interface SpeechRecognition extends EventTarget {
  continuous: boolean
  interimResults: boolean
  lang: string
  start(): void
  stop(): void
  onresult: ((event: SpeechRecognitionEvent) => void) | null
  onerror: ((event: SpeechRecognitionErrorEvent) => void) | null
  onend: (() => void) | null
}

interface SpeechRecognitionConstructor {
  new (): SpeechRecognition
}

interface SpeechRecognitionEvent extends Event {
  resultIndex: number
  results: SpeechRecognitionResultList
}

interface SpeechRecognitionErrorEvent extends Event {
  error: string
}

declare global {
  interface Window {
    SpeechRecognition: SpeechRecognitionConstructor
    webkitSpeechRecognition: SpeechRecognitionConstructor
  }
}

export function useSpeechRecognition(options: UseSpeechRecognitionOptions = {}) {
  const isRecording = ref(false)
  const isModelLoading = ref(false)
  const loadingProgress = ref(0)
  const transcript = ref('')
  const recordingTime = ref(0)
  const error = ref<string | null>(null)
  const isWebSpeechAvailable = ref(false)

  let recognition: SpeechRecognition | null = null
  let recordingTimer: number | null = null

  // 检查浏览器是否支持 Web Speech API
  function checkWebSpeechSupport(): boolean {
    const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition
    if (!SpeechRecognition) {
      return false
    }
    isWebSpeechAvailable.value = true
    return true
  }

  // 初始化语音识别
  function initRecognition(): boolean {
    if (!checkWebSpeechSupport()) {
      error.value = '您的浏览器不支持语音识别功能，请使用 Chrome 浏览器'
      return false
    }

    const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition
    recognition = new SpeechRecognition()
    recognition.continuous = true
    recognition.interimResults = true
    recognition.lang = 'zh-CN'

    recognition.onresult = (event: SpeechRecognitionEvent) => {
      let interimTranscript = ''
      let finalTranscript = ''

      for (let i = event.resultIndex; i < event.results.length; i++) {
        const result = event.results[i]
        if (result.isFinal) {
          finalTranscript += result[0].transcript
        } else {
          interimTranscript += result[0].transcript
        }
      }

      // 实时更新显示（包括临时结果）
      transcript.value = finalTranscript + interimTranscript

      if (finalTranscript) {
        options.onResult?.(finalTranscript)
      }
    }

    recognition.onerror = (event: SpeechRecognitionErrorEvent) => {
      console.error('[useSpeechRecognition] 识别错误:', event.error)
      if (event.error === 'not-allowed') {
        error.value = '麦克风权限被拒绝，请在浏览器设置中允许访问麦克风'
      } else if (event.error === 'no-speech') {
        // 没有检测到语音，不视为错误
        return
      } else {
        error.value = '语音识别错误: ' + event.error
      }
      options.onError?.(new Error(event.error))
      stopRecording()
    }

    recognition.onend = () => {
      // 如果不是手动停止，可能是意外结束
      if (isRecording.value) {
        isRecording.value = false
        if (recordingTimer) {
          clearInterval(recordingTimer)
          recordingTimer = null
        }
      }
    }

    return true
  }

  // 预加载（Web Speech API 不需要预加载，但保持接口兼容）
  async function preloadModel(): Promise<void> {
    checkWebSpeechSupport()
  }

  // 开始录音
  async function startRecording(): Promise<boolean> {
    if (!recognition && !initRecognition()) {
      return false
    }

    try {
      error.value = null
      transcript.value = ''

      // 请求麦克风权限并开始识别
      recognition!.start()
      isRecording.value = true
      recordingTime.value = 0

      // 开始计时
      recordingTimer = window.setInterval(() => {
        recordingTime.value++
      }, 1000)

      return true
    } catch (err) {
      error.value = '启动录音失败: ' + (err as Error).message
      options.onError?.(err as Error)
      return false
    }
  }

  // 停止录音
  async function stopRecording(): Promise<string | null> {
    if (!recognition || !isRecording.value) {
      return transcript.value || null
    }

    recognition.stop()
    isRecording.value = false

    if (recordingTimer) {
      clearInterval(recordingTimer)
      recordingTimer = null
    }

    const result = transcript.value.trim()
    if (result) {
      options.onResult?.(result)
    }
    return result || null
  }

  // 取消录音
  function cancelRecording(): void {
    if (recognition && isRecording.value) {
      recognition.stop()
    }

    if (recordingTimer) {
      clearInterval(recordingTimer)
      recordingTimer = null
    }

    isRecording.value = false
    recordingTime.value = 0
    transcript.value = ''
  }

  // 清除识别结果
  function clearTranscript(): void {
    transcript.value = ''
  }

  // 格式化录音时间 (MM:SS)
  function formatRecordingTime(seconds: number): string {
    const mins = Math.floor(seconds / 60)
    const secs = seconds % 60
    return `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`
  }

  // 组件卸载时清理
  onUnmounted(() => {
    cancelRecording()
    if (recognition) {
      recognition.stop()
    }
  })

  return {
    // 状态
    isRecording,
    isModelLoading,
    loadingProgress,
    transcript,
    recordingTime,
    error,
    isWebSpeechAvailable,

    // 方法
    preloadModel,
    startRecording,
    stopRecording,
    cancelRecording,
    clearTranscript,
    formatRecordingTime
  }
}

export type UseSpeechRecognitionReturn = ReturnType<typeof useSpeechRecognition>
