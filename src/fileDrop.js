export function validateFiles(files,{accept='',multiple=false}={}){
 if(!multiple&&files.length>1)return '每次只能选择一个文件';
 const allowed=accept.split(',').map(s=>s.trim().toLowerCase()).filter(Boolean);
 if(allowed.length&&files.some(file=>!allowed.some(rule=>rule.startsWith('.')?file.name.toLowerCase().endsWith(rule):rule.endsWith('/*')?file.type.toLowerCase().startsWith(rule.slice(0,-1)):file.type.toLowerCase()===rule)))return '文件类型不支持，请选择指定格式的文件';
 return '';
}
