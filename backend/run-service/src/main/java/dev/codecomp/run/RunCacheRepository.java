package dev.codecomp.run;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RunCacheRepository extends JpaRepository<RunCacheEntry, String> {}
