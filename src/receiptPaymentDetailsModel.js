import {moneyCents} from './orderMoney.js';

export const MAX_RECEIPT_PAYMENT_DETAILS = 50;
export const receiptToday = (date = new Date()) => date.toLocaleDateString('sv-SE', {timeZone:'Asia/Shanghai'});

export function validReceiptDate(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value || '')) return false;
  const date = new Date(value + 'T00:00:00Z');
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0,10) === value;
}

// A legacy frozen request is only projected for display; callers retry its original body.
export function receiptPaymentDetailsForDisplay(receipt) {
  const rows = Array.isArray(receipt?.paymentDetails) ? receipt.paymentDetails : receipt ? [receipt] : [];
  return rows.map((row, index) => ({lineNo:index + 1, method:row.method || '',
    amount:String(row.amount ?? ''), receiptDate:row.receiptDate || ''}));
}

export function receiptPaymentDetailTotals(rows) {
  let totalCents = 0, writeOffCents = 0;
  let valid = Array.isArray(rows) && rows.length > 0 && rows.length <= MAX_RECEIPT_PAYMENT_DETAILS;
  for (const row of rows || []) {
    const cents = moneyCents(row.amount);
    if (cents === null || cents <= 0) valid = false;
    else {
      totalCents += cents;
      if (row.method === '预收款核销') writeOffCents += cents;
    }
  }
  if (!Number.isSafeInteger(totalCents) || totalCents > 99999999999999) valid = false;
  return {totalCents, writeOffCents, valid};
}

export function canonicalReceiptPaymentDetails(rows) {
  if (!Array.isArray(rows) || !rows.length || rows.length > MAX_RECEIPT_PAYMENT_DETAILS)
    throw Error('收款明细须为1至50行');
  const paymentDetails = rows.map((row, index) => {
    const method = typeof row?.method === 'string' ? row.method : '';
    if (!method.trim() || method === '组合收款' || method.length > 50 || /[\u0000-\u001f\u007f-\u009f]/.test(method))
      throw Error('请选择第' + (index + 1) + '行有效的收款方式');
    const cents = moneyCents(row?.amount);
    if (cents === null || cents <= 0) throw Error('第' + (index + 1) + '行收款金额须大于零且最多两位小数');
    if (!validReceiptDate(row?.receiptDate)) throw Error('请选择第' + (index + 1) + '行有效的到账 / 核销日期');
    return {lineNo:index + 1, method, amount:cents / 100, receiptDate:row.receiptDate};
  });
  const totals = receiptPaymentDetailTotals(paymentDetails);
  if (!totals.valid) throw Error('收款明细合计金额超出范围');
  const methods = new Set(paymentDetails.map(row => row.method));
  return {paymentDetails, amount:totals.totalCents / 100,
    method:methods.size === 1 ? paymentDetails[0].method : '组合收款',
    receiptDate:paymentDetails.reduce((latest,row) => row.receiptDate > latest ? row.receiptDate : latest, ''),
    ...totals};
}

// Match each payment row to the selected order allocations in their displayed order.
// The server recomputes and freezes the authoritative matrix independently.
export function allocateReceiptPaymentDetails(paymentDetails, allocations) {
  const canonical = canonicalReceiptPaymentDetails(paymentDetails);
  const due = allocations.map(row => ({orderId:row.orderId, cents:moneyCents(row.amount)}));
  if (!due.length || due.some(row => !row.orderId || row.cents === null || row.cents <= 0)
      || due.reduce((sum,row) => sum + row.cents, 0) !== canonical.totalCents)
    throw Error('收款明细合计必须与所选订单本次付款合计一致');
  const result = [];
  let orderIndex = 0;
  for (const row of canonical.paymentDetails) {
    let remaining = moneyCents(row.amount);
    while (remaining > 0) {
      const order = due[orderIndex], cents = Math.min(remaining, order.cents);
      result.push({...row, orderId:order.orderId, amount:cents / 100});
      remaining -= cents; order.cents -= cents;
      if (!order.cents) orderIndex++;
    }
  }
  return result;
}
