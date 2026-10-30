-- The export only reads what anyone may read already (places, place_community, cities),
-- so it can run with the caller's own rights instead of the owner's.
grant usage on schema extensions to anon, authenticated;
alter function public.city_export(text) security invoker;
