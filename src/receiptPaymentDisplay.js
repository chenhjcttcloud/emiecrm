// Presentation joins only: financial allocation and validation belong to the frozen server batch.
export const hasReceiptPaymentDetails = record => Array.isArray(record?.paymentDetails) && record.paymentDetails.length > 0;
export const paymentLineLabel = lineNo => Number.isSafeInteger(lineNo) && lineNo > 0 ? `第${lineNo}笔` : '';

export function receiptPaymentRows(record) {
  if (!hasReceiptPaymentDetails(record)) return [];
  return record.paymentDetails.map(line => ({
    lineNo: line?.lineNo,
    method: typeof line?.method === 'string' ? line.method : '',
    amount: line?.amount ?? null,
    receiptDate: typeof line?.receiptDate === 'string' ? line.receiptDate : '',
  }));
}

export function allocationPaymentRows(record, allocation) {
  if (!hasReceiptPaymentDetails(record) || !Array.isArray(allocation?.paymentDetails)) return [];
  const details = new Map();
  for (const line of receiptPaymentRows(record)) {
    if (!paymentLineLabel(line.lineNo)) continue;
    details.set(line.lineNo, details.has(line.lineNo) ? null : line);
  }
  return allocation.paymentDetails.map(part => {
    const line = details.get(part?.lineNo);
    return {lineNo: part?.lineNo, method: line?.method || '', amount: part?.amount ?? null, receiptDate: line?.receiptDate || ''};
  });
}
