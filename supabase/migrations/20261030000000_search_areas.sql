-- BeanDesk: search anywhere. A search resolves a place or address once (geocode_cache) and makes
-- sure the grid cells around it have been fetched from OpenStreetMap (osm_cells). The first
-- search of an area fetches it; after that it comes straight from the database. The edge
-- function supabase/functions/search ties this together.

-- Places found through search do not belong to a configured city.
alter table public.places alter column city drop not null;

-- The world in cells of 0.02° latitude by 0.03° longitude: about 2.2 by 2.0 km in the Netherlands.
create table public.osm_cells (
    lat_index integer not null,
    lng_index integer not null,
    fetched_at timestamptz not null default now(),
    primary key (lat_index, lng_index)
);

-- One row per Overpass request made for a search, for throttling and to see what happens.
create table public.osm_fetches (
    id bigint generated always as identity primary key,
    fetched_at timestamptz not null default now(),
    cells integer not null,
    elements integer not null
);

create index osm_fetches_fetched_at_idx on public.osm_fetches (fetched_at);

-- Resolved searches, so each place or address is looked up only once.
create table public.geocode_cache (
    -- Lowercase with single spaces, as typed.
    query text primary key,
    label text not null,
    -- The PDOK type: woonplaats, gemeente, adres, postcode or weg.
    kind text not null,
    lat double precision not null,
    lng double precision not null,
    created_at timestamptz not null default now()
);

-- Only the search function (service role) uses these tables.
revoke all on public.osm_cells, public.osm_fetches, public.geocode_cache from anon, authenticated;
alter table public.osm_cells enable row level security;
alter table public.osm_fetches enable row level security;
alter table public.geocode_cache enable row level security;

-- The import rules in one place: which OSM elements are laptop spots and how they become places.
-- Noise: cannabis coffeeshops, private places and places marked closed. Pubs tagged as cafe are
-- hidden by hand (places.hidden).
create function public.osm_places(p_elements jsonb)
returns table (
    osm_type text,
    osm_id bigint,
    category public.place_category,
    name text,
    address text,
    opening_hours text,
    tags jsonb,
    lat double precision,
    lng double precision
)
language sql
immutable
set search_path = ''
as $$
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
        case tags ->> 'amenity'
            when 'cafe' then 'cafe'
            when 'library' then 'library'
            when 'coworking_space' then 'coworking'
        end::public.place_category,
        btrim(tags ->> 'name'),
        case
            when nullif(tags ->> 'addr:street', '') is not null
                then (tags ->> 'addr:street') || coalesce(' ' || nullif(tags ->> 'addr:housenumber', ''), '')
        end,
        nullif(btrim(tags ->> 'opening_hours'), ''),
        tags,
        lat,
        lng
    from parsed
    where osm_type in ('node', 'way', 'relation')
        and tags ->> 'amenity' in ('cafe', 'library', 'coworking_space')
        and nullif(btrim(tags ->> 'name'), '') is not null
        and lat is not null
        and lng is not null
        and coalesce(tags ->> 'shop', '') <> 'cannabis'
        and coalesce(tags ->> 'cannabis', 'no') = 'no'
        and coalesce(tags ->> 'access', '') not in ('private', 'no')
        and lower(btrim(coalesce(tags ->> 'opening_hours', ''))) not in ('closed', 'off')
    order by osm_type, osm_id
$$;

-- The city import, now on the shared rules above; otherwise unchanged.
create or replace function public.import_osm_elements(p_city text, p_elements jsonb, p_force boolean default false)
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
    select * from public.osm_places(p_elements);

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

