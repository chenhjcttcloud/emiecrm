import React,{useEffect,useRef,useState} from 'react';
import{currentDashboardMonth,dashboardMoney,dashboardTime,validTargetAmount}from'./dashboardModel';
import './dashboard.css';

export default function DashboardTargets({api,Icon,onSalesTargets}){
 const[year,setYear]=useState(()=>currentDashboardMonth().slice(0,4)),[data,setData]=useState(null),[loading,setLoading]=useState(true),[error,setError]=useState(''),[notice,setNotice]=useState(''),[editing,setEditing]=useState(null),[busy,setBusy]=useState(false),[reload,setReload]=useState(0);
 const request=useRef(0),active=useRef(true);
 useEffect(()=>{active.current=true;return()=>{active.current=false}},[]);
 useEffect(()=>{
  let valid=true;const id=++request.current;setLoading(true);setError('');setData(null);
  api('dashboard/targets?'+new URLSearchParams({year})).then(next=>{if(!next||!Array.isArray(next.targets)||next.targets.length!==12)throw Error('目标数据格式无效，请重试');if(valid&&request.current===id)setData(next)}).catch(e=>{if(valid&&request.current===id)setError(e.message||'加载失败，请重试')}).finally(()=>{if(valid&&request.current===id)setLoading(false)});
  return()=>{valid=false};
 },[api,year,reload]);
 async function save(event){
  event.preventDefault();if(busy||!editing||loading||!data||stale)return;
  if(!validTargetAmount(editing.amount)){setError('目标须为 0 至 999,999,999,999.99 元，最多两位小数');return}
  const draft={...editing};setBusy(true);setError('');setNotice('');
  try{
   const saved=await api('dashboard/targets',{month:draft.month,amount:draft.amount,revision:draft.revision});
   if(!active.current)return;
   setData(previous=>previous?{...previous,targets:previous.targets.map(target=>target.month===saved.month?saved:target)}:previous);setEditing(null);setNotice(draft.month+' 业绩目标已保存');
  }catch(e){
   if(!active.current)return;
   if(e.status===409){setNotice('目标已变化，已重新加载；请核对最新金额和目标来源。');setReload(value=>value+1)}
   setError(e.message||'保存失败，请重试');
  }finally{if(active.current)setBusy(false)}
 }
 const editingTarget=data?.targets.find(target=>target.month===editing?.month);
 const automaticEdit=editingTarget?.source==='personnel';
 const stale=Boolean(editing&&editingTarget&&(editing.revision!==editingTarget.revision||automaticEdit));
 return <div className="dashboard-targets"><div className="page-heading"><div><div className="breadcrumb">经营看板 <span>/</span> 业绩目标</div><h1>公司业绩目标</h1><p>按月汇总销售人员目标，用于销售看板的完成进度统计。</p></div><div className="page-heading-symbol"><Icon name="orders" size={28}/></div></div>
  <div className="dashboard-target-toolbar"><label className="dashboard-target-year"><span>目标年份</span><select aria-label="目标年份" value={year} disabled={busy} onChange={e=>{setYear(e.target.value);setEditing(null);setNotice('')}}>{Array.from(new Set([Number(year),...Array.from({length:11},(_,index)=>Number(currentDashboardMonth().slice(0,4))-5+index)])).sort((a,b)=>a-b).map(value=><option key={value} value={value}>{value} 年</option>)}</select></label><span>人员目标自动汇总 · 按月查看</span><button type="button" className="dashboard-target-refresh" disabled={loading||busy} onClick={()=>setReload(value=>value+1)}><Icon name="clock" size={14}/>刷新</button></div>
  {notice&&<p role="status" className="dashboard-notice">{notice}</p>}{error&&<div role="alert" className="dashboard-form-error">{error}{!editing&&<button type="button" className="link-btn" onClick={()=>setReload(value=>value+1)}>重新加载</button>}</div>}
  {editing&&<form className="dashboard-target-editor" aria-label="编辑月度业绩目标" onSubmit={save}>
   <div className="dashboard-target-editor-main">
    <div className="dashboard-target-period"><span className="dashboard-target-period-icon"><Icon name="edit" size={18}/></span><div><span>独立公司目标</span><h2>{editing.month.slice(0,4)} 年 {Number(editing.month.slice(-2))} 月</h2></div></div>
    <label className="dashboard-target-amount-field"><span>目标金额</span><div className="dashboard-target-money-input"><span aria-hidden="true">¥</span><input key={editing.month} aria-label="目标金额（元）" type="text" inputMode="decimal" maxLength={15} autoFocus required disabled={busy||automaticEdit} placeholder="请输入目标金额" value={editing.amount} onChange={e=>setEditing(previous=>({...previous,amount:e.target.value}))}/><span aria-hidden="true">元</span></div></label>
    <div className="dashboard-target-editor-actions"><button type="button" className="secondary" disabled={busy} onClick={()=>{setEditing(null);setError('')}}>取消</button><button type="submit" className="primary" disabled={busy||loading||!data||stale}>{busy?'正在保存…':'保存目标'}</button></div>
   </div>
   <p className="dashboard-target-editor-hint">仅在当月尚未设置人员目标时使用；人员目标设置后自动优先汇总。金额最多两位小数。</p>
   {stale&&<p className="dashboard-conflict" role="status">{automaticEdit?<>本月已按人员目标自动汇总为 {dashboardMoney(editingTarget.amount)}，请到销售业绩目标调整。{onSalesTargets&&<button type="button" className="link-btn" onClick={()=>onSalesTargets(editing.month)}>调整人员目标</button>}</>:<>当前最新目标为 {dashboardMoney(editingTarget.amount)}，由 {editingTarget.updatedBy||'其他管理员'} 更新。<button type="button" className="link-btn" onClick={()=>{setEditing(previous=>({...previous,revision:editingTarget.revision}));setError('');setNotice('已采用最新版本，保存将使用上方填写的金额。')}}>核对后使用最新版本</button></>}</p>}
  </form>}
  <section className="panel"><div className="table-scroll"><table className="dashboard-target-table"><thead><tr>{['月份','公司销售目标','目标来源','最近更新','修改人','操作'].map(label=><th key={label}>{label}</th>)}</tr></thead><tbody>{!loading&&data?.targets.map(target=>{const automatic=target.source==='personnel';return <tr key={target.month} className={editing?.month===target.month?'is-editing':''}><td>{Number(target.month.slice(-2))} 月</td><td className="dashboard-target-amount">{dashboardMoney(target.amount)}</td><td><span className={'dashboard-target-status '+(automatic?'is-auto':target.amount===null?'is-unset':'is-set')}>{automatic?'人员目标汇总':target.amount===null?'未设置':'独立公司目标'}</span>{automatic&&<small className="dashboard-target-person-count">已设置 {target.personCount} 人</small>}</td><td>{!automatic&&target.updatedAt?dashboardTime(target.updatedAt):'—'}</td><td>{!automatic&&target.updatedBy||'—'}</td><td><div className="dashboard-target-row-actions">{onSalesTargets&&<button type="button" className="link-btn" disabled={busy} onClick={()=>onSalesTargets(target.month)}>{automatic?'调整人员目标':'设置人员目标'} <Icon name="arrow" size={13}/></button>}{!automatic&&<button type="button" className="link-btn dashboard-target-manual-action" disabled={busy} onClick={()=>{setError('');setNotice('');setEditing({month:target.month,amount:target.amount===null?'':String(target.amount),revision:target.revision})}}>{target.amount===null?'单独设置公司目标':'编辑公司目标'}</button>}{automatic&&!onSalesTargets&&<span className="dashboard-target-action-note">由人员目标自动汇总</span>}</div></td></tr>})}</tbody></table></div>{loading&&<div className="empty" role="status">正在加载业绩目标…</div>}{!loading&&!data&&!error&&<div className="empty">暂无目标数据</div>}</section><p className="dashboard-target-footnote">人员月目标设置后自动汇总；尚未设置人员目标的月份沿用独立公司目标。按签单人或客户筛选时不计算公司完成率。</p>
 </div>;
}
