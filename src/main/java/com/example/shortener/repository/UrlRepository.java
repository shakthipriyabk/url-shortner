package com.example.shortener.repository;

import com.example.shortener.domain.UrlMapping;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

@Repository
public class UrlRepository {

    private static final RowMapper<UrlMapping> MAPPER = (rs, rowNum) -> {
        Timestamp expires = rs.getTimestamp("expires_at");
        return new UrlMapping(
                rs.getLong("id"),
                rs.getString("short_code"),
                rs.getString("original_url"),
                rs.getTimestamp("created_at").toInstant(),
                expires == null ? null : expires.toInstant());
    };

    private final JdbcClient jdbc;

    public UrlRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Next id from the Postgres sequence. Unique across all app instances, with no coordination between them. */
    public long nextId() {
        return jdbc.sql("select nextval('url_seq')").query(Long.class).single();
    }

    public void insert(UrlMapping m) {
        jdbc.sql("""
                        insert into url_mapping (id, short_code, original_url, created_at, expires_at)
                        values (:id, :code, :url, :createdAt, :expiresAt)
                        """)
                .param("id", m.id())
                .param("code", m.shortCode())
                .param("url", m.originalUrl())
                .param("createdAt", Timestamp.from(m.createdAt()))
                .param("expiresAt", m.expiresAt() == null ? null : Timestamp.from(m.expiresAt()))
                .update();
    }

    public Optional<UrlMapping> findByCode(String code) {
        return jdbc.sql("""
                        select id, short_code, original_url, created_at, expires_at
                        from url_mapping
                        where short_code = :code
                        """)
                .param("code", code)
                .query(MAPPER)
                .optional();
    }
}
