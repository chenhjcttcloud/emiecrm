export function enabledPaymentMethodNames(rows) {
  if (!Array.isArray(rows)) throw Error('收款方式配置格式无效，请重试');
  const ordered = rows.map((row, index) => ({row, index})).filter(({row}) => row?.status === '启用');
  const sort = value => Number.isFinite(Number(value)) && Number(value) > 0 ? Number(value) : Infinity;
  ordered.sort((a, b) => sort(a.row.sortOrder) - sort(b.row.sortOrder) || a.index - b.index);
  const names = [];
  for (const {row} of ordered) {
    if (typeof row.name !== 'string' || !row.name.trim() || row.name.length > 50 || /[\u0000-\u001f\u007f-\u009f]/.test(row.name))
      throw Error('收款方式配置名称无效，请联系管理员');
    if (row.name !== '预收款核销' && !names.includes(row.name)) names.push(row.name);
  }
  return names;
}

export function receiptMethodChoices(methods, original, locked) {
  return locked && original && !methods.includes(original) ? [original, ...methods] : methods;
}
