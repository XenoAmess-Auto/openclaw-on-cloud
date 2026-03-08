<script setup lang="ts">
import type { Person, Trait, PregnancyTrait } from '@/types'
import { computed } from 'vue'

const props = defineProps<{
  person: Person
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'edit', person: Person): void
  (e: 'die', person: Person): void
  (e: 'add-trait', person: Person): void
  (e: 'remove-trait', person: Person, traitId: string): void
}>()

// 使用emit避免未使用警告
const handleClose = () => emit('close')
const handleEdit = () => emit('edit', props.person)
const handleDie = () => emit('die', props.person)
const handleAddTrait = () => emit('add-trait', props.person)
const handleRemoveTrait = (traitId: string) => emit('remove-trait', props.person, traitId)

const genderText = computed(() => {
  const map: Record<string, string> = {
    MALE: '男性',
    FEMALE: '女性',
    UNKNOWN: '未知'
  }
  return map[props.person.gender] || '未知'
})

const statusText = computed(() => {
  return props.person.isAlive ? '存活' : '已故'
})

const statusClass = computed(() => {
  return props.person.isAlive ? 'alive' : 'dead'
})

const activeTraits = computed(() => {
  return props.person.traits?.filter(t => !t.expiresAt || new Date(t.expiresAt) > new Date()) || []
})

const clearedOnDeathTraits = computed(() => {
  return activeTraits.value.filter(t => t.clearedOnDeath)
})

const persistentTraits = computed(() => {
  return activeTraits.value.filter(t => !t.clearedOnDeath)
})

function isPregnancyTrait(trait: Trait): trait is PregnancyTrait {
  return trait.type === 'PREGNANCY'
}

function getStageText(stage: string): string {
  const map: Record<string, string> = {
    EARLY: '孕早期',
    MIDDLE: '孕中期',
    LATE: '孕晚期'
  }
  return map[stage] || stage
}

function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleDateString('zh-CN')
}

function getDaysUntil(dateStr: string): number {
  const due = new Date(dateStr)
  const now = new Date()
  const diff = due.getTime() - now.getTime()
  return Math.ceil(diff / (1000 * 60 * 60 * 24))
}

function getTraitIcon(type: string): string {
  const icons: Record<string, string> = {
    PREGNANCY: '🤰',
    default: '✨'
  }
  return icons[type] || icons.default
}

function getTraitClass(type: string): string {
  const classes: Record<string, string> = {
    PREGNANCY: 'trait-pregnancy'
  }
  return classes[type] || 'trait-default'
}
</script>

