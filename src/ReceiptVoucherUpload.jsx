import React,{useEffect,useRef,useState} from 'react';
import FileDropZone from './FileDropZone';
import BusinessImage from './BusinessImage';
import {uploadImage} from './authApi';
import {copyReceiptVouchers,privateVoucherUrl,RECEIPT_VOUCHER_ACCEPT,RECEIPT_VOUCHER_LIMIT,validateVoucherFiles} from './receiptVoucherModel';
import './receiptVouchers.css';

export function ReceiptVoucherGallery({attachments=[],title='收款凭证',onRemove,disabled=false,empty=false}){
 const images=Array.isArray(attachments)?attachments:[];
 if(!images.length&&!empty)return null;
 return <section className="receipt-vouchers" aria-label={title}><h3>{title}</h3>
  {!images.length?<p className="form-hint">暂无本次收款凭证。</p>:<div className="receipt-voucher-images">{images.map((image,index)=><figure key={`${image?.url}-${index}`}>
   {privateVoucherUrl(image?.url)?<BusinessImage link src={image.url} alt={image.name||'收款凭证 '+(index+1)} loading="lazy"/>:<span className="form-hint">历史图片暂不可预览</span>}
   <figcaption title={image?.name}>{image?.name||'收款凭证 '+(index+1)}</figcaption>
   {onRemove&&<button type="button" className="link-btn" disabled={disabled} aria-label={'删除收款凭证 '+(image?.name||index+1)} onClick={()=>onRemove(index)}>删除</button>}
  </figure>)}</div>}
 </section>;
}

export default function ReceiptVoucherUpload({value=[],onChange,disabled=false,onBusyChange,upload=uploadImage}){
 const[busy,setBusy]=useState(false),[error,setError]=useState('');
 const locked=useRef(false),current=useRef(value),active=useRef(true),busyCallback=useRef(onBusyChange);current.current=value;busyCallback.current=onBusyChange;
 useEffect(()=>{active.current=true;return()=>{active.current=false;busyCallback.current?.(false)}},[]);
 async function receive(files){
  if(disabled||locked.current)return;
  setError('');
  try{validateVoucherFiles(files,current.current.length)}catch(problem){setError(problem.message);return}
  locked.current=true;setBusy(true);onBusyChange?.(true);
  try{
   const results=await Promise.allSettled(files.map(file=>Promise.resolve().then(()=>upload(file))));
   if(!active.current)return;
   const images=[],failures=[];
   results.forEach((result,index)=>{
    if(result.status==='rejected'){failures.push(files[index].name+'：'+(result.reason?.message||'上传失败'));return}
    try{images.push(...copyReceiptVouchers([result.value]))}catch(problem){failures.push(files[index].name+'：'+problem.message)}
   });
   if(images.length)onChange?.([...current.current,...images]);
   if(failures.length)setError((images.length?'部分凭证未上传，已成功的图片已保留。':'收款凭证未上传成功，请重试。')+failures.join('；'));
  }finally{locked.current=false;if(active.current){setBusy(false);busyCallback.current?.(false)}}
 }
 const images=Array.isArray(value)?value:[],blocked=disabled||busy;
 return <div className="receipt-voucher-upload">
  <ReceiptVoucherGallery attachments={images} empty onRemove={index=>{if(!blocked)onChange?.(images.filter((_,position)=>position!==index))}} disabled={blocked}/>
  <FileDropZone label="上传收款凭证图片" accept={RECEIPT_VOUCHER_ACCEPT} multiple disabled={blocked||images.length>=RECEIPT_VOUCHER_LIMIT} onFiles={receive}/>
  <p className="form-hint">PNG、JPEG、GIF、WebP，每张不超过5MB，最多10张；可预览或删除，不必填。</p>
  {images.length>RECEIPT_VOUCHER_LIMIT&&<p className="form-hint">历史已保存{images.length}张凭证，原记录保留，暂不能新增。</p>}
  {busy&&<p className="form-hint" role="status">正在上传收款凭证，上传完成后可提交。</p>}
  {error&&<p className="system-error" role="alert">{error}</p>}
 </div>;
}
