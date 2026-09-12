export const MAX_ORDER_USDC = 30;

export function validateOrder(order: any) {
  const symbol = String(order.symbol || "").toUpperCase().replace(/[^A-Z0-9]/g, "");
  const side = String(order.side || "").toUpperCase();
  const quote = Number(order.quoteAmountUsdc);
  const price = Number(order.limitPrice);
  const base = order.baseQuantity == null ? null : Number(order.baseQuantity);
  if (!/^[A-Z0-9]{2,20}USDC$/.test(symbol) || symbol === "USDCUSDC" || !["BUY", "SELL"].includes(side) ||
      (order.orderType && order.orderType !== "LIMIT") || !Number.isFinite(quote) || quote <= 1 || quote > MAX_ORDER_USDC ||
      !Number.isFinite(price) || price <= 0 ||
      (base !== null && (!Number.isFinite(base) || base <= 0 || base * price > quote + 1e-8)) ||
      (side === "SELL" && base === null)) throw new Error("invalid_batch_order");
  return {symbol, side, order_type: "LIMIT", quote_amount_usdc: quote, limit_price: price, base_quantity: base};
}

export function summarize(ids: string[], rows: any[], now = Date.now()) {
  const orders = ids.map(id => {
    const row = rows.find(x => x.id === id);
    if (!row) return {id, state: "missing", confirmed: false};
    const exchangeStatus = String(row.result?.orderStatus || "");
    const confirmed = row.status === "executed" && !!row.bybit_order_id &&
      ["New", "PartiallyFilled", "Filled"].includes(exchangeStatus);
    const state = confirmed ? "placed" : row.status === "pending" && Date.parse(row.expires_at) <= now ? "expired" :
      row.status === "pending" && row.result?.blocked ? "blocked" :
      row.status === "executed" ? "unconfirmed_or_closed" : row.status;
    return {id, state, confirmed, orderId: row.bybit_order_id || null, exchangeStatus,
      reason: row.result?.reason || row.result?.error || null};
  });
  const confirmed = orders.filter(x => x.confirmed).length;
  return {allConfirmed: ids.length > 0 && confirmed === ids.length, confirmed, total: ids.length,
    pending: orders.filter(x => ["pending", "processing", "missing"].includes(x.state)).length, orders};
}

/** Read-only wait. A timeout is pending, never success. All lookups are account-scoped. */
export async function waitForBatch(sb: any, account: string, ids: string[], timeoutMs = 20000) {
  if (!Array.isArray(ids) || ids.length < 1 || ids.length > 20 || new Set(ids).size !== ids.length ||
      ids.some(id => !/^[a-f0-9-]{36}$/i.test(id))) throw new Error("invalid_proposal_ids");
  const deadline = Date.now() + Math.max(0, Math.min(20000, Number(timeoutMs) || 0));
  while (true) {
    const {data, error} = await sb.from("chk_trade_proposals")
      .select("id,status,bybit_order_id,result,expires_at")
      .eq("account_fingerprint", account).in("id", ids);
    if (error) throw error;
    const result = summarize(ids, data || []);
    if (result.allConfirmed || result.pending === 0 || Date.now() >= deadline) return result;
    await new Promise(resolve => setTimeout(resolve, Math.min(1000, deadline - Date.now())));
  }
}

export async function createBatch(sb: any, account: string, body: any) {
  const batchId = String(body.batchId || "");
  if (!/^[a-f0-9-]{36}$/i.test(batchId) || !Array.isArray(body.orders) || body.orders.length < 1 || body.orders.length > 20)
    throw new Error("invalid_batch");
  // Validate every row before insertion. One multi-row insert is atomic.
  const validated = body.orders.map(validateOrder);
  const ids: string[] = [];
  for (let i = 0; i < validated.length; i++) {
    const bytes = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(`${account}:${batchId}:${i}`)));
    const hex = Array.from(bytes.slice(0, 16), b => b.toString(16).padStart(2, "0")).join("");
    ids.push(`${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`);
  }
  const rows = validated.map((order: any, i: number) => ({...order, id: ids[i], account_fingerprint: account,
    exchange: "BYBIT", source: `chatgpt-batch:${batchId}`, status: "pending",
    rationale: String(body.orders[i].rationale || "").slice(0,4000), expires_at: new Date(Date.now() + 120 * 60000).toISOString()}));
  const {error} = await sb.from("chk_trade_proposals").insert(rows);
  if (error) {
    if (error.code !== "23505") throw error;
    const {data, error: readError} = await sb.from("chk_trade_proposals").select("*").eq("account_fingerprint", account).eq("source", `chatgpt-batch:${batchId}`);
    if (readError) throw readError;
    if (!data || data.length !== rows.length || rows.some((row: any) => {
      const old = data.find((x: any) => x.id === row.id);
      return !old || old.symbol !== row.symbol || old.side !== row.side || old.order_type !== row.order_type ||
        Number(old.quote_amount_usdc) !== row.quote_amount_usdc || Number(old.limit_price) !== row.limit_price ||
        (old.base_quantity == null ? null : Number(old.base_quantity)) !== row.base_quantity;
    })) throw new Error("batch_id_conflict");
  }
  return {ok: true, batchId, proposalIds: ids, ...(await waitForBatch(sb, account, ids, 20000))};
}
