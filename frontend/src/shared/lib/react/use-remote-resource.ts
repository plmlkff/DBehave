import { useEffect, useState } from 'react'

type Resource<T> = {
  key: string
  attempt: number
  data?: T
  error?: string
}

// load должен быть стабильным: функция из API-модуля либо useCallback.
export function useRemoteResource<T>(key: string, load: (signal: AbortSignal) => Promise<T>) {
  const [attempt, setAttempt] = useState(0)
  const [resource, setResource] = useState<Resource<T> | null>(null)

  useEffect(() => {
    const controller = new AbortController()

    load(controller.signal).then(
      data => {
        if (!controller.signal.aborted) setResource({ key, attempt, data })
      },
      error => {
        if (!controller.signal.aborted) {
          const message = error instanceof TypeError
            ? 'Не удалось связаться с сервером. Проверьте, что backend запущен.'
            : error instanceof Error ? error.message : 'Не удалось загрузить данные.'
          setResource({ key, attempt, error: message })
        }
      },
    )

    return () => controller.abort()
  }, [key, load, attempt])

  const current = resource?.key === key && resource.attempt === attempt ? resource : null

  return {
    data: current?.data,
    error: current?.error,
    loading: current === null,
    retry: () => setAttempt(value => value + 1),
  }
}
