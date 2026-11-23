import {moneyCents} from './orderMoney.js';

export const receiptApprovalLabels = {
  QUEUED:'待发起审批', SUBMITTING:'正在发起', PENDING:'审批中', UNKNOWN:'发起结果待核对',
  APPROVED:'已通过并入账', REJECTED:'已驳回', CANCELED:'已撤回', DELETED:'审批已删除', FAILED:'发起失败',
};
export const receiptApprovalActive = record => ['QUEUED','SUBMITTING','PENDING','UNKNOWN'].includes(record?.status)
  || (record?.status === 'APPROVED' && record.booked !== true);
export const receiptApprovalCompleted = record => ['APPROVED','REJECTED','CANCELED','DELETED'].includes(record?.status);
export const receiptApprovalCompletionKey = record => `${record.id}:${record.status}:${record.booked === true}`;
export const reservedReceiptOrderIds = rows => new Set((rows || []).filter(receiptApprovalActive)
  .flatMap(record => (record.allocations || []).map(line => line.orderId)));
export const canWriteOffReceipt = orders => Boolean(orders?.length) && orders.every(order =>
  ['销售订单','海外提货单'].includes(order.type) && order.source !== 'jushuitan-settlement');
export const receiptPaymentMethods = (methods, orders) => [...new Set([
  ...(methods || []).filter(method => method !== '预收款核销'), ...(canWriteOffReceipt(orders) ? ['预收款核销'] : []),
])];
export function receiptApprovalProblem(config, error = '', canRegister = true) {
  if (!canRegister) return '当前账号没有登记收款权限';
  if (error) return '无法读取收款审批配置，请重新加载后提交';
  if (!config) return '正在读取收款审批配置…';
  if (config.enabled === false && config.required === false) return '';
  if (!config.enabled) return config.message || '收款审批尚未配置，请联系管理员';
  if (!config.canSubmit) return config.message || '请使用已绑定的飞书账号，并确认收款权限后发起审批';
  return '';
}
export function validReceiptDate(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value || '')) return false;
  const date = new Date(value + 'T00:00:00Z');
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0,10) === value;
}
export function buildReceiptApprovalRequest({requestId, db, customerId, selected, payments, amount, method,
  receiptDate, receiptCategory, note = '', methods, config, records = []}) {
  if (!config?.enabled) throw Error('收款审批尚未配置');
  const problem = receiptApprovalProblem(config);
  if (problem) throw Error(problem);
  const customer = db.customers.find(row => row.id === customerId);
  if (!customer) throw Error('请选择有效客户');
  if (!selected.length || selected.some(order => order.customerId !== customerId || order.status !== '已批准' || order.deletedAt))
    throw Error('请选择该客户已批准且未结清的订单');
  const reserved = reservedReceiptOrderIds(records);
  if (selected.some(order => reserved.has(order.id))) throw Error('所选订单已有未完成的收款审批，请先核对原申请');
  if (!receiptPaymentMethods(methods,selected).includes(method)) throw Error('请选择有效收款方式；预收款和聚水潭结算单不能使用预收款核销');
  if (!validReceiptDate(receiptDate)) throw Error('请选择有效的实际到账日期');
  if (!(config.receiptCategories || []).includes(receiptCategory)) throw Error('请选择飞书模板支持的收款类别');
  const total = moneyCents(amount);
  if (total === null || total <= 0) throw Error('实际收款金额须大于零且最多两位小数');
  let allocated = 0;
  const allocations = selected.map(order => {
    const value = moneyCents(payments[order.id]), orderTotal = moneyCents(order.total), paid = moneyCents(order.paid ?? 0);
    if (value === null || value <= 0 || orderTotal === null || paid === null || value > orderTotal - paid)
      throw Error('本次付款须大于零，且不能超过该订单未收金额');
    allocated += value;
    return {orderId:order.id,amount:value / 100};
  });
  if (!Number.isSafeInteger(allocated) || allocated !== total) throw Error('实际收款金额必须与本次付款合计一致');
  if (method === '预收款核销' && (moneyCents(customer.balance) ?? 0) < total) throw Error('客户预收款余额不足');
  return {requestId,revision:db.revision,customerId,amount:total / 100,method,receiptDate,receiptCategory,
    note:String(note).trim(),allocations};
}

// Only an explicit server opt-out may use the existing direct-booking path.
export function buildDirectReceiptChange(input) {
  if (input.config?.enabled !== false || input.config?.required !== false) throw Error('本次收款必须先通过飞书审批');
  const settlement=input.selected.some(order=>order.source==='jushuitan-settlement');
  if(settlement&&!validReceiptDate(input.receiptDate))throw Error('请填写结算单的实际到账日期');
  const details=buildReceiptApprovalRequest({...input,requestId:'direct-validation-only',
    receiptDate:input.receiptDate||'2000-01-01',receiptCategory:'direct-validation-only',
    config:{enabled:true,required:true,canSubmit:true,receiptCategories:['direct-validation-only']}});
  const amounts=new Map(details.allocations.map(line=>[line.orderId,line.amount]));
  const views=new Map(input.selected.map(order=>[order.id,order]));
  return {
    data:{...input.db,orders:input.db.orders.map(order=>amounts.has(order.id)?{...order,
      paid:((moneyCents(views.get(order.id).paid??0)??0)+(moneyCents(amounts.get(order.id))??0))/100,
      method:input.method,updated:new Date().toLocaleString('sv-SE',{timeZone:'Asia/Shanghai'})}:order)},
    verification:{amount:details.amount,customerId:input.customerId,
      customer:input.db.customers.find(customer=>customer.id===input.customerId)?.name,method:input.method,
      ...(settlement?{receivedOn:input.receiptDate}:{})},
  };
}
