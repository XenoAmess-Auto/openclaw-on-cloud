import type { Person, CreatePersonRequest, SetPregnancyRequest } from '@/types'
import apiClient from './client'

export const personApi = {
  /**
   * 获取所有人物
   */
  getAll(): Promise<Person[]> {
    return apiClient.get('/persons')
  },

  /**
   * 获取所有存活人物
   */
  getAlive(): Promise<Person[]> {
    return apiClient.get('/persons/alive')
  },

  /**
   * 根据ID获取人物
   */
  getById(id: string): Promise<Person> {
    return apiClient.get(`/persons/${id}`)
  },

  /**
   * 创建人物
   */
  create(data: CreatePersonRequest): Promise<Person> {
    return apiClient.post('/persons', data)
  },

  /**
   * 更新人物
   */
  update(id: string, data: Partial<Person>): Promise<Person> {
    return apiClient.put(`/persons/${id}`, data)
  },

  /**
   * 删除人物
   */
  delete(id: string): Promise<void> {
    return apiClient.delete(`/persons/${id}`)
  },

  /**
   * 设置人物死亡
   */
  die(id: string, reason: string): Promise<Person> {
    return apiClient.post(`/persons/${id}/die`, { reason })
  },

  /**
   * 设置怀孕状态
   */
  setPregnant(id: string, data: SetPregnancyRequest): Promise<Person> {
    return apiClient.post(`/persons/${id}/pregnant`, data)
  },

  /**
   * 移除怀孕状态
   */
  removePregnancy(id: string): Promise<Person> {
    return apiClient.delete(`/persons/${id}/pregnant`)
  },

  /**
   * 清理过期特质
   */
  cleanupTraits(id: string): Promise<void> {
    return apiClient.post(`/persons/${id}/cleanup-traits`)
  }
}
