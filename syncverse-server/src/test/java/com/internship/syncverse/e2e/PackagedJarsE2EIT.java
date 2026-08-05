package com.internship.syncverse.e2e;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackagedJarsE2EIT {

    private static final Pattern STARTED_PORT = Pattern.compile("Tomcat started on port (\\d+)");

    @TempDir
    Path temporaryDirectory;

    @Test
    void packagedServerAndTwoClientsConvergeAsSeparateProcesses() throws Exception {
        Path serverJar = artifact("syncverse-server", "server.jar");
        Path clientJar = artifact("syncverse-client", "client.jar");
        assertTrue(Files.isRegularFile(serverJar), () -> "Missing " + serverJar);
        assertTrue(Files.isRegularFile(clientJar), () -> "Missing " + clientJar);

        Path serverLog = temporaryDirectory.resolve("server.log");
        Path aliceLog = temporaryDirectory.resolve("alice.log");
        Path bobLog = temporaryDirectory.resolve("bob.log");
        Path data = Files.createDirectory(temporaryDirectory.resolve("data"));
        Path aliceWorkspace = Files.createDirectory(temporaryDirectory.resolve("alice"));
        Path bobWorkspace = Files.createDirectory(temporaryDirectory.resolve("bob"));

        Process server = start(
                serverLog,
                Map.of("SERVER_PORT", "0",
                        "SYNCVERSE_DATA_DIR", data.toString()),
                serverJar.toString(), "AlphaServer");
        Process alice = null;
        Process bob = null;
        try {
            await(Duration.ofSeconds(15), () -> serverPort(serverLog) > 0,
                    () -> readLog(serverLog));
            int port = serverPort(serverLog);
            await(Duration.ofSeconds(10), () -> serverReady(port), () -> readLog(serverLog));
            Map<String, String> clientEnvironment = Map.of(
                    "SYNCVERSE_SERVER_URL", "http://localhost:" + port);
            alice = start(aliceLog, clientEnvironment,
                    clientJar.toString(), "Alice_Node", aliceWorkspace.toString());
            bob = start(bobLog, clientEnvironment,
                    clientJar.toString(), "Bob_Node", bobWorkspace.toString());
            await(Duration.ofSeconds(10), () ->
                    logContains(aliceLog, "SyncVerse client Alice_Node started")
                            && logContains(bobLog, "SyncVerse client Bob_Node started"),
                    () -> readLog(aliceLog) + System.lineSeparator() + readLog(bobLog));

            Path aliceFile = aliceWorkspace.resolve("demo.txt");
            Path bobFile = bobWorkspace.resolve("demo.txt");
            Files.writeString(aliceFile, "created");
            long createMillis = await(Duration.ofSeconds(10), () ->
                    Files.exists(bobFile) && Files.readString(bobFile).equals("created"),
                    () -> readLog(serverLog) + System.lineSeparator() + readLog(bobLog));

            Files.writeString(aliceFile, "updated");
            long updateMillis = await(
                    Duration.ofSeconds(10), () -> Files.readString(bobFile).equals("updated"),
                    () -> readLog(serverLog) + System.lineSeparator() + readLog(bobLog));

            Files.delete(aliceFile);
            long deleteMillis = await(
                    Duration.ofSeconds(10), () -> Files.notExists(bobFile),
                    () -> readLog(serverLog) + System.lineSeparator() + readLog(bobLog));

            System.out.printf(
                    "PACKAGED_SMOKE_METRICS createMs=%d updateMs=%d deleteMs=%d%n",
                    createMillis, updateMillis, deleteMillis);

            assertTrue(server.isAlive(), () -> readLog(serverLog));
            assertTrue(alice.isAlive(), () -> readLog(aliceLog));
            assertTrue(bob.isAlive(), () -> readLog(bobLog));
        } finally {
            stop(bob, bobLog);
            stop(alice, aliceLog);
            stop(server, serverLog);
        }

        assertFalse(readLog(serverLog).contains("contentBase64"));
    }

    private static Process start(
            Path log, Map<String, String> environment, String jar, String... arguments)
            throws Exception {
        String[] command = new String[3 + arguments.length];
        command[0] = javaExecutable();
        command[1] = "-jar";
        command[2] = jar;
        System.arraycopy(arguments, 0, command, 3, arguments.length);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().putAll(environment);
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        return builder.start();
    }

    private static String javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe"
                : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    private static Path artifact(String module, String filename) {
        Path cwd = Path.of("").toAbsolutePath();
        Path fromRoot = cwd.resolve(module).resolve("target").resolve(filename);
        if (Files.exists(fromRoot)) {
            return fromRoot;
        }
        if (cwd.getFileName().toString().equals(module)) {
            return cwd.resolve("target").resolve(filename);
        }
        return cwd.resolveSibling(module).resolve("target").resolve(filename);
    }

    private static int serverPort(Path log) {
        Matcher matcher = STARTED_PORT.matcher(readLog(log));
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return 0;
    }

    private static boolean serverReady(int port) {
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/api/register"))
                    .timeout(Duration.ofMillis(500))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"messageType\":\"HELLO\",\"clientName\":\"SmokeProbe\"}"))
                    .build();
            return HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.discarding())
                    .statusCode() == 201;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean logContains(Path log, String text) throws Exception {
        return Files.exists(log) && Files.readString(log).contains(text);
    }

    private static String readLog(Path log) {
        try {
            return Files.exists(log) ? Files.readString(log) : "Log does not exist: " + log;
        } catch (Exception exception) {
            return "Cannot read log " + log + ": " + exception.getMessage();
        }
    }

    private static void stop(Process process, Path log) throws Exception {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        if (process.isAlive()) {
            throw new AssertionError("Process did not terminate:" + System.lineSeparator()
                    + readLog(log));
        }
    }

    private static long await(
            Duration timeout, CheckedCondition condition, Supplier<String> diagnostic)
            throws Exception {
        long started = System.nanoTime();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.evaluate()) {
                return Duration.ofNanos(System.nanoTime() - started).toMillis();
            }
            Thread.sleep(50);
        }
        assertTrue(condition.evaluate(),
                () -> "Condition did not converge before " + timeout
                        + System.lineSeparator() + diagnostic.get());
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean evaluate() throws Exception;
    }
}
