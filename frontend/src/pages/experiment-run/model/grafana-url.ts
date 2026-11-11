export function grafanaEmbedUrl(value: string) {
  try {
    const url = new URL(value)
    url.searchParams.set('kiosk', '1')
    return url.toString()
  } catch {
    return null
  }
}
