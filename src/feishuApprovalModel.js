export const approvalLabels = {
  QUEUED: '飞书审批待发起', SUBMITTING: '飞书审批提交中', PENDING: '飞书审批中',
  APPROVED: '飞书审批已通过', REJECTED: '飞书审批已驳回', CANCELED: '飞书审批已撤回',
  DELETED: '飞书审批已删除', UNKNOWN: '飞书审批待核对', FAILED: '飞书审批提交失败',
};

export const isSalesApprovalOrder = order => order?.source !== 'jushuitan-settlement' && ['销售订单', '海外提货单'].includes(order?.type);
export const isSettlementApprovalOrder = order => order?.source === 'jushuitan-settlement' && order?.type === '销售订单';
export const isLegacyApprovedSettlement = (order, record) => isSettlementApprovalOrder(order) && order.status === '已批准' && !record;
export const isApprovalOrder = order => isSalesApprovalOrder(order) || isSettlementApprovalOrder(order) || (order?.source !== 'jushuitan-settlement' && order?.type === '预收款订单');
export const approvalLocksOrder = record => ['QUEUED', 'SUBMITTING', 'PENDING', 'UNKNOWN', 'APPROVED'].includes(record?.status);
export const completedApproval = record => ['APPROVED', 'REJECTED', 'CANCELED', 'DELETED'].includes(record?.status);

export function approvalSettings(config, order) {
  if (!config) return null;
  if (!isApprovalOrder(order)) return {required: false, enabled: false, canSubmit: false};
  const prefix = isSalesApprovalOrder(order) || isSettlementApprovalOrder(order) ? 'sales' : order?.type === '预收款订单' ? 'prepayment' : null;
  if (!prefix) return {required: false, enabled: false, canSubmit: false};
  const enabled = Boolean(config[prefix + 'Enabled'] ?? config.enabled);
  return {required: true, enabled,
    canSubmit: enabled && Boolean(config[prefix + 'CanSubmit'] ?? config.canSubmit),
    message: config[prefix + 'Message'] || ''};
}

export function submissionProblem(config, order, error = '') {
  if (!isApprovalOrder(order)) return '';
  const draftHint = isSettlementApprovalOrder(order) ? '' : '可先保存草稿。';
  if (error) return '无法读取飞书审批设置，请重试。' + draftHint;
  const settings = approvalSettings(config, order);
  if (!settings) return '正在读取飞书审批设置…';
  if (settings.message) return settings.message + '。' + draftHint;
  if (!settings.enabled) return '飞书审批尚未接通：请管理员配置该类型的审批模板。' + draftHint;
  if (!settings.canSubmit) return '当前账号暂不能发起飞书审批，请核对应用配置、业务权限和飞书身份绑定。' + draftHint;
  return '';
}

export function canSubmitApproval(order, record, config) {
  return Boolean(!order?.deletedAt && isApprovalOrder(order) && ['草稿', '审批中'].includes(order.status) &&
    approvalSettings(config, order)?.canSubmit && !approvalLocksOrder(record) &&
    (!record || Boolean(record.retryVersion)));
}

export function approvalStatus(order, record, config, error = '') {
  if (order.status === '已取消') return '已取消';
  if (record?.cancelPending) return '取消待确认';
  if (record) return approvalLabels[record.status] || '飞书审批状态待核对';
  if (!isApprovalOrder(order) || order.status !== '审批中') return order.status || '待提交';
  if (error) return '审批状态加载失败';
  const settings = approvalSettings(config, order);
  if (!settings) return '审批状态加载中';
  if (!settings.enabled) return '飞书审批未接通';
  return settings.enabled ? '待发起飞书审批' : order.status;
}

export function canCancelOrder(order, record, shipment) {
  if (!isApprovalOrder(order) || isSettlementApprovalOrder(order) || order.deletedAt || shipment || !['草稿', '审批中'].includes(order.status) || moneyCents(order.paid ?? 0) !== 0) return false;
  return record ? record.canCancel === true : true;
}

export function canDeleteOrder(order, record, shipment) {
  if (!isSalesApprovalOrder(order) || order.deletedAt || shipment || moneyCents(order.paid ?? 0) !== 0) return false;
  return record ? record.canDelete === true : ['草稿', '已取消'].includes(order.status);
}

export const visibleBusinessOrders = orders => orders.filter(order => !order.deletedAt);
export const activeBusinessOrders = orders => orders.filter(order => !order.deletedAt && order.status !== '已取消');

export function orderExecutionView(order, shipment) {
  const effective = shipment?.effectiveOrder;
  const total = effective?.total ?? order.total, paid = effective?.paid ?? order.paid ?? 0;
  const totalCents = moneyCents(total), paidCents = moneyCents(paid);
  return {total, paid, effective: Boolean(effective), executionItems: Array.isArray(effective?.items),
    items: Array.isArray(effective?.items) ? effective.items : Array.isArray(order.items) ? order.items : [],
    unpaid: order.status === '已取消' ? 0 : totalCents === null || paidCents === null ? null : Math.max(0, totalCents - paidCents) / 100};
}

export function approvalInstanceUrl(instanceCode) {
  return /^[A-Za-z0-9_-]{1,128}$/.test(instanceCode ?? '')
    ? 'https://applink.feishu.cn/client/mini_program/open?mode=appCenter&appId=cli_9cb844403dbb9108&path=' +
      encodeURIComponent('pc/pages/in-process/index?instanceId=' + instanceCode) : null;
}

