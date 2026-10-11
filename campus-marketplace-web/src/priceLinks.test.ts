import { describe, expect, it } from 'vitest'
import { priceCheckLinks, priceCheckQuery, roundedBdt } from './priceLinks'

const base = { productName: 'Samsung Galaxy A54 8/128GB', title: 'Phone for sale', priceUsd: 45, condition: 'Good', bdtPerUsd: 123 }

describe('roundedBdt', () => {
  it('rounds to the nearest 100', () => {
    expect(roundedBdt(45, 123)).toBe(5500) // 5535
    expect(roundedBdt(4.5, 123)).toBe(600) // 553.5
    expect(roundedBdt(10, 125)).toBe(1300) // 1250 rounds half up
    expect(roundedBdt(0, 123)).toBe(0)
  })
})

describe('priceCheckQuery', () => {
  it('names the product, the taka and dollar price, and the condition', () => {
    expect(priceCheckQuery(base)).toBe(
      'Samsung Galaxy A54 8/128GB used price in Bangladesh. Is ৳5,500 (about $45) a good deal for a good one?',
    )
  })

  it('falls back to the title, and to "used" without a condition', () => {
    expect(priceCheckQuery({ ...base, productName: '  ', condition: null, priceUsd: 12.5 })).toBe(
      'Phone for sale used price in Bangladesh. Is ৳1,500 (about $12.50) a good deal for a used one?',
    )
    expect(priceCheckQuery({ ...base, productName: null })).toContain('Phone for sale used price')
  })
})

describe('priceCheckLinks', () => {
  it('encodes every part of the URLs', () => {
    const [google, bikroy, daraz] = priceCheckLinks({ ...base, productName: 'Casio fx-991ES & co. #1 50%/new?' })
    expect(google.href.startsWith('https://www.google.com/search?udm=50&q=')).toBe(true)
    const q = new URL(google.href).searchParams
    expect(q.get('udm')).toBe('50')
    expect(q.get('q')).toBe('Casio fx-991ES & co. #1 50%/new? used price in Bangladesh. Is ৳5,500 (about $45) a good deal for a good one?')
    expect(bikroy.href).toBe('https://bikroy.com/search?query=Casio%20fx-991ES%20%26%20co.%20%231%2050%25%2Fnew%3F')
    expect(new URL(daraz.href).searchParams.get('q')).toBe('Casio fx-991ES & co. #1 50%/new?')
    expect(new URL(daraz.href).pathname).toBe('/catalog/')
    expect(google.href).not.toContain(' ')
    expect(google.href).not.toContain('৳')
  })

  it('uses the title when the assistant could not name the product', () => {
    const links = priceCheckLinks({ ...base, productName: undefined })
    expect(links.map((l) => l.label)).toEqual(['Ask Google AI Mode', 'Search Bikroy', 'Search Daraz'])
    expect(links[1].href).toBe('https://bikroy.com/search?query=Phone%20for%20sale')
  })
})
