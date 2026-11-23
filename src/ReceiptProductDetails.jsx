import React from 'react';
import {orderTotal} from './orderMoney';
import {receiptProductMoney as money} from './receiptProductModel';
import './ReceiptProductDetails.css';

export {itemsFromOrder,productSummary,productDetailsText} from './receiptProductModel';

export default function ReceiptProductDetails({items=[],source}){
 const rows=Array.isArray(items)?items:[];
 return <div className="receipt-product-details">
  {source==='order'&&<p className="receipt-products-source">当前关联订单资料；该流水未记录收款时的商品快照。</p>}
  {source==='snapshot'&&<p className="receipt-products-source">收款登记时保存的产品明细。</p>}
  {source==='unknown'&&<p className="receipt-products-source">该流水未记录商品资料，也无法从关联订单读取。</p>}
  {!rows.length?<p className="receipt-products-empty">暂无产品明细。</p>:<>
   <div className="receipt-product-table-scroll"><table className="receipt-product-table" aria-label="产品明细"><thead><tr>{['产品 / SKU','规格型号','数量','单价','小计'].map(label=><th scope="col" key={label}>{label}</th>)}</tr></thead>
    <tbody>{rows.map((item,index)=><tr key={`${item.product||item.sku||'item'}-${index}`}><td><strong>{item.name||item.product||'未命名产品'}</strong>{item.sku&&<small>{item.sku}</small>}</td><td>{item.model||'—'}</td><td>{item.qty??'—'}</td><td>{money(item.price)}</td><td>{money(orderTotal([item]))}</td></tr>)}</tbody>
   </table></div><div className="receipt-products-total"><span>商品合计</span><strong>{money(orderTotal(rows))}</strong></div>
  </>}
 </div>;
}

export function ReceiptDetailDrawer({receipt,DialogLayer,Icon,onClose}){
 const registered=receipt?.time?new Date(receipt.time):null;
 const time=registered&&Number.isFinite(registered.getTime())?registered.toLocaleString('sv-SE',{timeZone:'Asia/Shanghai'}):'—';
 return <DialogLayer kind="drawer" label="收款流水详情" open={Boolean(receipt)} onClose={onClose}>{receipt&&<section className="drawer receipt-detail-drawer">
  <div className="modal-title"><h2>收款流水详情</h2><button type="button" aria-label="关闭收款详情" onClick={onClose}><Icon name="close"/></button></div>
  <div className="modal-body"><div className="receipt-detail-heading"><div><span>本笔流水金额</span><strong>{money(receipt.amount)}</strong></div><span>{receipt.id}</span></div>
   <dl className="receipt-detail-fields">{[['订单编号',receipt.orderId||receipt.order],['客户',receipt.customer],['签单人',receipt.owner],['流水类型',receipt.type],['收款方式',receipt.method],['实际到账日期',receipt.receivedOn],['登记时间',time],['操作人',receipt.operator],['备注',receipt.note]].map(([label,value])=><div key={label}><dt>{label}</dt><dd>{value||'—'}</dd></div>)}</dl>
   <div className="receipt-detail-products"><h3>产品明细</h3><p>商品合计是关联订单的商品金额，不代表本笔收款、核销或退款金额。</p><ReceiptProductDetails items={receipt.items} source={receipt.itemsSource}/></div>
  </div><div className="modal-footer"><button type="button" className="secondary" onClick={onClose}>关闭</button></div>
 </section>}</DialogLayer>;
}
