-- BeanDesk: search places by name across the Netherlands, for the search function
-- (supabase/functions/search). Every word has to occur in the name or the address; case and the
-- common accents do not matter. At most 100 places, by name, in the export format.

-- Lowercase without accents, so "cafe" finds "Café".
create function public.fold_text(p_text text)
returns text
language sql
immutable
set search_path = ''
as $$
    select translate(lower(coalesce(p_text, '')), 'àáâäãåèéêëìíîïòóôöõùúûüçñ', 'aaaaaaeeeeiiiiooooouuuucn')
$$;

create function public.name_search(p_query text, p_label text)
returns jsonb
language sql
stable
set search_path = ''
as $$
    with words as (
        -- LIKE wildcards in what was typed count as plain characters.
        select '%' || replace(replace(replace(w, '\', '\\'), '%', '\%'), '_', '\_') || '%' as pattern
        from unnest(string_to_array(public.fold_text(p_query), ' ')) as w
        where w <> ''
    ),
    rows as (
        select
            public.export_place(p, c) as place,
            p.name,
            extensions.st_y(p.location::extensions.geometry) as lat,
            extensions.st_x(p.location::extensions.geometry) as lng
        from public.places p
        left join public.place_community c on c.place_id = p.id
        where p.deleted_at is null
            and not p.hidden
            and exists (select 1 from words)
            and not exists (
                select 1 from words
                where public.fold_text(p.name || ' ' || coalesce(p.address, '')) not like words.pattern
            )
        order by p.name
        limit 100
    )
    select jsonb_build_object(
        'version', 1,
        'city', p_label,
        'timezone', 'Europe/Amsterdam',
        'generated_at', now(),
        'attribution', '© OpenStreetMap-bijdragers',
        'center', (
            select case when count(*) > 0 then jsonb_build_object(
                'lat', round(avg(r.lat)::numeric, 6),
                'lng', round(avg(r.lng)::numeric, 6)
            ) end
            from rows r
        ),
        'places', coalesce((select jsonb_agg(r.place order by r.name) from rows r), '[]'::jsonb)
    )
$$;

revoke execute on function public.name_search(text, text) from public, anon, authenticated;
grant execute on function public.name_search(text, text) to service_role;
