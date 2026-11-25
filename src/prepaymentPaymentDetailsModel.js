import {canonicalReceiptPaymentDetails, receiptToday} from './receiptPaymentDetailsModel.js';

// Legacy prepayments are projected for display only. An approval retry keeps its
// original order and request, including the absence of paymentDetails.
export function prepaymentPaymentDetailsForDisplay(order) {
  if (!order) return [];
  const rows = Object.hasOwn(order, 'paymentDetails')
    ? Array.isArray(order.paymentDetails) ? order.paymentDetails : []
    : [{method:order.method, amount:order.total, receiptDate:order.approvalDraft?.receiptDate ?? order.receiptDate}];
  return rows.map((row, index) => ({lineNo:index + 1,
    method:typeof row?.method === 'string' ? row.method : '',
    amount:row?.amount == null ? '' : String(row.amount),
    receiptDate:typeof row?.receiptDate === 'string' ? row.receiptDate : ''}));
}

export function prepaymentDetailsForDraft(order, today = receiptToday()) {
  const rows = prepaymentPaymentDetailsForDisplay(order);
  if (order && Object.hasOwn(order, 'paymentDetails')) return rows;
  return (rows.length ? rows : [{lineNo:1, method:'', amount:'', receiptDate:''}])
    .map(row => ({...row, receiptDate:row.receiptDate || today}));
}

export function canonicalPrepaymentPaymentDetails(rows) {
  if (Array.isArray(rows)) rows.forEach((row, index) => {
    if (row?.method === '预收款核销') throw Error('第' + (index + 1) + '行预收款不能使用预收款核销');
  });
  const canonical = canonicalReceiptPaymentDetails(rows);
  return {...canonical, total:canonical.amount};
}
