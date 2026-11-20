export const emptyBusinessData=()=>({customers:[],products:[],orders:[]});

// Keep background reads and writes in one place so a delayed read cannot undo a
// successful save. Requests from an older mounted/permission lifecycle are ignored.
export function createBusinessDataStore(api,onChange=()=>{},{now=()=>Date.now()}={}){
 let snapshot={data:emptyBusinessData(),loading:true,loadError:'',refreshing:false,refreshError:''};
 let active=false,canWrite=false,loaded=false,generation=0,inflight=null;
 let confirmedAt=-Infinity;
 const publish=patch=>{
  snapshot={...snapshot,...patch};
  if(active)onChange(snapshot);
 };
 const current=version=>active&&version===generation;
 const accept=(value,confirmed=false)=>{
  const next=value??emptyBusinessData();
  if(Number(next.revision??0)<Number(snapshot.data.revision??0))return snapshot.data;
  if(next===snapshot.data)return next;
  if(confirmed)confirmedAt=now();
  publish({data:next});
  return next;
 };

 function refresh(options={}){
  if(!active)return Promise.resolve(null);
  if(inflight)return inflight;
  const maxAgeMs=options?.maxAgeMs,age=now()-confirmedAt;
  if(loaded&&!snapshot.refreshError&&Number.isFinite(maxAgeMs)&&maxAgeMs>0&&age>=0&&age<maxAgeMs)
   return Promise.resolve(snapshot.data);
  const version=generation,initial=!loaded;
  const requestedRevision=loaded&&Number.isSafeInteger(snapshot.data.revision)&&snapshot.data.revision>=0?snapshot.data.revision:null;
  publish(initial?{loading:true,loadError:''}:{refreshing:true,refreshError:''});
  const request=Promise.resolve().then(async()=>{
   try{
    let stored=await api(requestedRevision===null?'state':'state?knownRevision='+requestedRevision);
    if(!current(version))return null;
    if(stored?.unchanged===true){
     if(requestedRevision===null||stored.revision!==requestedRevision||
      ['customers','products','orders'].some(key=>Object.hasOwn(stored,key)))
      throw Error('服务器返回了无效的业务版本，请稍后重试。');
     if(snapshot.data.revision===requestedRevision)confirmedAt=now();
     publish({loadError:'',refreshError:''});
     return snapshot.data;
    }
    if(stored==null&&canWrite){
     try{stored=await api('state',{data:emptyBusinessData()})}
     catch(error){
      if(!current(version))return null;
      if(error.status!==409)throw error;
      stored=await api('state');
     }
    }
    if(!current(version))return null;
    loaded=true;
    publish({loadError:'',refreshError:''});
    return accept(stored,true);
   }catch(error){
    if(current(version)){
     confirmedAt=-Infinity;
     publish(initial?{loadError:error.message||'请稍后重试。'}:{refreshError:error.message||'刷新失败，请稍后重试。'});
    }
    return null;
   }finally{
    if(current(version)){
     if(inflight===request)inflight=null;
     publish({loading:false,refreshing:false});
    }
   }
  });
  inflight=request;
  return request;
 }

 return {
  getSnapshot:()=>snapshot,
  start(writePermission){
   if(active&&canWrite===writePermission)return refresh();
   active=true;
   canWrite=writePermission;
   generation++;
   inflight=null;
   confirmedAt=-Infinity;
   return refresh();
  },
  dispose(){active=false;generation++;inflight=null},
  refresh,
  retry:refresh,
  setData(update){
   if(!active)return snapshot.data;
   return accept(typeof update==='function'?update(snapshot.data):update,true);
  },
  async save(next,receiptVerification){
   if(!active)return null;
   const version=generation;
   // The submitted snapshot owns its revision. A refresh must not make an old
   // form appear current and silently bypass the server's conflict protection.
   const stored=await api('state',{data:next,revision:next.revision??snapshot.data.revision,
    ...(receiptVerification?{receiptVerification}:{})});
   if(!current(version))return null;
   return accept(stored,true);
  }
 };
}
