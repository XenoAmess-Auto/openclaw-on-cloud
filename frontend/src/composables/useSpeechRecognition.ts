import { ref, onUnmounted } from 'vue'
import { AutoProcessor, WhisperForConditionalGeneration, env } from '@xenova/transformers'

// 配置 transformers 环境
env.allowLocalModels = true
env.allowRemoteModels = false
env.useBrowserCache = false

// 配置 ONNX Runtime WASM 后端
env.backends.onnx.wasm.numThreads = 1
env.backends.onnx.wasm.simd = true

// 调试：拦截 fetch 请求
const originalFetch = window.fetch
window.fetch = async (...args) => {
  const url = args[0] as string
  console.log('[fetch]', url)
  const response = await originalFetch(...args)
  if (!response.ok && url.includes('/models/')) {
    console.error('[fetch] 模型文件加载失败:', url, response.status)
  }
  return response
}

// 音频处理工具函数
async function audioBufferToFloat32Array(audioBuffer: AudioBuffer): Promise<Float32Array> {
  const channelData = audioBuffer.getChannelData(0)
  return channelData
}

async function resampleAudio(audioData: Float32Array, srcSampleRate: number, dstSampleRate: number): Promise<Float32Array> {
  if (srcSampleRate === dstSampleRate) {
    return audioData
  }
  
  const ratio = dstSampleRate / srcSampleRate
  const newLength = Math.round(audioData.length * ratio)
  const result = new Float32Array(newLength)
  
  for (let i = 0; i < newLength; i++) {
    const srcIndex = i / ratio
    const srcIndexInt = Math.floor(srcIndex)
    const frac = srcIndex - srcIndexInt
    
    if (srcIndexInt >= audioData.length - 1) {
      result[i] = audioData[audioData.length - 1]
    } else {
      result[i] = audioData[srcIndexInt] * (1 - frac) + audioData[srcIndexInt + 1] * frac
    }
  }
  
  return result
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

  let processor: any = null
  let model: any = null
  let mediaRecorder: MediaRecorder | null = null
  let audioChunks: Blob[] = []
  let recordingTimer: number | null = null
  let audioContext: AudioContext | null = null

  // 检测是否有本地模型
  async function hasLocalModel(): Promise<boolean> {
    try {
      const response = await fetch('/models/encoder_model_quantized.onnx', { method: 'HEAD' })
      return response.ok
    } catch {
      return false
    }
  }

  // 加载模型
  async function loadModel(): Promise<boolean> {
    if (model && processor) return true

    try {
      isModelLoading.value = true
      error.value = null

      useLocalModel.value = await hasLocalModel()
      
      if (!useLocalModel.value) {
        throw new Error('本地模型文件不存在，请运行 ./download-model.sh')
      }

      console.log('[useSpeechRecognition] 开始加载 Whisper 模型...')

      // 加载 Processor（处理音频特征提取）
      console.log('[useSpeechRecognition] 加载 Processor...')
      processor = await AutoProcessor.from_pretrained('Xenova/whisper-tiny.en', {
        quantized: true,
        local_files_only: true
      })
      console.log('[useSpeechRecognition] Processor 加载完成')

      // 加载 Whisper 专用模型（Seq2Seq 架构）
      console.log('[useSpeechRecognition] 加载 Whisper Model...')
      model = await WhisperForConditionalGeneration.from_pretrained('Xenova/whisper-tiny.en', {
        quantized: true,
        local_files_only: true,
        progress_callback: (progress: number) => {
          loadingProgress.value = Math.round(progress * 100)
          console.log(`[useSpeechRecognition] 模型加载进度: ${Math.round(progress * 100)}%`)
        }
      })
      console.log('[useSpeechRecognition] Model 加载完成')

      isModelLoading.value = false
      return true
    } catch (err) {
      const errorMsg = (err as Error).message
      console.error('[useSpeechRecognition] 模型加载失败:', err)
      console.error('[useSpeechRecognition] 错误堆栈:', (err as Error).stack)
      
      // 检查是否是 ONNX 会话创建失败
      if (errorMsg.includes('session')) {
        console.error('[useSpeechRecognition] ONNX 会话创建失败，可能原因:')
        console.error('  1. 模型文件损坏或不兼容')
        console.error('  2. WASM 文件缺失')
        console.error('  3. 内存不足')
        console.error('  4. 浏览器安全限制')
      }
      
      error.value = '模型加载失败: ' + errorMsg
      isModelLoading.value = false
      options.onError?.(err as Error)
      return false
    }
  }

  // 预加载模型
  async function preloadModel(): Promise<void> {
    if (model && processor) return
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

  // 音频转录 - 使用 Seq2Seq 模型的 generate 方法
  async function transcribeAudio(audioBlob: Blob): Promise<string> {
    if (!model || !processor) throw new Error('模型未加载')

    // 解码音频
    audioContext = new (window.AudioContext || (window as any).webkitAudioContext)({
      sampleRate: 16000
    })

    const arrayBuffer = await audioBlob.arrayBuffer()
    const audioBuffer = await audioContext.decodeAudioData(arrayBuffer)
    
    // 转换为 Float32Array 并重采样到 16kHz
    const audioData = await audioBufferToFloat32Array(audioBuffer)
    const resampledData = await resampleAudio(audioData, audioBuffer.sampleRate, 16000)
    
    await audioContext.close()
    audioContext = null

    // 使用 processor 处理音频（提取 mel 频谱特征）
    console.log('[useSpeechRecognition] 处理音频特征...')
    const inputs = await processor(resampledData, {
      sampling_rate: 16000,
      return_tensors: 'pt'
    })

    // 使用模型的 generate 方法进行推理
    console.log('[useSpeechRecognition] 开始推理...')
    const outputs = await model.generate(inputs.input_features, {
      max_new_tokens: 128,
      language: 'chinese',
      task: 'transcribe'
    })

    // 解码输出 token
    console.log('[useSpeechRecognition] 解码结果...')
    const outputText = await processor.batch_decode(outputs, { skip_special_tokens: true })
    
    return outputText[0]?.trim() || ''
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