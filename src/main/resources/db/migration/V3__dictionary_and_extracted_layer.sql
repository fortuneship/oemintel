-- Attribute dictionary and extracted layer (SDD sections 8.3 and 8.4).
-- Extracted data is machine-owned: a revision, once CURRENT, is never modified (AD-27).

create extension if not exists pg_trgm;

-- ---------------------------------------------------------------- dictionary

create table product_type (
    id    bigint generated always as identity primary key,
    code  text not null unique,
    label text not null
);

create table unit_definition (
    id                  bigint generated always as identity primary key,
    symbol              text           not null unique,
    dimension           text           not null,
    factor_to_canonical numeric(30, 15) not null,
    offset_to_canonical numeric(30, 15) not null default 0,
    display_label       text           not null,
    is_canonical        boolean        not null default false
);

create table unit_alias (
    id                 bigint generated always as identity primary key,
    raw_text           text   not null,
    unit_definition_id bigint not null references unit_definition (id),
    oem_id             bigint references oem (id),
    constraint unit_alias_text unique nulls not distinct (raw_text, oem_id)
);

create table attribute_definition (
    id                     bigint generated always as identity primary key,
    code                   text        not null unique,
    label                  text        not null,
    data_type              text        not null check (data_type in ('TEXT', 'RICH_TEXT', 'NUMBER', 'RANGE', 'BOOLEAN',
                                                                     'ENUM', 'MULTI_VALUE', 'CODE', 'COMPOSITE')),
    dimension              text,
    canonical_unit_id      bigint references unit_definition (id),
    precision_digits       integer,
    scope                  text        not null default 'BOTH' check (scope in ('FAMILY', 'VARIANT', 'BOTH')),
    display_group          text,
    sort_order             integer     not null default 0,
    is_system              boolean     not null default false,
    is_critical            boolean     not null default false,
    composite_hint         jsonb,
    -- Inert in Phase 1: filled by the spreadsheet import so Phase 2 publishing can reuse it.
    magento_attribute_code text,
    created_at             timestamptz not null default now(),
    updated_at             timestamptz not null default now()
);

create table attribute_option (
    id                      bigint generated always as identity primary key,
    attribute_definition_id bigint not null references attribute_definition (id),
    code                    text   not null,
    label                   text   not null,
    synonyms                text[] not null default '{}',
    sort_order              integer not null default 0,
    constraint attribute_option_code unique (attribute_definition_id, code)
);

create table attribute_label_alias (
    id                      bigint generated always as identity primary key,
    oem_id                  bigint      not null references oem (id),
    locale                  text        not null,
    raw_label               text        not null,
    attribute_definition_id bigint      not null references attribute_definition (id),
    created_by              text        not null,
    created_at              timestamptz not null default now(),
    constraint attribute_label_alias_label unique (oem_id, locale, raw_label)
);

-- System attributes: name and descriptions go through the same value/override/provenance machinery as specs.
insert into attribute_definition (code, label, data_type, scope, display_group, sort_order, is_system)
values ('name', 'Name', 'TEXT', 'FAMILY', 'General', 0, true),
       ('description', 'Description', 'RICH_TEXT', 'FAMILY', 'General', 1, true),
       ('short_description', 'Short description', 'RICH_TEXT', 'FAMILY', 'General', 2, true);

-- ---------------------------------------------------------------- catalogue

create table product_family (
    id                      bigint generated always as identity primary key,
    oem_id                  bigint        not null references oem (id),
    locale                  text          not null,
    family_key              text          not null,
    canonical_url           text,
    display_name            text,
    product_type_id         bigint references product_type (id),
    product_type_method     text check (product_type_method in ('CATEGORY_MAP', 'REVIEWER')),
    lifecycle_status        text          not null default 'UNKNOWN'
                                check (lifecycle_status in ('ACTIVE', 'DISCONTINUED', 'REPLACED', 'UNKNOWN')),
    review_status           text          not null default 'NEW'
                                check (review_status in ('NEW', 'IN_REVIEW', 'NEEDS_REREVIEW', 'APPROVED', 'REJECTED')),
    current_revision_id     bigint,
    first_seen_job_id       bigint references onboarding_job (id) on delete set null,
    last_seen_job_id        bigint references onboarding_job (id) on delete set null,
    completeness            numeric(5, 2),
    superseded_by_id        bigint references product_family (id),
    changed_since_rejection boolean       not null default false,
    version                 bigint        not null default 0,
    created_at              timestamptz   not null default now(),
    updated_at              timestamptz   not null default now(),
    constraint product_family_key unique (oem_id, locale, family_key)
);

create index product_family_name_trgm on product_family using gin (display_name gin_trgm_ops);
create index product_family_review on product_family (review_status);

