import React,{useEffect,useRef,useState} from 'react';
import PaymentMethodPicker from './PaymentMethodPicker';
import {enabledPaymentMethodNames,receiptMethodChoices} from './paymentMethodModel';
import {allocateReceiptPaymentDetails,canonicalReceiptPaymentDetails,MAX_RECEIPT_PAYMENT_DETAILS,receiptPaymentDetailsForDisplay,receiptPaymentDetailTotals,receiptToday} from './receiptPaymentDetailsModel';
import './ReceiptPaymentDetails.css';
import './receiptOrderSelection.css';
import {customerOptions} from './customerOptions';
import {moneyCents} from './orderMoney';
import {recordId} from './recordId';
import {orderExecutionView} from './feishuApprovalModel';
import {orderNumber} from './orderNumber';
import ReceiptApprovalPanel from './ReceiptApprovals';
import {buildDirectReceiptChange,buildReceiptApprovalRequest,receiptApprovalLabels,receiptApprovalProblem,receiptPaymentMethods,reservedReceiptOrderIds} from './receiptApprovalModel';
import ReceiptProductDetails from './ReceiptProductDetails';
import ReceiptLedger from './ReceiptLedger';
import ReceiptVoucherUpload from './ReceiptVoucherUpload';
import {canReceiveOrder,itemsFromOrder,productSummary} from './receiptProductModel';

const money=value=>'¥ '+Number(value||0).toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2});
const cents=value=>moneyCents(value)??0;
const newPaymentDetail=()=>({key:recordId('receipt-line-'),method:'',amount:'',receiptDate:receiptToday()});
const paymentDetailsForAttempt=attempt=>receiptPaymentDetailsForDisplay(attempt).map(row=>({...row,key:recordId('receipt-line-')}));

