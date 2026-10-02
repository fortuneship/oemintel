-- Human layer (SDD section 8.4). Only people write here; machines read it and flag drift, never overwrite it.

create table product_type_attribute (
    id                      bigint generated always as identity primary key,
    product_type_id         bigint  not null references product_type (id),
    attribute_definition_id bigint  not null references attribute_definition (id),
    required                boolean not null default false,
    display_order           integer not null default 0,
    constraint product_type_attribute_unique unique (product_type_id, attribute_definition_id)
);

create table category_product_type_map (
    id                    bigint generated always as identity primary key,
    oem_id                bigint  not null references oem (id),
    category_path_pattern text    not null,
    product_type_id       bigint  not null references product_type (id),
    priority              integer not null default 0
);

create table label_mapping_suggestion (
    id                      bigint generated always as identity primary key,
    oem_id                  bigint      not null references oem (id),
    raw_label               text        not null,
    example_values          text[]      not null default '{}',
    suggested_definition_id bigint references attribute_definition (id),
    proposed_definition     jsonb,
    reason                  text,
    llm_call_id             bigint references llm_call (id),
    status                  text        not null default 'PENDING'
                                check (status in ('PENDING', 'ACCEPTED', 'REJECTED', 'EDITED')),
    decided_by              text,
    decided_at              timestamptz,
    created_at              timestamptz not null default now()
);

-- Overrides are keyed by (family, variant, attribute key), never by an attribute_value row id (AD-16):
-- value rows are recreated on every revision, overrides must survive that.
create table field_override (
    id                        bigint generated always as identity primary key,
    family_id                 bigint         not null references product_family (id),
    variant_id                bigint references product_variant (id),
    attribute_definition_id   bigint references attribute_definition (id),
    raw_label                 text,
    action                    text           not null check (action in ('SET', 'CLEAR', 'CHOOSE', 'CONFIRM',
                                                                        'NOT_APPLICABLE')),
    value_text                text,
    value_number              numeric(30, 10),
    value_min                 numeric(30, 10),
    value_max                 numeric(30, 10),
    unit_id                   bigint references unit_definition (id),
    qualifier                 text,
    option_id                 bigint references attribute_option (id),
    chosen_source             jsonb,
    reason                    text,
    based_on_revision_id      bigint references extraction_revision (id),
    source_value_fingerprint  char(64),
    active                    boolean        not null default true,
    created_by                text           not null,
    created_at                timestamptz    not null default now(),
    constraint field_override_key check (attribute_definition_id is not null or raw_label is not null)
);

-- At most one active override per key; editing deactivates the old row and inserts a new one (full history).
create unique index field_override_one_active
    on field_override (family_id, coalesce(variant_id, 0), coalesce(attribute_definition_id, 0), coalesce(raw_label, ''))
    where active;

create table asset_curation (
    id              bigint generated always as identity primary key,
    family_id       bigint      not null references product_family (id),
    asset_source_id bigint      not null references asset_source (id),
    asset_sha256    char(64),
    inclusion       text        not null default 'INCLUDED' check (inclusion in ('INCLUDED', 'EXCLUDED')),
    role            text,
    position        integer,
    main_image      boolean     not null default false,
    set_by          text        not null,
    set_at          timestamptz not null default now(),
    constraint asset_curation_unique unique (family_id, asset_source_id)
);

create table document_fact_assignment (
    id                      bigint generated always as identity primary key,
    family_id               bigint      not null references product_family (id),
    variant_id              bigint references product_variant (id),
    fact_identity           char(64)    not null,
    attribute_definition_id bigint references attribute_definition (id),
    assigned_by             text        not null,
    assigned_at             timestamptz not null default now()
);

create table product_flag (
    id                  bigint generated always as identity primary key,
    family_id           bigint      not null references product_family (id),
    variant_id          bigint references product_variant (id),
    target_key          text        not null default '',
    flag_type           text        not null,
    severity            text        not null check (severity in ('INFO', 'WARNING', 'BLOCKING')),
    status              text        not null default 'OPEN' check (status in ('OPEN', 'RESOLVED', 'DISMISSED')),
    -- A dismissal holds only while the underlying condition is unchanged (section 9.10).
    condition_fingerprint char(64),
    message             text,
    raised_in_revision_id bigint references extraction_revision (id),
    resolution_note     text,
    resolved_by         text,
    resolved_at         timestamptz,
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    constraint product_flag_unique unique (family_id, flag_type, target_key)
);

create index product_flag_open on product_flag (family_id) where status = 'OPEN';

create table review_event (
    id                   bigint generated always as identity primary key,
    family_id            bigint      not null references product_family (id),
    from_status          text,
    to_status            text        not null,
    actor                text        not null,
    reason_code          text,
    reason               text,
    revision_id          bigint references extraction_revision (id),
    family_version       bigint,
    acknowledged_warnings jsonb,
    created_at           timestamptz not null default now()
);

create index review_event_family on review_event (family_id, created_at desc);

create table approval_snapshot (
    id              bigint generated always as identity primary key,
    family_id       bigint      not null references product_family (id),
    review_event_id bigint      not null references review_event (id),
    revision_id     bigint      not null references extraction_revision (id),
    family_version  bigint      not null,
    snapshot        jsonb       not null,
    snapshot_hash   char(64)    not null,
    created_at      timestamptz not null default now()
);

create table saved_view (
    id         bigint generated always as identity primary key,
    user_id    bigint      not null references app_user (id),
    name       text        not null,
    filters    jsonb       not null default '{}'::jsonb,
    columns    jsonb       not null default '[]'::jsonb,
    is_default boolean     not null default false,
    created_at timestamptz not null default now(),
    constraint saved_view_name unique (user_id, name)
);

-- AD-29: an approval snapshot is what Phase 2 publishes, so the database itself refuses to change or delete one.
create function forbid_modification() returns trigger language plpgsql as $$
begin
    raise exception '% is immutable', tg_table_name;
end;
$$;

create trigger approval_snapshot_immutable
    before update or delete on approval_snapshot
    for each row execute function forbid_modification();
