export const approvalLabels = {
  QUEUED: '飞书审批待发起', SUBMITTING: '飞书审批提交中', PENDING: '飞书审批中',
  APPROVED: '飞书审批已通过', REJECTED: '飞书审批已驳回', CANCELED: '飞书审批已撤回',
  DELETED: '飞书审批已删除', UNKNOWN: '飞书审批待核对', FAILED: '飞书审批提交失败',
};

export const isSalesApprovalOrder = order => order?.source !== 'jushuitan-settlement' && ['销售订单', '海外提货单'].includes(order?.type);
export const isCreditSalesOrder = order => order?.source !== 'jushuitan-settlement' && order?.type === '销售订单';
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
  if (!isApprovalOrder(order) || isSettlementApprovalOrder(order) || order.deletedAt || shipment || record?.creditPush || !['草稿', '审批中'].includes(order.status) || moneyCents(order.paid ?? 0) !== 0) return false;
  return record ? record.canCancel === true : true;
}

export function canDeleteOrder(order, record, shipment) {
  if (!isSalesApprovalOrder(order) || order.deletedAt || shipment || record?.creditPush || moneyCents(order.paid ?? 0) !== 0) return false;
  return record ? record.canDelete === true : ['草稿', '已取消'].includes(order.status);
}

export const visibleBusinessOrders = orders => orders.filter(order => !order.deletedAt);
export const activeBusinessOrders = orders => orders.filter(order => !order.deletedAt && order.status !== '已取消');

export function orderExecutionView(order, shipment) {
  const effective = shipment?.effectiveOrder;
  const total = effective?.total ?? order.total;
  const paid = shipment?.creditFulfillment === true ? order.paid ?? 0 : effective?.paid ?? order.paid ?? 0;
  const creditExecuted = shipment?.creditFulfillment === true && (['ACCEPTED', 'PARTIAL', 'SHIPPED', 'EXCEPTION'].includes(shipment.status) || Boolean(shipment.oId));
  const totalCents = moneyCents(total), paidCents = moneyCents(paid);
  return {total, paid, effective: Boolean(effective), executionItems: Array.isArray(effective?.items),
    items: Array.isArray(effective?.items) ? effective.items : Array.isArray(order.items) ? order.items : [],
    unpaid: order.status === '已取消' && !creditExecuted ? 0 : totalCents === null || paidCents === null ? null : Math.max(0, totalCents - paidCents) / 100};
}

export function approvalInstanceUrl(instanceCode) {
  return /^[A-Za-z0-9_-]{1,128}$/.test(instanceCode ?? '')
    ? 'https://applink.feishu.cn/client/mini_program/open?mode=appCenter&appId=cli_9cb844403dbb9108&path=' +
      encodeURIComponent('pc/pages/in-process/index?instanceId=' + instanceCode) : null;
}

export function approvalDraftValues(config, order, fields) {
  const result = {};
  if (isSalesApprovalOrder(order)) {
    const draft = order.approvalDraft ?? {};
    const hasCreditField = fields?.has?.('creditFulfillment') || fields?.get?.('creditFulfillment') != null;
    const creditValue = hasCreditField ? fields.get('creditFulfillment') : draft.creditFulfillment;
    const credit = isCreditSalesOrder(order) && (creditValue === true || creditValue === 'true');
    if (config?.salesRecipientRequired || credit)
      for (const key of ['recipientName', 'recipientPhone', 'recipientAddress'])
        result[key] = String(fields?.get(key) ?? (credit ? draft[key] : '') ?? '');
    for (const key of ['recipientProvince', 'recipientCity', 'recipientDistrict']) {
      const present = fields?.has?.(key) || fields?.get?.(key) != null;
      if (present || Object.hasOwn(draft, key)) result[key] = String(present ? fields.get(key) ?? '' : draft[key] ?? '');
    }
    const hasWarehouseField = fields?.has?.('warehouseId') || fields?.get?.('warehouseId') != null;
    if (hasWarehouseField || Object.hasOwn(draft, 'warehouseId')) {
      result.warehouseId = String(hasWarehouseField ? fields.get('warehouseId') ?? '' : draft.warehouseId ?? '');
      result.warehouseName = result.warehouseId.trim()
        ? String(hasWarehouseField ? fields.get('warehouseName') ?? '' : draft.warehouseName ?? '') : '';
    }
    if (isCreditSalesOrder(order)) {
      Object.assign(result,logisticsDraftValues(draft,fields));
      if (hasCreditField || Object.hasOwn(draft, 'creditFulfillment')) {
        result.creditFulfillment = credit;
      }
      const hasShopField = fields?.has?.('shopId') || fields?.get?.('shopId') != null;
      if (hasShopField || Object.hasOwn(draft, 'shopId')) {
        result.shopId = String(hasShopField ? fields.get('shopId') ?? '' : draft.shopId ?? '');
        result.shopName = result.shopId.trim()
          ? String(hasShopField ? fields.get('shopName') ?? '' : draft.shopName ?? '') : '';
      }
    }
  }
  if (order.type === '预收款订单' && config?.prepaymentReceiptForm)
    for (const key of ['receiptDate', 'receiptCategory']) result[key] = String(fields?.get(key) ?? '');
  return result;
}

