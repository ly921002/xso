package com.example.essentialsx.common;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 外部进程模式（NATIVE_MODE=process）。
 * <p>
 * JNA 模式最隐蔽但要求自己编译 c-shared 版 xray；若你只想用官方 xray 可执行文件，
 * 用这个模式即可，进程会以独立进程出现（可通过 RUN_AS 改名降低显眼度）。
 */
public final class ProcessRunner {

    private final String executable;
    private final List<String> arguments;
    private final String name;
    private final Path workingDir;
    private Process process;

    public ProcessRunner(String executable, List<String> arguments, String name, Path workingDir) {
        this.executable = executable;
        this.arguments = arguments;
        this.name = name;
        this.workingDir = workingDir;
    }

    public synchronized void start(java.util.function.Consumer<String> log) {
        if (process != null && process.isAlive()) {
            return;
        }
        Path bin = Path.of(executable);
        if (!Files.isRegularFile(bin)) {
            throw new IllegalStateException(name + " executable not found: " + bin.toAbsolutePath());
        }
        List<String> command = new ArrayList<>();
        command.add(bin.toAbsolutePath().toString());
        command.addAll(arguments);

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            if (workingDir != null) {
                pb.directory(workingDir.toFile());
            }
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            process = pb.start();
            log.accept(name + " started as pid " + process.pid());
        } catch (IOException e) {
            throw new IllegalStateException("failed to start " + name + ": " + e.getMessage(), e);
        }
    }

    public synchronized void stop() {
        if (process == null) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        process = null;
    }

    public boolean isAlive() {
        return process != null && process.isAlive();
    }
}
