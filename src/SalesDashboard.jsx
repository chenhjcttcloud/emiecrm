import React,{useEffect,useMemo,useRef,useState} from 'react';
import Pagination from './Pagination';
import{DASHBOARD_FILTER_KEY,currentDashboardMonth,shiftDashboardMonth,normalizeDashboardFilters,targetProgress,dashboardMoney,dashboardTime,dashboardDetailRows,sumDashboardMoney}from'./dashboardModel';
import './dashboard.css';

const loadFilters=()=>{try{return normalizeDashboardFilters(JSON.parse(localStorage.getItem(DASHBOARD_FILTER_KEY)))}catch{return normalizeDashboardFilters(null)}};
const metricTitles={orders:'订单明细',settled:'已结清订单',pending:'待回款订单',amount:'订单金额明细',paid:'累计已回款明细',outstanding:'待回款明细',advance:'新增预收款明细',day:'每日订单明细'};

export default function SalesDashboard({api,Icon,DialogLayer,canManageTargets=false,onManageTargets,onSalesTargets}){
 const[filters,setFilters]=useState(loadFilters),[data,setData]=useState(null),[loading,setLoading]=useState(true),[error,setError]=useState(''),[refresh,setRefresh]=useState(0),[selection,setSelection]=useState(null),[detailPage,setDetailPage]=useState(1);
 const request=useRef(0);
 const filterKey=JSON.stringify(filters);
 useEffect(()=>{try{localStorage.setItem(DASHBOARD_FILTER_KEY,filterKey)}catch{}},[filterKey]);
 useEffect(()=>{
  const id=++request.current;let active=true;
  setLoading(true);setError('');setSelection(null);setData(null);
  api('dashboard/sales?'+new URLSearchParams(filters)).then(next=>{
   if(!active||request.current!==id)return;
   if(!next?.summary||!next?.target||!Array.isArray(next.daily)||!Array.isArray(next.orders)||!Array.isArray(next.advanceReceipts)||!Array.isArray(next.options?.owners)||!Array.isArray(next.options?.customers))throw Error('看板数据格式无效，请重试');
   setData(next);
  }).catch(e=>{if(active&&request.current===id)setError(e.message||'加载失败，请重试')}).finally(()=>{if(active&&request.current===id)setLoading(false)});
  return()=>{active=false};
 },[api,filterKey,refresh]);
 const update=(key,value)=>setFilters(previous=>({...previous,[key]:value}));
 const currentMonth=currentDashboardMonth();
 const hasFilters=Boolean(filters.owner||filters.customerId);
 const options=data?.options||{owners:[],customers:[]};
 const progress=targetProgress(data?.summary?.orderAmount,data?.target?.amount,data?.targetApplicable);
 const selectedTargetScope=data?.target?.scope?.mode==='selected';
 const emptyTargetScope=selectedTargetScope&&!data?.target?.scope?.departmentIds?.length;
 const automaticTarget=data?.target?.source==='personnel'||selectedTargetScope;
 const maxAmount=Math.max(0,...(data?.daily||[]).map(day=>Number(day.amount)||0));
 const detailRows=useMemo(()=>dashboardDetailRows(data,selection),[data,selection]);
 const currentDetailPage=Math.min(detailPage,Math.max(1,Math.ceil(detailRows.length/20)));
 const visibleDetails=detailRows.slice((currentDetailPage-1)*20,currentDetailPage*20);
 function openDetails(kind,date){setDetailPage(1);setSelection({kind,date})}
 const selectedCustomer=options.customers.find(customer=>customer.id===filters.customerId)?.name;
 const context=[filters.month,filters.owner||'全部签单人',selectedCustomer||(filters.customerId?'所选客户':'全部客户')].join(' · ');
 const count=value=>value!==null&&value!==undefined&&Number.isFinite(Number(value))?Number(value).toLocaleString('zh-CN'):'—';
 return <div className="sales-dashboard">
  <div className="page-heading"><div><div className="breadcrumb">经营看板 <span>/</span> 销售看板</div><h1>销售看板</h1><p>目标、销售与回款进度，一屏掌握。</p></div><div className="dashboard-heading-actions">{canManageTargets&&<button type="button" className="secondary" onClick={onManageTargets}><Icon name="settings" size={16}/>业绩目标</button>}</div></div>
  <section className="dashboard-filters" aria-label="销售看板筛选">
   <div className="dashboard-filter-controls">
    <div className="dashboard-month-switch" role="group" aria-label="切换统计月份">
     <button type="button" className="dashboard-month-step" aria-label="上个月" title="上个月" disabled={filters.month==='2000-01'} onClick={()=>setFilters(previous=>({...previous,month:shiftDashboardMonth(previous.month,-1)}))}><Icon name="chevron" size={15} style={{transform:'rotate(90deg)'}}/></button>
     <label className="dashboard-month-control"><span className="dashboard-visually-hidden">统计月份</span><input aria-label="统计月份" type="month" min="2000-01" max="9999-12" value={filters.month} onChange={e=>{const value=e.target.value;if(/^\d{4}-(0[1-9]|1[0-2])$/.test(value)&&value>='2000-01'&&value<='9999-12')update('month',value)}}/></label>
     <button type="button" className="dashboard-month-step" aria-label="下个月" title="下个月" disabled={filters.month==='9999-12'} onClick={()=>setFilters(previous=>({...previous,month:shiftDashboardMonth(previous.month,1)}))}><Icon name="chevron" size={15} style={{transform:'rotate(-90deg)'}}/></button>
    </div>
    <label className="dashboard-filter-control"><span>签单人</span><select aria-label="签单人" value={filters.owner} onChange={e=>update('owner',e.target.value)}><option value="">全部签单人</option>{filters.owner&&!options.owners.includes(filters.owner)&&<option value={filters.owner}>{filters.owner}</option>}{options.owners.map(owner=><option key={owner} value={owner}>{owner}</option>)}</select></label>
    <label className="dashboard-filter-control dashboard-customer-control"><span>客户</span><select aria-label="客户" value={filters.customerId} onChange={e=>update('customerId',e.target.value)}><option value="">全部客户</option>{filters.customerId&&!options.customers.some(customer=>customer.id===filters.customerId)&&<option value={filters.customerId}>已选择客户</option>}{options.customers.map(customer=><option key={customer.id} value={customer.id}>{customer.name}</option>)}</select></label>
   </div>
   <div className="dashboard-toolbar-end">
    <span className="dashboard-toolbar-status" role="status">{loading?'正在更新…':error?'更新失败':data&&<>更新于 <time dateTime={data.generatedAt} title={dashboardTime(data.generatedAt)}>{new Date(data.generatedAt).toLocaleTimeString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false})}</time></>}</span>
    <div className="dashboard-filter-actions">
     {hasFilters&&<button type="button" className="dashboard-reset-button" aria-label="重置签单人与客户" onClick={()=>setFilters(previous=>({...previous,owner:'',customerId:''}))}><Icon name="close" size={13}/>重置</button>}
     <button type="button" className="dashboard-today-button" aria-pressed={filters.month===currentMonth} onClick={()=>update('month',currentMonth)}>本月</button>
     <button type="button" className="dashboard-refresh-button" aria-label="刷新数据" disabled={loading} onClick={()=>setRefresh(value=>value+1)}><Icon name="clock" size={14}/>{loading?'更新中':'刷新'}</button>
    </div>
   </div>
  </section>
  {error&&<div className="dashboard-state dashboard-error" role="alert"><Icon name="clock" size={25}/><strong>看板加载失败</strong><p>{error}</p><button type="button" className="secondary" onClick={()=>setRefresh(value=>value+1)}>重新加载</button></div>}
  {loading&&<div className="dashboard-state" role="status" aria-live="polite"><span className="dashboard-loading-mark"/><strong>正在汇总销售数据…</strong><p>读取订单、回款与预收款记录</p></div>}
  {!loading&&!error&&data&&<>
   <div className="dashboard-canvas">
    <div className="dashboard-top-grid">
     <section className="dashboard-goal-card" aria-label="公司月度业绩目标"><div className="dashboard-card-heading"><h2>业绩目标</h2><span>{selectedTargetScope?'参与部门目标汇总':automaticTarget?'销售目标自动汇总':'公司月度目标'}</span></div>
      {progress.state==='active'?<><strong className="dashboard-goal-percent">{progress.percent.toLocaleString('zh-CN',{maximumFractionDigits:1})}<small>%</small></strong><div className="dashboard-progress" role="progressbar" aria-label="月度业绩目标完成率" aria-valuemin={0} aria-valuemax={100} aria-valuenow={progress.width} aria-valuetext={progress.percent.toFixed(1)+'%'}><span style={{width:progress.width+'%'}}/></div><div className="dashboard-goal-amounts"><span>当前 {dashboardMoney(data.summary.orderAmount)}</span><span>目标 {dashboardMoney(data.target.amount)}</span></div></>:<div className="dashboard-goal-empty"><strong>{progress.state==='filtered'?'当前筛选不计算公司完成率':progress.state==='zero'?(emptyTargetScope?'暂无参与部门':'本月目标为 0 元'):'本月目标未设置'}</strong><span>{progress.state==='filtered'?'切换全部签单人与客户，查看公司目标进度。':progress.state==='zero'?(emptyTargetScope?'请先选择参与部门，再设置人员目标。':automaticTarget?'调整参与范围或人员目标后显示完成率。':'设置大于零的目标后显示完成率。'):'设置销售人员月目标，自动汇总公司目标。'}</span>{canManageTargets&&!onSalesTargets&&!automaticTarget&&progress.state!=='filtered'&&<button type="button" className="link-btn" onClick={onManageTargets}>设置公司目标 <Icon name="arrow" size={14}/></button>}</div>}
      <div className="dashboard-goal-source"><span>{automaticTarget?<>{selectedTargetScope&&'参与范围内 · '}已设置 {count(data.target.personCount)} 人{progress.state==='filtered'&&<> · 公司目标 {dashboardMoney(data.target.amount)}</>}</>:data.target.source==='manual'?'独立公司目标':'人员目标按月自动汇总'}</span>{onSalesTargets&&<button type="button" className="link-btn" onClick={()=>onSalesTargets(filters.month)}>{selectedTargetScope&&data.target.personCount===0?'查看参与范围':canManageTargets?(automaticTarget?'调整人员目标':'设置人员目标'):'查看人员目标'}<Icon name="arrow" size={13}/></button>}</div>
     </section>
     <button type="button" className="dashboard-count-card dashboard-count-settled" onClick={()=>openDetails('settled')}><span>已结清订单数量</span><strong>{count(data.summary.settledCount)}</strong><small>查看订单 <Icon name="arrow" size={14}/></small></button>
     <button type="button" className="dashboard-count-card dashboard-count-pending" onClick={()=>openDetails('pending')}><span>待回款订单数量</span><strong>{count(data.summary.pendingCount)}</strong><small>包含部分回款 <Icon name="arrow" size={14}/></small></button>
    </div>
    <div className="dashboard-money-grid">
     <Metric title="订单总金额" value={data.summary.orderAmount} note={'已批准销售订单 · '+count(data.summary.orderCount)+' 笔'} tone="orange" Icon={Icon} onClick={()=>openDetails('amount')}/>
     <Metric title="订单累计已回款" value={data.summary.settledAmount} note="截至当前，包含预收款核销" tone="blue" Icon={Icon} onClick={()=>openDetails('paid')}/>
     <Metric title="本月新增预收款" value={data.summary.advanceReceived} note="按收款登记日期统计" tone="olive" Icon={Icon} onClick={()=>openDetails('advance')}/>
     <Metric title="待回款金额" value={data.summary.outstandingAmount} note="所选月份订单截至当前未结清金额" tone="blue" Icon={Icon} onClick={()=>openDetails('outstanding')}/>
    </div>
    <section className="dashboard-chart-card"><div className="dashboard-chart-heading"><div><h2>每日订单金额</h2><p>已批准销售订单，金额单位：元</p></div><button type="button" className="link-btn" onClick={()=>openDetails('orders')}>全部订单明细 <Icon name="arrow" size={14}/></button></div>
     <div className="dashboard-chart" role="group" aria-label={filters.month+'每日订单金额柱状图，点击日期查看明细'}>
      <div className="dashboard-chart-y" aria-hidden="true">{[1,.75,.5,.25,0].map(scale=><span key={scale}>{axisAmount(maxAmount*scale)}</span>)}</div>
      <div className="dashboard-chart-plot"><div className="dashboard-chart-grid" aria-hidden="true">{[0,1,2,3,4].map(n=><span key={n}/>)}</div><div className="dashboard-chart-columns">{data.daily.map(day=><button type="button" key={day.date} className={'dashboard-chart-column'+(Number(day.amount)>0?' has-value':'')} aria-label={day.date+'，订单金额 '+dashboardMoney(day.amount)+'，'+day.count+' 笔，查看订单'} title={day.date+' · '+dashboardMoney(day.amount)+' · '+day.count+' 笔'} onClick={()=>openDetails('day',day.date)}><span className="dashboard-column-space"><span className="dashboard-column-fill" style={{height:(maxAmount>0?Math.max(0,Number(day.amount))/maxAmount*100:0)+'%'}}/></span><span className="dashboard-column-date">{day.date.slice(-2)}</span></button>)}</div></div>
     </div>
     {maxAmount===0&&<div className="dashboard-chart-empty">所选月份暂无销售订单金额，点击日期可查看明细。</div>}
    </section>
   </div>
   <div className="dashboard-footnotes"><div><Icon name="cash" size={17}/><span>当前预收款余额 <strong>{dashboardMoney(data.summary.advanceBalance)}</strong><small>{data.summary.advanceBalance===null?'签单人筛选下不分摊客户余额':'按当前客户余额统计，不随月份回溯'}</small></span></div><p>普通订单按创建日期归属；订单回款为截至当前的累计结算，后续补收会更新历史月份。</p></div>
   {data.warnings?.length>0&&<details className="dashboard-warnings"><summary>统计口径与数据提示（{data.warnings.length}）</summary><ul>{data.warnings.map((warning,index)=><li key={index}>{warning}</li>)}</ul></details>}
   <DialogLayer open={Boolean(selection)} onClose={()=>setSelection(null)} kind="drawer" label="看板统计明细"><div className="drawer dashboard-detail"><div className="modal-title"><h2>{selection?.kind==='day'?selection.date+' 订单明细':metricTitles[selection?.kind]||'统计明细'}</h2><button type="button" aria-label="关闭明细" onClick={()=>setSelection(null)}><Icon name="close" size={18}/></button></div><div className="modal-body"><p className="dashboard-detail-context">{context}</p><div className="dashboard-detail-total">共 {detailRows.length} 条记录 <strong>{dashboardMoney(sumDashboardMoney(detailRows.map(row=>selection?.kind==='advance'?row.amount:selection?.kind==='paid'?row.paid:selection?.kind==='outstanding'?row.outstanding:row.total)))}</strong></div><div className="table-scroll"><table className="dashboard-detail-table"><thead><tr>{(selection?.kind==='advance'?['登记日期','编号 / 客户','收款方式','收款金额']:['归属日期','订单 / 客户','签单人','订单金额','累计已回款','待回款']).map(header=><th key={header}>{header}</th>)}</tr></thead><tbody>{visibleDetails.map(row=>selection?.kind==='advance'?<tr key={row.id}><td>{row.date}</td><td>{row.order}<small>{row.customer}</small></td><td>{row.method}</td><td>{dashboardMoney(row.amount)}</td></tr>:<tr key={row.id}><td>{row.date}</td><td>{row.id}<small>{row.customer}</small>{row.source==='jushuitan-settlement'&&<small>结算补录</small>}</td><td>{row.owner||'—'}</td><td>{dashboardMoney(row.total)}</td><td>{dashboardMoney(row.paid)}</td><td>{dashboardMoney(row.outstanding)}</td></tr>)}</tbody></table></div>{!detailRows.length&&<div className="empty">暂无符合条件的记录</div>}<Pagination total={detailRows.length} page={currentDetailPage} pageSize={20} onPage={setDetailPage} label="看板明细分页"/><p className="dashboard-detail-context">订单金额使用已生效的执行金额；回款包含核销，金额不代表本月现金流入。</p></div><div className="modal-footer"><button type="button" className="secondary" onClick={()=>setSelection(null)}>关闭明细</button></div></div></DialogLayer>
  </>}
 </div>;
}

function Metric({title,value,note,tone,Icon,onClick}){
 return <button type="button" className={'dashboard-money-card dashboard-tone-'+tone} onClick={onClick}><span>{title}</span><strong>{dashboardMoney(value)}</strong><small>{note}<Icon name="arrow" size={15}/></small></button>;
}

function axisAmount(value){
 if(value>=100000000)return(value/100000000).toLocaleString('zh-CN',{maximumFractionDigits:1})+'亿';
 if(value>=10000)return(value/10000).toLocaleString('zh-CN',{maximumFractionDigits:1})+'万';
 return value.toLocaleString('zh-CN',{maximumFractionDigits:0});
}
