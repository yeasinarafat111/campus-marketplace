import type { LedgerType } from '../api/revenue'

/**
 * One series per revenue source. The first three slots of the validated categorical palette (blue, orange, aqua),
 * which pass the colour-blind and contrast checks as a set; the aqua is under 3:1 on white, so every figure is also
 * written out in text (legend, tiles, tooltip and the table view). Text never takes the series colour.
 */
export const SERIES_COLORS: Record<LedgerType, string> = {
  POSTING_FEE: '#2a78d6',
  FEATURE_FEE: '#eb6834',
  FORFEITED_DEPOSIT: '#1baf7a',
}
