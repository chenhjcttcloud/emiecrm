const fields = ['logisticsMode', 'logisticsCode', 'logisticsCompany'];
const control = /[\u0000-\u001f\u007f-\u009f]/;

export function validLogisticsCode(value) {
  return typeof value === 'string' && value.length > 0 && value.length <= 100
    && value.trim() === value && !control.test(value);
}

export function logisticsDraftValues(draft = {}, form) {
  const present = key => Boolean(form?.has?.(key) || form?.get?.(key) != null);
  if (!fields.some(key => present(key) || Object.hasOwn(draft, key))) return {};
  const read = key => String(present(key) ? form.get(key) ?? '' : draft[key] ?? '');
  const mode = read('logisticsMode') || 'WAREHOUSE';
  if (mode === 'WAREHOUSE') {
    if (present('logisticsMode')) return {};
    return Object.fromEntries(fields.filter(key => Object.hasOwn(draft, key)).map(key => [key, draft[key]]));
  }
  return {logisticsMode: mode, logisticsCode: read('logisticsCode'),logisticsCompany: read('logisticsCompany')};
}

export function logisticsDetails(draft, form) {
  const plan = logisticsDraftValues(draft, form);
  if (!Object.keys(plan).length) return plan;
  if (!['WAREHOUSE', 'CARRIER'].includes(plan.logisticsMode)) throw Error('请选择有效的物流安排');
  if (plan.logisticsMode === 'WAREHOUSE') return plan;
  if (!validLogisticsCode(plan.logisticsCode)) throw Error('请选择有效的物流公司');
  if (!plan.logisticsCompany.trim() || plan.logisticsCompany.length > 191 || control.test(plan.logisticsCompany))
    throw Error('物流公司名称无效，请重新选择');
  if ((form?.has?.('logisticsReady') || form?.get?.('logisticsReady') != null) && form.get('logisticsReady') !== 'true')
    throw Error('所选仓库的物流目录暂不可用，请重试或选择由仓库安排；可先保存草稿。');
  if (form?.get?.('logisticsChoiceValid') === 'false') throw Error('原选物流在当前仓库不可用，请重新选择或改为由仓库安排');
  return plan;
}

export function plannedLogistics(draft = {}) {
  if (!draft.logisticsMode || draft.logisticsMode === 'WAREHOUSE') return '由仓库安排';
  if (draft.logisticsMode !== 'CARRIER') return '物流安排待核对';
  return draft.logisticsCompany ? draft.logisticsCompany + (draft.logisticsCode ? '（编码 ' + draft.logisticsCode + '）' : '') : '指定物流公司待选择';
}

export function logisticsParcels(rows) {
  if (!Array.isArray(rows)) return [];
  return rows.filter(row => row && typeof row === 'object').map((row, index) => ({
    id: String(row.id ?? ''), company: String(row.company ?? ''), trackingNumber: String(row.trackingNumber ?? ''), key: String(row.id ?? '') + ':' + index,
  }));
}
