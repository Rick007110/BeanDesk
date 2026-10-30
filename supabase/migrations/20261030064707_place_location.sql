-- BeanDesk: what the search function (supabase/functions/search) needs on top of search_areas.

-- Where a place is, for fetching its surroundings again after a report.
create function public.place_location(p_place_id bigint)
returns table (lat double precision, lng double precision)
language sql
stable
set search_path = ''
as $$
    select extensions.st_y(location::extensions.geometry), extensions.st_x(location::extensions.geometry)
    from public.places
    where id = p_place_id and deleted_at is null
$$;

revoke execute on function public.place_location(bigint) from public, anon, authenticated;
grant execute on function public.place_location(bigint) to service_role;
-- PostGIS lives in the extensions schema.
grant usage on schema extensions to service_role;
