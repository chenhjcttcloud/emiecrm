import {useCallback,useEffect,useRef,useState} from 'react';
import {api} from './authApi';
import {createBusinessDataStore} from './businessDataStore';

export default function useDatabase(canWrite){
 const store=useRef(null);
 const[snapshot,setSnapshot]=useState(()=>{
  store.current=createBusinessDataStore(api,setSnapshotLater);
  return store.current.getSnapshot();
 });
 function setSnapshotLater(value){setSnapshot(value)}

 useEffect(()=>{
  store.current.start(canWrite);
  return()=>store.current.dispose();
 },[canWrite]);

 const save=useCallback((next,receiptVerification)=>store.current.save(next,receiptVerification),[]);
 const setDatabase=useCallback(update=>store.current.setData(update),[]);
 const retry=useCallback(()=>store.current.retry(),[]);
 const refresh=useCallback(options=>store.current.refresh(options),[]);
 return[snapshot.data,save,setDatabase,snapshot.loading,snapshot.loadError,retry,
  refresh,snapshot.refreshing,snapshot.refreshError];
}
