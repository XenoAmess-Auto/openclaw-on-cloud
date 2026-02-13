import apiClient from './client'
import type { FileUploadResponse } from '@/types'

export const fileApi = {
  upload: (file: File) => {
    const formData = new FormData()
    formData.append('file', file)
    return apiClient.post<FileUploadResponse>('/files/upload', formData, {
      headers: {
        'Content-Type': 'multipart/form-data'
      }
    })
  }
}