create table extraction_revision (
    id                  bigint generated always as identity primary key,
    family_id           bigint      not null references product_family (id),
    revision_number     integer     not null,
    job_id              bigint references onboarding_job (id) on delete set null,
    status              text        not null check (status in ('DRAFT', 'CURRENT', 'SUPERSEDED')),
    cause               text        not null check (cause in ('CRAWL', 'REEXTRACT', 'REPARSE', 'RENORMALIZE', 'LLM',
                                                              'DOC_VERSION', 'ALIAS', 'MANUAL_UPLOAD')),
    parent_revision_id  bigint references extraction_revision (id),
    input_fingerprint   char(64),
    content_fingerprint char(64),
    extractor_version   text,
    parser_version      text,
    normalizer_version  text,
    prompt_version      text,
    llm_model           text,
    completeness        text        not null default 'COMPLETE' check (completeness in ('COMPLETE', 'PARTIAL')),
    failed_sections     jsonb       not null default '[]'::jsonb,
    validator_result    jsonb,
    source_resource_ids bigint[]    not null default '{}',
    significance        text check (significance in ('NONE', 'MINOR', 'MATERIAL')),
    change_counts       jsonb,
    created_at          timestamptz not null default now(),
    constraint extraction_revision_number unique (family_id, revision_number)
);

create unique index extraction_revision_one_current on extraction_revision (family_id) where status = 'CURRENT';

alter table product_family
    add constraint product_family_current_revision foreign key (current_revision_id) references extraction_revision (id);

create table product_variant (
    id                      bigint generated always as identity primary key,
    family_id               bigint      not null references product_family (id),
    oem_id                  bigint      not null references oem (id),
    locale                  text        not null,
    sku_display             text        not null,
    sku_normalized          text        not null,
    variant_label           text,
    lifecycle_status        text        not null default 'UNKNOWN'
                                check (lifecycle_status in ('ACTIVE', 'DISCONTINUED', 'REPLACED', 'UNKNOWN')),
    excluded                boolean     not null default false,
    excluded_by             text,
    first_seen_revision_id  bigint references extraction_revision (id),
    last_seen_revision_id   bigint references extraction_revision (id),
    created_at              timestamptz not null default now(),
    updated_at              timestamptz not null default now(),
    constraint product_variant_sku unique (oem_id, locale, sku_normalized)
);

create index product_variant_family on product_variant (family_id);
create index product_variant_sku_trgm on product_variant using gin (sku_normalized gin_trgm_ops);

alter table job_candidate
    add constraint job_candidate_family foreign key (family_id) references product_family (id),
    add constraint job_candidate_matched_family foreign key (matched_family_id) references product_family (id);

create table llm_call (
    id             bigint generated always as identity primary key,
    job_id         bigint references onboarding_job (id) on delete set null,
    task_type      text          not null,
    prompt_version text          not null,
    provider       text          not null,
    model_id       text          not null,
    input_hash     char(64)      not null,
    input_key      text,
    output_key     text,
    status         text          not null check (status in ('OK', 'INVALID_OUTPUT', 'TRUNCATED', 'REFUSED', 'ERROR',
                                                            'BUDGET_SKIPPED')),
    stop_reason    text,
    tokens_in      integer,
    tokens_out     integer,
    latency_ms     integer,
    cost_estimate  numeric(12, 6),
    created_at     timestamptz   not null default now()
);

-- Cache key (AD-23): same task, prompt, model and input means the stored result is reused.
create index llm_call_cache on llm_call (task_type, prompt_version, model_id, input_hash) where status = 'OK';

create table document_fact (
    id               bigint generated always as identity primary key,
    asset_id         bigint      not null references asset (id),
    parser_version   text        not null,
    fact_kind        text        not null check (fact_kind in ('ATTRIBUTE', 'CERT_NUMBER', 'ISSUER', 'STANDARD',
                                                               'ISSUE_DATE', 'EXPIRY_DATE', 'DOC_REVISION', 'SKU_MENTION')),
    raw_label        text,
    raw_value        text        not null,
    applies_to_sku   text,
    page             integer,
    locator          text,
    excerpt          text,
    method           text        not null check (method in ('TABLE', 'PATTERN', 'OCR', 'LLM')),
    grounding_status text        not null default 'NOT_APPLICABLE'
                         check (grounding_status in ('VERIFIED', 'UNVERIFIED', 'REJECTED', 'NOT_APPLICABLE')),
    quote            text,
    row_id           text,
    condition_text   text,
    verifier_reasons jsonb,
    llm_call_id      bigint references llm_call (id),
    fact_identity    char(64)    not null,
    created_at       timestamptz not null default now()
);

