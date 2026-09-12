import test from 'node:test';
import assert from 'node:assert/strict';
import { validateOrder, summarize, waitForBatch, createBatch } from '../supabase/chk-mcp-bridge/batch.ts';
const ids = Array.from({length:5}, (_,i)=>`00000000-0000-0000-0000-00000000000${i}`);
const order = {symbol:'RENDERUSDC',side:'BUY',orderType:'LIMIT',quoteAmountUsdc:5,limitPrice:1};
const placed = ids.map(id=>({id,status:'executed',bybit_order_id:`bybit-${id}`,result:{orderStatus:'New'}}));
function mock(rows = []) {
 const db = {rows, inserts:0};
 db.from = () => {
  const q = {select(){return this},eq(k,v){this[k]=v; return this},in(k,v){this.ids=v;return this},
   insert(batch){db.inserts++;if(batch.some(x=>db.rows.some(y=>y.id===x.id)))return Promise.resolve({error:{code:'23505'}}); db.rows.push(...batch.map(x=>({...x,status:'executed',bybit_order_id:`bybit-${x.id}`,result:{orderStatus:'New'}})));return Promise.resolve({error:null})},
   then(resolve,reject){return Promise.resolve({data:db.rows.filter(x=>(!this.account_fingerprint||x.account_fingerprint===this.account_fingerprint)&&(!this.source||x.source===this.source)&&(!this.ids||this.ids.includes(x.id))),error:null}).then(resolve,reject)}};
  return q;
 };return db;
}
test('accepts 30 USDC and rejects 30.01, NaN, Infinity and one dollar',()=>{
 assert.equal(validateOrder({...order,quoteAmountUsdc:30}).quote_amount_usdc,30);
 for(const n of [30.01,NaN,Infinity,1,-1])assert.throws(()=>validateOrder({...order,quoteAmountUsdc:n}));
});
test('rejects wrong market and SELL quantities exceeding quoted budget',()=>{
 assert.throws(()=>validateOrder({...order,symbol:'BTCUSDT'}));
 assert.throws(()=>validateOrder({...order,side:'SELL'}));
 assert.throws(()=>validateOrder({...order,side:'SELL',baseQuantity:6}));
});
test('five confirmed placements are required for success',()=>{
 assert.equal(summarize(ids,placed).allConfirmed,true);
 assert.equal(summarize(ids,placed.slice(0,1)).allConfirmed,false);
 assert.equal(summarize(ids,placed.slice(0,1)).pending,4);
});
test('SENT, missing order ID, rejected and cancelled are never placements',()=>{
 for(const result of [{orderStatus:'SENT'},{orderStatus:'Rejected'},{orderStatus:'Cancelled'}])
  assert.equal(summarize([ids[0]],[{...placed[0],result}]).allConfirmed,false);
 assert.equal(summarize([ids[0]],[{...placed[0],bybit_order_id:''}]).allConfirmed,false);
});
test('reports blocked, expired and processing individually',()=>{
 const rows=[{id:ids[0],status:'pending',result:{blocked:true,reason:'daily_cap'}},
 {id:ids[1],status:'pending',expires_at:'2000-01-01'}, {id:ids[2],status:'processing'}];
 const r=summarize(ids.slice(0,3),rows);
 assert.deepEqual(r.orders.map(x=>x.state),['blocked','expired','processing']);assert.equal(r.pending,1);
});
test('wait cannot read another account and timeout is not success',async()=>{
 const db=mock(placed.map(x=>({...x,account_fingerprint:'other'})));
 assert.equal((await waitForBatch(db,'owner',ids,0)).allConfirmed,false);
});
test('batch inserts all five atomically and a retry preserves IDs',async()=>{
 const db=mock();const body={batchId:ids[0],orders:Array.from({length:5},()=>({...order}))};
 const a=await createBatch(db,'owner',body),b=await createBatch(db,'owner',body);
 assert.equal(a.allConfirmed,true);assert.equal(a.total,5);assert.equal(db.rows.length,5);
 assert.deepEqual(a.proposalIds,b.proposalIds);
 await assert.rejects(createBatch(db,'owner',{...body,orders:body.orders.slice(0,4)}),/batch_id_conflict/);
 await assert.rejects(createBatch(db,'owner',{...body,orders:body.orders.map(x=>({...x,limitPrice:2}))}),/batch_id_conflict/);
});
test('invalid fifth order cannot create the first four',async()=>{
 const db=mock();await assert.rejects(createBatch(db,'owner',{batchId:ids[0],orders:[order,order,order,order,{...order,quoteAmountUsdc:31}]}));
 assert.equal(db.inserts,0);
});
test('duplicate IDs cannot be counted twice',async()=>{
 await assert.rejects(waitForBatch(mock(),'owner',[ids[0],ids[0]],0),/invalid_proposal_ids/);
});
test('MCP adapter requires follow-up for incomplete batches',async()=>{
 const {handleBatchTool,batchTools}=await import('../mcp/batch-tools.mjs');
 const r=await handleBatchTool('create_trade_batch',{batchId:ids[0]},async()=>({ok:true,allConfirmed:false,pending:4,proposalIds:ids}));
 assert.match(r.content[0].text,/Continuer wait_trade_batch/);
 assert.equal(batchTools[0].inputSchema.properties.orders.items.properties.quoteAmountUsdc.maximum,30);
});
