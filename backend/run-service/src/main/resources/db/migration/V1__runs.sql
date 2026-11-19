-- Run results keyed by sha256(language + code), so the same code is answered without running it again.
create table run_cache (
    hash        varchar(64)              primary key,
    language    varchar(30)              not null,
    status      varchar(20)              not null,
    stdout      varchar(70000)           not null,
    stderr      varchar(70000)           not null,
    exit_code   int                      not null,
    duration_ms bigint                   not null,
    truncated   boolean                  not null,
    created_at  timestamp with time zone not null
);

-- Every queued run and what became of it. The queue itself is SQS; this table is the state the browser sees.
-- user_id is null for the system's own runs (repo-service measuring an exercise's baseline).
-- sent_at stays null until the message is on the queue, so a run whose send failed can be found and sent again.
-- Finished rows are deleted after a day.
create table run_jobs (
    id          uuid                     primary key,
    user_id     uuid,
    language    varchar(30)              not null,
    code        varchar(1000000)         not null,
    status      varchar(10)              not null,
    worker      varchar(100),
    created_at  timestamp with time zone not null,
    sent_at     timestamp with time zone,
    started_at  timestamp with time zone,
    finished_at timestamp with time zone,
    result      varchar(1000000),
    error       varchar(1000)
);

create index idx_run_jobs_status_created on run_jobs (status, created_at);
create index idx_run_jobs_user_status on run_jobs (user_id, status);
