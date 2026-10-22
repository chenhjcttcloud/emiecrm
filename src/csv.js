// User text stays text when opened by spreadsheet applications; numeric amounts stay numeric.
export function csvCell(value){
 let text=String(value??'');
 if(typeof value!=='number'&&/^[\s\uFEFF]*[=+\-@]/u.test(text))text="'"+text;
 return '"'+text.replaceAll('"','""')+'"';
}