<template>
  <div class="person-detail-overlay" @click.self="handleClose">
    <div class="person-detail-panel">
      <!-- 头部 -->
      <div class="detail-header">
        <div class="avatar-section">
          <img 
            :src="person.avatar || '/default-avatar.png'" 
            :alt="person.displayName"
            class="person-avatar"
          />
          <span class="status-badge" :class="statusClass">
            {{ statusText }}
          </span>
        </div>
        
        <div class="basic-info">
          <h2 class="person-name">{{ person.displayName }}</h2>
          <div class="meta-info">
            <span class="meta-item">
              <span class="label">性别：</span>
              <span class="value">{{ genderText }}</span>
            </span>
            <span class="meta-item">
              <span class="label">年龄：</span>
              <span class="value">{{ person.age }} 岁</span>
            </span>
          </div>
          
          <div v-if="!person.isAlive && person.deathReason" class="death-info">
            <span class="death-label">死因：</span>
            <span class="death-reason">{{ person.deathReason }}</span>
          </div>
        </div>
        
        <button class="close-btn" @click="handleClose"></button>
      </div>
      
      <!-- 特质列表 -->
      <div class="traits-section">
        <div class="section-header">
          <h3>🎭 特质与状态</h3>
          <button class="add-btn" @click="handleAddTrait">
            + 添加特质
          </button>
        </div>
        
        <div v-if="activeTraits.length === 0" class="empty-traits">
          暂无特质
        </div>
        
        <div v-else class="traits-content">
          <!-- 临时特质（死亡清除） -->
          <div v-if="clearedOnDeathTraits.length > 0" class="trait-group">
            <h4 class="group-title">
              <span class="group-icon">⏳</span>
              当前状态（死亡时清除）
            </h4>            
            <div class="trait-list">
              <div 
                v-for="trait in clearedOnDeathTraits" 
                :key="trait.id"
                class="trait-card"
                :class="getTraitClass(trait.type)"
              >
                <div class="trait-header">
                  <span class="trait-icon">{{ getTraitIcon(trait.type) }}</span>
                  <span class="trait-name">{{ trait.name }}</span>
                  <button 
                    class="remove-btn"
                    @click="$emit('remove-trait', person, trait.id)"
                    title="移除特质"
                  >
                    ×
                  </button>
                </div>
                
                <div class="trait-desc">{{ trait.description }}</div>
                
                <!-- 怀孕特质的特殊展示 -->
                <div v-if="isPregnancyTrait(trait)" class="pregnancy-detail">
                  <div class="pregnancy-info">
                    <div class="info-row">
                      <span class="info-label">阶段：</span>
                      <span class="stage-badge" :class="trait.stage?.toLowerCase()">
                        {{ getStageText(trait.stage || '') }}
                      </span>
                    </div>
                    <div v-if="trait.fatherName" class="info-row">
                      <span class="info-label">父亲：</span>
                      <span class="info-value">{{ trait.fatherName }}</span>
                    </div>
                    <div class="info-row">
                      <span class="info-label">预计分娩：</span>
                      <span class="info-value">{{ formatDate(trait.dueDate || '') }}</span>
                      <span v-if="trait.dueDate" class="days-left">（还有 {{ getDaysUntil(trait.dueDate) }} 天）</span>
                    </div>
                    
                    <div v-if="trait.dueDate" class="pregnancy-progress">
                      <div 
                        class="progress-bar"
                        :style="{ width: Math.max(0, Math.min(100, (270 - getDaysUntil(trait.dueDate)) / 270 * 100)) + '%' }"
                      ></div>
                    </div>
                  </div>
                </div>
                
                <div class="trait-meta">
                  <span class="trait-date">获得：{{ formatDate(trait.createdAt) }}</span>
                  <span v-if="trait.expiresAt" class="trait-expires">
                    过期：{{ formatDate(trait.expiresAt) }}
                  </span>
                </div>
              </div>
            </div>
          </div>
          
          <!-- 永久特质（死亡保留） -->
          <div v-if="persistentTraits.length > 0" class="trait-group">
            <h4 class="group-title">
              <span class="group-icon">💎</span>
              永久特质（死亡后保留）
            </h4>
            
            <div class="trait-list">
              <div 
                v-for="trait in persistentTraits" 
                :key="trait.id"
                class="trait-card persistent"
              >
                <div class="trait-header">
                  <span class="trait-icon">{{ getTraitIcon(trait.type) }}</span>
                  <span class="trait-name">{{ trait.name }}</span>
                  <button 
                    class="remove-btn"
                    @click="handleRemoveTrait(trait.id)"
                    title="移除特质"
                  >
                    ×
                  </button>
                </div>
                
                <div class="trait-desc">{{ trait.description }}</div>
                
                <div class="trait-meta">
                  <span class="trait-date">获得：{{ formatDate(trait.createdAt) }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
      
      <!-- 底部操作栏 -->
      <div class="action-bar">
        <button class="btn-secondary" @click="handleEdit">
          ✏️ 编辑
        </button>
        
        <button 
          v-if="person.isAlive"
          class="btn-danger"
          @click="handleDie"
        >
          💀 死亡
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.person-detail-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.7);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 20px;
}

.person-detail-panel {
  background: #1a1a2e;
  border-radius: 16px;
  width: 100%;
  max-width: 600px;
  max-height: 90vh;
  overflow-y: auto;
  color: #fff;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.5);
}

/* 头部 */
.detail-header {
  display: flex;
  gap: 20px;
  padding: 24px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
  position: relative;
}

.avatar-section {
  position: relative;
  flex-shrink: 0;
}

.person-avatar {
  width: 100px;
  height: 100px;
  border-radius: 50%;
  object-fit: cover;
  border: 3px solid rgba(255, 255, 255, 0.2);
}

.status-badge {
  position: absolute;
  bottom: 5px;
  right: 5px;
  padding: 4px 10px;
  border-radius: 12px;
  font-size: 12px;
  font-weight: bold;
}

.status-badge.alive {
  background: #4caf50;
}

.status-badge.dead {
  background: #f44336;
}

.basic-info {
  flex: 1;
}

.person-name {
  font-size: 24px;
  font-weight: bold;
  margin: 0 0 12px 0;
  color: #fff;
}

.meta-info {
  display: flex;
  gap: 20px;
  margin-bottom: 10px;
}

.meta-item .label {
  color: rgba(255, 255, 255, 0.5);
}

.meta-item .value {
  color: #fff;
  font-weight: 500;
}

.death-info {
  margin-top: 10px;
  padding: 8px 12px;
  background: rgba(244, 67, 54, 0.1);
  border-radius: 8px;
  border-left: 3px solid #f44336;
}

.death-label {
  color: rgba(255, 255, 255, 0.5);
}

.death-reason {
  color: #f44336;
}

.close-btn {
  position: absolute;
  top: 16px;
  right: 16px;
  width: 32px;
  height: 32px;
  border: none;
  background: rgba(255, 255, 255, 0.1);
  border-radius: 50%;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.2s;
}

.close-btn:hover {
  background: rgba(255, 255, 255, 0.2);
}

.close-btn::before,
.close-btn::after {
  content: '';
  position: absolute;
  width: 16px;
  height: 2px;
  background: #fff;
}

.close-btn::before {
  transform: rotate(45deg);
}

