import React,{useRef,useState} from 'react';
import {validateFiles} from './fileDrop.js';

export default function FileDropZone({accept,multiple=false,disabled=false,onFiles,label='上传文件',children,inputRef}){
 const localInput=useRef(null),depth=useRef(0),locked=useRef(false);
 const[dragging,setDragging]=useState(false),[pending,setPending]=useState(false),[error,setError]=useState('');
 const blocked=disabled||pending;
 async function receive(files){
  if(disabled||locked.current)return;
  const list=Array.from(files||[]);if(!list.length)return;
  const message=validateFiles(list,{accept,multiple});setError(message);if(message)return;
  locked.current=true;setPending(true);
  try{await onFiles(list)}catch(e){setError(e.message||'文件处理失败')}finally{locked.current=false;setPending(false)}
 }
 function stop(e){e.preventDefault();e.stopPropagation()}
 return <div className={'file-drop-zone'+(dragging?' is-dragging':'')+(blocked?' is-disabled':'')} aria-busy={blocked} onDragEnter={e=>{stop(e);if(e.dataTransfer.types.includes('Files')){depth.current++;if(!blocked)setDragging(true)}}} onDragOver={e=>{stop(e);e.dataTransfer.dropEffect=blocked?'none':'copy'}} onDragLeave={e=>{stop(e);if(--depth.current<=0){depth.current=0;setDragging(false)}}} onDrop={e=>{stop(e);depth.current=0;setDragging(false);receive(e.dataTransfer.files)}}>
  <input ref={node=>{localInput.current=node;if(inputRef)inputRef.current=node}} className="file-drop-input" type="file" accept={accept} multiple={multiple} disabled={blocked} aria-label={label} onChange={e=>{const files=Array.from(e.target.files||[]);e.target.value='';receive(files)}}/>
  {children}
  <button type="button" className="file-drop-button" disabled={blocked} onClick={e=>{e.preventDefault();localInput.current.click()}}>{blocked?'正在处理…':label}</button>
  <span className="file-drop-hint">{dragging?'松开即可上传':'或将文件拖拽到此处'}</span>
  {error&&<span className="file-drop-error" role="alert">{error}</span>}
 </div>;
}
