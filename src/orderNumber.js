// Business numbers are display values; relationships and API requests keep the original id.
export const orderNumber = order => order?.orderNumber || order?.id || '';

export function orderNumberForId(id, orders = []) {
  return orderNumber(orders.find(order => order.id === id)) || id || '';
}

export function nextOrderNumber(orders, date = new Date()) {
  let maximum = 0n;
  for (const order of orders) {
    for (const value of [order.orderNumber, order.id]) {
      const match = /^(?:\d{4})-(?:\d{2})-(\d{8,})$/.exec(value ?? '');
      if (match && BigInt(match[1]) > maximum) maximum = BigInt(match[1]);
    }
  }
  const month = date.toLocaleDateString('sv-SE', {timeZone: 'Asia/Shanghai'}).slice(0, 7);
  return month + '-' + String(maximum + 1n).padStart(8, '0');
}
