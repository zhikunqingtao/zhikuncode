package com.aicodeassistant.authorization;

import com.aicodeassistant.config.database.DatabaseResolver;
import com.aicodeassistant.config.database.SqliteConfig;
import com.aicodeassistant.config.database.V015_CreateInteractionSchema;
import com.aicodeassistant.config.database.V019_CreateAuthorizationSchema;
import com.aicodeassistant.model.PermissionScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionGrantRepositoryTest {
    @TempDir Path temp;
    private SqliteConfig sqlite;

    @AfterEach void close() { if (sqlite != null) sqlite.destroy(); }

    @Test
    void sessionGrantIsInheritedByDescendantsButDirectRunGrantIsNot() {
        Fixture f = fixture();
        OperationDescriptor bash = operation("Bash", "execute", "bash-v2", RiskClass.GUARDED,
                List.of(EffectClass.PROCESS, EffectClass.READ_RESOURCE), List.of(), "bash-op");
        AuthorizationSubject child = new AuthorizationSubject("s-root", "r-root", "r-child", "workspace", temp);
        String sessionGrant = f.repository.create(child, bash, PermissionScope.SESSION, null);

        AuthorizationSubject sibling = new AuthorizationSubject("s-root", "r-root", "r-sibling", "workspace", temp);
        assertThat(f.repository.findMatch(sibling, bash).grantId()).isEqualTo(sessionGrant);

        OperationDescriptor exactFile = operation("Read", TypedFileOperation.READ_FILE.name(), "file-v1",
                RiskClass.SAFE, List.of(EffectClass.READ_RESOURCE),
                List.of(new ResourceRef("path", "README.md", false)), "root-file");
        AuthorizationSubject root = new AuthorizationSubject("s-root", "r-root", "r-root", "workspace", temp);
        String direct = f.repository.create(root, exactFile, PermissionScope.RUN, null);
        assertThat(f.repository.findMatch(root, exactFile).grantId()).isEqualTo(direct);
        assertThat(f.repository.findMatch(child, exactFile)).isNull();
    }

    @Test
    void persistedChildRunWithSyntheticSessionInheritsRootSessionGrant() {
        Fixture f = fixture();
        AuthorizationSubjectResolver resolver = new AuthorizationSubjectResolver(f.jdbc);
        AuthorizationSubject root = resolver.resolve("r-root");
        AuthorizationSubject child = resolver.resolve("r-child");
        assertThat(child.rootSessionId()).isEqualTo(root.rootSessionId());
        assertThat(child.rootRunId()).isEqualTo(root.rootRunId());
        assertThat(child.currentRunId()).isEqualTo("r-child");

        OperationDescriptor bash = operation("Bash", "execute", "bash-v2", RiskClass.GUARDED,
                List.of(EffectClass.PROCESS, EffectClass.READ_RESOURCE), List.of(), "inherited-bash");
        String grantId = f.repository.create(root, bash, PermissionScope.SESSION, null);
        assertThat(f.repository.findMatch(child, bash).grantId()).isEqualTo(grantId);
    }

    @Test
    void createReturnsTheExactInsertedGrantRatherThanAnotherMatchingScope() {
        Fixture f = fixture();
        AuthorizationSubject root = new AuthorizationSubject(
                "s-root", "r-root", "r-root", "workspace", temp);
        OperationDescriptor bash = operation("Bash", "execute", "bash-v2", RiskClass.GUARDED,
                List.of(EffectClass.PROCESS, EffectClass.READ_RESOURCE), List.of(), "same-bash");
        String runGrant = f.repository.create(root, bash, PermissionScope.RUN, null);
        String sessionGrant = f.repository.create(root, bash, PermissionScope.SESSION, null);

        assertThat(sessionGrant).isNotEqualTo(runGrant);
        assertThat(f.jdbc.queryForList(
                "SELECT grant_id,scope FROM permission_grants WHERE grant_id IN (?,?) ORDER BY scope",
                runGrant, sessionGrant)).hasSize(2);
    }

    @Test
    void workspaceCapabilityCrossesSessionsOnlyForSameWorkspaceAndSegment() {
        Fixture f = fixture();
        OperationDescriptor read = operation("Read", TypedFileOperation.READ_FILE.name(), "file-v1",
                RiskClass.SAFE, List.of(EffectClass.READ_RESOURCE),
                List.of(new ResourceRef("path", "src/main/App.java", false)), "read-one");
        AuthorizationSubject first = new AuthorizationSubject("s-root", "r-root", "r-root", "workspace", temp);
        String grant = f.repository.create(first, read, PermissionScope.WORKSPACE, null);

        OperationDescriptor sameDirectory = operation("Read", TypedFileOperation.READ_FILE.name(), "file-v1",
                RiskClass.SAFE, List.of(EffectClass.READ_RESOURCE),
                List.of(new ResourceRef("path", "src/main/Other.java", false)), "read-two");
        AuthorizationSubject otherSession = new AuthorizationSubject("s-other", "r-other", "r-other", "workspace", temp);
        assertThat(f.repository.findMatch(otherSession, sameDirectory).grantId()).isEqualTo(grant);
        assertThat(f.repository.findMatch(new AuthorizationSubject("s-other", "r-other", "r-other", "other", temp),
                sameDirectory)).isNull();

        OperationDescriptor siblingPrefix = operation("Read", TypedFileOperation.READ_FILE.name(), "file-v1",
                RiskClass.SAFE, List.of(EffectClass.READ_RESOURCE),
                List.of(new ResourceRef("path", "src/main2/Other.java", false)), "read-three");
        assertThat(f.repository.findMatch(otherSession, siblingPrefix)).isNull();
    }

    @Test
    void rootLevelFileExpandsOnlyToWorkspaceRootDirectory() {
        Fixture f = fixture();
        AuthorizationSubject subject = new AuthorizationSubject(
                "s-root", "r-root", "r-root", "workspace", temp);
        OperationDescriptor rootFile = operation("Read", TypedFileOperation.READ_FILE.name(), "file-v1",
                RiskClass.SAFE, List.of(EffectClass.READ_RESOURCE),
                List.of(new ResourceRef("path", "README.md", false)), "root-file");
        assertThat(f.repository.supportedScopes(rootFile))
                .containsExactly(PermissionScope.RUN, PermissionScope.SESSION, PermissionScope.WORKSPACE);
        String grant = f.repository.create(subject, rootFile, PermissionScope.WORKSPACE, null);

        OperationDescriptor siblingRootFile = operation("Read", TypedFileOperation.READ_FILE.name(), "file-v1",
                RiskClass.SAFE, List.of(EffectClass.READ_RESOURCE),
                List.of(new ResourceRef("path", "LICENSE", false)), "root-sibling");
        OperationDescriptor nestedFile = operation("Read", TypedFileOperation.READ_FILE.name(), "file-v1",
                RiskClass.SAFE, List.of(EffectClass.READ_RESOURCE),
                List.of(new ResourceRef("path", "src/App.java", false)), "nested-file");
        assertThat(f.repository.findMatch(subject, siblingRootFile).grantId()).isEqualTo(grant);
        assertThat(f.repository.findMatch(subject, nestedFile).grantId()).isEqualTo(grant);
    }

    @Test
    void directoryListingCapabilityDoesNotExpandToParentDirectory() {
        Fixture f = fixture();
        AuthorizationSubject subject = new AuthorizationSubject(
                "s-root", "r-root", "r-root", "workspace", temp);
        OperationDescriptor listed = operation("Glob", TypedFileOperation.LIST_DIRECTORY.name(), "file-v1",
                RiskClass.SAFE, List.of(EffectClass.READ_RESOURCE),
                List.of(new ResourceRef("path", "src/main", false)), "list-main");
        String grant = f.repository.create(subject, listed, PermissionScope.WORKSPACE, null);

        OperationDescriptor descendant = operation("Glob", TypedFileOperation.LIST_DIRECTORY.name(), "file-v1",
                RiskClass.SAFE, List.of(EffectClass.READ_RESOURCE),
                List.of(new ResourceRef("path", "src/main/java", false)), "list-child");
        OperationDescriptor sibling = operation("Glob", TypedFileOperation.LIST_DIRECTORY.name(), "file-v1",
                RiskClass.SAFE, List.of(EffectClass.READ_RESOURCE),
                List.of(new ResourceRef("path", "src/test", false)), "list-sibling");
        assertThat(f.repository.findMatch(subject, descendant).grantId()).isEqualTo(grant);
        assertThat(f.repository.findMatch(subject, sibling)).isNull();
    }

    private Fixture fixture() {
        DatabaseResolver resolver = new DatabaseResolver("", temp.toString());
        sqlite = new SqliteConfig(resolver);
        var dataSource = sqlite.getProjectDataSource(temp);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("PRAGMA foreign_keys=ON");
        jdbc.execute("CREATE TABLE sessions(id TEXT PRIMARY KEY,working_dir TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE run_envelopes(id TEXT PRIMARY KEY,session_id TEXT NOT NULL,parent_run_id TEXT,status TEXT NOT NULL)");
        new V015_CreateInteractionSchema(jdbc).execute();
        new V019_CreateAuthorizationSchema(jdbc).execute();
        jdbc.update("INSERT INTO sessions(id,working_dir) VALUES('s-root',?),('s-other',?)",
                temp.toString(), temp.toString());
        jdbc.update("INSERT INTO run_envelopes(id,session_id,parent_run_id,status) VALUES" +
                "('r-root','s-root',NULL,'running'),('r-child','s-child','r-root','running')," +
                "('r-sibling','s-child','r-root','running'),('r-other','s-other',NULL,'running')");
        PermissionGrantRepository repository = new PermissionGrantRepository(jdbc, sqlite, resolver,
                new DataSourceTransactionManager(dataSource), new ObjectMapper(), Clock.systemUTC());
        return new Fixture(jdbc, repository);
    }

    private static OperationDescriptor operation(String tool, String action, String analyzer, RiskClass risk,
                                                   List<EffectClass> effects, List<ResourceRef> resources,
                                                   String operationHash) {
        return new OperationDescriptor(1, tool, action, "input-" + operationHash, analyzer,
                effects, resources, List.of(), List.of(), risk, operationHash, tool + " operation");
    }

    private record Fixture(JdbcTemplate jdbc, PermissionGrantRepository repository) { }
}
