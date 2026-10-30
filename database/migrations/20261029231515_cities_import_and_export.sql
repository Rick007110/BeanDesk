-- BeanDesk: cities, the OpenStreetMap import and the city export, all in the database.
--
-- import_city() fetches a city from the Overpass API (http extension) and hands the elements
-- to import_osm_elements(), which holds every import rule. pg_cron runs it weekly.
-- city_export() builds the JSON the app downloads; anyone may call it.

create table public.cities (
    slug text primary key,
    -- Must match the municipal boundary in OpenStreetMap; Dutch municipalities are admin_level 8.
    name text not null,
    admin_level integer not null default 8,
    timezone text not null default 'Europe/Amsterdam',
    -- Imported every week by pg_cron.
    active boolean not null default false
);

insert into public.cities (slug, name, active) values
    ('utrecht', 'Utrecht', true),
    ('groningen', 'Groningen', false),
    ('amsterdam', 'Amsterdam', false)
on conflict (slug) do nothing;

alter table public.places
    add constraint places_city_fkey foreign key (city) references public.cities (slug);

revoke all on public.cities from anon, authenticated;
grant select on public.cities to anon, authenticated;
alter table public.cities enable row level security;
create policy "Cities are public" on public.cities
    for select to anon, authenticated using (true);

-- The Overpass query for one city: cafés, libraries and coworking spaces inside its boundary.
create function public.overpass_query(p_city text)
returns text
language sql
stable
set search_path = ''
as $$
    -- "out center" gives ways and relations a single coordinate.
    select format(
        '[out:json][timeout:180];'
        'area["boundary"="administrative"]["admin_level"="%s"]["name"="%s"]->.city;'
        'nwr["amenity"~"^(cafe|library|coworking_space)$"](area.city);'
        'out center tags;',
        c.admin_level,
        replace(replace(c.name, '\', '\\'), '"', '\"')
    )
    from public.cities c
    where c.slug = p_city
$$;

-- Imports Overpass elements for a city: filters what is no laptop spot, upserts the rest and
-- soft-deletes places that are gone from OSM. Only rows that really change get a new
-- updated_at. Refuses to delete more than half of a city unless p_force is set: that is far
-- more likely a bad Overpass answer than half the cafés closing in a week.
create function public.import_osm_elements(p_city text, p_elements jsonb, p_force boolean default false)
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
    deleted integer;
begin
    if not exists (select 1 from public.cities where slug = p_city) then
        raise exception 'unknown_city: %', p_city;
    end if;

    -- A cron run imports several cities in one transaction, so drop the previous batch first.
    drop table if exists pg_temp.incoming;
    create temporary table incoming on commit drop as
    with parsed as (
        select
            e ->> 'type' as osm_type,
            (e ->> 'id')::bigint as osm_id,
            coalesce(e -> 'tags', '{}'::jsonb) as tags,
            coalesce((e ->> 'lat')::float8, (e -> 'center' ->> 'lat')::float8) as lat,
            coalesce((e ->> 'lon')::float8, (e -> 'center' ->> 'lon')::float8) as lng
        from jsonb_array_elements(p_elements) as e
    )
    select distinct on (osm_type, osm_id)
        osm_type,
        osm_id,
        tags,
        lat,
        lng,
        case tags ->> 'amenity'
            when 'cafe' then 'cafe'
            when 'library' then 'library'
            when 'coworking_space' then 'coworking'
        end::public.place_category as category,
        btrim(tags ->> 'name') as name,
        case
            when nullif(tags ->> 'addr:street', '') is not null
                then (tags ->> 'addr:street') || coalesce(' ' || nullif(tags ->> 'addr:housenumber', ''), '')
        end as address,
        nullif(btrim(tags ->> 'opening_hours'), '') as opening_hours
    from parsed
    where osm_type in ('node', 'way', 'relation')
        and tags ->> 'amenity' in ('cafe', 'library', 'coworking_space')
        and nullif(btrim(tags ->> 'name'), '') is not null
        and lat is not null
        and lng is not null
        -- Noise: cannabis coffeeshops, private places and places marked closed.
        -- Pubs tagged as cafe are hidden by hand (places.hidden).
        and coalesce(tags ->> 'shop', '') <> 'cannabis'
        and coalesce(tags ->> 'cannabis', 'no') = 'no'
        and coalesce(tags ->> 'access', '') not in ('private', 'no')
        and lower(btrim(coalesce(tags ->> 'opening_hours', ''))) not in ('closed', 'off')
    order by osm_type, osm_id;

    select count(*) into existing from public.places where city = p_city and deleted_at is null;
    select count(*) into seen from pg_temp.incoming;
    if existing > 0 and seen < existing * 0.5 and not p_force then
        raise exception 'import_aborted: Overpass returned % places for %, but % are active', seen, p_city, existing;
    end if;

    with upserted as (
        insert into public.places as p
            (osm_type, osm_id, city, category, name, address, location, opening_hours, osm_tags, updated_at)
        select
            i.osm_type, i.osm_id, p_city, i.category, i.name, i.address,
            extensions.st_setsrid(extensions.st_makepoint(i.lng, i.lat), 4326)::extensions.geography,
            i.opening_hours, i.tags, now()
        from pg_temp.incoming i
        on conflict (osm_type, osm_id) do update set
            city = excluded.city,
            category = excluded.category,
            name = excluded.name,
            address = excluded.address,
            location = excluded.location,
            opening_hours = excluded.opening_hours,
            osm_tags = excluded.osm_tags,
            deleted_at = null,
            updated_at = now()
        where (p.city, p.category, p.name, p.address, p.opening_hours, p.osm_tags, p.deleted_at,
               extensions.st_astext(p.location))
            is distinct from
              (excluded.city, excluded.category, excluded.name, excluded.address, excluded.opening_hours,
               excluded.osm_tags, null::timestamptz, extensions.st_astext(excluded.location))
        returning (xmax = 0) as is_insert
    )
    select count(*) filter (where is_insert), count(*) filter (where not is_insert)
    into inserted, updated
    from upserted;

    update public.places p
    set deleted_at = now(), updated_at = now()
    where p.city = p_city
        and p.deleted_at is null
        and not exists (
            select 1 from pg_temp.incoming i where i.osm_type = p.osm_type and i.osm_id = p.osm_id
        );
    get diagnostics deleted = row_count;

    return jsonb_build_object(
        'city', p_city,
        'elements', jsonb_array_length(p_elements),
        'places', seen,
        'inserted', inserted,
        'updated', updated,
        'unchanged', seen - inserted - updated,
        'deleted', deleted
    );
end;
$$;

-- Fetches a city from the Overpass API and imports it. Needs the http extension.
create function public.import_city(p_city text, p_force boolean default false)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    query text := public.overpass_query(p_city);
    response record;
    payload jsonb;
begin
    if query is null then
        raise exception 'unknown_city: %', p_city;
    end if;
    -- Overpass asks clients to identify themselves; big cities can take a minute.
    perform extensions.http_set_curlopt('CURLOPT_USERAGENT', 'BeanDesk-import/0.1 (+https://github.com/Rick007110/BeanDesk)');
    perform extensions.http_set_curlopt('CURLOPT_TIMEOUT', '200');
    select * into response
    from extensions.http_post(
        'https://overpass-api.de/api/interpreter',
        'data=' || extensions.urlencode(query),
        'application/x-www-form-urlencoded'
    );
    if response.status <> 200 then
        raise exception 'overpass_failed: HTTP %', response.status;
    end if;
    payload := response.content::jsonb;
    -- Overpass reports timeouts and memory errors in a 200 response.
    if coalesce(payload ->> 'remark', '') ilike '%error%' then
        raise exception 'overpass_failed: %', payload ->> 'remark';
    end if;
    return public.import_osm_elements(p_city, coalesce(payload -> 'elements', '[]'::jsonb), p_force);
end;
$$;

-- The city export the app downloads: every visible place with what OSM and users say.
-- The format is parsed by core/src/main/kotlin/nl/beandesk/core/export/CityExport.kt.
create function public.city_export(p_city text)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
    with rows as (
        select
            p.id,
            extensions.st_y(p.location::extensions.geometry) as lat,
            extensions.st_x(p.location::extensions.geometry) as lng,
            jsonb_build_object(
                'id', p.id,
                'osm_type', p.osm_type,
                'osm_id', p.osm_id,
                'category', p.category,
                'name', p.name,
                'address', p.address,
                'lat', round(extensions.st_y(p.location::extensions.geometry)::numeric, 7),
                'lng', round(extensions.st_x(p.location::extensions.geometry)::numeric, 7),
                'opening_hours', p.opening_hours,
                -- What OSM says; null when the tag is missing or unclear (such as "terminal").
                'osm', jsonb_build_object(
                    'wifi', case
                        when not (p.osm_tags ? 'internet_access') then null
                        when p.osm_tags ->> 'internet_access' = 'no' then false
                        when p.osm_tags ->> 'internet_access' in ('yes', 'wifi')
                            or 'wlan' = any (string_to_array(p.osm_tags ->> 'internet_access', ';')) then true
                    end,
                    -- "customers" means free for whoever buys something, so free for our users.
                    'wifi_free', case p.osm_tags ->> 'internet_access:fee'
                        when 'no' then true
                        when 'customers' then true
                        when 'yes' then false
                    end,
                    'power', case p.osm_tags ->> 'power_supply'
                        when 'yes' then true
                        when 'no' then false
                    end
                ),
                'community', case when c.place_id is not null then jsonb_build_object(
                    'wifi', c.wifi,
                    'power', c.power,
                    'quiet', c.quiet,
                    'laptop_policy', c.laptop_policy,
                    'closed_reports', c.closed_reports,
                    'last_report_at', c.last_report_at
                ) end,
                'updated_at', greatest(p.updated_at, c.updated_at)
            ) as place
        from public.places p
        left join public.place_community c on c.place_id = p.id
        where p.city = p_city and p.deleted_at is null and not p.hidden
    )
    select jsonb_build_object(
        'version', 1,
        'city', ci.slug,
        'timezone', ci.timezone,
        'generated_at', now(),
        'attribution', '© OpenStreetMap-bijdragers',
        'center', (
            select case when count(*) > 0 then jsonb_build_object(
                'lat', round(avg(r.lat)::numeric, 6),
                'lng', round(avg(r.lng)::numeric, 6)
            ) end
            from rows r
        ),
        'places', coalesce((select jsonb_agg(r.place order by r.id) from rows r), '[]'::jsonb)
    )
    from public.cities ci
    where ci.slug = p_city
$$;

-- Supabase grants new functions to everyone; only the export is for the app.
revoke execute on function public.overpass_query(text) from public, anon, authenticated;
revoke execute on function public.import_osm_elements(text, jsonb, boolean) from public, anon, authenticated;
revoke execute on function public.import_city(text, boolean) from public, anon, authenticated;
grant execute on function public.import_osm_elements(text, jsonb, boolean) to service_role;
grant execute on function public.import_city(text, boolean) to service_role;
grant execute on function public.city_export(text) to anon, authenticated;

-- On Supabase: the http extension for import_city() and a weekly import of the active cities
-- (Mondays 03:17 UTC). Plain PostgreSQL without these extensions (tests) skips this part.
do $$
begin
    if exists (select 1 from pg_available_extensions where name = 'http') then
        create extension if not exists http with schema extensions;
    end if;
    if exists (select 1 from pg_available_extensions where name = 'pg_cron') then
        create extension if not exists pg_cron;
        perform cron.schedule(
            'beandesk-import-cities',
            '17 3 * * 1',
            $job$select public.import_city(slug) from public.cities where active$job$
        );
    end if;
end;
$$;
