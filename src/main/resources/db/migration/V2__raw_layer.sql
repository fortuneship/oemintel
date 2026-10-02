-- Raw layer (SDD section 8.2). Rows here describe immutable content: a fetch or a file is never edited,
-- a newer observation is a new row.

create table fetched_resource (
    id                   bigint generated always as identity primary key,
    oem_id               bigint      not null references oem (id),
    job_id               bigint references onboarding_job (id) on delete set null,
    requested_url        text        not null,
    normalized_url       text        not null,
    final_url            text,
    locale               text,
    http_status          integer,
    content_type         text,
    content_hash         char(64),
    storage_key          text,
    fetch_mode           text        not null check (fetch_mode in ('HTTP', 'BROWSER')),
    classification       text        not null,
    redirect_chain       jsonb       not null default '[]'::jsonb,
    etag                 text,
    last_modified        text,
    byte_size            bigint,
    duration_ms          integer,
    not_modified_from_id bigint references fetched_resource (id),
    captured_xhr         boolean     not null default false,
    fetched_at           timestamptz not null default now()
);

create index fetched_resource_url_time on fetched_resource (normalized_url, fetched_at desc);

create table asset (
    id                   bigint generated always as identity primary key,
    sha256               char(64)    not null unique,
    storage_key          text        not null,
    mime_type            text        not null,
    byte_size            bigint      not null,
    asset_kind           text        not null check (asset_kind in ('IMAGE', 'DOCUMENT', 'CAD', 'OTHER')),
    doc_type             text check (doc_type in ('DATASHEET', 'CERTIFICATE', 'INSTALLATION_GUIDE', 'DECLARATION',
                                                  'CATALOGUE', 'BROCHURE', 'UNKNOWN')),
    image_width          integer,
    image_height         integer,
    image_class          text check (image_class in ('PRODUCT_PHOTO', 'DRAWING', 'ICON', 'PLACEHOLDER', 'UNKNOWN')),
    color_space          text,
    page_count           integer,
    has_text_layer       boolean,
    preview_key          text,
    usage_rights         text        not null default 'UNKNOWN'
                             check (usage_rights in ('UNKNOWN', 'PERMITTED', 'RESTRICTED')),
    original_filename    text,
    title                text,
    issue_date           date,
    expiry_date          date,
    checksum_verified_at timestamptz,
    superseded           boolean     not null default false,
    created_at           timestamptz not null default now()
);

create table asset_source (
    id               bigint generated always as identity primary key,
    oem_id           bigint      not null references oem (id),
    url              text        not null,
    -- Manual uploads have no URL; they get a synthetic 'upload:<uuid>' key so the uniqueness rule still holds.
    normalized_url   text        not null,
    host             text,
    current_asset_id bigint references asset (id),
    source_type      text        not null default 'CRAWLED' check (source_type in ('CRAWLED', 'MANUAL_UPLOAD')),
    status           text        not null default 'PENDING'
                         check (status in ('PENDING', 'DOWNLOADED', 'BROKEN', 'GATED', 'TOO_LARGE', 'WRONG_TYPE',
                                           'SKIPPED_BY_POLICY', 'INDIRECT')),
    gating_reason    text check (gating_reason in ('LOGIN', 'FORM', 'CONSENT', 'UNKNOWN')),
    last_http_status integer,
    etag             text,
    last_modified    text,
    content_length   bigint,
    size_override    bigint,
    uploaded_by      text,
    note             text,
    last_checked_at  timestamptz,
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    constraint asset_source_url unique (oem_id, normalized_url)
);

create table asset_source_history (
    id              bigint generated always as identity primary key,
    asset_source_id bigint      not null references asset_source (id),
    asset_id        bigint      not null references asset (id),
    job_id          bigint references onboarding_job (id) on delete set null,
    observed_at     timestamptz not null default now()
);

create index asset_source_history_source on asset_source_history (asset_source_id, observed_at desc);

create table document_analysis (
    id                        bigint generated always as identity primary key,
    asset_id                  bigint       not null references asset (id),
    parser_version            text         not null,
    status                    text         not null
                                  check (status in ('PARSED', 'PARTIAL', 'SCANNED', 'MIXED', 'ENCRYPTED', 'CORRUPT',
                                                    'TEXT_UNRELIABLE', 'NOT_PARSED', 'TIMED_OUT')),
    pages_total               integer,
    pages_parsed              integer,
    text_chars                integer,
    text_layer_ratio          numeric(5, 4),
    text_key                  text,
    tables_key                text,
    preview_key               text,
    doc_type                  text,
    classification_method     text,
    classification_confidence numeric(5, 4),
    language                  text,
    duration_ms               integer,
    created_at                timestamptz  not null default now(),
    constraint document_analysis_version unique (asset_id, parser_version)
);
