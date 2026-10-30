-- BeanDesk without Overpass: all of the Netherlands comes from the extract import
-- (import_osm_file), now every night, and the extract says which municipality each place lies in.
-- That replaces the weekly city import and the search function's own fetches, which both asked
-- the public Overpass servers.

do $$
begin
    if exists (select 1 from pg_extension where extname = 'pg_cron') then
        if exists (select 1 from cron.job where jobname = 'beandesk-import-cities') then
            perform cron.unschedule('beandesk-import-cities');
        end if;
        -- The Action publishes a new file every night at 02:23 UTC; import it three hours later.
        perform cron.schedule('beandesk-import-netherlands', '23 5 * * *', $job$select public.import_osm_file()$job$);
    end if;
end;
$$;

drop function public.import_city(text, boolean);
drop function public.overpass_query(text);
drop function public.missing_osm_cells(double precision, double precision, integer, interval);
drop function public.place_location(bigint);
drop table public.osm_fetches;

-- Imports elements for these cells (p_cells: [{lat_index, lng_index}]) and marks the cells as
-- imported, as before; new: an element's "gemeente" sets the place's city (cities.name), so a
-- new café in Utrecht shows up in Utrecht's export too.
create or replace function public.import_osm_area(p_cells jsonb, p_elements jsonb)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    existing integer;
    seen integer;
    inserted integer;
    updated integer;
    deleted integer := 0;
begin
    drop table if exists pg_temp.area_cells;
    create temporary table area_cells on commit drop as
    select (c ->> 'lat_index')::integer as lat_index, (c ->> 'lng_index')::integer as lng_index
    from jsonb_array_elements(p_cells) as c;

    drop table if exists pg_temp.incoming;
    create temporary table incoming on commit drop as
    select * from public.osm_places(p_elements);

    select count(*) into existing
    from public.places p
    join pg_temp.area_cells a
        on a.lat_index = floor(extensions.st_y(p.location::extensions.geometry) / 0.02)
        and a.lng_index = floor(extensions.st_x(p.location::extensions.geometry) / 0.03)
    where p.deleted_at is null;

    select count(*) into seen
    from pg_temp.incoming i
    join pg_temp.area_cells a on a.lat_index = floor(i.lat / 0.02) and a.lng_index = floor(i.lng / 0.03);

    with upserted as (
        insert into public.places as p
            (osm_type, osm_id, category, name, address, location, opening_hours, osm_tags, updated_at)
        select
            i.osm_type, i.osm_id, i.category, i.name, i.address,
            extensions.st_setsrid(extensions.st_makepoint(i.lng, i.lat), 4326)::extensions.geography,
            i.opening_hours, i.tags, now()
        from pg_temp.incoming i
        on conflict (osm_type, osm_id) do update set
            category = excluded.category,
            name = excluded.name,
            address = excluded.address,
            location = excluded.location,
            opening_hours = excluded.opening_hours,
            osm_tags = excluded.osm_tags,
            deleted_at = null,
            updated_at = now()
        where (p.category, p.name, p.address, p.opening_hours, p.osm_tags, p.deleted_at,
               extensions.st_astext(p.location))
            is distinct from
              (excluded.category, excluded.name, excluded.address, excluded.opening_hours,
               excluded.osm_tags, null::timestamptz, extensions.st_astext(excluded.location))
        returning (xmax = 0) as is_insert
    )
    select count(*) filter (where is_insert), count(*) filter (where not is_insert)
    into inserted, updated
    from upserted;

    if existing = 0 or seen >= existing * 0.5 then
        update public.places p
        set deleted_at = now(), updated_at = now()
        from pg_temp.area_cells a
        where p.deleted_at is null
            and a.lat_index = floor(extensions.st_y(p.location::extensions.geometry) / 0.02)
            and a.lng_index = floor(extensions.st_x(p.location::extensions.geometry) / 0.03)
            and not exists (
                select 1 from pg_temp.incoming i where i.osm_type = p.osm_type and i.osm_id = p.osm_id
            );
        get diagnostics deleted = row_count;
    end if;

    -- The municipality the extract found a place in is its city, when the app has that city.
    update public.places p
    set city = ci.slug, updated_at = now()
    from jsonb_array_elements(p_elements) as e
    join public.cities ci on ci.name = e ->> 'gemeente'
    where p.osm_type = e ->> 'type'
        and p.osm_id = (e ->> 'id')::bigint
        and p.city is distinct from ci.slug;

    insert into public.osm_cells (lat_index, lng_index, fetched_at)
    select lat_index, lng_index, now() from pg_temp.area_cells
    on conflict (lat_index, lng_index) do update set fetched_at = excluded.fetched_at;

    return jsonb_build_object(
        'cells', (select count(*) from pg_temp.area_cells),
        'elements', jsonb_array_length(p_elements),
        'inserted', inserted,
        'updated', updated,
        'deleted', deleted
    );
end;
$$;

revoke execute on function public.import_osm_area(jsonb, jsonb) from public, anon, authenticated;
grant execute on function public.import_osm_area(jsonb, jsonb) to service_role;
