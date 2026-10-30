-- BeanDesk: a better search by name, replacing name_search() in the search function.
-- - Spaces and punctuation do not matter: "coffeecompany" finds "Coffee Company".
-- - The town and postcode from OpenStreetMap count too: "coffee company breda".
-- - Optionally only near a point, nearest first: the search function uses that for a text like
--   "coffeecompany breda" where only "breda" is a place.

create function public.find_by_name(
    p_query text,
    p_label text,
    p_lat double precision default null,
    p_lng double precision default null,
    p_radius_m integer default null
)
returns jsonb
language sql
stable
set search_path = ''
as $$
    with here as (
        select case when p_lat is not null and p_lng is not null and p_radius_m is not null then
            extensions.st_setsrid(extensions.st_makepoint(p_lng, p_lat), 4326)::extensions.geography
        end as point
    ),
    words as (
        -- LIKE wildcards in what was typed count as plain characters.
        select '%' || replace(replace(replace(w, '\', '\\'), '%', '\%'), '_', '\_') || '%' as pattern
        from unnest(string_to_array(public.fold_text(p_query), ' ')) as w
        where w <> ''
    ),
    candidates as (
        select
            p,
            c,
            public.fold_text(concat_ws(' ', p.name, p.address, p.osm_tags ->> 'addr:city', p.osm_tags ->> 'addr:postcode')) as haystack,
            case when h.point is not null then extensions.st_distance(p.location, h.point) end as distance
        from public.places p
        cross join here h
        left join public.place_community c on c.place_id = p.id
        where p.deleted_at is null
            and not p.hidden
            and (h.point is null or extensions.st_dwithin(p.location, h.point, p_radius_m))
    ),
    rows as (
        select
            public.export_place(k.p, k.c) as place,
            (k.p).name as name,
            k.distance,
            extensions.st_y((k.p).location::extensions.geometry) as lat,
            extensions.st_x((k.p).location::extensions.geometry) as lng
        from candidates k
        where exists (select 1 from words)
            -- Every word occurs, as typed or in the name without spaces and punctuation
            -- ("annemax" for "Anne&Max").
            and not exists (
                select 1 from words
                where k.haystack not like words.pattern
                    and regexp_replace(k.haystack, '[^a-z0-9]', '', 'g') not like words.pattern
            )
        order by k.distance nulls last, (k.p).name
        limit 100
    )
    select jsonb_build_object(
        'version', 1,
        'city', p_label,
        'timezone', 'Europe/Amsterdam',
        'generated_at', now(),
        'attribution', '© OpenStreetMap-bijdragers',
        'center', case
            when p_lat is not null and p_lng is not null then jsonb_build_object('lat', p_lat, 'lng', p_lng)
            else (
                select case when count(*) > 0 then jsonb_build_object(
                    'lat', round(avg(r.lat)::numeric, 6),
                    'lng', round(avg(r.lng)::numeric, 6)
                ) end
                from rows r
            )
        end,
        'places', coalesce((select jsonb_agg(r.place order by r.distance nulls last, r.name) from rows r), '[]'::jsonb)
    )
$$;

revoke execute on function public.find_by_name(text, text, double precision, double precision, integer)
    from public, anon, authenticated;
grant execute on function public.find_by_name(text, text, double precision, double precision, integer) to service_role;
