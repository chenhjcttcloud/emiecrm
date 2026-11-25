import React from 'react';
import PaymentMethodPicker from './PaymentMethodPicker';
import {receiptMethodChoices} from './paymentMethodModel';
import {receiptPaymentDetailTotals,receiptToday} from './receiptPaymentDetailsModel';
import {prepaymentDetailsForDraft,prepaymentPaymentDetailsForDisplay} from './prepaymentPaymentDetailsModel';
import './PrepaymentPaymentDetails.css';

const money=value=>'¥ '+Number(value||0).toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2});

export function PrepaymentPaymentSummary({order}) {
  const rows=prepaymentPaymentDetailsForDisplay(order);
  return <section className="prepayment-payment-summary" aria-label="预收款明细">
    <h3>预收款明细</h3><div className="order-detail-table-scroll"><table className="order-detail-table">
      <thead><tr><th scope="col">序号</th><th scope="col">收款方式</th><th scope="col">预收款金额</th><th scope="col">收款发生日期</th></tr></thead>
      <tbody>{rows.map((row,index)=><tr key={index}><td>{index+1}</td><td>{row.method||'—'}</td><td>{money(row.amount)}</td><td>{row.receiptDate||'—'}</td></tr>)}</tbody>
    </table></div><p className="prepayment-payment-summary-total">预收款合计 <strong>{money(order.total)}</strong></p>
  </section>;
}

export default function PrepaymentPaymentDetails({order,methods=[],loading=false,error='',disabled=false,locked=false,onChange,onRefresh,Icon}) {
  const rows=locked?prepaymentPaymentDetailsForDisplay(order):prepaymentDetailsForDraft(order);
  const totals=receiptPaymentDetailTotals(rows);
  const update=(index,key,value)=>onChange?.(rows.map((row,position)=>position===index?{...row,[key]:value}:row));
  return <section className="prepayment-payment-details" aria-label="预收款明细">
    <div className="prepayment-payment-details-heading"><h3>预收款明细</h3><button type="button" className="secondary" disabled={disabled||rows.length>=50}
      onClick={()=>onChange?.([...rows,{lineNo:rows.length+1,method:'',amount:'',receiptDate:receiptToday()}])}>{Icon&&<Icon name="plus" size={15}/>}添加预收款明细</button></div>
    <input type="hidden" name="prepaymentPaymentDetails" value={JSON.stringify(rows)} disabled={disabled}/>
    {!disabled&&<><button type="button" className="link-btn" disabled={loading} onClick={onRefresh}>{loading?'正在读取收款方式…':'刷新收款方式'}</button>
      {error&&<p className="system-error" role="status">{error}，请重新加载收款方式。</p>}
      {!loading&&!error&&!methods.length&&<p className="form-hint" role="status">暂无启用的收款方式，请在系统管理 → 收款方式中配置。</p>}</>}
    {rows.map((row,index)=><div className="prepayment-payment-detail-row" key={index} role="group" aria-label={'预收款明细第'+(index+1)+'行'}>
      <span className="prepayment-payment-detail-number">{index+1}</span>
      <div className="field"><span>收款方式 <em>*</em></span><PaymentMethodPicker label={'第'+(index+1)+'行收款方式'}
        value={row.method} options={receiptMethodChoices(methods,row.method,disabled||Boolean(row.method&&!methods.includes(row.method)))}
        disabled={disabled||loading||Boolean(error)} onChange={value=>update(index,'method',value)}/>
        {!disabled&&!loading&&!error&&row.method&&!methods.includes(row.method)&&<small className="system-error">原选方式“{row.method}”当前未启用，请重新选择。</small>}</div>
      <label className="field"><span>预收款金额 <em>*</em></span><input aria-label={'第'+(index+1)+'行预收款金额'} type="number" min="0.01" step="0.01" required
        value={row.amount} disabled={disabled} placeholder="请输入金额" onChange={event=>update(index,'amount',event.target.value)}/></label>
      <label className="field"><span>收款发生日期 <em>*</em></span><input aria-label={'第'+(index+1)+'行收款发生日期'} type="date" required
        value={row.receiptDate} disabled={disabled} onChange={event=>update(index,'receiptDate',event.target.value)}/></label>
      <button type="button" className="link-btn prepayment-remove-detail" aria-label={'删除第'+(index+1)+'行预收款明细'} disabled={disabled||rows.length===1}
        onClick={()=>onChange?.(rows.filter((_,position)=>position!==index))}>{Icon&&<Icon name="close" size={17}/>}删除</button>
    </div>)}
    <div className="prepayment-payment-detail-total"><span>预收款合计</span><output aria-label="预收款合计">{money(totals.totalCents/100)}</output></div>
    <p className="form-hint">最多50行，每笔保留自己的收款方式、金额及发生日期。合计自动计算，与选填产品金额独立；审批不登记实际收款。</p>
  </section>;
}
