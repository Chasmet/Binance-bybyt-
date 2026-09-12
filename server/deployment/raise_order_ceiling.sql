-- Apply through the Supabase migration tool after coordinated server review.
-- No pending orders are inserted, and no account data is rewritten.
ALTER TABLE public.chk_trade_proposals
  DROP CONSTRAINT chk_trade_proposals_quote_amount_usdc_check,
  ADD CONSTRAINT chk_trade_proposals_quote_amount_usdc_check
    CHECK (quote_amount_usdc > 1 AND quote_amount_usdc <= 30);
