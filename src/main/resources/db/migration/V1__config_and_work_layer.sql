-- Configuration and work layer (SDD section 8.1).
-- Conventions: bigint identity keys, unique constraints on natural keys, text enums with check constraints,
-- timestamptz everywhere, no soft deletes.

create table app_user (
    id            bigint generated always as identity primary key,
    username      text        not null unique,
    display_name  text        not null,
    password_hash text        not null,
    role          text        not null check (role in ('ADMIN', 'REVIEWER')),
    enabled       boolean     not null default true,
    created_at    timestamptz not null default now()
);

create table oem (
    id                    bigint generated always as identity primary key,
    code                  text        not null unique,
    name                  text        not null,
    base_url              text        not null,
    adapter_key           text        not null,
    default_locale        text        not null,
    adapter_config        jsonb       not null default '{}'::jsonb,
    default_usage_rights  text        not null default 'UNKNOWN'
                              check (default_usage_rights in ('UNKNOWN', 'PERMITTED', 'RESTRICTED')),
    -- Legal gate (AD-30): FULL and REFRESH jobs refuse to start unless this is AUTHORISED.
    crawl_auth_status     text        not null default 'PENDING'
                              check (crawl_auth_status in ('PENDING', 'AUTHORISED', 'DENIED')),
    crawl_auth_note       text,
    crawl_auth_decided_by text,
    crawl_auth_decided_at timestamptz,
    active                boolean     not null default true,
    created_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now()
);

create table crawl_host (
    id                        bigint generated always as identity primary key,
    host                      text        not null unique,
    oem_id                    bigint      not null references oem (id),
    robots_txt                text,
    robots_fetched_at         timestamptz,
    min_delay_ms              integer     not null default 3000,
    current_delay_ms          integer     not null default 3000,
    crawl_delay_ms            integer,
    last_request_at           timestamptz,
    status                    text        not null default 'OK' check (status in ('OK', 'PAUSED', 'BLOCKED')),
    blocked_until             timestamptz,
    block_reason              text,
    consecutive_block_signals integer     not null default 0,
    probe_count               integer     not null default 0,
    crawl_window              jsonb,
    paused_by                 text,
    created_at                timestamptz not null default now(),
    updated_at                timestamptz not null default now()
);

create table onboarding_job (
    id                   bigint generated always as identity primary key,
    oem_id               bigint      not null references oem (id),
    mode                 text        not null check (mode in ('DRY_RUN', 'FULL', 'SEED_LIST', 'REFRESH',
                                                              'REEXTRACT', 'REPARSE_DOCUMENTS', 'RENORMALIZE')),
    locale               text        not null,
    limit_value          integer     not null check (limit_value > 0),
    limit_unit           text        not null check (limit_unit in ('FAMILY', 'SKU')),
    existing_policy      text        not null default 'NEW_ONLY' check (existing_policy in ('NEW_ONLY', 'REFRESH')),
    options              jsonb       not null default '{}'::jsonb,
    status               text        not null check (status in ('CREATED', 'DISCOVERING', 'RUNNING', 'PAUSED',
                                                                'COMPLETED', 'COMPLETED_WITH_ERRORS',
                                                                'CANCELLED', 'FAILED')),
    status_before_pause  text,
    pause_reason         text,
    promoted_from_job_id bigint references onboarding_job (id),
    archived             boolean     not null default false,
    created_by           text        not null,
    created_at           timestamptz not null default now(),
    started_at           timestamptz,
    finished_at          timestamptz,
    updated_at           timestamptz not null default now()
);

-- AD-06: one active job per OEM.
create unique index onboarding_job_one_active_per_oem
    on onboarding_job (oem_id)
    where status in ('CREATED', 'DISCOVERING', 'RUNNING', 'PAUSED');

create table work_item (
    id               bigint generated always as identity primary key,
    job_id           bigint      not null references onboarding_job (id) on delete cascade,
    task_type        text        not null,
    lane             text        not null check (lane in ('NETWORK', 'LOCAL', 'LLM')),
    host             text,
    target           text        not null,
    payload          jsonb       not null default '{}'::jsonb,
    dedupe_key       text        not null,
    parent_id        bigint references work_item (id) on delete set null,
    priority         integer     not null default 0,
    status           text        not null default 'PENDING'
                         check (status in ('PENDING', 'LEASED', 'RETRY_WAIT', 'DONE', 'DEAD', 'CANCELLED', 'SKIPPED')),
    attempts         integer     not null default 0,
    max_attempts     integer     not null,
    lease_seconds    integer     not null,
    next_attempt_at  timestamptz not null default now(),
    lease_owner      text,
    lease_token      uuid,
    lease_expires_at timestamptz,
    error_class      text,
    last_error       text,
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    started_at       timestamptz,
    finished_at      timestamptz,
    constraint work_item_dedupe unique (job_id, task_type, dedupe_key)
);

create index work_item_leasable on work_item (lane, next_attempt_at, priority desc, id)
    where status in ('PENDING', 'RETRY_WAIT');
create index work_item_leased_expiry on work_item (lease_expires_at) where status = 'LEASED';
create index work_item_leased_host on work_item (host) where status = 'LEASED';
create index work_item_job_status on work_item (job_id, status, task_type);

create table job_candidate (
    id                  bigint generated always as identity primary key,
    job_id              bigint      not null references onboarding_job (id) on delete cascade,
    url                 text        not null,
    normalized_url      text        not null,
    page_type_guess     text,
    confirmed_page_type text,
    family_key          text,
    category_paths      jsonb       not null default '[]'::jsonb,
    discovery_sources   text[]      not null default '{}',
    sitemap_lastmod     timestamptz,
    locale              text,
    hreflang            jsonb,
    order_key           text        not null,
    status              text        not null default 'DISCOVERED'
                            check (status in ('DISCOVERED', 'UNKNOWN', 'ADMITTED', 'REJECTED', 'DUPLICATE', 'COMPLETED')),
    reason              text,
    family_id           bigint,
    matched_family_id   bigint,
    sku_count           integer,
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    constraint job_candidate_url unique (job_id, normalized_url)
);

create index job_candidate_admission on job_candidate (job_id, status, order_key, id);

create table job_event (
    id         bigint generated always as identity primary key,
    job_id     bigint      not null references onboarding_job (id) on delete cascade,
    level      text        not null check (level in ('INFO', 'WARN', 'ERROR')),
    event_type text        not null,
    message    text        not null,
    actor      text,
    created_at timestamptz not null default now()
);

create index job_event_job_time on job_event (job_id, created_at desc);

-- First OEM. The locale is an assumption until Step 0 reconnaissance confirms the site's locale scheme [S0 #4].
insert into oem (code, name, base_url, adapter_key, default_locale)
values ('ROXTEC', 'Roxtec', 'https://www.roxtec.com', 'roxtec', 'en');
