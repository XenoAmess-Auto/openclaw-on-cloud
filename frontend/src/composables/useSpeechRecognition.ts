import { ref, onUnmounted } from 'vue'
import { pipeline, AutomaticSpeechRecognitionPipeline, env } from '@xenova/transformers'

// 配置 transformers 环境
env.allowLocalModels = true
env.useBrowserCache = true

// Whisper base 模型，30MB，中英混合识别准确
const WHISPER_MODEL = 'Xenova/whisper-base'
// 可选模型：
// - 'Xenova/whisper-tiny' (15MB，速度最快，准确率稍低)
// - 'Xenova/whisper-small' (120MB，准确率最高)

interface UseSpeechRecognitionOptions {
  onResult?: (text: string) => void
  onError?: (error: Error) => void
}

export function useSpeechRecognition(options: UseSpeechRecognitionOptions = {}) {
  const isRecording = ref(false)
  const isModelLoading = ref(false)
  const loadingProgress = ref(0)
  const transcript = ref('')
  const recordingTime = ref(0)
  const error = ref<string | null>(null)

  let transcriber: AutomaticSpeechRecognitionPipeline | null = null
  let mediaRecorder: MediaRecorder | null = null
  let audioChunks: Blob[] = []
  let recordingTimer: number | null = null
  let audioContext: AudioContext | null = null

  // 懒加载模型
  async function loadModel(): Promise<boolean> {
    if (transcriber) return true

    try {
      isModelLoading.value = true
      error.value = null

      // 设置缓存目录（浏览器 IndexedDB）
      env.cacheDir = 'openclaw-whisper-models'

      transcriber = await pipeline(
        'automatic-speech-recognition',
        WHISPER_MODEL,
        {
          quantized: true, // 使用量化模型，体积减半，速度更快
          progress_callback: (progress: number) => {
            loadingProgress.value = Math.round(progress * 100)
          }
        }
      )

      isModelLoading.value = false
      return true
    } catch (err) {
      const errorMsg = (err as Error).message
      console.error('[useSpeechRecognition] 模型加载失败:', err)

      // 提供更友好的错误信息
      if (errorMsg.includes('Unexpected token') || errorMsg.includes('<!DOCTYPE')) {
        error.value = '模型下载失败：网络连接问题或 CDN 访问受限，请检查网络后重试'
      } else if (errorMsg.includes('fetch') || errorMsg.includes('network')) {
        error.value = '网络连接失败，无法下载语音识别模型'
      } else {
        error.value = '模型加载失败: ' + errorMsg
      }

      isModelLoading.value = false
      options.onError?.(err as Error)
      return false
    }
  }

  // 预加载模型（在空闲时后台加载）
  async function preloadModel(): Promise<void> {
    if (transcriber) return
    await loadModel()
  }

  // 开始录音
  async function startRecording(): Promise<boolean> {
    // 确保模型已加载
    const modelReady = await loadModel()
    if (!modelReady) return false

    try {
      error.value = null

      // 请求麦克风权限
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          sampleRate: 16000, // Whisper 最优采样率
          channelCount: 1,   // 单声道
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true
        }
      })

      // 创建 MediaRecorder
      const mimeType = MediaRecorder.isTypeSupported('audio/webm;codecs=opus')
        ? 'audio/webm;codecs=opus'
        : MediaRecorder.isTypeSupported('audio/webm')
          ? 'audio/webm'
          : 'audio/mp4'

      mediaRecorder = new MediaRecorder(stream, { mimeType })
      audioChunks = []

      mediaRecorder.ondataavailable = (e) => {
        if (e.data.size > 0) {
          audioChunks.push(e.data)
        }
      }

      mediaRecorder.start(100) // 每100ms收集一次数据
      isRecording.value = true
      recordingTime.value = 0

      // 开始计时
      recordingTimer = window.setInterval(() => {
        recordingTime.value++
      }, 1000)

      return true
    } catch (err) {
      error.value = '无法访问麦克风: ' + (err as Error).message
      options.onError?.(err as Error)
      return false
    }
  }

  // 停止录音并识别
  async function stopRecording(): Promise<string | null> {
    if (!mediaRecorder || !isRecording.value) return null

    return new Promise((resolve) => {
      mediaRecorder!.onstop = async () => {
        // 清除计时器
        if (recordingTimer) {
          clearInterval(recordingTimer)
          recordingTimer = null
        }

        // 停止所有音频轨道
        mediaRecorder!.stream.getTracks().forEach(t => t.stop())

        try {
          // 合并音频数据
          const audioBlob = new Blob(audioChunks, { type: mediaRecorder!.mimeType })

          // 音频格式转换和识别
          const result = await transcribeAudio(audioBlob)

          transcript.value = result
          isRecording.value = false
          options.onResult?.(result)
          resolve(result)
        } catch (err) {
          error.value = '识别失败: ' + (err as Error).message
          isRecording.value = false
          options.onError?.(err as Error)
          resolve(null)
        }
      }

      mediaRecorder!.stop()
    })
  }

  // 音频转录
  async function transcribeAudio(audioBlob: Blob): Promise<string> {
    if (!transcriber) throw new Error('模型未加载')

    // 将 Blob 转换为 Float32Array
    const audioData = await blobToFloat32Array(audioBlob)

    // 使用 Whisper 进行语音识别
    const result = await transcriber(audioData, {
      language: 'chinese', // 指定中文，提高准确率
      task: 'transcribe',
      return_timestamps: false,
      // 额外参数优化中文识别
      max_new_tokens: 128,
      chunk_length_s: 30,
      stride_length_s: 5
    })

    // 处理 result 可能是数组的情况
    const output = Array.isArray(result) ? result[0] : result
    return (output?.text || '').trim()
  }

  // 将 Blob 转换为 Float32Array (Whisper 输入格式)
  async function blobToFloat32Array(blob: Blob): Promise<Float32Array> {
    // 创建 AudioContext 用于解码音频
    audioContext = new (window.AudioContext || (window as any).webkitAudioContext)({
      sampleRate: 16000
    })

    const arrayBuffer = await blob.arrayBuffer()
    const audioBuffer = await audioContext.decodeAudioData(arrayBuffer)

    // 获取单声道数据
    const channelData = audioBuffer.getChannelData(0)

    // 关闭 AudioContext 释放资源
    await audioContext.close()
    audioContext = null

    return channelData
  }

  // 取消录音
  function cancelRecording(): void {
    if (mediaRecorder && isRecording.value) {
      mediaRecorder.stop()
      mediaRecorder.stream.getTracks().forEach(t => t.stop())
    }

    if (recordingTimer) {
      clearInterval(recordingTimer)
      recordingTimer = null
    }

    isRecording.value = false
    recordingTime.value = 0
    audioChunks = []
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
    if (audioContext) {
      audioContext.close()
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

    // 方法
    loadModel,
    preloadModel,
    startRecording,
    stopRecording,
    cancelRecording,
    clearTranscript,
    formatRecordingTime
  }
}

export type UseSpeechRecognitionReturn = ReturnType<typeof useSpeechRecognition>
