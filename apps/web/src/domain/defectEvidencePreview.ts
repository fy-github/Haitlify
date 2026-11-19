/** Defect surface only: no approved static directory exists, so never request a path/URL.
 * Signature checks do not promise successful image decode; consumers must handle img error/load.
 * Source is never rewritten: rejected data remains available as raw evidence/export.
 */
export const resolveDefectScreenshotPreview = (source: unknown): { src?: string; reason?: string } => {
  if (typeof source !== 'string' || !source) return { reason: '截图引用为空或无效，原始证据已保留' }
  const match = /^data:image\/(png|jpeg|webp);base64,((?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?)$/.exec(source)
  if (!match || !match[2]) return { reason: '仅允许 PNG/JPEG/WebP dataURL；路径目录未获批准，原始引用已保留' }
  let bytes: string
  try {
    bytes = atob(match[2])
    if (btoa(bytes) !== match[2]) return { reason: '截图 base64 无效，原始证据已保留' }
  } catch { return { reason: '截图无法解码，原始证据已保留' } }
  const has = (offset: number, signature: number[]) => signature.every((byte, i) => bytes.charCodeAt(offset + i) === byte)
  const png = match[1] === 'png' && bytes.length >= 33 && has(0, [137, 80, 78, 71, 13, 10, 26, 10]) && bytes.slice(12, 16) === 'IHDR'
  const jpeg = match[1] === 'jpeg' && bytes.length >= 4 && has(0, [255, 216, 255]) && has(bytes.length - 2, [255, 217])
  const webp = match[1] === 'webp' && bytes.length >= 20 && bytes.slice(0, 4) === 'RIFF' && bytes.slice(8, 12) === 'WEBP' && ['VP8 ', 'VP8L', 'VP8X'].includes(bytes.slice(12, 16))
  if (!png && !jpeg && !webp) return { reason: '截图类型或文件签名无效，原始证据已保留' }
  return { src: source }
}