create index document_fact_asset on document_fact (asset_id, parser_version);

create table attribute_value (
    id                      bigint generated always as identity primary key,
    revision_id             bigint         not null references extraction_revision (id),
    family_id               bigint         not null references product_family (id),
    variant_id              bigint references product_variant (id),
    attribute_definition_id bigint references attribute_definition (id),
    raw_label               text           not null,
    raw_value               text           not null,
    value_text              text,
    value_number            numeric(30, 10),
    value_min               numeric(30, 10),
    value_max               numeric(30, 10),
    unit_id                 bigint references unit_definition (id),
    original_unit           text,
    original_number         text,
    qualifier               text check (qualifier in ('EXACT', 'APPROX', 'MAX', 'MIN', 'LESS_THAN', 'GREATER_THAN',
                                                      'NOMINAL_WITH_TOLERANCE')),
    tolerance               numeric(30, 10),
    option_id               bigint references attribute_option (id),
    position                integer        not null default 0,
    normalization_status    text           not null default 'PENDING'
                                check (normalization_status in ('PENDING', 'OK', 'PARTIAL', 'UNPARSEABLE', 'NOT_SPECIFIED')),
    normalization_reasons   jsonb,
    normalizer_version      text,
    source_type             text           not null check (source_type in ('JSON_API', 'JSON_LD', 'HTML_SELECTOR',
                                                                          'PDF_TEXT', 'OCR', 'LLM')),
    source_resource_id      bigint references fetched_resource (id),
    source_asset_id         bigint references asset (id),
    document_fact_id        bigint references document_fact (id),
    source_locator          text,
    source_excerpt          text,
    corroborating_sources   jsonb          not null default '[]'::jsonb,
    note                    text,
    quote                   text,
    row_id                  text,
    condition_text          text,
    grounding_status        text           not null default 'NOT_APPLICABLE'
                                check (grounding_status in ('VERIFIED', 'UNVERIFIED', 'REJECTED', 'NOT_APPLICABLE')),
    verifier_reasons        jsonb,
    llm_call_id             bigint references llm_call (id),
    created_at              timestamptz    not null default now()
);

create index attribute_value_revision on attribute_value (revision_id);
create index attribute_value_family_attr on attribute_value (family_id, attribute_definition_id);

create table unstructured_section (
    id                 bigint generated always as identity primary key,
    revision_id        bigint      not null references extraction_revision (id),
    heading            text,
    text_content       text        not null,
    source_resource_id bigint references fetched_resource (id),
    locator            text,
    char_count         integer     not null,
    status             text        not null default 'PENDING' check (status in ('PENDING', 'LLM_DONE', 'SKIPPED')),
    created_at         timestamptz not null default now()
);

create table product_asset_link (
    id              bigint generated always as identity primary key,
    revision_id     bigint      not null references extraction_revision (id),
    family_id       bigint      not null references product_family (id),
    variant_id      bigint references product_variant (id),
    asset_source_id bigint      not null references asset_source (id),
    role_hint       text,
    position        integer     not null default 0,
    displayed_title text,
    context_heading text,
    created_at      timestamptz not null default now()
);

create index product_asset_link_family on product_asset_link (family_id, revision_id);

create table product_relation (
    id                bigint generated always as identity primary key,
    revision_id       bigint      not null references extraction_revision (id),
    from_family_id    bigint      not null references product_family (id),
    to_family_id      bigint references product_family (id),
    target_sku_text   text,
    relation_type     text        not null check (relation_type in ('ACCESSORY', 'COMPONENT', 'REPLACED_BY', 'RELATED')),
    created_at        timestamptz not null default now(),
    constraint product_relation_target check (to_family_id is not null or target_sku_text is not null)
);

create table family_category (
    id            bigint generated always as identity primary key,
    family_id     bigint  not null references product_family (id),
    revision_id   bigint references extraction_revision (id),
    category_path text    not null,
    position      integer not null default 0
);

create table revision_change (
    id           bigint generated always as identity primary key,
    revision_id  bigint not null references extraction_revision (id),
    change_kind  text   not null check (change_kind in ('VALUE_CHANGED', 'VALUE_ADDED', 'VALUE_REMOVED',
                                                        'VARIANT_ADDED', 'VARIANT_REMOVED', 'VARIANT_REAPPEARED',
                                                        'ASSET_ADDED', 'ASSET_REMOVED', 'DOC_VERSION', 'LIFECYCLE',
                                                        'CATEGORY', 'RELATION', 'DESCRIPTION')),
    target_key   text   not null,
    old_value    jsonb,
    new_value    jsonb,
    significance text   not null check (significance in ('NONE', 'MINOR', 'MATERIAL'))
);

create index revision_change_revision on revision_change (revision_id);
