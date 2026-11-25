// Business numbers are display values; relationships and API requests keep the original id.
export const orderNumber = order => order?.orderNumber || order?.id || '';

export function orderNumberForId(id, orders = []) {
  return orderNumber(orders.find(order => order.id === id)) || id || '';
}

function validReservedNumber(value) {
  if (typeof value !== 'string' || value.length > 191) return false;
  const match = /^N(\d{2})(\d{2})(\d{2})(\d{4,})$/.exec(value);
  if (!match || BigInt(match[4]) === 0n) return false;
  const [year, month, day] = match.slice(1, 4).map(Number);
  const date = new Date(Date.UTC(2000 + year, month - 1, day));
  return date.getUTCFullYear() === 2000 + year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day;
}

// Keep this attempt for the lifetime of one form. The server owns the date,
// sequence and unused reservations; retrying never creates another number.
export async function reserveOrderNumber(api, attempt) {
  if (attempt.orderNumber) return attempt.orderNumber;
  try {
    const result = await api('orders/number-reservations', {requestId: attempt.requestId});
    if (!validReservedNumber(result?.orderNumber))
      throw Error('服务器返回的订单编号格式无效');
    attempt.orderNumber = result.orderNumber;
    return attempt.orderNumber;
  } catch (error) {
    throw Error('无法取得订单编号：' + error.message + '。请在当前表单重试，系统会沿用本次编号预留请求。', {cause: error});
  }
}
