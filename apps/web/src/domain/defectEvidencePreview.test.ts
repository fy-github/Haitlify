import { describe, expect, it } from 'vitest'
import { resolveDefectScreenshotPreview } from './defectEvidencePreview'
const png = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aFe0AAAAASUVORK5CYII='

describe('defect-only raster preview trust policy', () => {
  it('accepts a raster dataURL without changing any source bytes', () => {
    expect(resolveDefectScreenshotPreview(png)).toStrictEqual({ src: png })
  })
  it.each([
    'javascript:alert(1)', 'vbscript:x', 'file:///Users/x.png', 'filesystem:https://x/a', 'blob:https://x/a',
    'data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=', 'data:text/html;base64,PHNjcmlwdD4=',
    'data:image/*;base64,AAAA', 'data:image/png;base64,AAAA', 'data:image/png;base64,',
    'data:image/png;base64,%%%=', 'data:image/png;base64,iVBORw0KGgo=',
    png.replace('base64,', 'base64,\n'), png.replace('data:', ' data:'), png.replace('data:', 'da\tta:'),
    'https://same-origin.invalid/screenshots/x.png', 'https://evil.invalid/track.png', '/screenshots/x.png',
    '/api/private.png', '../x.png', '//evil.invalid/x', '/Users/x.png', 'C:\\x.png', '\\\\host\\x.png', '', null, false, 0
  ])('rejects binding %s while leaving raw available', raw => {
    const output = resolveDefectScreenshotPreview(raw)
    expect(output.src).toBeUndefined(); expect(output.reason).toEqual(expect.any(String)); expect(Object.prototype.hasOwnProperty.call(output, 'src')).toBe(false)
  })
  it('accepts an actual Pillow-produced WebP without transformation', () => {
    const webp = 'data:image/webp;base64,UklGRjgAAABXRUJQVlA4ICwAAADQAQCdASoBAAEAAUAmJaACdLoB+AADsAD+8ql//JTX+NuWr/4s5GI7LwAAAA=='
    expect(resolveDefectScreenshotPreview(webp)).toStrictEqual({ src: webp })
    expect(resolveDefectScreenshotPreview(webp.replace('image/webp', 'image/jpeg')).src).toBeUndefined()
  })
  it('refuses MIME spoofed PNG containing SVG bytes', () => {
    expect(resolveDefectScreenshotPreview('data:image/png;base64,PHN2Zz48L3N2Zz4=').src).toBeUndefined()
  })
})