-- The cells within p_radius_m of a point that were never fetched, or longer ago than p_max_age.
create function public.missing_osm_cells(
    p_lat double precision,
    p_lng double precision,
    p_radius_m integer,
    p_max_age interval default interval '30 days'
)
returns table (
    lat_index integer,
    lng_index integer,
    south double precision,
    west double precision,
    north double precision,
    east double precision
)
language sql
stable
set search_path = ''
as $$
    with bounds as (
        select
            floor((p_lat - p_radius_m / 111320.0) / 0.02)::integer as i0,
            floor((p_lat + p_radius_m / 111320.0) / 0.02)::integer as i1,
            floor((p_lng - p_radius_m / (111320.0 * cos(radians(p_lat)))) / 0.03)::integer as j0,
            floor((p_lng + p_radius_m / (111320.0 * cos(radians(p_lat)))) / 0.03)::integer as j1
    )
    select
        i,
        j,
        (i * 0.02)::double precision,
        (j * 0.03)::double precision,
        ((i + 1) * 0.02)::double precision,
        ((j + 1) * 0.03)::double precision
    from bounds b, generate_series(b.i0, b.i1) as i, generate_series(b.j0, b.j1) as j
    where not exists (
        select 1 from public.osm_cells c
        where c.lat_index = i and c.lng_index = j and c.fetched_at > now() - p_max_age
    )
    order by i, j
$$;

-- Imports the Overpass elements fetched for these cells (p_cells: [{lat_index, lng_index}]) and
-- marks the cells as fetched. Places keep the city they had. Places in these cells that OSM no
-- longer has are soft-deleted, unless far fewer places come back than before: that is more likely
-- a bad Overpass answer than mass closures, so then nothing is deleted.
create function public.import_osm_area(p_cells jsonb, p_elements jsonb)
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

-- One place in the export format; shared by city_export() and area_export().
create function public.export_place(p public.places, c public.place_community)
returns jsonb
language sql
stable
set search_path = ''
as $$
    select jsonb_build_object(
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
    )
$$;

create or replace function public.city_export(p_city text)
returns jsonb
language sql
stable
set search_path = ''
as $$
    with rows as (
        select
            p.id,
            extensions.st_y(p.location::extensions.geometry) as lat,
            extensions.st_x(p.location::extensions.geometry) as lng,
            public.export_place(p, c) as place
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

-- The places within p_radius_m of a point, nearest first, in the export format. "city" holds
-- the label of what was searched; "center" is the searched point.
create function public.area_export(
    p_lat double precision,
    p_lng double precision,
    p_radius_m integer,
    p_label text
)
returns jsonb
language sql
stable
set search_path = ''
as $$
    with here as (
        select extensions.st_setsrid(extensions.st_makepoint(p_lng, p_lat), 4326)::extensions.geography as point
    ),
    rows as (
        select public.export_place(p, c) as place, extensions.st_distance(p.location, h.point) as distance
        from public.places p
        cross join here h
        left join public.place_community c on c.place_id = p.id
        where p.deleted_at is null
            and not p.hidden
            and extensions.st_dwithin(p.location, h.point, p_radius_m)
        order by distance
        limit 500
    )
    select jsonb_build_object(
        'version', 1,
        'city', p_label,
        'timezone', 'Europe/Amsterdam',
        'generated_at', now(),
        'attribution', '© OpenStreetMap-bijdragers',
        'center', jsonb_build_object('lat', p_lat, 'lng', p_lng),
        'radius_m', p_radius_m,
        'places', coalesce((select jsonb_agg(r.place order by r.distance) from rows r), '[]'::jsonb)
    )
$$;

-- Only the search function (service role) runs these; export_place stays callable, since the
-- public city_export() runs with the caller's rights and uses it.
revoke execute on function public.osm_places(jsonb) from public, anon, authenticated;
revoke execute on function public.missing_osm_cells(double precision, double precision, integer, interval)
    from public, anon, authenticated;
revoke execute on function public.import_osm_area(jsonb, jsonb) from public, anon, authenticated;
revoke execute on function public.area_export(double precision, double precision, integer, text)
    from public, anon, authenticated;
grant execute on function public.osm_places(jsonb) to service_role;
grant execute on function public.missing_osm_cells(double precision, double precision, integer, interval) to service_role;
grant execute on function public.import_osm_area(jsonb, jsonb) to service_role;
grant execute on function public.area_export(double precision, double precision, integer, text) to service_role;
grant select, insert, update on public.osm_cells, public.osm_fetches, public.geocode_cache to service_role;
