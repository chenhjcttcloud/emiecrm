import React from 'react';
import BusinessImage from './BusinessImage';
import OrderChangeLog from './OrderChangeLog';
import {ApprovalFlow, ApprovalStatus} from './FeishuOrderApproval';
import {shipmentLabel} from './JushuitanOrders';
import {orderTotal} from './orderMoney';
import {orderExecutionView} from './feishuApprovalModel';
import './OrderDetails.css';

const money = value => value === null || value === undefined || value === '' || !Number.isFinite(Number(value))
  ? '—' : new Intl.NumberFormat('zh-CN', {style: 'currency', currency: 'CNY'}).format(Number(value));
const display = value => value === undefined || value === null || value === '' ? '—' : value;

export default function OrderDetails({order, customer, products, approvals, shipment, api, DialogLayer, Badge, Icon, onClose}) {
  if (!order) return null;
  const execution = orderExecutionView(order, shipment);
  const items = execution.items;
  const catalog = new Map(products.map(product => [product.id, product]));
  const recipient = order.approvalDraft;
  const hasRecipient = recipient?.recipientName || recipient?.recipientPhone || recipient?.recipientAddress;
  const canceled = order.status === '已取消';
  const prepayment = order.type === '预收款订单';
  const fields = [
    ['客户名称', order.customer || customer?.name], ['客户主体', customer?.entity], ['签单人', order.owner],
    ['订单类型', prepayment ? '预收款' : order.type], ['创建时间', order.created], ['更新时间', order.updated],
  ];
  if (order.type === '销售订单' && order.source !== 'jushuitan-settlement') fields.push(['发货状态', shipmentLabel(shipment)]);
  if (order.source === 'jushuitan-settlement') fields.push(['订单来源', '聚水潭结算补录']);
  return <DialogLayer open kind="drawer" label="订单详情" onClose={onClose}>
    <div className="drawer order-detail-drawer">
      <div className="modal-title"><h2>{prepayment ? '预收款详情' : '订单详情'}</h2>
        <button type="button" aria-label="关闭订单详情" onClick={onClose}><Icon name="close" size={18}/></button>
      </div>
      <div className="modal-body">
        <div className="order-detail-heading"><strong>{order.id}</strong>
          <ApprovalStatus order={order} record={approvals.records[order.id]} config={approvals.config} error={approvals.error} Badge={Badge}/>
        </div>
        {canceled && <p className="order-detail-source">订单已取消，不再计入待回款金额。</p>}
        <dl className="order-detail-fields">{fields.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{display(value)}</dd></div>)}</dl>
        <dl className="order-detail-amounts">{[[prepayment ? '预收款金额' : '订单总额', execution.total],
          ['已收款', execution.paid], ['待收款', execution.unpaid]].map(([label, value]) =>
          <div key={label}><dt>{label}</dt><dd>{money(value)}</dd></div>)}</dl>
        {execution.effective && <p className="order-products-caption">当前执行金额以上方为准，原订单总额为 {money(order.total)}。</p>}
        {hasRecipient && <section><h3>收货信息</h3><dl className="order-detail-fields">
          <div><dt>收货人</dt><dd>{display(recipient.recipientName)}</dd></div>
          <div><dt>收货电话</dt><dd>{display(recipient.recipientPhone)}</dd></div>
          <div className="order-detail-wide"><dt>收货地址</dt><dd>{display(recipient.recipientAddress)}</dd></div>
        </dl></section>}
        <section><h3>{execution.executionItems ? '当前执行明细' : prepayment ? '产品明细（选填）' : '产品明细'}</h3>
          {items.length ? <div className="order-detail-table-scroll"><table className="order-detail-table" aria-label="订单产品明细">
            <thead><tr><th scope="col">商品 / SKU</th><th scope="col">规格型号</th><th scope="col">数量</th><th scope="col">单价</th><th scope="col">小计</th></tr></thead>
            <tbody>{items.map((item, index) => {const product = catalog.get(item.product);return <tr key={`${item.product}-${index}`}>
              <td>{display(product?.name || item.name || item.product)}<small>{product?.jstSku || product?.code || item.sku || ''}</small></td>
              <td>{display(product?.model || item.model)}</td><td>{display(item.qty)}</td><td>{money(item.price)}</td><td>{money(orderTotal([item]))}</td>
            </tr>;})}</tbody>
          </table></div> : <p className="order-detail-empty">暂无产品明细。</p>}
          {prepayment && <p className="order-products-caption">商品明细合计 {money(orderTotal(items))}，预收款金额独立填写。</p>}
        </section>
        {order.note && <section><h3>备注</h3><p className="order-detail-note">{order.note}</p></section>}
        {Boolean(order.attachments?.length) && <section><h3>图片附件</h3><div className="order-detail-attachments">
          {order.attachments.map((image, index) => <BusinessImage key={`${image.url}-${index}`} link src={image.url}
            alt={image.name || `订单图片 ${index + 1}`} title={image.name} loading="lazy"/>)}
        </div></section>}
        <ApprovalFlow order={order} approvals={approvals} api={api} Badge={Badge}/>
        <section><OrderChangeLog key={order.id} orderId={order.id} api={api}/></section>
      </div>
      <div className="modal-footer"><button type="button" className="secondary" onClick={onClose}>关闭</button></div>
    </div>
  </DialogLayer>;
}