export default function ReceiptOrders({api,db,Icon,save,DialogLayer,entryOnly=false,onEntryClose,onRegistered,
  approvals,canRegister=true,attempt=null,onAttemptChange,initialOrderId,shipments={},onOpenOrder}){
  const preset=db.orders.find(order=>order.id===initialOrderId);
  const[selecting,setSelecting]=useState(entryOnly),
    [customer,setCustomer]=useState(attempt?.customerId||preset?.customerId||''),[payments,setPayments]=useState(()=>attempt?Object.fromEntries(attempt.allocations.map(row=>[row.orderId,String(row.amount)])):preset?{[preset.id]:''}:{}),
    [paymentDetails,setPaymentDetails]=useState(()=>attempt?paymentDetailsForAttempt(attempt):[newPaymentDetail()]),
    [saving,setSaving]=useState(false),[formError,setFormError]=useState('');
  const[note,setNote]=useState(attempt?.note||''),
    [expandedOrder,setExpandedOrder]=useState(null),[checkingSubmission,setCheckingSubmission]=useState(Boolean(attempt)),[feedback,setFeedback]=useState('');
  const submission=useRef(attempt),submitting=useRef(false),allocationInputs=useRef(new Map());
  const[attachments,setAttachments]=useState(()=>Array.isArray(attempt?.attachments)?attempt.attachments.map(image=>({...image})):[]),[voucherUploading,setVoucherUploading]=useState(false);
  const[paymentMethods,setPaymentMethods]=useState([]),[methodsLoading,setMethodsLoading]=useState(!attempt),[methodsError,setMethodsError]=useState(''),[methodsVersion,setMethodsVersion]=useState(0);
  useEffect(()=>{
    if(!selecting||checkingSubmission)return;
    let active=true;setMethodsLoading(true);setMethodsError('');setPaymentMethods([]);
    api('system/payment-methods').then(rows=>{const names=enabledPaymentMethodNames(rows);if(active)setPaymentMethods(names)})
      .catch(problem=>{if(active){setPaymentMethods([]);setMethodsError(problem.message||'读取收款方式配置失败')}})
      .finally(()=>{if(active)setMethodsLoading(false)});
    return()=>{active=false};
  },[api,selecting,checkingSubmission,methodsVersion]);
  useEffect(()=>{
    const pending=submission.current;
    if(!pending||!checkingSubmission)return;
    const record=approvals.rows.find(row=>row.id===pending.requestId||row.requestId===pending.requestId);
    if(record)completeSubmission(record);
  },[approvals.rows,checkingSubmission]);
  const eligible=db.orders.map(order=>({...order,...orderExecutionView(order,shipments[order.id])})).filter(canReceiveOrder);
  const customerOrders=eligible.filter(order=>order.customerId===customer);
  const selected=customerOrders.filter(order=>Object.hasOwn(payments,order.id));
  const reserved=reservedReceiptOrderIds(approvals.rows),methods=receiptPaymentMethods(paymentMethods,selected);
  const invalidMethods=paymentDetails.some(row=>row.method&&!methods.includes(row.method));
  const methodProblem=checkingSubmission?'':methodsLoading?'正在读取收款方式配置…':methodsError?'无法读取收款方式配置，请重新加载':!methods.length?'暂无可用收款方式，请先配置':invalidMethods?'原选收款方式当前未启用，请重新选择':'';
  const needsReceivedDate=selected.some(order=>order.source==='jushuitan-settlement');
  const totals=selected.reduce((sum,order)=>({total:sum.total+cents(order.total),paid:sum.paid+cents(order.paid),unpaid:sum.unpaid+cents(order.total)-cents(order.paid),payment:sum.payment+cents(payments[order.id])}),{total:0,paid:0,unpaid:0,payment:0});
  const detailTotals=receiptPaymentDetailTotals(paymentDetails);
  const receivedCents=detailTotals.totalCents,receivedValid=detailTotals.valid;
  const invalidAllocation=selected.find(order=>{const amount=moneyCents(payments[order.id]);return amount===null||amount<=0||amount>cents(order.total)-cents(order.paid)});
  const allocationProblem=checkingSubmission||!invalidAllocation?'':!String(payments[invalidAllocation.id]??'').trim()?'请先填写所选订单的“实际回款”金额。':
    (moneyCents(payments[invalidAllocation.id])??0)>cents(invalidAllocation.total)-cents(invalidAllocation.paid)?'订单 '+orderNumber(invalidAllocation)+' 的实际回款不能超过应回款 '+money((cents(invalidAllocation.total)-cents(invalidAllocation.paid))/100)+'。':'请填写有效的实际回款，每笔须大于零且最多两位小数。';
  const difference=receivedCents-totals.payment,reconciled=receivedValid&&!allocationProblem&&totals.payment>0&&difference===0;
  let detailProblem='',allocationPreview=[];
  try{canonicalReceiptPaymentDetails(paymentDetails);if(reconciled)allocationPreview=allocateReceiptPaymentDetails(paymentDetails,selected.map(order=>({orderId:order.id,amount:cents(payments[order.id])/100})))}catch(error){detailProblem=error.message}
  const formLocked=saving||checkingSubmission||voucherUploading;
  const approvalRequired=!(approvals.config?.enabled===false&&approvals.config?.required===false);
  const problem=receiptApprovalProblem(approvals.config,approvals.error,canRegister);
  const balance=db.customers.find(row=>row.id===customer)?.balance??0;
  function completeSubmission(record){
    if(!submission.current)return;
    submission.current=null;onAttemptChange?.(null);setCheckingSubmission(false);setFormError('');
    const message=record.status==='APPROVED'&&record.booked!==true?'收款审批已通过，入账待处理，请在审批详情中核对。':
      '收款申请已记录：'+(receiptApprovalLabels[record.status]||'请查看审批状态')+'。审批通过后入账，不触发发货。';
    setFeedback(message);setSelecting(false);setPayments({});setPaymentDetails([newPaymentDetail()]);setAttachments([]);onRegistered?.(record);
  }
  async function submitPayment(event){
    event.preventDefault();if(submitting.current||voucherUploading)return;setFormError('');
    if(!submission.current&&methodProblem){setFormError(methodProblem);return}
    if(!canRegister){setFormError('当前账号没有登记收款权限');return}
    let payload=submission.current;
    if(!payload&&!approvalRequired){
      if(problem){setFormError(problem);return}
      submitting.current=true;setSaving(true);
      try{
        const change=buildDirectReceiptChange({db,customerId:customer,selected,payments,paymentDetails,
          attachments,methods:paymentMethods,config:approvals.config,records:approvals.rows});
        await save(change.data,change.verification);setSelecting(false);setPayments({});setPaymentDetails([newPaymentDetail()]);
        setAttachments([]);setFeedback('收款已登记。');onRegistered?.({direct:true});
      }catch(failure){setFormError(failure.message)}finally{submitting.current=false;setSaving(false)}
      return;
    }
    if(!payload){
      if(problem){setFormError(problem);return}
      try{payload=buildReceiptApprovalRequest({requestId:recordId(''),db,customerId:customer,selected,payments,paymentDetails,
        note,attachments,methods:paymentMethods,config:approvals.config,records:approvals.rows})}
      catch(failure){setFormError(failure.message);return}
      submission.current=payload;onAttemptChange?.(payload);
    }
    submitting.current=true;setSaving(true);
    try{
      const result=await approvals.request(null,null,payload);
      if(result?.approval)completeSubmission(result.approval);
    }catch(failure){
      if(!failure.status||failure.status>=500){
        setCheckingSubmission(true);setFormError('收款申请结果待核对，已保留原申请和填写内容。请核对原申请，避免重复提交。'+failure.message);
      }else{submission.current=null;onAttemptChange?.(null);setCheckingSubmission(false);setFormError(failure.message)}
    }finally{submitting.current=false;setSaving(false)}
  }
  function closeEntry(){if(saving||voucherUploading)return;setSelecting(false);onEntryClose?.()}
  function openEntry(){
    if(submission.current){setCheckingSubmission(true);setSelecting(true);return}
    setCustomer('');setPayments({});setPaymentDetails([newPaymentDetail()]);setPaymentMethods([]);setMethodsLoading(true);setMethodsError('');setNote('');setAttachments([]);setExpandedOrder(null);setFormError('');setSelecting(true);
  }
  const receiptForm=<DialogLayer kind="drawer" label="收款审批申请" open={selecting} onClose={closeEntry}><form className="drawer receipt-approval-form" onSubmit={submitPayment}>
    <div className="modal-title"><h2>{approvalRequired||checkingSubmission?'新建收款申请':'新建收款'}</h2><button type="button" disabled={saving||voucherUploading} aria-label="关闭收款申请" onClick={closeEntry}><Icon name="close"/></button></div>
    <div className="modal-body">
      {approvalRequired&&<p className="receipt-approval-notice">提交后发起飞书收款审批，审批通过前不计入已收金额。预收款核销同样通过审批后扣减余额；收款审批不触发发货。</p>}
      {problem&&<p className="system-error" role="status">{problem} <button type="button" className="link-btn" disabled={saving} onClick={approvals.retry}>重新加载</button></p>}
      {checkingSubmission&&<p className="receipt-approval-notice" role="status">原收款申请结果待核对，已锁定本次填写内容。可核对原申请，关闭后也会保留原申请编号。</p>}
      <div className="field"><span>客户 <em>*</em></span><PaymentMethodPicker label="客户" options={customerOptions(db.customers)} value={customer} disabled={formLocked} onChange={value=>{if(value===customer)return;setAttachments([]);setCustomer(value);setPayments({});setPaymentDetails([newPaymentDetail()]);setExpandedOrder(null);setFormError('')}}/></div>
      <p className="form-hint">切换客户会清空本次收款明细和凭证，请为所选客户重新填写及上传。</p>
      <p className="form-hint">勾选已批准且未结清的订单，再填写每笔订单的实际回款。应回款为当前未收金额，可按实际到账分次回款；已有未完成收款审批的订单暂不能重复申请。</p>
      <div className="receipt-order-selection" role="list" aria-label="可收款订单">{customerOrders.map(order=>{
        const chosen=Object.hasOwn(payments,order.id),occupied=reserved.has(order.id),due=cents(order.total)-cents(order.paid),amount=moneyCents(payments[order.id]);
        const blank=chosen&&!String(payments[order.id]??'').trim(),invalid=chosen&&(amount===null||amount<=0||amount>due);
        const inputId='receipt-actual-'+encodeURIComponent(order.id),hintId=inputId+'-hint';
        return <article key={order.id} role="listitem" aria-label={'收款订单 '+orderNumber(order)} className={'receipt-order-card'+(chosen?' is-selected':'')+(occupied?' is-reserved':'')}>
          <div className="receipt-order-card-heading"><label className="receipt-order-choice"><input type="checkbox" aria-label={'选择订单 '+orderNumber(order)} disabled={formLocked||occupied} checked={chosen}
            onChange={event=>setPayments(previous=>{const next={...previous};if(event.target.checked)next[order.id]='';else delete next[order.id];return next})}/>
            <span><strong>{orderNumber(order)}</strong><small>{order.type}</small></span></label>{occupied&&<span className="receipt-order-reserved-label">已有未完成的收款审批</span>}</div>
          <div className="receipt-order-card-products"><span>{productSummary(itemsFromOrder(order,db.products))}</span><button type="button" className="link-btn receipt-order-products-toggle" aria-label={'查看订单产品 '+orderNumber(order)} aria-expanded={expandedOrder===order.id}
            aria-controls={'receipt-products-'+encodeURIComponent(order.id)} onClick={()=>setExpandedOrder(previous=>previous===order.id?null:order.id)}>{expandedOrder===order.id?'收起产品明细':'查看产品明细'}</button></div>
          <dl className="receipt-order-card-amounts">{[['订单总金额',order.total],['已回款',order.paid],['应回款',due/100]].map(([label,value])=><div key={label}><dt>{label}</dt><dd>{money(value)}</dd></div>)}</dl>
          <div className={'receipt-order-actual-field'+(blank&&!checkingSubmission?' needs-amount':'')}><div><label htmlFor={inputId}>实际回款 {chosen&&<em>*</em>}</label>
            <small id={hintId}>{occupied?'请先核对原收款申请。':blank&&!checkingSubmission?'已选择此订单，请填写本次实际回款。':chosen?'按本次实际到账填写，最多为上方应回款。':'勾选此订单后填写实际回款。'}</small></div>
            <input ref={element=>{if(element)allocationInputs.current.set(order.id,element);else allocationInputs.current.delete(order.id)}} id={inputId} aria-label={orderNumber(order)+' 实际回款'} aria-describedby={hintId}
              aria-invalid={invalid&&!checkingSubmission||undefined} type="number" min="0.01" step="0.01" max={(due/100).toFixed(2)} required disabled={formLocked||occupied||!chosen} placeholder={chosen?'请填写实际回款':'先勾选订单'} value={payments[order.id]??''}
              onChange={event=>setPayments(previous=>({...previous,[order.id]:event.target.value}))}/>
          </div>
          {expandedOrder===order.id&&<div className="receipt-order-card-expanded" id={'receipt-products-'+encodeURIComponent(order.id)}><ReceiptProductDetails items={itemsFromOrder(order,db.products)}/></div>}
        </article>;
      })}{!customerOrders.length&&<div className="empty">{customer?'该客户暂无已批准且未结清的订单':'请先选择客户'}</div>}</div>
      <div className="receipt-totals receipt-order-totals">{[['所选订单总金额',totals.total],['已回款合计',totals.paid],['应回款合计',totals.unpaid],['实际回款合计',totals.payment]].map(([label,value])=><div key={label}><span>{label}</span><output aria-label={label}>{money(value/100)}</output></div>)}</div>
      <section className="receipt-payment-details" aria-label="收款明细">
        <div className="receipt-payment-details-heading"><h3>收款明细</h3><button type="button" className="secondary" disabled={formLocked||paymentDetails.length>=MAX_RECEIPT_PAYMENT_DETAILS} onClick={()=>setPaymentDetails(rows=>[...rows,newPaymentDetail()])}><Icon name="plus" size={15}/>添加收款明细</button></div>
        {!checkingSubmission&&<><button type="button" className="link-btn" disabled={formLocked||methodsLoading} onClick={()=>setMethodsVersion(value=>value+1)}>{methodsLoading?'正在读取收款方式…':'刷新收款方式'}</button>
          {methodsError&&<p className="system-error" role="status">{methodsError}，请重新加载收款方式。</p>}
          {!methodsLoading&&!methodsError&&!paymentMethods.length&&<p className="form-hint">暂无启用的收款方式，请在“系统管理 → 收款方式”配置；符合条件的订单仍可使用预收款核销。</p>}
        </>}
        {paymentDetails.map((row,index)=><div className="receipt-payment-detail-row" key={row.key} role="group" aria-label={'收款明细第'+(index+1)+'行'}>
          <span className="receipt-payment-detail-number">{index+1}</span>
          <div className="field"><span>收款方式 <em>*</em></span><PaymentMethodPicker label={'第'+(index+1)+'行收款方式'} options={receiptMethodChoices(methods,row.method,checkingSubmission||Boolean(row.method&&!methods.includes(row.method)))} value={row.method} onChange={value=>{setPaymentDetails(rows=>rows.map((line,position)=>position===index?{...line,method:value}:line));setFormError('')}} disabled={formLocked||methodsLoading||Boolean(methodsError)}/>
            {!checkingSubmission&&!methodsLoading&&!methodsError&&row.method&&!methods.includes(row.method)&&<small className="system-error">{row.method==='预收款核销'?'所选订单不支持预收款核销，请改用其他收款方式。':'原选方式“'+row.method+'”当前未启用，请重新选择。'}</small>}
          </div>
          <label className="field"><span>{row.method==='预收款核销'?'核销金额':'收款金额'} <em>*</em></span><input aria-label={'第'+(index+1)+'行收款金额'} type="number" min="0.01" step="0.01" required disabled={formLocked} placeholder="请输入金额" value={row.amount} onChange={event=>{setPaymentDetails(rows=>rows.map((line,position)=>position===index?{...line,amount:event.target.value}:line));setFormError('')}}/></label>
          <label className="field"><span>{row.method==='预收款核销'?'核销日期':'到账日期'} <em>*</em></span><input type="date" aria-label={'第'+(index+1)+'行到账日期'} required disabled={formLocked} value={row.receiptDate} onChange={event=>{setPaymentDetails(rows=>rows.map((line,position)=>position===index?{...line,receiptDate:event.target.value}:line));setFormError('')}}/></label>
          <button type="button" className="link-btn receipt-remove-detail" aria-label={'删除第'+(index+1)+'行收款明细'} disabled={formLocked||paymentDetails.length===1} onClick={()=>{setPaymentDetails(rows=>rows.filter((_,position)=>position!==index));setFormError('')}}><Icon name="close" size={17}/><span>删除</span></button>
        </div>)}
        <div className="receipt-payment-detail-total"><span>收款明细合计</span><output aria-label="收款明细合计">{money(receivedCents/100)}</output></div>
        <p className="form-hint">最多50行。合计按每行金额自动计算；每笔保留自己的收款方式及到账 / 核销日期。</p>
        {checkingSubmission&&<p className="form-hint">核对时沿用原申请的收款明细、凭证和申请编号，不会重建申请。</p>}
      </section>
      {detailTotals.writeOffCents>0&&<p className="receipt-approval-date-note">客户当前预收款可用余额 {money(balance)}；本次核销 {money(detailTotals.writeOffCents/100)}，核销后余额 {money(Number(balance)-detailTotals.writeOffCents/100)}。核销使用已有余额，不新增现金收款。{approvalRequired?'审批通过时再次核验余额，审批中暂不扣减。':'提交时再次核验余额。'}</p>}
      {detailTotals.writeOffCents>cents(balance)&&<p className="system-error" role="status">本次核销超过可用余额，请减少核销金额或改用其他收款方式。</p>}
      {needsReceivedDate&&<p className="receipt-approval-date-note">所选包含聚水潭结算单，请填写每笔真实到账日期。结算单不支持预收款核销。</p>}
      {allocationPreview.length>0&&<details className="receipt-payment-allocation-preview"><summary>查看收款分配预览</summary><p className="form-hint">按上方所选订单显示顺序，依次分配每笔收款；审批通过后按核定明细入账。</p><div className="table-scroll"><table className="receipt-selection"><thead><tr>{['明细行','收款方式','到账 / 核销日期','分配订单','分配金额'].map(label=><th key={label}>{label}</th>)}</tr></thead><tbody>{allocationPreview.map((row,index)=><tr key={index}><td>{row.lineNo}</td><td>{row.method}</td><td>{row.receiptDate}</td><td>{orderNumber(selected.find(order=>order.id===row.orderId))}</td><td>{money(row.amount)}</td></tr>)}</tbody></table></div></details>}
      <label className="field"><span>收款申请备注</span><textarea aria-label="收款申请备注" maxLength={2000} disabled={formLocked} value={note} onChange={event=>setNote(event.target.value)} placeholder="填写本次收款说明，不修改原订单备注"/></label>
      <ReceiptVoucherUpload value={attachments} onChange={setAttachments} disabled={saving||checkingSubmission} onBusyChange={setVoucherUploading}/>
      <div className={'receipt-reconciliation '+(reconciled?'is-matched':receivedCents||allocationProblem?'is-mismatched':'')} role="status" aria-live="polite">{allocationProblem||(!receivedCents?'请填写收款明细金额，与上方实际回款合计核对。':!receivedValid?'每行金额须大于零且最多两位小数。':reconciled?'金额核对一致：收款明细合计与实际回款合计均为 '+money(totals.payment/100):'金额不一致：收款明细合计 '+money(receivedCents/100)+'，实际回款合计 '+money(totals.payment/100)+'，相差 '+money(Math.abs(difference)/100))}
        {allocationProblem&&!formLocked&&<button type="button" className="link-btn receipt-order-focus-amount" onClick={()=>{const input=allocationInputs.current.get(invalidAllocation.id);input?.scrollIntoView({block:'center'});input?.focus({preventScroll:true})}}>填写实际回款</button>}
      </div>
      {formError&&<p className="system-error" role="alert">{formError}</p>}
    </div><div className="modal-footer"><button className="secondary" type="button" disabled={saving||voucherUploading} onClick={closeEntry}>{checkingSubmission?'关闭并保留申请':'取消'}</button><button className="primary" type="submit" disabled={saving||voucherUploading||!canRegister||(!checkingSubmission&&(Boolean(problem)||Boolean(methodProblem)||Boolean(detailProblem)||detailTotals.writeOffCents>cents(balance)||!selected.length||!reconciled))}>{saving?'正在提交…':voucherUploading?'正在上传凭证…':checkingSubmission?'核对原收款申请':approvalRequired?'提交并发起审批':'确认收款'}</button></div>
  </form></DialogLayer>;
  if(entryOnly)return receiptForm;
  return <>
    <div className="page-heading"><div><div className="breadcrumb">业务管理 <span>/</span> 收款管理 <span>/</span> 收款</div><h1>收款</h1><p>统一登记收款、跟踪审批，筛选并核对已入账流水。</p></div><button type="button" className="primary" disabled={!canRegister||(!submission.current&&Boolean(problem))} title={problem||undefined} onClick={openEntry}><Icon name="plus" size={17}/>{submission.current?'继续核对收款申请':'新建收款'}</button></div>
    {feedback&&<p className="receipt-approval-feedback" role="status">{feedback}</p>}
    <ReceiptApprovalPanel approvals={approvals} api={api} orders={db.orders} DialogLayer={DialogLayer} Icon={Icon}/>
    {receiptForm}
    <ReceiptLedger api={api} orders={db.orders} Icon={Icon} DialogLayer={DialogLayer} revision={db.revision} onOpenOrder={onOpenOrder}/>
  </>;
}
