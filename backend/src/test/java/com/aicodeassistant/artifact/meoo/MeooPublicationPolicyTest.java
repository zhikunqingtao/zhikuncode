package com.aicodeassistant.artifact.meoo;

import com.aicodeassistant.config.meoo.MeooPublishProperties;
import com.aicodeassistant.tool.*;
import com.aicodeassistant.verify.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MeooPublicationPolicyTest {
    @TempDir Path temp;
    Path root; MeooPublicationPolicy policy; MeooPublishProperties props; EvidenceStore evidence;
    ToolUseContext context;
    @BeforeEach void setup() throws Exception {
        root=Files.createDirectory(temp.resolve("site")).toRealPath();
        Path credential=temp.resolve("credentials.json");
        Files.writeString(credential,"{\"apiBaseUrl\":\"https://meoo.com\",\"credentialType\":\"api_key\",\"apiKey\":\"meoo_ak_test_fixture_only\",\"userId\":\"account-test\"}");
        Files.setPosixFilePermissions(credential,PosixFilePermissions.fromString("rw-------"));
        props=new MeooPublishProperties();props.setEnabled(true);props.setCredentialsFile(credential.toString());
        evidence=mock(EvidenceStore.class);policy=new MeooPublicationPolicy(props,evidence);
        context=ToolUseContext.of(root.toString(),"session").withCurrentRunId("run").withToolUseId("tool");
        Files.writeString(root.resolve("index.html"),"<h1>Site</h1>");
    }
    ToolInput input(String path,String runtime) { return ToolInput.from(Map.of("path",path,"runtime",runtime)); }
    @Test void staticDirectoryPreservesAssetsAndExcludesPrivateAndDevelopmentFiles() throws Exception {
        Files.createDirectories(root.resolve("vendor")); Files.writeString(root.resolve("vendor/main.js"),"export const value=42;");
        Files.writeString(root.resolve(".env"),"private");Files.writeString(root.resolve("credentials.json"),"private");Files.writeString(root.resolve("verify.py"),"print(1)");
        Files.createSymbolicLink(root.resolve("outside.js"),temp.resolve("credentials.json"));
        var snapshot=policy.inspect(input(".","static"),context,false);
        assertThat(snapshot.files()).extracting(MeooPublicationPolicy.FileFact::relativePath).containsExactly("index.html","vendor/main.js");
        Path stage=temp.resolve("stage");policy.stage(snapshot,stage);
        assertThat(Files.readString(stage.resolve("vendor/main.js"))).contains("42");
    }
    @Test void standaloneHtmlAndResourceDirectoryRequirement() throws Exception {
        assertThat(policy.inspect(input("index.html","static"),context,false).files()).hasSize(1);
        Files.writeString(root.resolve("index.html"),"<script src='./main.js'></script>");
        assertThatThrownBy(()->policy.inspect(input("index.html","static"),context,false)).hasMessage("MEOO_STATIC_DIRECTORY_REQUIRED");
        assertThatThrownBy(()->policy.inspect(input(".","static"),context,false)).hasMessage("MEOO_STATIC_RESOURCE_MISSING");
    }
    @Test void cssAndResponsiveResourcesMustBePresent() throws Exception {
        Files.writeString(root.resolve("index.html"),"<style>body{background:url('./image.png')}</style>");
        assertThatThrownBy(()->policy.inspect(input("index.html","static"),context,false)).hasMessage("MEOO_STATIC_DIRECTORY_REQUIRED");
        assertThatThrownBy(()->policy.inspect(input(".","static"),context,false)).hasMessage("MEOO_STATIC_RESOURCE_MISSING");
        Files.writeString(root.resolve("image.png"),"fixture");
        assertThat(policy.inspect(input(".","static"),context,false).files()).hasSize(2);
        Files.writeString(root.resolve("index.html"),"<img srcset='image.png 1x, missing.png 2x'>");
        assertThatThrownBy(()->policy.inspect(input(".","static"),context,false)).hasMessage("MEOO_STATIC_RESOURCE_MISSING");
    }
    @Test void moduleDependenciesAreRequired() throws Exception {
        Files.writeString(root.resolve("main.js"),"import {texture} from './utils/texture.js';");
        assertThatThrownBy(()->policy.inspect(input(".","static"),context,false)).hasMessage("MEOO_STATIC_RESOURCE_MISSING");
    }
    @Test void inlineModulesRequireCompleteDirectoryAndKeepExternalImportsValid() throws Exception {
        for(String script:List.of("import './main.js';", "import {value} from './main.js';", "import('./main.js');")) {
            Files.writeString(root.resolve("index.html"),"<script type='module'>"+script+"</script>");
            assertThatThrownBy(()->policy.inspect(input("index.html","static"),context,false)).hasMessage("MEOO_STATIC_DIRECTORY_REQUIRED");
            assertThatThrownBy(()->policy.inspect(input(".","static"),context,false)).hasMessage("MEOO_STATIC_RESOURCE_MISSING");
        }
        Files.writeString(root.resolve("main.js"),"export const value=42;");
        assertThat(policy.inspect(input(".","static"),context,false).files())
            .extracting(MeooPublicationPolicy.FileFact::relativePath).containsExactly("index.html","main.js");
        Files.writeString(root.resolve("index.html"),"<script type='module'>import 'https://example.com/main.js';</script>");
        assertThat(policy.inspect(input("index.html","static"),context,false).files()).hasSize(1);
    }
    @Test void refusesEscapesAndSecrets() throws Exception {
        assertThatThrownBy(()->policy.inspect(input("../credentials.json","static"),context,false)).hasMessage("MEOO_PATH_ESCAPE");
        Files.writeString(root.resolve("app.js"),"const secret='meoo_ak_abcdefghijklmnopqrst';");
        assertThatThrownBy(()->policy.inspect(input(".","static"),context,false)).hasMessage("MEOO_SENSITIVE_CONTENT");
    }
    @Test void packageLimitsTrackPlatformImageCap() {
        // 平台仅硬性限制全栈源码 zip 不超过 100MiB；静态发布无平台包限制，只受本地配额约束。
        assertThat(MeooPublishProperties.PLATFORM_IMAGE_SOURCE_ZIP_BYTES).isEqualTo(100L*1024*1024);
        assertThat(MeooPublishProperties.IMAGE_SOURCE_MAX_BYTES).isEqualTo(102_760_448L);
        assertThat(new MeooPublishProperties().getMaxBytes()).isEqualTo(100L*1024*1024);
    }
    @Test void packageExceedingLimitIsRejectedAtInspection() throws Exception {
        Files.writeString(root.resolve("big.js"),"x".repeat(100));
        props.setMaxBytes(64);
        assertThatThrownBy(()->policy.inspect(input(".","static"),context,false)).hasMessage("MEOO_PACKAGE_LIMIT");
        assertThat(MeooException.guidance("MEOO_PACKAGE_LIMIT")).contains("100MiB");
    }
    @Test void imageEffectiveLimitNeverExceedsPlatformMargin() {
        var p=new MeooPublishProperties();
        p.setMaxBytes(MeooPublishProperties.PLATFORM_IMAGE_SOURCE_ZIP_BYTES);
        // 本地配额高于平台阈值时，image 仍按 98%×100MiB 提前拒绝；static 不受该平台限制。
        assertThat(MeooPublicationPolicy.effectiveMaxBytes("image",p)).isEqualTo(MeooPublishProperties.IMAGE_SOURCE_MAX_BYTES);
        assertThat(MeooPublicationPolicy.effectiveMaxBytes("static",p)).isEqualTo(MeooPublishProperties.PLATFORM_IMAGE_SOURCE_ZIP_BYTES);
        p.setMaxBytes(1024);
        assertThat(MeooPublicationPolicy.effectiveMaxBytes("image",p)).isEqualTo(1024);
        assertThat(MeooPublicationPolicy.effectiveMaxBytes("static",p)).isEqualTo(1024);
    }
    @Test void maxBytesAbovePlatformImageCapIsInvalidConfiguration() {
        props.setMaxBytes(MeooPublishProperties.PLATFORM_IMAGE_SOURCE_ZIP_BYTES+1);
        assertThatThrownBy(()->policy.inspect(input(".","static"),context,false)).hasMessage("MEOO_CONFIG_INVALID");
    }
    @Test void checksEvidenceAndRejectsModificationAfterApproval() throws Exception {
        var passed=EvidenceBundle.builder().bundleId("ev").sessionId("session").verdict("verified").items(List.of(new EvidenceItem(null,"test","passed",null,Map.of("workspace",root.toString(),"meooSnapshotSha256",policy.inspect(input(".","static"),context,false).sha256(),"meooRuntime","static")))).createdAt(Instant.now().plusSeconds(1)).build();
        when(evidence.findById("ev")).thenReturn(Optional.of(passed));
        var input=ToolInput.from(Map.of("path",".","runtime","static","verification_id","ev"));
        var snapshot=policy.inspect(input,context,true);
        Files.writeString(root.resolve("index.html"),"changed");
        assertThatThrownBy(()->policy.stage(snapshot,temp.resolve("stage"))).hasMessage("MEOO_SNAPSHOT_CHANGED");
        assertThatThrownBy(()->policy.inspect(input(".","static"),context,true)).hasMessage("MEOO_VERIFICATION_REQUIRED");
    }

    @Test void failedEvidenceCannotAuthorizePublicationRegardlessOfSession() {
        var snapshot = policy.inspect(input(".", "static"), context, false);
        for (var identity : List.of(List.of("session", "failed"), List.of("other-session", "failed"))) {
            var bundle = EvidenceBundle.builder().bundleId("ev-invalid").sessionId(identity.get(0)).verdict(identity.get(1))
                    .items(List.of(new EvidenceItem(null, "test", "snapshot", null, Map.of("workspace", root.toString(),
                            "meooSnapshotSha256", snapshot.sha256(), "meooRuntime", "static"))))
                    .createdAt(Instant.now().plusSeconds(1)).build();
            when(evidence.findById("ev-invalid")).thenReturn(Optional.of(bundle));
            var publish = ToolInput.from(Map.of("path", ".", "runtime", "static", "verification_id", "ev-invalid"));
            assertThatThrownBy(() -> policy.inspect(publish, context, true)).hasMessage("MEOO_VERIFICATION_REQUIRED");
        }
    }
    @Test void anotherSessionCanPublishExistingVerifiedContentButNotChangedContent() throws Exception {
        var snapshot = policy.inspect(input(".", "static"), context, false);
        var bundle = EvidenceBundle.builder().bundleId("ev-existing").sessionId("original-session").verdict("verified")
                .items(List.of(new EvidenceItem(null, "test", "snapshot", null, Map.of("workspace", root.toString(),
                        "meooSnapshotSha256", snapshot.sha256(), "meooRuntime", "static"))))
                .createdAt(Instant.now().plusSeconds(1)).build();
        when(evidence.findById("ev-existing")).thenReturn(Optional.of(bundle));
        var publish = ToolInput.from(Map.of("path", ".", "runtime", "static", "verification_id", "ev-existing"));
        assertThat(policy.inspect(publish, context, true).sha256()).isEqualTo(snapshot.sha256());
        Files.writeString(root.resolve("index.html"), "<h1>Changed content</h1>");
        assertThatThrownBy(() -> policy.inspect(publish, context, true))
                .hasMessage("MEOO_VERIFICATION_WORKSPACE_MISMATCH");
    }
    @Test void imageChecksScriptsStorageAndIgnoreRules() throws Exception {
        assertThatThrownBy(()->policy.inspect(input(".","image"),context,false)).hasMessage("MEOO_IMAGE_SCRIPTS_REQUIRED");
        Files.createDirectories(root.resolve("scripts"));
        Files.writeString(root.resolve("scripts/setup.sh"),"#!/bin/sh\nnode --version\n");
        Files.writeString(root.resolve("scripts/start.sh"),"#!/bin/sh\nPORT=${PORT:-9000} node server.js\n");
        Files.writeString(root.resolve("server.js"),"console.log('server');");
        Files.writeString(root.resolve("ignored.txt"),"excluded");
        Files.writeString(root.resolve(".dockerignore"),"*.txt\n!.env\n");Files.writeString(root.resolve(".env"),"private");
        assertThat(policy.inspect(input(".","image"),context,false).files()).extracting(MeooPublicationPolicy.FileFact::relativePath).doesNotContain(".env","ignored.txt");
        Files.writeString(root.resolve("server.js"),"import sqlite3 from 'sqlite3';");
        assertThatThrownBy(()->policy.inspect(input(".","image"),context,false)).hasMessage("MEOO_PERSISTENT_STORAGE_ADAPTATION_REQUIRED");
        Files.writeString(root.resolve("server.js"),"console.log(process.env.API_KEY);");
        assertThatThrownBy(()->policy.inspect(input(".","image"),context,false)).hasMessage("MEOO_APP_ENV_REQUIRED");
    }
    @Test void disabledAndCredentialPermissionsAreEnforced() throws Exception {
        props.setEnabled(false);
        assertThatThrownBy(()->policy.inspect(input(".","static"),context,false)).hasMessage("MEOO_DISABLED");
        props.setEnabled(true);Files.setPosixFilePermissions(Path.of(props.getCredentialsFile()),PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(()->policy.inspect(input(".","static"),context,false)).hasMessage("MEOO_CREDENTIAL_PERMISSIONS");
    }
    @Test void dockerignoreSupportsOrderedNegationAndDirectories() {
        DockerIgnore ignore=new DockerIgnore(List.of("*.log","!keep.log","build/","src/**/temp?.txt"));
        assertThat(ignore.ignored("a/debug.log",false)).isTrue();
        assertThat(ignore.ignored("keep.log",false)).isFalse();
        assertThat(ignore.ignored("build/main.js",false)).isTrue();
        assertThat(ignore.ignored("src/a/temp1.txt",false)).isTrue();
        assertThat(ignore.ignored("BUILD/main.js",false)).isTrue();
        assertThat(ignore.ignored("KEEP.LOG",false)).isFalse();
    }
    @Test void rejectsSelfExcludedRulesBeforeStagingAndAllowsExplicitReinclude() throws Exception {
        Files.writeString(root.resolve(".dockerignore"),".dockerignore\n");
        assertThatThrownBy(()->policy.inspect(input(".","image"),context,false)).hasMessage("MEOO_IGNORE_SELF_EXCLUDED");
        Files.createDirectories(root.resolve("scripts"));
        Files.writeString(root.resolve("scripts/setup.sh"),"#!/bin/sh\nnode --version\n");
        Files.writeString(root.resolve("scripts/start.sh"),"#!/bin/sh\nPORT=${PORT:-9000} node build/server.js\n");
        Files.createDirectories(root.resolve("build"));
        Files.writeString(root.resolve("build/server.js"),"console.log('server');");
        Files.writeString(root.resolve(".dockerignore"),".dockerignore\n!/.dockerignore\n");
        var snapshot=policy.inspect(input(".","image"),context,false);
        assertThat(snapshot.files()).extracting(MeooPublicationPolicy.FileFact::relativePath).contains(".dockerignore","build/server.js");
        Path stage=temp.resolve("payload");policy.stage(snapshot,stage);
        assertThat(Files.readString(stage.resolve(".dockerignore"))).isEqualTo(Files.readString(root.resolve(".dockerignore")));
        // Opt-in contract check against the installed, pinned CLI, with no cloud access.
        String cliRoot=System.getProperty("meoo.contract.cliRoot");
        if(cliRoot!=null) {
            String script="""
                const fs=require('fs'),path=require('path'),crypto=require('crypto');
                const [cli,root,zipPath]=process.argv.slice(1);
                if(require(path.join(cli,'package.json')).version!=='0.5.4') throw Error('CLI version');
                const {createDockerignoreMatcher}=require(path.join(cli,'dist/lib/dockerignore.js'));
                const {createZipFromDirectory}=require(path.join(cli,'dist/lib/archive.js'));
                const ignore=createDockerignoreMatcher(fs.readFileSync(path.join(root,'.dockerignore'),'utf8').split(/\\r?\\n/));
                createZipFromDirectory(root,zipPath,(p,d)=>!ignore(p,d));
                const Zip=require(path.join(cli,'node_modules/adm-zip'));
                const entries=new Zip(zipPath).getEntries().filter(e=>!e.isDirectory).map(e=>({
                    path:e.entryName,size:e.getData().length,sha256:crypto.createHash('sha256').update(e.getData()).digest('hex')
                }));
                process.stdout.write(JSON.stringify(entries));
                """;
            Path result=temp.resolve("archive-manifest.json");
            Process process=new ProcessBuilder("node","-e",script,cliRoot,stage.toString(),temp.resolve("source.zip").toString())
                .redirectOutput(result.toFile()).redirectError(temp.resolve("archive-error.log").toFile()).start();
            try {
                assertThat(process.waitFor(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(process.exitValue()).isZero();
            } finally { if(process.isAlive()) process.destroyForcibly(); }
            var actual=new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(result));
            assertThat(actual.size()).isEqualTo(snapshot.files().size());
            for(var file:snapshot.files()) {
                var entry=java.util.stream.StreamSupport.stream(actual.spliterator(),false)
                    .filter(e->e.path("path").asText().equals(file.relativePath())).findFirst().orElseThrow();
                assertThat(entry.path("size").asLong()).isEqualTo(file.size());
                assertThat(entry.path("sha256").asText()).isEqualTo(file.sha256());
            }
        }
    }
}
