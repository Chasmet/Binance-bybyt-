-- Batches intentionally contain multiple slices of the same symbol/side.
-- Their idempotency is enforced by deterministic primary keys and
-- chk_trade_batch_position; submission order is enforced by chk_reserve_submission.
-- Preserve the legacy singleton guard for proposals outside tracked batches.
DROP INDEX public.chk_trade_proposals_one_pending_per_symbol_side;
CREATE UNIQUE INDEX chk_trade_proposals_one_pending_per_symbol_side
  ON public.chk_trade_proposals(account_fingerprint,symbol,side)
  WHERE batch_id IS NULL AND status IN ('pending','processing');