// Old full addresses remain unchanged; structured regions are only added by explicit entry.
export function recipientAddressText(draft = {}) {
  const [province, city, district, address] = ['recipientProvince', 'recipientCity', 'recipientDistrict', 'recipientAddress']
    .map(key => String(draft?.[key] ?? '').trim());
  const grouping = ['市辖区', '县', '省直辖县级行政区划', '自治区直辖县级行政区划', '省直辖行政单位', '自治区直辖行政单位'];
  return [province, city === province || grouping.includes(city) ? '' : city, district, address].filter(Boolean).join('');
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

export function plannedCreditShop(order) {
  if (!isCreditSalesOrder(order)) return null;
  const id = warehouseIdentifier(order.approvalDraft?.shopId);
  return id ? {id, name: String(order.approvalDraft?.shopName ?? '').trim()} : null;
}

// A unique exact owner-name match is a suggestion only. The form never selects it silently.
export function suggestedCreditShop(shops, owner) {
  const name = String(owner ?? '').trim();
  if (!name) return null;
  const matches = (shops ?? []).filter(shop => String(shop?.name ?? '').trim() === name && warehouseIdentifier(shop?.id));
  return matches.length === 1 ? {id: warehouseIdentifier(matches[0].id), name} : null;
}

export function jstNativeStatusLabel(status) {
  return {WaitPay: '聚水潭待付款 / 账期待处理', WaitConfirm: '聚水潭待审核',
    WaitFConfirm: '聚水潭待财务审核', Delivering: '聚水潭发货中', WaitOuterSent: '等待外仓发货',
    Sent: '聚水潭已发货', Cancelled: '聚水潭已取消', Question: '聚水潭订单异常'}[status] || '';
}

export function creditPushView(order, approval, shipment) {
  if (!isCreditSalesOrder(order)) return null;
  const push = approval?.creditPush;
  if (!push && shipment?.creditFulfillment !== true && order.approvalDraft?.creditFulfillment !== true) return null;
  const nativeStatus = shipment?.nativeStatus || push?.nativeStatus || '';
  const actual = shipment?.creditFulfillment ? shipment.status : '';
  const accepted = ['ACCEPTED', 'PARTIAL', 'SHIPPED', 'EXCEPTION'].includes(actual)
    || ['ACCEPTED', 'PARTIAL', 'SHIPPED', 'EXCEPTION'].includes(push?.status)
    || Boolean(shipment?.oId || push?.oId);
  if ((order.deletedAt || order.status === '已取消') && !accepted && !shipment) return null;
  const statuses = {QUEUED: ['待自动推送', 'info'], BLOCKED: ['自动推送需补充资料', 'warning'],
    SUBMITTING: ['正在推送聚水潭', 'info'], UNKNOWN: ['聚水潭推送结果待核对', 'warning'],
    FAILED: ['聚水潭推送失败', 'error'], ACCEPTED: ['聚水潭已接单 / 待核对审核进度', 'info'],
    PARTIAL: ['聚水潭部分发货', 'success'], SHIPPED: ['聚水潭已发货', 'success']};
  statuses.EXCEPTION = ['聚水潭订单异常', 'error'];
  statuses.CANCELLED = ['聚水潭订单已取消', 'warning'];
  const status = ['ACCEPTED', 'PARTIAL', 'SHIPPED', 'EXCEPTION', 'CANCELLED'].includes(actual) ? actual : push?.status || actual;
  const draftOnly = !push && !shipment && order.status === '草稿';
  const [label, tone] = statuses[status] || [draftOnly ? '草稿未推送' : '正式提交后推送', 'info'];
  const nativeMessage = {WaitPay: '聚水潭返回待付款状态，请核对账期挂账资料；CRM实收按实际入账记录。',
    WaitConfirm: '聚水潭原单待审核，财务直接在聚水潭审核；审核和发货进度以聚水潭回传为准。',
    WaitFConfirm: '聚水潭原单等待财务审核，审核后继续发货流程。'}[nativeStatus];
  const issue = accepted && !['CANCELLED'].includes(actual) && nativeStatus !== 'Cancelled'
    && (['FAILED', 'REJECTED', 'CANCELED', 'DELETED', 'UNKNOWN'].includes(approval?.status) || order.status === '已取消' || order.deletedAt);
  const shippedWhilePending = accepted && (['PARTIAL', 'SHIPPED'].includes(actual) || nativeStatus === 'Sent')
    && (['PENDING', 'QUEUED', 'SUBMITTING'].includes(approval?.status) || (!approval?.status && order.status === '审批中'));
  const approvalAttentionLabel = issue ? approval?.status === 'UNKNOWN' ? '飞书结果待核对' : '飞书审批异常'
    : shippedWhilePending ? '飞书审批并行处理中' : '';
  const actualLabel = ['PARTIAL', 'SHIPPED'].includes(actual) ? label : jstNativeStatusLabel(nativeStatus) || label;
  return {label: actualLabel, tone: nativeStatus === 'WaitPay' ? 'warning' : ['WaitConfirm', 'WaitFConfirm'].includes(nativeStatus) ? 'info' : tone,
    message: nativeMessage || shipment?.message || push?.message || (draftOnly ? '保存草稿不会推送聚水潭。' : '正式提交即并行发起飞书审批并推送聚水潭，财务在聚水潭审核。'),
    approvalWarning: issue ? approvalAttentionLabel + '，请核对聚水潭原单。驳回或撤回不会自动取消聚水潭订单；重新发起飞书审批继续沿用原单。'
      : shippedWhilePending ? '飞书审批并行处理中，聚水潭已发货或部分发货，请核对原单。' : '',
    approvalAttentionLabel,
    nativeStatus, soId: shipment?.soId || push?.soId, oId: shipment?.oId || push?.oId,
    trigger: push?.trigger, orderSubmissionId: push?.submissionId || push?.orderSubmissionId};
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
    const credit = isCreditSalesOrder(order) && input.creditFulfillment === true;
    if (input.warehouseId?.trim()) {
      const id = warehouseIdentifier(input.warehouseId);
      if (!id) throw Error('请选择有效的发货仓库');
      const name = input.warehouseName.trim();
      if (name.length > 191) throw Error('发货仓库名称最多191字');
      result.warehouseId = id;
      result.warehouseName = name;
    }
    if (credit) {
      Object.assign(result,logisticsDetails(order.approvalDraft,fields));
      if ((fields?.has?.('creditShippingReady') || fields?.get?.('creditShippingReady') != null)
          && fields.get('creditShippingReady') !== 'true')
        throw Error('聚水潭账期推送设置暂不可用，请重新加载后提交；可先保存草稿。');
      if (fields?.get?.('creditShippingChoiceValid') === 'false')
        throw Error('所选聚水潭店铺或仓库当前不可用，请重新选择后提交；可先保存草稿。');
      const shopId = warehouseIdentifier(input.shopId);
      if (!shopId) throw Error('请选择有效的聚水潭店铺，正式提交后将推送原单。');
      if (!result.warehouseId) throw Error('请选择发货仓库，正式提交后将推送原单。');
      const shopName = String(input.shopName ?? '').trim();
      if (shopName.length > 191 || /[\u0000-\u001f\u007f-\u009f]/.test(shopName)) throw Error('聚水潭店铺名称无效，最多191字');
      result.creditFulfillment = true;
      result.shopId = shopId;
      result.shopName = shopName;
    }
    if (config?.salesRecipientRequired || credit) {
      const limits = credit ? [50, 30, 200] : [100, 50, 500];
      for (const [index, [key, label]] of [['recipientName', '收货人'], ['recipientPhone', '收货电话'], ['recipientAddress', '收货地址']].entries()) {
        const max = limits[index];
        const value = String(input[key] ?? '').trim();
        if (!value || value.length > max) throw Error('请填写有效的' + label + '，最多' + max + '字');
        result[key] = value;
      }
      // Validate ERP limits before approval; approved recipient details are immutable.
      if (credit && !/^[0-9+() -]{7,30}$/.test(result.recipientPhone))
        throw Error('收货电话须为7至30位数字，可包含 +、括号、空格或连字符');
      if (credit) for (const [key, label] of [['recipientProvince', '省份'], ['recipientCity', '城市'], ['recipientDistrict', '区 / 县']]) {
        const value = String(input[key] ?? '').trim();
        if (!value || value.length > 50) throw Error('请填写有效的' + label + '，最多50字');
        result[key] = value;
      }
    }
    if (config?.salesDiscountRequired) result.discounts = (order.items ?? []).map((item, index) => approvalDiscount(fields?.get('approvalDiscount-' + index) ?? (credit ? item.approvalDiscount : undefined)));
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
import {logisticsDetails,logisticsDraftValues} from './orderLogisticsModel.js';
