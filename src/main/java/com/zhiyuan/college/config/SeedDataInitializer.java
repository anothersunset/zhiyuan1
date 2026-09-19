package com.zhiyuan.college.config;

import java.nio.charset.StandardCharsets;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

/**
 * Reconciles the running database with the seed registry (classpath:db/data.sql).
 *
 * <p>sql/data.sql is the single source of truth for reference data (universities,
 * majors, admission cutoffs, score-rank mappings and demo accounts). MySQL's
 * docker-entrypoint-initdb.d only replays it on an EMPTY data volume, so edits to
 * the file never reach an already-initialized environment. With
 * {@code DB_SEED_MODE=always} this runner re-applies the file on every startup;
 * every INSERT is INSERT IGNORE guarded by unique keys, so the replay is
 * idempotent: new rows are added, existing rows (and any runtime edits) are kept.
 *
 * <p>Schema changes do NOT belong here — schema.sql keeps its own
 * DB_SCHEMA_INIT_MODE switch, because re-running the schema against an existing
 * volume breaks on DROP/FK ordering.
 */
@Component
@Order(100)
public class SeedDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedDataInitializer.class);

    public static final String MODE_NEVER = "never";
    public static final String MODE_ALWAYS = "always";

    private final DataSource dataSource;
    private final String seedMode;

    public SeedDataInitializer(DataSource dataSource,
                               @org.springframework.beans.factory.annotation.Value("${app.db-seed-mode:never}") String seedMode) {
        this.dataSource = dataSource;
        this.seedMode = seedMode == null ? MODE_NEVER : seedMode.trim().toLowerCase();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!MODE_ALWAYS.equals(seedMode)) {
            log.info("Seed replay skipped (app.db-seed-mode={})", seedMode);
            return;
        }
        long start = System.currentTimeMillis();
        try (var connection = dataSource.getConnection()) {
            EncodedResource script = new EncodedResource(
                    new ClassPathResource("db/data.sql"), StandardCharsets.UTF_8);
            ScriptUtils.executeSqlScript(connection, script);
            log.info("Seed registry applied from db/data.sql in {} ms (mode=always, idempotent INSERT IGNORE)",
                    System.currentTimeMillis() - start);
        } catch (Exception ex) {
            // Seeding must never block the application from starting; core features
            // keep working with whatever data the database already holds.
            log.error("Seed replay from db/data.sql failed after {} ms", System.currentTimeMillis() - start, ex);
        }
    }
}
