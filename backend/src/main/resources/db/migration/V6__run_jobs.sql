-- Code runs waiting for, or handled by, a worker. The API inserts QUEUED rows; workers claim them, run the
-- sandbox and store the response the browser receives in result. Finished rows are deleted after a day.
create table run_jobs (
    id           uuid                     primary key,
    user_id      uuid                     not null,
    kind         varchar(20)              not null,
    language     varchar(30)              not null,
    code         varchar(1000000)         not null,
    repo_slug    varchar(120),
    status       varchar(10)              not null,
    attempts     int                      not null,
    worker       varchar(100),
    created_at   timestamp with time zone not null,
    started_at   timestamp with time zone,
    heartbeat_at timestamp with time zone,
    finished_at  timestamp with time zone,
    result       varchar(1000000),
    error        varchar(1000)
);

create index idx_run_jobs_status_created on run_jobs (status, created_at);
create index idx_run_jobs_user_status on run_jobs (user_id, status);