export function approvalDraftValues(config, order, fields) {
  const result = {};
  if (isSalesApprovalOrder(order)) {
    if (config?.salesRecipientRequired)
      for (const key of ['recipientName', 'recipientPhone', 'recipientAddress']) result[key] = String(fields?.get(key) ?? '');
    const draft = order.approvalDraft ?? {};
    const hasWarehouseField = fields?.has?.('warehouseId') || fields?.get?.('warehouseId') != null;
    if (hasWarehouseField || Object.hasOwn(draft, 'warehouseId')) {
      result.warehouseId = String(hasWarehouseField ? fields.get('warehouseId') ?? '' : draft.warehouseId ?? '');
      result.warehouseName = result.warehouseId.trim()
        ? String(hasWarehouseField ? fields.get('warehouseName') ?? '' : draft.warehouseName ?? '') : '';
    }
  }
  if (order.type === '预收款订单' && config?.prepaymentReceiptForm)
    for (const key of ['receiptDate', 'receiptCategory']) result[key] = String(fields?.get(key) ?? '');
  return result;
}

export function warehouseIdentifier(value) {
  if (typeof value !== 'string' && !(typeof value === 'number' && Number.isSafeInteger(value))) return '';
  const id = String(value).trim();
  return /^[1-9][0-9]{0,18}$/.test(id) && (id.length < 19 || id <= '9223372036854775807') ? id : '';
}

export function plannedOrderWarehouse(order) {
  if (!isSalesApprovalOrder(order)) return null;
  const id = warehouseIdentifier(order.approvalDraft?.warehouseId);
  return id ? {id, name: String(order.approvalDraft?.warehouseName ?? '').trim()} : null;
}

export function shippingWarehouseId(order, record) {
  if (record?.request) return record.request.wms_co_id ?? '';
  return plannedOrderWarehouse(order)?.id ?? '';
}

export function approvalDiscount(value) {
  const entered = String(value ?? '').trim() || '0';
  const invalid = () => Error('商品折扣须在0至999999999999.99之间，最多六位小数');
  if (entered.length > 64) throw invalid();
  const match = /^([+-]?)(\d+(?:\.\d*)?|\.\d+)(?:e([+-]?\d+))?$/i.exec(entered);
  if (!match) throw invalid();
  const [, sign, amount, exponentText = '0'] = match;
  const [whole, fraction = ''] = amount.split('.');
  const digits = (whole + fraction).replace(/^0+/, '');
  const significant = digits.replace(/0+$/, '');
  const exponent = Number(exponentText), power = exponent - fraction.length;
  if (Math.abs(exponent) > 2_147_483_647 || Math.abs(power) > 2_147_483_647) throw invalid();
  if (!significant) return entered;
  const scale = -(power + digits.length - significant.length);
  if (sign === '-' || scale > 6 || significant.length - scale > 12 ||
    BigInt(significant.padEnd(significant.length + 6 - scale, '0')) > 999_999_999_999_990_000n) throw invalid();
  return entered;
}

export function approvalDetails(config, order, fields) {
  const input = approvalDraftValues(config, order, fields);
  if (isSalesApprovalOrder(order)) {
    const result = {};
    if (input.warehouseId?.trim()) {
      const id = warehouseIdentifier(input.warehouseId);
      if (!id) throw Error('请选择有效的发货仓库');
      const name = input.warehouseName.trim();
      if (name.length > 191) throw Error('发货仓库名称最多191字');
      result.warehouseId = id;
      result.warehouseName = name;
    }
    if (config?.salesRecipientRequired) {
      for (const [key, label, max] of [['recipientName', '收货人', 100], ['recipientPhone', '收货电话', 50], ['recipientAddress', '收货地址', 500]]) {
        const value = input[key].trim();
        if (!value || value.length > max) throw Error('请填写有效的' + label + '，最多' + max + '字');
        result[key] = value;
      }
    }
    if (config?.salesDiscountRequired) result.discounts = (order.items ?? []).map((item, index) => approvalDiscount(fields?.get('approvalDiscount-' + index)));
    return result;
  }
  if (order.type !== '预收款订单' || !config?.prepaymentReceiptForm) return {};
  const receiptDate = input.receiptDate.trim();
  const receiptCategory = input.receiptCategory.trim();
  const parsed = /^\d{4}-\d{2}-\d{2}$/.test(receiptDate) ? new Date(receiptDate + 'T00:00:00Z') : null;
  if (!parsed || Number.isNaN(parsed.getTime()) || parsed.toISOString().slice(0, 10) !== receiptDate)
    throw Error('请选择有效的收款发生日期');
  if (!receiptCategory || !(config.prepaymentReceiptCategories ?? []).includes(receiptCategory))
    throw Error('请选择飞书审批模板支持的收款类别');
  return {receiptDate, receiptCategory};
}

export function approvalTime(value) {
  if (value === undefined || value === null || value === '') return '';
  const numeric = typeof value === 'number' || /^\d+$/.test(String(value));
  let time = numeric ? Number(value) : Date.parse(value);
  if (numeric && time > 0 && time < 100_000_000_000) time *= 1000;
  if (!Number.isFinite(time) || time <= 0) return '';
  const date = new Date(time);
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString('sv-SE', {timeZone: 'Asia/Shanghai'});
}
import {moneyCents} from './orderMoney.js';
