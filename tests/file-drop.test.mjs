import {test} from 'node:test';
import assert from 'node:assert/strict';
import {validateFiles} from '../src/fileDrop.js';
const image={name:'图片.png',type:'image/png'},json={name:'backup.JSON',type:''};
test('图片附件支持批量拖入，拒绝不支持的文件及混合批次',()=>{
 const options={multiple:true,accept:'image/png,image/jpeg,image/gif,image/webp'};
 assert.equal(validateFiles([image,{name:'photo.webp',type:'image/webp'}],options),'');
 assert.ok(validateFiles([image,{name:'script.html',type:'text/html'}],options));
});
test('产品图片和备份只接受一个文件，备份兼容无 MIME 的 JSON 文件',()=>{
 assert.ok(validateFiles([image,image],{accept:'image/*'}));
 assert.equal(validateFiles([image],{accept:'image/*'}),'');
 assert.equal(validateFiles([json],{accept:'application/json,.json'}),'');
 assert.ok(validateFiles([image],{accept:'application/json,.json'}));
});
