/**
 * 显示悬浮提示
 * @param message 提示消息
 * @param duration 显示时长(毫秒)，默认 3000ms
 * @param type 提示类型: 'success' | 'info' | 'warning' | 'error'，默认 'success'
 */
export function showToast(
  message: string,
  duration: number = 3000,
  type: 'success' | 'info' | 'warning' | 'error' = 'success'
) {
  // 创建提示元素
  const toast = document.createElement('div')
  toast.className = `ooc-toast ooc-toast-${type}`
  toast.textContent = message
  
  // 设置样式
  toast.style.cssText = `
    position: fixed;
    top: 20px;
    left: 50%;
    transform: translateX(-50%) translateY(-100px);
    background: ${type === 'success' ? '#10b981' : type === 'error' ? '#ef4444' : type === 'warning' ? '#f59e0b' : '#3b82f6'};
    color: white;
    padding: 12px 24px;
    border-radius: 8px;
    font-size: 14px;
    font-weight: 500;
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
    z-index: 10000;
    transition: transform 0.3s ease, opacity 0.3s ease;
    opacity: 0;
    pointer-events: none;
    max-width: 80%;
    text-align: center;
    word-wrap: break-word;
  `
  
  document.body.appendChild(toast)
  
  // 触发动画显示
  requestAnimationFrame(() => {
    toast.style.transform = 'translateX(-50%) translateY(0)'
    toast.style.opacity = '1'
  })
  
  // 指定时间后移除
  setTimeout(() => {
    toast.style.transform = 'translateX(-50%) translateY(-100px)'
    toast.style.opacity = '0'
    setTimeout(() => {
      if (toast.parentNode) {
        document.body.removeChild(toast)
      }
    }, 300)
  }, duration)
}
