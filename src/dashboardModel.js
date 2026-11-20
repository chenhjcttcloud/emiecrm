export const DASHBOARD_FILTER_KEY='emie-crm-dashboard-filters-v1';

export function currentDashboardMonth(){
 return new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit'}).format(new Date()).slice(0,7);
}

export function shiftDashboardMonth(value,step){
 if(typeof value!=='string'||!/^\d{4}-(0[1-9]|1[0-2])$/.test(value)||!Number.isSafeInteger(step))return value;
 const index=Number(value.slice(0,4))*12+Number(value.slice(5))-1+step;
 if(index<2000*12||index>9999*12+11)return value;
 return String(Math.floor(index/12)).padStart(4,'0')+'-'+String(index%12+1).padStart(2,'0');
}

export function normalizeDashboardFilters(value){
 const validMonth=typeof value?.month==='string'&&/^\d{4}-(0[1-9]|1[0-2])$/.test(value.month)&&Number(value.month.slice(0,4))>=2000&&Number(value.month.slice(0,4))<=9999;
 return{month:validMonth?value.month:currentDashboardMonth(),owner:typeof value?.owner==='string'?value.owner.slice(0,191):'',customerId:typeof value?.customerId==='string'?value.customerId.slice(0,191):''};
}

export function targetProgress(amount,target,applicable=true){
 if(!applicable)return{state:'filtered',percent:null,width:0};
 if(target===null||target===undefined||target==='')return{state:'unset',percent:null,width:0};
 const goal=Number(target),achieved=Number(amount);
 if(!Number.isFinite(goal)||goal<0||!Number.isFinite(achieved))return{state:'unset',percent:null,width:0};
 if(goal===0)return{state:'zero',percent:null,width:0};
 const percent=Math.max(0,achieved/goal*100);
 return{state:'active',percent,width:Math.min(100,percent)};
}

export function validTargetAmount(value){
 const text=String(value??'');
 if(!/^\d{1,12}(?:\.\d{1,2})?$/.test(text))return false;
 return Number(text)<=999999999999.99;
}

export function dashboardMoney(value){
 const cents=minorUnits(value);
 if(cents===null)return'—';
 const negative=cents<0n,absolute=negative?-cents:cents;
 return'¥ '+(negative?'-':'')+(absolute/100n).toLocaleString('zh-CN')+'.'+String(absolute%100n).padStart(2,'0');
}

function minorUnits(value){
 if(value===null||value===undefined||value==='')return null;
 const match=String(value).match(/^(-?)(\d+)(?:\.(\d+))?$/);
 if(!match)return null;
 const fraction=match[3]||'';
 const absolute=BigInt(match[2])*100n+BigInt(fraction.slice(0,2).padEnd(2,'0'))+(fraction.length>2&&Number(fraction[2])>=5?1n:0n);
 return match[1]?-absolute:absolute;
}

export function sumDashboardMoney(values){
 let total=0n;
 for(const value of values){const cents=minorUnits(value);if(cents===null)return null;total+=cents}
 const negative=total<0n,absolute=negative?-total:total;
 return(negative?'-':'')+String(absolute/100n)+'.'+String(absolute%100n).padStart(2,'0');
}

export function summarizeSalesTargets(rows){
 const configured=(Array.isArray(rows)?rows:[]).filter(row=>typeof row?.owner==='string'&&row.owner.trim()&&row.amount!==null&&row.amount!==undefined);
 if(!configured.length)return{amount:null,personCount:0};
 return{amount:configured.every(row=>validTargetAmount(row.amount))?sumDashboardMoney(configured.map(row=>row.amount)):null,personCount:configured.length};
}

export function dashboardTime(value){
 const date=new Date(value);
 return Number.isNaN(date.getTime())?'—':date.toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false});
}

export function dashboardDetailRows(data,selection){
 if(!data||!selection)return[];
 if(selection.kind==='advance')return data.advanceReceipts||[];
 const rows=data.orders||[];
 if(selection.kind==='settled')return rows.filter(row=>row.settled);
 if(selection.kind==='pending'||selection.kind==='outstanding')return rows.filter(row=>Number(row.outstanding)>0);
 if(selection.kind==='day')return rows.filter(row=>row.date===selection.date);
 return rows;
}
