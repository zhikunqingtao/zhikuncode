package com.aicodeassistant.config.database;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component @Order(16)
public class V016_RemoveLegacyGlobalPermissionRules implements Migration {
    private static final String CHECKSUM = "97199feb578f0b39ee8c8971be647fd06c3ea8abeb1358ce76c70962f8a359a7";
    private final JdbcTemplate jdbc;
    public V016_RemoveLegacyGlobalPermissionRules(@Qualifier("globalJdbcTemplate") JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public Scope scope() { return Scope.GLOBAL; }
    @Override public String checksum() { return CHECKSUM; }
    @Override public void execute() { jdbc.execute("DROP TABLE IF EXISTS permission_rules"); }
    @Override public void validate() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='permission_rules'", Integer.class);
        if (count != null && count != 0) throw new IllegalStateException("Legacy global permission_rules remains");
    }
}
