-- One row per person, keyed by the provider's stable id for them. The id is the user id every service stores.
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
