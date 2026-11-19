-- JSON list of per-test results for FIX submissions, so past test runs can be shown again.
alter table repo_submissions add column detail varchar(20000);
