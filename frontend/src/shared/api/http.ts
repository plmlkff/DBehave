const apiBaseUrl = (import.meta.env.VITE_API_BASE_URL || '/api').replace(/\/+$/, '')

export class ApiError extends Error {
  readonly status: number
  readonly body: unknown

  constructor(status: number, body: unknown) {
    super(`Ошибка API: ${status}`)
    this.name = 'ApiError'
    this.status = status
    this.body = body
  }
}

export async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers)
  headers.set('Accept', 'application/json')
  if (options.body) headers.set('Content-Type', 'application/json')

  const response = await fetch(`${apiBaseUrl}${path}`, { ...options, headers })
  const text = await response.text()
  let body: unknown

  try {
    body = text ? JSON.parse(text) : null
  } catch {
    if (!response.ok) throw new ApiError(response.status, null)
    throw new Error('Сервер вернул ответ в неожиданном формате. Проверьте адрес API.')
  }

  if (!response.ok) throw new ApiError(response.status, body)
  return body as T
}
