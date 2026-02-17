import { ref, onUnmounted } from 'vue'
import { pipeline, AutomaticSpeechRecognitionPipeline, env } from '@xenova/transformers'

// 配置 transformers 环境
env.allowLocalModels = true
env.allowRemoteModels = false  // 强制使用本地模型，防止请求 Hugging Face
env.useBrowserCache = false

// 尝试本地模型路径，使用分块模型文件
const LOCAL_MODEL_PATH = '/models'
const REMOTE_MODEL_PATH = 'Xenova/whisper-base'

// 检测是否有本地模型（检查 encoder 文件）
async function hasLocalModel(): Promise<boolean> {
  try {
    const response = await fetch('/models/encoder_model_quantized.onnx', { method: 'HEAD' })
    return response.ok
  } catch {
    return false
  }
}

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
  const useLocalModel = ref(false)

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

      // 检测本地模型
      useLocalModel.value = await hasLocalModel()
      // 强制使用本地模型路径
      const modelPath = LOCAL_MODEL_PATH

      if (useLocalModel.value) {
        console.log('[useSpeechRecognition] 使用本地模型:', modelPath)
      } else {
        console.warn('[useSpeechRecognition] 本地模型文件检测失败，但仍尝试使用本地路径:', modelPath)
        console.warn('[useSpeechRecognition] 如果模型加载失败，请运行: ./download-model.sh')
      }

      console.log('[useSpeechRecognition] 开始加载模型...')
      transcriber = await pipeline(
        'automatic-speech-recognition',
        modelPath,
        {
          quantized: true,
          progress_callback: (progress: number) => {
            loadingProgress.value = Math.round(progress * 100)
            console.log(`[useSpeechRecognition] 模型加载进度: ${Math.round(progress * 100)}%`)
          }
        }
      )

      console.log('[useSpeechRecognition] 模型加载成功')
      isModelLoading.value = false
      return true
    } catch (err) {
      const errorMsg = (err as Error).message
      console.error('[useSpeechRecognition] 模型加载失败:', err)

      if (errorMsg.includes('Unexpected token') || errorMsg.includes('<!DOCTYPE')) {
        error.value = '模型加载失败：服务器返回了 HTML 而非模型文件。请确保 public/models/ 目录下有完整的模型文件，或运行 ./download-model.sh 下载。'
      } else if (errorMsg.includes('fetch') || errorMsg.includes('network') || errorMsg.includes('Failed to fetch')) {
        error.value = '模型加载失败：无法获取模型文件。请检查网络连接或运行 ./download-model.sh 下载本地模型。'
      } else if (errorMsg.includes('Cannot find module') || errorMsg.includes('Could not locate')) {
        error.value = '模型文件缺失：请在项目根目录运行 ./download-model.sh 下载模型文件。'
      } else {
        error.value = '模型加载失败: ' + errorMsg
      }

      isModelLoading.value = false
      options.onError?.(err as Error)
      return false
    }
  }

  // 预加载模型
  async function preloadModel(): Promise<void> {
    if (transcriber) return
    await loadModel()
  }

  // 开始录音
  async function startRecording(): Promise<boolean> {
    const modelReady = await loadModel()
    if (!modelReady) return false

    try {
      error.value = null

      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          sampleRate: 16000,
          channelCount: 1,
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true
        }
      })

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

      mediaRecorder.start(100)
      isRecording.value = true
      recordingTime.value = 0

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
        if (recordingTimer) {
          clearInterval(recordingTimer)
          recordingTimer = null
        }

        mediaRecorder!.stream.getTracks().forEach(t => t.stop())

        try {
          const audioBlob = new Blob(audioChunks, { type: mediaRecorder!.mimeType })
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

    const audioData = await blobToFloat32Array(audioBlob)

    const result = await transcriber(audioData, {
      language: 'chinese',
      task: 'transcribe',
      return_timestamps: false,
      max_new_tokens: 128,
      chunk_length_s: 30,
      stride_length_s: 5
    })

    const output = Array.isArray(result) ? result[0] : result
    return (output?.text || '').trim()
  }

  // 将 Blob 转换为 Float32Array
  async function blobToFloat32Array(blob: Blob): Promise<Float32Array> {
    audioContext = new (window.AudioContext || (window as any).webkitAudioContext)({
      sampleRate: 16000
    })

    const arrayBuffer = await blob.arrayBuffer()
    const audioBuffer = await audioContext.decodeAudioData(arrayBuffer)
    const channelData = audioBuffer.getChannelData(0)

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

  // 格式化录音时间
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
