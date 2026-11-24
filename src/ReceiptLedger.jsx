import React,{useEffect,useRef,useState} from 'react';
import Pagination from './Pagination';
import {dashboardMoney,dashboardTime} from './dashboardModel';
import {ReceiptDetailDrawer} from './ReceiptProductDetails';
import {productSummary} from './receiptProductModel';
import {orderNumberForId} from './orderNumber';
import {receiptLedgerCsv} from './receiptLedgerExport';
import './sales.css';
import './salesReceipts.css';

const emptyFilters={month:'',owner:'',customerId:'',method:''};
export default function ReceiptLedger({api,Icon,DialogLayer,revision,onOpenOrder,orders=[]}){
 const[filters,setFilters]=useState(emptyFilters),[query,setQuery]=useState(''),[search,setSearch]=useState(''),[page,setPage]=useState(1),[pageSize,setPageSize]=useState(20),[reload,setReload]=useState(0);
 const[state,setState]=useState({key:'',loading:true,data:null,error:''}),[options,setOptions]=useState({owners:[],customers:[],methods:[]});
 const generation=useRef(0);
 const[detail,setDetail]=useState(null);
 const[exportState,setExportState]=useState({busy:false,key:'',error:''});
 const exportPending=useRef(false),mounted=useRef(true),activeKey=useRef('');
 useEffect(()=>{mounted.current=true;return()=>{mounted.current=false}},[]);
 useEffect(()=>{const timer=setTimeout(()=>setSearch(query.trim()),240);return()=>clearTimeout(timer)},[query]);
 const key=JSON.stringify({filters,q:search,page,pageSize,reload,revision});
 const exportKey=JSON.stringify({key,query:query.trim()});
 activeKey.current=exportKey;
 useEffect(()=>{
  let active=true;const id=++generation.current;setState({key,loading:true,data:null,error:''});
  api('sales/receipts?'+new URLSearchParams({...filters,q:search,page,pageSize})).then(data=>{
   if(!active||generation.current!==id)return;
   if(!Array.isArray(data?.rows)||!data.summary||!Number.isSafeInteger(data.total)||!Number.isSafeInteger(data.page)||!Array.isArray(data.options?.owners)||!Array.isArray(data.options?.customers)||!Array.isArray(data.options?.methods))throw Error('收款流水格式无效，请重试');
   setOptions(data.options);setState({key,loading:false,data,error:''});
  }).catch(error=>{if(active&&generation.current===id)setState({key,loading:false,data:null,error:error.message||'收款流水加载失败'})});
  return()=>{active=false};
 },[api,key]);
 const loading=state.key!==key||state.loading||query.trim()!==search;
 const data=loading?null:state.data,error=!loading&&state.key===key?state.error:'';
 function change(name,value){setPage(1);setFilters(previous=>({...previous,[name]:value}))}
 function reset(){setQuery('');setPage(1);setFilters({...emptyFilters})}
 const filtered=Boolean(query||Object.values(filters).some(Boolean));
 function downloadRows(rows,suffix){
  const content=receiptLedgerCsv(rows,orders);
  const url=URL.createObjectURL(new Blob([content],{type:'text/csv;charset=utf-8'}));
  const link=document.createElement('a');link.href=url;link.download='收款流水-'+(filters.month||'全部月份')+'-'+suffix+'.csv';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
 }
 function exportPage(){if(data)downloadRows(data.rows,'第'+data.page+'页')}
 async function exportAll(){
  if(loading||!data||exportPending.current)return;
  const requestedKey=exportKey;
  exportPending.current=true;setExportState({busy:true,key:requestedKey,error:''});
  try{
   const result=await api('sales/receipts/export?'+new URLSearchParams({...filters,q:search}));
   if(!mounted.current)return;
   if(activeKey.current!==requestedKey){setExportState({busy:false,key:activeKey.current,error:'筛选条件或列表已变化，请重新导出。'});return}
   if(!Array.isArray(result?.rows)||!Number.isSafeInteger(result.total)||result.total!==result.rows.length)throw Error('收款流水导出结果不完整，请重试');
   downloadRows(result.rows,'全部匹配');
  }catch(failure){if(mounted.current&&activeKey.current===requestedKey)setExportState({busy:false,key:requestedKey,error:failure.message||'收款流水导出失败，请重试'})}
  finally{exportPending.current=false;if(mounted.current)setExportState(previous=>({...previous,busy:false}))}
 }
 const exportError=exportState.key===exportKey?exportState.error:'';
 return <div className="receipt-ledger sales-page sales-receipts">
  <ReceiptDetailDrawer receipt={detail} orders={orders} DialogLayer={DialogLayer} Icon={Icon} onClose={()=>setDetail(null)}/>
  <section className="sales-toolbar" aria-label="收款明细筛选"><div className="sales-toolbar-controls">
   <label className="sales-search-control"><Icon name="search" size={16}/><input aria-label="搜索收款流水" maxLength={200} value={query} onChange={e=>{setQuery(e.target.value);setPage(1)}} placeholder="搜索订单、客户、签单人或操作人"/></label>
   <label className="sales-inline-control sales-month-filter"><span>收款月</span><input aria-label="收款明细月份" type="month" min="2000-01" max="9999-12" value={filters.month} onChange={e=>{if(!e.target.value||/^\d{4}-(0[1-9]|1[0-2])$/.test(e.target.value))change('month',e.target.value)}}/>{filters.month&&<button type="button" className="sales-clear-control" aria-label="清除收款月份" onClick={()=>change('month','')}><Icon name="close" size={12}/></button>}</label>
   <label className="sales-inline-control"><span>签单人</span><select aria-label="收款明细签单人" value={filters.owner} onChange={e=>change('owner',e.target.value)}><option value="">全部签单人</option>{options.owners.map(owner=><option key={owner} value={owner}>{owner}</option>)}</select></label>
   <label className="sales-inline-control sales-customer-filter"><span>客户</span><select aria-label="收款明细客户" value={filters.customerId} onChange={e=>change('customerId',e.target.value)}><option value="">全部客户</option>{options.customers.map(customer=><option key={customer.id} value={customer.id}>{customer.name}</option>)}</select></label>
   <label className="sales-inline-control"><span>收款方式</span><select aria-label="收款明细方式" value={filters.method} onChange={e=>change('method',e.target.value)}><option value="">全部方式</option>{options.methods.map(method=><option key={method} value={method}>{method}</option>)}</select></label>
  </div><div className="sales-toolbar-actions">{filtered&&<button type="button" className="sales-quiet-button" onClick={reset}>重置</button>}<button type="button" className="sales-refresh-button" disabled={loading} onClick={()=>setReload(value=>value+1)}><Icon name="clock" size={14}/>{loading?'加载中':'刷新'}</button></div></section>
  <div className="sales-items-overview" aria-live="polite">{[['匹配流水',data?data.total.toLocaleString('zh-CN'):'—'],['流水金额合计',data?dashboardMoney(data.summary.amount):'—'],['收款客户',data?data.summary.customers:'—'],['税额合计',data?dashboardMoney(data.summary.tax):'—']].map(([label,value])=><div key={label}><span>{label}</span><strong>{value}</strong></div>)}</div>
  <section className="panel sales-list-panel" aria-label="收款流水"><div className="sales-list-heading"><span>收款流水 <small>汇总覆盖全部匹配结果，审批中的申请不计入</small></span><div className="receipt-ledger-export-actions"><button type="button" className="sales-quiet-button" disabled={loading||exportState.busy||Boolean(error)||!data?.rows.length} onClick={exportPage}><Icon name="export" size={15}/>导出本页</button><button type="button" className="sales-quiet-button" disabled={loading||exportState.busy||Boolean(error)||!data?.total} onClick={exportAll}><Icon name="export" size={15}/>{exportState.busy?'正在导出…':'导出全部匹配'}</button></div></div>
   {exportError&&<div className="sales-list-state is-error" role="alert">{exportError}</div>}
   <div className="table-scroll"><table className="sales-receipts-table"><thead><tr>{['订单编号','客户名称','签单人','订单当前回款状态','流水类型','收款方式','收款金额','税率（原记录）','税额','订单日期','实际到账日期','登记时间','操作人','产品明细','操作'].map(label=><th key={label}>{label}</th>)}</tr></thead><tbody>{data?.rows.map(row=><tr key={row.id}>
    <td>{row.orderId&&row.status!=='未关联订单'&&onOpenOrder?<button type="button" className="sales-order-link" onClick={()=>onOpenOrder(row.orderId)}>{row.orderNumber||orderNumberForId(row.orderId,orders)}</button>:row.orderNumber||orderNumberForId(row.orderId||row.order,orders)||'—'}</td><td>{row.customer||'—'}</td><td>{row.owner||'—'}</td>
    <td><span className={'sales-status '+(row.status==='已回款'?'is-approved':'')}>{row.status||'待核对'}</span></td><td>{row.type==='预收款订单'?'预收款':row.type||'—'}</td><td>{row.method||'—'}</td><td className={'sales-number sales-total'+(Number(row.amount)<0?' sales-refund':'')}>{dashboardMoney(row.amount)}</td><td>{row.taxRate??'—'}</td><td className="sales-number">{dashboardMoney(row.tax)}</td><td>{row.orderDate||'—'}</td><td>{row.receivedOn||'—'}</td><td>{dashboardTime(row.time)}</td><td>{row.operator||'—'}</td><td><span className="receipt-products-summary">{productSummary(row.items)}</span></td><td><button type="button" className="sales-order-link" aria-label={'查看收款详情 '+row.id} onClick={()=>setDetail(row)}>查看详情</button></td>
   </tr>)}</tbody></table></div>
   {(loading||error||!data?.rows.length)&&<div className={'sales-list-state'+(error?' is-error':'')} role={error?'alert':'status'}>{loading?'正在加载收款流水…':error||'暂无符合条件的收款记录'}{error&&<button type="button" className="sales-quiet-button" onClick={()=>setReload(value=>value+1)}>重新加载</button>}</div>}
   {data&&<Pagination total={data.total} page={data.page} pageSize={pageSize} pageSizes={[10,20,50,100]} onPage={setPage} onPageSize={value=>{setPageSize(value);setPage(1)}} label="收款流水分页"/>}
  </section>
  {data?.warnings?.length>0&&<details className="sales-data-warnings"><summary>数据与统计提示（{data.warnings.length}）</summary><ul>{data.warnings.map((warning,index)=><li key={index}>{warning}</li>)}</ul></details>}
  <p className="sales-page-note">收款月份优先使用实际到账日期，未记录时使用登记日期。流水金额包含预收款核销与正负差额，不等同于现金净流入；回款状态为订单当前状态。税率按原记录展示，未记录的税率、税额显示“—”；任一匹配流水税额缺失时不展示税额合计。</p>
 </div>;
}
