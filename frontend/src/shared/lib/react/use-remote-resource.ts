import { useEffect, useState } from 'react'
import { ApiError } from '@/shared/api'

type Resource<T> = {
  key: string
  attempt: number
  data?: T
  error?: string
}

type Polling<T> = {
  intervalMs: number
  shouldPoll: (data: T) => boolean
}

// load и shouldPoll должны быть стабильными: функции модуля либо useCallback.
export function useRemoteResource<T>(key: string, load: (signal: AbortSignal) => Promise<T>, polling?: Polling<T>) {
  const [attempt, setAttempt] = useState(0)
  const [resource, setResource] = useState<Resource<T> | null>(null)
  const intervalMs = polling?.intervalMs ?? 0
  const shouldPoll = polling?.shouldPoll

  useEffect(() => {
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout> | undefined

    async function refresh() {
      try {
        const data = await load(controller.signal)
        if (controller.signal.aborted) return
        setResource({ key, attempt, data })
        if (intervalMs > 0 && shouldPoll?.(data)) {
          timer = setTimeout(refresh, intervalMs)
        }
      } catch (error) {
        if (controller.signal.aborted) return
        const message = error instanceof TypeError
          ? 'Не удалось связаться с сервером. Проверьте, что backend запущен.'
          : error instanceof Error ? error.message : 'Не удалось загрузить данные.'
        setResource(current => ({
          key,
          attempt,
          data: current?.key === key && current.attempt === attempt ? current.data : undefined,
          error: message,
        }))
        const permanent = error instanceof ApiError && error.status >= 400 && error.status < 500 && error.status !== 429
        if (intervalMs > 0 && !permanent) {
          timer = setTimeout(refresh, Math.max(intervalMs, 5000))
        }
      }
    }

    void refresh()
    return () => {
      controller.abort()
      clearTimeout(timer)
    }
  }, [key, load, attempt, intervalMs, shouldPoll])

  const current = resource?.key === key && resource.attempt === attempt ? resource : null

  return {
    data: current?.data,
    error: current?.error,
    loading: current === null,
    retry: () => setAttempt(value => value + 1),
  }
}
