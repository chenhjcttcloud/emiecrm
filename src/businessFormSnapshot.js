// A background read may advance the list while a form is open. Keep both its
// data and its revision together so the server can reject a stale submission.
export function captureBusinessForm(data){
 return structuredClone(data);
}

export function replaceBusinessFormRecord(base,collection,entry){
 const records=base[collection];
 return {...base,[collection]:records.some(record=>record.id===entry.id)
  ?records.map(record=>record.id===entry.id?entry:record)
  :[entry,...records]};
}
