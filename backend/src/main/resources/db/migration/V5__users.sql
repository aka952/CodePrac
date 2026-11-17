-- Accounts created at first sign-in. id is the user id used by attempts, repo_submissions and the rate limiter.
create table users (
    id            uuid                     primary key,
    provider      varchar(20)              not null,
    subject       varchar(255)             not null,
    email         varchar(320),
    name          varchar(200),
    picture       varchar(1000),
    created_at    timestamp with time zone not null,
    last_login_at timestamp with time zone not null,
    unique (provider, subject)
);
