import { JSEncrypt } from 'jsencrypt'
import apiClient from './client'

let publicKey: string | null = null

export async function getPublicKey(): Promise<string> {
  // 每次获取新的公钥，避免缓存问题
  const response = await apiClient.get('/auth/public-key')
  publicKey = response.data.publicKey
  return publicKey!
}

export function encryptWithPublicKey(data: string, key: string): string {
  const encryptor = new JSEncrypt()
  encryptor.setPublicKey(key)
  const encrypted = encryptor.encrypt(data)
  if (!encrypted) {
    throw new Error('Encryption failed')
  }
  return encrypted
}

export function clearPublicKeyCache() {
  publicKey = null
}