.close-btn::after {
  transform: rotate(-45deg);
}

/* 特质区域 */
.traits-section {
  padding: 24px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.section-header h3 {
  margin: 0;
  font-size: 18px;
  color: #fff;
}

.add-btn {
  padding: 6px 14px;
  background: #4caf50;
  border: none;
  border-radius: 6px;
  color: #fff;
  font-size: 14px;
  cursor: pointer;
  transition: background 0.2s;
}

.add-btn:hover {
  background: #45a049;
}

.empty-traits {
  text-align: center;
  padding: 40px;
  color: rgba(255, 255, 255, 0.4);
  font-style: italic;
}

.trait-group {
  margin-bottom: 20px;
}

.trait-group:last-child {
  margin-bottom: 0;
}

.group-title {
  font-size: 14px;
  color: rgba(255, 255, 255, 0.6);
  margin: 0 0 12px 0;
  display: flex;
  align-items: center;
  gap: 6px;
}

.group-icon {
  font-size: 16px;
}

.trait-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.trait-card {
  background: rgba(255, 255, 255, 0.05);
  border-radius: 12px;
  padding: 16px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  transition: transform 0.2s, box-shadow 0.2s;
}

.trait-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
}

.trait-card.persistent {
  border-left: 4px solid #9c27b0;
}

.trait-card.trait-pregnancy {
  background: linear-gradient(135deg, rgba(233, 30, 99, 0.1), rgba(156, 39, 176, 0.1));
  border-color: rgba(233, 30, 99, 0.3);
}

.trait-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.trait-icon {
  font-size: 20px;
}

.trait-name {
  font-weight: bold;
  font-size: 16px;
  flex: 1;
}

.remove-btn {
  width: 24px;
  height: 24px;
  border: none;
  background: rgba(255, 255, 255, 0.1);
  border-radius: 50%;
  color: rgba(255, 255, 255, 0.5);
  font-size: 16px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  line-height: 1;
  transition: all 0.2s;
}

.remove-btn:hover {
  background: #f44336;
  color: #fff;
}

.trait-desc {
  color: rgba(255, 255, 255, 0.7);
  font-size: 14px;
  margin-bottom: 12px;
  line-height: 1.5;
}

.trait-meta {
  display: flex;
  gap: 16px;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.4);
}

/* 怀孕详情 */
.pregnancy-detail {
  background: rgba(0, 0, 0, 0.2);
  border-radius: 8px;
  padding: 12px;
  margin: 12px 0;
}

.pregnancy-info {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.info-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.info-label {
  color: rgba(255, 255, 255, 0.5);
  font-size: 13px;
}

.info-value {
  color: #fff;
  font-weight: 500;
}

.stage-badge {
  padding: 2px 10px;
  border-radius: 10px;
  font-size: 12px;
  font-weight: bold;
}

.stage-badge.early {
  background: #4caf50;
  color: #fff;
}

.stage-badge.middle {
  background: #ff9800;
  color: #fff;
}

.stage-badge.late {
  background: #f44336;
  color: #fff;
}

.days-left {
  color: #e91e63;
  font-size: 12px;
}

.pregnancy-progress {
  height: 6px;
  background: rgba(255, 255, 255, 0.1);
  border-radius: 3px;
  overflow: hidden;
  margin-top: 8px;
}

.progress-bar {
  height: 100%;
  background: linear-gradient(90deg, #e91e63, #9c27b0);
  border-radius: 3px;
  transition: width 0.3s ease;
}

/* 底部操作栏 */
.action-bar {
  padding: 20px 24px;
  display: flex;
  gap: 12px;
  justify-content: flex-end;
}

.btn-secondary {
  padding: 10px 20px;
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 8px;
  color: #fff;
  font-size: 14px;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-secondary:hover {
  background: rgba(255, 255, 255, 0.2);
}

.btn-danger {
  padding: 10px 20px;
  background: #f44336;
  border: none;
  border-radius: 8px;
  color: #fff;
  font-size: 14px;
  cursor: pointer;
  transition: background 0.2s;
}

.btn-danger:hover {
  background: #d32f2f;
}

/* 滚动条样式 */
.person-detail-panel::-webkit-scrollbar {
  width: 8px;
}

.person-detail-panel::-webkit-scrollbar-track {
  background: rgba(255, 255, 255, 0.05);
  border-radius: 4px;
}

.person-detail-panel::-webkit-scrollbar-thumb {
  background: rgba(255, 255, 255, 0.2);
  border-radius: 4px;
}

.person-detail-panel::-webkit-scrollbar-thumb:hover {
  background: rgba(255, 255, 255, 0.3);
}

@media (max-width: 600px) {
  .person-detail-panel {
    max-width: 100%;
    border-radius: 0;
    max-height: 100vh;
  }
  
  .detail-header {
    flex-direction: column;
    align-items: center;
    text-align: center;
  }
  
  .meta-info {
    justify-content: center;
  }
}
</style>
