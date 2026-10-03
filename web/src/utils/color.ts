/**
 * Colour helpers.
 *
 * Groups carry an ARGB integer from the native side, because that is what Android stores.
 * The front end converts to CSS at render time.
 */

/** `0xff4c9dff` -> `'#4c9dff'`. */
export function argbToHex(argb: number): string {
  const rgb = argb & 0xffffff
  return '#' + rgb.toString(16).padStart(6, '0')
}

/** `0xff4c9dff` -> `'rgba(76, 157, 255, a)'`, for tints that respect the group colour. */
export function argbToRgba(argb: number, alpha: number): string {
  const r = (argb >> 16) & 0xff
  const g = (argb >> 8) & 0xff
  const b = argb & 0xff
  return `rgba(${r}, ${g}, ${b}, ${alpha})`
}

/**
 * The palette offered when creating a group. Kept here rather than in CSS because the value
 * round-trips to the database as an ARGB int.
 */
export const GROUP_PALETTE: { name: string; argb: number }[] = [
  { name: '蔚蓝', argb: 0xff4c9dff },
  { name: '青碧', argb: 0xff38d2c4 },
  { name: '苔绿', argb: 0xff4ecb71 },
  { name: '琥珀', argb: 0xffffb547 },
  { name: '珊瑚', argb: 0xffff7a6b },
  { name: '藕荷', argb: 0xffb48cff },
  { name: '玫红', argb: 0xffff6fa8 },
  { name: '石板', argb: 0xff8b97a8 },
]
