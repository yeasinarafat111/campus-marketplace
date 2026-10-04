export const formatPrice = (price: number) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(price)

export const formatCategory = (c: string) => c.charAt(0) + c.slice(1).toLowerCase()
