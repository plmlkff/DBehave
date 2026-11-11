export function grafanaEmbedUrl(value: string) {
  try {
    const url = new URL(value)
    url.searchParams.set('kiosk', '1')
    url.searchParams.set('refresh', '1s')
    return url.toString()
  } catch {
    return null
  }
}
