import React, {useState} from 'react';
import BusinessImage from './BusinessImage';
import OrderChangeLog from './OrderChangeLog';
import CreditOrderPush from './CreditOrderPush';
import {ReceiptVoucherGallery} from './ReceiptVoucherUpload';
import {PrepaymentPaymentSummary} from './PrepaymentPaymentDetails';
import OrderLogistics from './OrderLogistics';
import {plannedLogistics} from './orderLogisticsModel';
import {ApprovalFlow, ApprovalStatus} from './FeishuOrderApproval';
import {shipmentLabel} from './JushuitanOrders';
import {orderTotal} from './orderMoney';
import {orderNumber} from './orderNumber';
import {canSubmitApproval,creditPushView,isLegacyApprovedSettlement,isSettlementApprovalOrder,orderExecutionView,plannedCreditShop,plannedOrderWarehouse,recipientAddressText,submissionProblem} from './feishuApprovalModel';
import './orderWarehousePicker.css';
import './OrderDetails.css';

const money = value => value === null || value === undefined || value === '' || !Number.isFinite(Number(value))
  ? '—' : new Intl.NumberFormat('zh-CN', {style: 'currency', currency: 'CNY'}).format(Number(value));
const display = value => value === undefined || value === null || value === '' ? '—' : value;

export default function OrderDetails({order, customer, products, approvals, shipment, api, DialogLayer, Badge, Icon, onClose, onSubmitApproval,canCreditPush=false,onCreditPush}) {
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState('');
  if (!order) return null;
  const execution = orderExecutionView(order, shipment);
  const items = execution.items;
  const catalog = new Map(products.map(product => [product.id, product]));
  const recipient = order.approvalDraft;
  const warehouse = plannedOrderWarehouse(order);
  const shop = plannedCreditShop(order);
  const hasRecipient = recipient?.recipientName || recipient?.recipientPhone || recipient?.recipientAddress;
  const actualRecipient = shipment?.request ? {
    recipientName: shipment.request.receiver_name, recipientPhone: shipment.request.receiver_mobile,
    recipientProvince: shipment.request.receiver_state, recipientCity: shipment.request.receiver_city,
    recipientDistrict: shipment.request.receiver_district, recipientAddress: shipment.request.receiver_address,
  } : null;
  const actualAddress = actualRecipient ? recipientAddressText(actualRecipient) : '';
  const actualDiffers = Boolean(actualRecipient && (actualAddress || actualRecipient.recipientName || actualRecipient.recipientPhone)
    && (actualAddress !== recipientAddressText(recipient) || String(actualRecipient.recipientName ?? '') !== String(recipient?.recipientName ?? '')
      || String(actualRecipient.recipientPhone ?? '') !== String(recipient?.recipientPhone ?? '')));
  const canceled = order.status === '已取消';
  const prepayment = order.type === '预收款订单';
  const settlement = isSettlementApprovalOrder(order);
  const record = approvals.records[order.id];
  const creditPush = creditPushView(order,record,shipment);
  const legacyApproved = isLegacyApprovedSettlement(order, record);
  const canSubmit = settlement && Boolean(onSubmitApproval) && canSubmitApproval(order, record, approvals.config);
  const problem = settlement && ['草稿', '审批中'].includes(order.status)
    ? submissionProblem(approvals.config, order, approvals.error) : '';
  const busy = submitting || Boolean(approvals.busy[order.id]);
  const close = () => { if (!busy) onClose(); };
  async function submitApproval() {
    if (!canSubmit || busy) return;
    setSubmitting(true);
    setSubmitError('');
    try { await onSubmitApproval(order); }
    catch (error) { setSubmitError(error.message); }
    finally { setSubmitting(false); }
  }
  const fields = [
    ['客户名称', order.customer || customer?.name], ['客户主体', customer?.entity], ['签单人', order.owner],
    ['订单类型', prepayment ? '预收款' : order.type], ['创建时间', order.created], ['更新时间', order.updated],
  ];
  if(warehouse)fields.push(['计划发货仓库',(warehouse.name||'仓库')+'（编号 '+warehouse.id+'）']);
  if(shop)fields.push(['聚水潭店铺',(shop.name||'店铺')+'（编号 '+shop.id+'）']);
  if(order.type==='销售订单'&&order.source!=='jushuitan-settlement')fields.push(['计划物流',plannedLogistics(order.approvalDraft)]);
  if (order.type === '销售订单' && order.source !== 'jushuitan-settlement') fields.push([creditPush?'聚水潭进度':'发货状态', creditPush?.label||shipmentLabel(shipment)]);
  if (order.source === 'jushuitan-settlement') fields.push(['订单来源', '聚水潭结算补录']);
  return <DialogLayer open kind="drawer" label="订单详情" onClose={close}>
    <div className="drawer order-detail-drawer">
      <div className="modal-title"><h2>{prepayment ? '预收款详情' : '订单详情'}</h2>
        <button type="button" disabled={busy} aria-label="关闭订单详情" onClick={close}><Icon name="close" size={18}/></button>
      </div>
      <div className="modal-body">
        <div className="order-detail-heading"><strong>{orderNumber(order)}</strong>
          <ApprovalStatus order={order} record={approvals.records[order.id]} config={approvals.config} error={approvals.error} Badge={Badge}/>
        </div>
        {canceled && <p className="order-detail-source">{creditPush?.oId ? 'CRM订单已取消，聚水潭原单及真实应收须单独核对。' : '订单已取消，不再计入待回款金额。'}</p>}
        <dl className="order-detail-fields">{fields.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{display(value)}</dd></div>)}</dl>
        <dl className="order-detail-amounts">{[[prepayment ? '预收款金额' : '订单总额', execution.total],
          ['已收款', execution.paid], ['待收款', execution.unpaid]].map(([label, value]) =>
          <div key={label}><dt>{label}</dt><dd>{money(value)}</dd></div>)}</dl>
        {execution.effective && <p className="order-products-caption">当前执行金额以上方为准，原订单总额为 {money(order.total)}。</p>}
        {prepayment&&<PrepaymentPaymentSummary order={order}/>}
        {creditPush&&<section className="order-credit-push-status" aria-label="聚水潭账期订单推送">
          <h3>聚水潭推送</h3><Badge tone={creditPush.tone==='success'?'green':creditPush.tone==='info'?'cyan':'warning'}>{creditPush.label}</Badge>
          <p role={['warning','error'].includes(creditPush.tone)?'status':undefined}>{creditPush.message}</p>
          {creditPush.approvalWarning&&<p className="credit-approval-warning" role="alert">{creditPush.approvalWarning}</p>}
          <dl className="order-detail-fields">
            <div><dt>聚水潭外部订单号</dt><dd>{display(creditPush.soId)}</dd></div>
            <div><dt>聚水潭内部单号</dt><dd>{display(creditPush.oId)}</dd></div>
          </dl>
          <p>账期推送不登记收款；已收款和待收款按上方实际入账金额显示。</p>
        </section>}
        {shipment&&order.type==='销售订单'&&order.source!=='jushuitan-settlement'&&<section aria-label="实际出库物流"><h3>实际出库物流</h3><OrderLogistics shipments={shipment.shipments}/></section>}
        {hasRecipient && <section><h3>{actualDiffers ? '原审批收货信息' : '收货信息'}</h3><dl className="order-detail-fields">
          <div><dt>收货人</dt><dd>{display(recipient.recipientName)}</dd></div>
          <div><dt>收货电话</dt><dd>{display(recipient.recipientPhone)}</dd></div>
          <div className="order-detail-wide"><dt>收货地址</dt><dd>{display(recipientAddressText(recipient))}</dd></div>
        </dl></section>}
        {actualDiffers && <section aria-label="实际发货收货信息"><h3>实际发货收货信息</h3><dl className="order-detail-fields">
          <div><dt>收货人</dt><dd>{display(actualRecipient.recipientName)}</dd></div>
          <div><dt>收货电话</dt><dd>{display(actualRecipient.recipientPhone)}</dd></div>
          <div className="order-detail-wide"><dt>收货地址</dt><dd>{display(actualAddress)}</dd></div>
        </dl><p className="form-hint">以上为本次聚水潭发货资料，原审批内容保留。</p></section>}
        <CreditOrderPush key={order.id} order={order} approval={record} shipment={shipment} api={api} canWrite={canCreditPush} onPush={onCreditPush}/>
        {settlement ? <section><h3>结算补录信息</h3>
          <dl className="order-detail-fields">
            <div><dt>结算方式</dt><dd>{order.settlement?.cycle === 'month' ? '月结' : '日结'}</dd></div>
            <div><dt>发货账期</dt><dd>{display(order.settlement?.periodStart)} 至 {display(order.settlement?.periodEnd)}</dd></div>
            <div><dt>聚水潭合计</dt><dd>{money(order.settlement?.erpAmount)}</dd></div>
            {order.settlement?.adjustmentReason && <div className="order-detail-wide"><dt>差额说明</dt><dd>{order.settlement.adjustmentReason}</dd></div>}
          </dl>
          <p className="order-products-caption">关联聚水潭销售单可从订单操作中的“关联单据”查看。{legacyApproved ? '历史已批准的结算补录，保留原确认结果；无飞书审批记录。' : '结算补录须经飞书审批通过后生效。'}</p>
        </section> : <section><h3>{execution.executionItems ? '当前执行明细' : prepayment ? '产品明细（选填）' : '产品明细'}</h3>
          {items.length ? <div className="order-detail-table-scroll"><table className="order-detail-table" aria-label="订单产品明细">
            <thead><tr><th scope="col">商品 / SKU</th><th scope="col">规格型号</th><th scope="col">数量</th><th scope="col">单价</th><th scope="col">小计</th></tr></thead>
            <tbody>{items.map((item, index) => {const product = catalog.get(item.product);return <tr key={`${item.product}-${index}`}>
              <td>{display(product?.name || item.name || item.product)}<small>{product?.jstSku || product?.code || item.sku || ''}</small></td>
              <td>{display(product?.model || item.model)}</td><td>{display(item.qty)}</td><td>{money(item.price)}</td><td>{money(orderTotal([item]))}</td>
            </tr>;})}</tbody>
          </table></div> : <p className="order-detail-empty">暂无产品明细。</p>}
          {prepayment && <p className="order-products-caption">商品明细合计 {money(orderTotal(items))}，预收款金额独立填写。</p>}
        </section>}
        {order.note && <section><h3>备注</h3><p className="order-detail-note">{order.note}</p></section>}
        {prepayment?<ReceiptVoucherGallery attachments={order.attachments} empty/>:Boolean(order.attachments?.length) && <section><h3>图片附件</h3><div className="order-detail-attachments">
          {order.attachments.map((image, index) => <BusinessImage key={`${image.url}-${index}`} link src={image.url}
            alt={image.name || `订单图片 ${index + 1}`} title={image.name} loading="lazy"/>)}
        </div></section>}
        <ApprovalFlow order={order} approvals={approvals} api={api} Badge={Badge}/>
        {problem && <p className="system-error" role="alert">{problem}</p>}
        {submitError && <p className="system-error" role="alert">{submitError}</p>}
        <section><OrderChangeLog key={order.id} orderId={order.id} orderNumber={orderNumber(order)} api={api}/></section>
      </div>
      <div className="modal-footer"><button type="button" className="secondary" disabled={busy} onClick={close}>关闭</button>
        {canSubmit && <button type="button" className="primary" disabled={busy || Boolean(problem)} onClick={submitApproval}>{busy ? '正在提交…' : record ? '重新发起飞书审批' : '发起飞书审批'}</button>}
      </div>
    </div>
  </DialogLayer>;
}
