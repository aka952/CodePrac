-- Local development only: one database and one login per service, as on RDS. A service can reach only its own
-- database, so "no service reads another service's tables" is enforced by the database, not just by convention.
-- Runs once, when the Postgres volume is first created.
create user identity with password 'identity';
create database identity owner identity;
create user exercise with password 'exercise';
create database exercise owner exercise;
create user repo with password 'repo';
create database repo owner repo;
create user run with password 'run';
create database run owner run;

revoke connect on database identity, exercise, repo, run from public;
