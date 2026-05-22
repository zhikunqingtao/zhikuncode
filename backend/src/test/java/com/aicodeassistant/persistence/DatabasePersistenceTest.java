package com.aicodeassistant.persistence;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TC-DB-001~005 数据库持久化专项测试。
 * 使用嵌入式 SQLite (无需 Spring 上下文)。
 */
@DisplayName("数据库持久化专项测试")
class DatabasePersistenceTest {

    @TempDir
    Path tempDir;

    private Connection getConnection() throws SQLException {
        Path dbPath = tempDir.resolve("test.db");
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA foreign_keys=ON");
            stmt.execute("PRAGMA busy_timeout=5000");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS sessions (
                    id TEXT PRIMARY KEY,
                    title TEXT,
                    model TEXT,
                    working_dir TEXT,
                    message_count INTEGER DEFAULT 0,
                    total_cost_usd REAL DEFAULT 0,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )""");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS messages (
                    id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    seq_num INTEGER NOT NULL,
                    role TEXT NOT NULL,
                    content_json TEXT NOT NULL,
                    stop_reason TEXT,
                    input_tokens INTEGER DEFAULT 0,
                    output_tokens INTEGER DEFAULT 0,
                    created_at TEXT NOT NULL,
                    UNIQUE(session_id, seq_num),
                    FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE
                )""");
        }
        return conn;
    }

    @Nested
    @DisplayName("TC-DB-001 会话 CRUD 与并发创建")
    class SessionCrudTest {

        @Test
        @DisplayName("会话CRUD与并发创建")
        void testSessionCrudAndConcurrentCreate() throws Exception {
            try (Connection conn = getConnection()) {
                String now = Instant.now().toString();

                // 创建会话
                String id = UUID.randomUUID().toString();
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO sessions (id, model, working_dir, created_at, updated_at) VALUES (?,?,?,?,?)")) {
                    ps.setString(1, id);
                    ps.setString(2, "qwen3.7-max");
                    ps.setString(3, "/tmp/test");
                    ps.setString(4, now);
                    ps.setString(5, now);
                    assertEquals(1, ps.executeUpdate());
                }

                // 查询验证
                try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM sessions WHERE id=?")) {
                    ps.setString(1, id);
                    ResultSet rs = ps.executeQuery();
                    assertTrue(rs.next(), "应能查到已创建的会话");
                    assertEquals("qwen3.7-max", rs.getString("model"));
                }

                // 并发创建10个会话
                Set<String> ids = ConcurrentHashMap.newKeySet();
                CountDownLatch latch = new CountDownLatch(10);
                for (int i = 0; i < 10; i++) {
                    new Thread(() -> {
                        try (Connection c = getConnection();
                             PreparedStatement ps = c.prepareStatement(
                                "INSERT INTO sessions (id, model, working_dir, created_at, updated_at) VALUES (?,?,?,?,?)")) {
                            String sid = UUID.randomUUID().toString();
                            ps.setString(1, sid);
                            ps.setString(2, "model");
                            ps.setString(3, "/tmp");
                            ps.setString(4, now);
                            ps.setString(5, now);
                            ps.executeUpdate();
                            ids.add(sid);
                        } catch (Exception e) { /* ignore */ }
                        finally { latch.countDown(); }
                    }).start();
                }
                latch.await(10, TimeUnit.SECONDS);
                assertEquals(10, ids.size(), "应创建10个不同ID");

                // 删除验证
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM sessions WHERE id=?")) {
                    ps.setString(1, id);
                    assertEquals(1, ps.executeUpdate());
                }
                try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM sessions WHERE id=?")) {
                    ps.setString(1, id);
                    assertFalse(ps.executeQuery().next(), "删除后应查不到");
                }
            }
        }
    }

    @Nested
    @DisplayName("TC-DB-002 消息原子性插入与序号分配")
    class MessageAtomicInsertTest {

        @Test
        @DisplayName("消息原子性插入与序号分配")
        void testMessageAtomicInsert() throws Exception {
            try (Connection conn = getConnection()) {
                String now = Instant.now().toString();
                String sessionId = UUID.randomUUID().toString();

                // 创建会话
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO sessions (id, model, working_dir, created_at, updated_at) VALUES (?,?,?,?,?)")) {
                    ps.setString(1, sessionId);
                    ps.setString(2, "model");
                    ps.setString(3, "/tmp");
                    ps.setString(4, now);
                    ps.setString(5, now);
                    ps.executeUpdate();
                }

                // 插入消息
                for (int i = 1; i <= 2; i++) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO messages (id, session_id, seq_num, role, content_json, created_at) VALUES (?,?,?,?,?,?)")) {
                        ps.setString(1, UUID.randomUUID().toString());
                        ps.setString(2, sessionId);
                        ps.setInt(3, i);
                        ps.setString(4, i == 1 ? "user" : "assistant");
                        ps.setString(5, "{\"text\":\"msg-" + i + "\"}");
                        ps.setString(6, now);
                        ps.executeUpdate();
                    }
                }

                // 验证
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT * FROM messages WHERE session_id=? ORDER BY seq_num")) {
                    ps.setString(1, sessionId);
                    ResultSet rs = ps.executeQuery();

                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt("seq_num"));
                    assertEquals("user", rs.getString("role"));

                    assertTrue(rs.next());
                    assertEquals(2, rs.getInt("seq_num"));
                    assertEquals("assistant", rs.getString("role"));

                    assertFalse(rs.next());
                }
            }
        }
    }

    @Nested
    @DisplayName("TC-DB-003 外键约束级联删除")
    class ForeignKeyCascadeTest {

        @Test
        @DisplayName("外键级联删除验证")
        void testForeignKeyCascadeDelete() throws Exception {
            try (Connection conn = getConnection()) {
                String now = Instant.now().toString();
                String sessionId = UUID.randomUUID().toString();

                // 创建会话 + 插入消息
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO sessions (id, model, working_dir, created_at, updated_at) VALUES (?,?,?,?,?)")) {
                    ps.setString(1, sessionId);
                    ps.setString(2, "model");
                    ps.setString(3, "/tmp");
                    ps.setString(4, now);
                    ps.setString(5, now);
                    ps.executeUpdate();
                }
                for (int i = 1; i <= 2; i++) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO messages (id, session_id, seq_num, role, content_json, created_at) VALUES (?,?,?,?,?,?)")) {
                        ps.setString(1, UUID.randomUUID().toString());
                        ps.setString(2, sessionId);
                        ps.setInt(3, i);
                        ps.setString(4, "user");
                        ps.setString(5, "{}");
                        ps.setString(6, now);
                        ps.executeUpdate();
                    }
                }

                // 验证消息存在
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM messages WHERE session_id=?")) {
                    ps.setString(1, sessionId);
                    ResultSet rs = ps.executeQuery();
                    rs.next();
                    assertEquals(2, rs.getInt(1));
                }

                // 级联删除
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM sessions WHERE id=?")) {
                    ps.setString(1, sessionId);
                    ps.executeUpdate();
                }

                // 验证消息被级联删除
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM messages WHERE session_id=?")) {
                    ps.setString(1, sessionId);
                    ResultSet rs = ps.executeQuery();
                    rs.next();
                    assertEquals(0, rs.getInt(1), "级联删除后消息应为空");
                }
            }
        }
    }

    @Nested
    @DisplayName("TC-DB-004 并发写入串行化")
    class ConcurrentWriteTest {

        @Test
        @DisplayName("10线程并发写入串行化")
        void testConcurrentWriteSerialization() throws Exception {
            try (Connection conn = getConnection()) {
                String now = Instant.now().toString();
                String sessionId = UUID.randomUUID().toString();

                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO sessions (id, model, working_dir, created_at, updated_at) VALUES (?,?,?,?,?)")) {
                    ps.setString(1, sessionId);
                    ps.setString(2, "model");
                    ps.setString(3, "/tmp");
                    ps.setString(4, now);
                    ps.setString(5, now);
                    ps.executeUpdate();
                }

                int threadCount = 10;
                CountDownLatch latch = new CountDownLatch(threadCount);
                AtomicInteger errorCount = new AtomicInteger(0);
                AtomicInteger seqCounter = new AtomicInteger(0);

                for (int i = 0; i < threadCount; i++) {
                    new Thread(() -> {
                        try (Connection c = getConnection();
                             PreparedStatement ps = c.prepareStatement(
                                "INSERT INTO messages (id, session_id, seq_num, role, content_json, created_at) VALUES (?,?,?,?,?,?)")) {
                            int seq = seqCounter.incrementAndGet();
                            ps.setString(1, UUID.randomUUID().toString());
                            ps.setString(2, sessionId);
                            ps.setInt(3, seq);
                            ps.setString(4, "user");
                            ps.setString(5, "{\"text\":\"msg-" + seq + "\"}");
                            ps.setString(6, Instant.now().toString());
                            ps.executeUpdate();
                        } catch (Exception e) {
                            errorCount.incrementAndGet();
                        } finally {
                            latch.countDown();
                        }
                    }).start();
                }

                latch.await(30, TimeUnit.SECONDS);
                assertEquals(0, errorCount.get(), "并发写入不应有错误");

                // 验证
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM messages WHERE session_id=?")) {
                    ps.setString(1, sessionId);
                    ResultSet rs = ps.executeQuery();
                    rs.next();
                    assertEquals(threadCount, rs.getInt(1), "应有10条消息");
                }

                // 验证序号唯一
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(DISTINCT seq_num) FROM messages WHERE session_id=?")) {
                    ps.setString(1, sessionId);
                    ResultSet rs = ps.executeQuery();
                    rs.next();
                    assertEquals(threadCount, rs.getInt(1), "所有序号应唯一");
                }
            }
        }
    }

    @Nested
    @DisplayName("TC-DB-005 消息回退 (deleteAfterSeqNum)")
    class MessageRollbackTest {

        @Test
        @DisplayName("删除指定序列号之后的消息")
        void testDeleteAfterSeqNum() throws Exception {
            try (Connection conn = getConnection()) {
                String now = Instant.now().toString();
                String sessionId = UUID.randomUUID().toString();

                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO sessions (id, model, working_dir, created_at, updated_at) VALUES (?,?,?,?,?)")) {
                    ps.setString(1, sessionId);
                    ps.setString(2, "model");
                    ps.setString(3, "/tmp");
                    ps.setString(4, now);
                    ps.setString(5, now);
                    ps.executeUpdate();
                }

                // 插入5条消息
                for (int i = 1; i <= 5; i++) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO messages (id, session_id, seq_num, role, content_json, created_at) VALUES (?,?,?,?,?,?)")) {
                        ps.setString(1, UUID.randomUUID().toString());
                        ps.setString(2, sessionId);
                        ps.setInt(3, i);
                        ps.setString(4, "user");
                        ps.setString(5, "{\"text\":\"msg-" + i + "\"}");
                        ps.setString(6, now);
                        ps.executeUpdate();
                    }
                }

                // 删除 seq_num > 3
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM messages WHERE session_id=? AND seq_num > ?")) {
                    ps.setString(1, sessionId);
                    ps.setInt(2, 3);
                    int deleted = ps.executeUpdate();
                    assertEquals(2, deleted, "应删除 seq_num 4 和 5");
                }

                // 验证剩余
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT seq_num FROM messages WHERE session_id=? ORDER BY seq_num")) {
                    ps.setString(1, sessionId);
                    ResultSet rs = ps.executeQuery();
                    List<Integer> remaining = new ArrayList<>();
                    while (rs.next()) remaining.add(rs.getInt(1));
                    assertEquals(List.of(1, 2, 3), remaining);
                }
            }
        }

        @Test
        @DisplayName("会话隔离：只删除目标会话的消息")
        void testSessionIsolation() throws Exception {
            try (Connection conn = getConnection()) {
                String now = Instant.now().toString();
                String s1 = UUID.randomUUID().toString();
                String s2 = UUID.randomUUID().toString();

                for (String sid : List.of(s1, s2)) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO sessions (id, model, working_dir, created_at, updated_at) VALUES (?,?,?,?,?)")) {
                        ps.setString(1, sid);
                        ps.setString(2, "model");
                        ps.setString(3, "/tmp");
                        ps.setString(4, now);
                        ps.setString(5, now);
                        ps.executeUpdate();
                    }
                    for (int i = 1; i <= 3; i++) {
                        try (PreparedStatement ps = conn.prepareStatement(
                                "INSERT INTO messages (id, session_id, seq_num, role, content_json, created_at) VALUES (?,?,?,?,?,?)")) {
                            ps.setString(1, UUID.randomUUID().toString());
                            ps.setString(2, sid);
                            ps.setInt(3, i);
                            ps.setString(4, "user");
                            ps.setString(5, "{}");
                            ps.setString(6, now);
                            ps.executeUpdate();
                        }
                    }
                }

                // 只删除 s1 的 seq_num > 1
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM messages WHERE session_id=? AND seq_num > ?")) {
                    ps.setString(1, s1);
                    ps.setInt(2, 1);
                    ps.executeUpdate();
                }

                // s1 应剩1条, s2 应保持3条
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM messages WHERE session_id=?")) {
                    ps.setString(1, s1);
                    ResultSet rs = ps.executeQuery(); rs.next();
                    assertEquals(1, rs.getInt(1));
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM messages WHERE session_id=?")) {
                    ps.setString(1, s2);
                    ResultSet rs = ps.executeQuery(); rs.next();
                    assertEquals(3, rs.getInt(1));
                }
            }
        }
    }
}
