-- Android and MCP access these tables through authenticated Edge Functions
-- using service_role. Direct public PostgREST access must not bypass that auth.
ALTER TABLE public.chk_cancel_proposals ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.chk_chart_state ENABLE ROW LEVEL SECURITY;
