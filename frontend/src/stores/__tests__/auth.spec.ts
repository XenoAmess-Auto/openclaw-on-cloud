import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '../auth'

describe('Auth Store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
  })

  it('should initialize with null user and token', () => {
    const store = useAuthStore()
    
    expect(store.user).toBeNull()
    expect(store.token).toBeNull()
    expect(store.isAuthenticated).toBe(false)
    expect(store.loading).toBe(false)
  })

  it('should set authentication data', () => {
    const store = useAuthStore()
    const authData = {
      token: 'test-token',
      userId: 'user-123',
      username: 'testuser',
      email: 'test@example.com',
      avatar: undefined,
      roles: ['ROLE_USER']
    }

    store.setAuth(authData)

    expect(store.token).toBe('test-token')
    expect(store.user?.username).toBe('testuser')
    expect(store.user?.email).toBe('test@example.com')
    expect(store.isAuthenticated).toBe(true)
    expect(localStorage.getItem('token')).toBe('test-token')
  })

  it('should clear authentication data', () => {
    const store = useAuthStore()
    const authData = {
      token: 'test-token',
      userId: 'user-123',
      username: 'testuser',
      email: 'test@example.com',
      avatar: undefined,
      roles: ['ROLE_USER']
    }

    store.setAuth(authData)
    store.clearAuth()

    expect(store.token).toBeNull()
    expect(store.user).toBeNull()
    expect(store.isAuthenticated).toBe(false)
    expect(localStorage.getItem('token')).toBeNull()
  })

  it('should logout', () => {
    const store = useAuthStore()
    const authData = {
      token: 'test-token',
      userId: 'user-123',
      username: 'testuser',
      email: 'test@example.com',
      avatar: undefined,
      roles: ['ROLE_USER']
    }

    store.setAuth(authData)
    store.logout()

    expect(store.token).toBeNull()
    expect(store.user).toBeNull()
    expect(store.isAuthenticated).toBe(false)
  })
})
