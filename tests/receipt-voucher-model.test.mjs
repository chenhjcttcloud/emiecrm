import test from 'node:test';
import assert from 'node:assert/strict';
import {copyReceiptVouchers,privateVoucherUrl,RECEIPT_VOUCHER_SIZE,validateVoucherFiles} from '../src/receiptVoucherModel.js';

test('receipt vouchers only reference authenticated internal assets and preserve immutable attachment snapshots',()=>{
 const image={name:'银行到账.png',url:'/api/assets/private-image_1.png'},input=[image];
 const copy=copyReceiptVouchers(input);assert.deepEqual(copy,input);assert.notEqual(copy,input);assert.notEqual(copy[0],image);
 image.name='后来修改.png';input.push({name:'另外一张.png',url:'/api/assets/second.png'});
 assert.equal(copy.length,1);assert.equal(copy[0].name,'银行到账.png');
 for(const url of ['https://example.test/proof.png','data:image/png;base64,test','blob:other','/api/assets/../secret','/api/assets/file.png?token=secret','/api/assets/..hidden']){
  assert.equal(privateVoucherUrl(url),false);assert.throws(()=>copyReceiptVouchers([{name:'test.png',url}]),/内部图片/);
 }
 assert.deepEqual(copyReceiptVouchers(undefined),[]);assert.deepEqual(copyReceiptVouchers([]),[]);
 assert.throws(()=>copyReceiptVouchers(Array.from({length:11},()=>({name:'图.png',url:'/api/assets/one.png'}))),/最多上传10/);
});

test('voucher file limits allow supported images at 5MB and reject invalid batches without changing old records',()=>{
 const image={name:'receipt.png',type:'image/png',size:RECEIPT_VOUCHER_SIZE};
 assert.doesNotThrow(()=>validateVoucherFiles([image],9));
 for(const type of ['image/png','image/jpeg','image/gif','image/webp'])assert.doesNotThrow(()=>validateVoucherFiles([{...image,type}]));
 assert.doesNotThrow(()=>validateVoucherFiles([{...image,type:'',name:'receipt.JPG'}]));
 assert.throws(()=>validateVoucherFiles([image],10),/最多上传10/);
 for(const size of [0,RECEIPT_VOUCHER_SIZE+1])assert.throws(()=>validateVoucherFiles([{...image,size}]),/5MB/);
 assert.throws(()=>validateVoucherFiles([{...image,type:'application/pdf',name:'receipt.pdf'}]),/仅支持/);
 assert.throws(()=>validateVoucherFiles([{...image,name:'x'.repeat(256)}]),/名称无效/);
});
