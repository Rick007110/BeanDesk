-- Trigger functions are not meant to be called through the API. Supabase grants new functions
-- to everyone, so take that back; triggers still fire without EXECUTE.
revoke execute on function public.handle_new_user() from public, anon, authenticated;
revoke execute on function public.enforce_report_rate_limit() from public, anon, authenticated;
revoke execute on function public.apply_report_to_community() from public, anon, authenticated;

-- Deleting a place cascades to favorites; this keeps that lookup fast.
create index favorites_place_idx on public.favorites (place_id);
