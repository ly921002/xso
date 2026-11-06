package com.example.essentialsx.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * 隐私文件权限。
 * <p>
 * 这些文件里放的是 REALITY 私钥、VLESS 加密串、UUID ——
 * 在共享主机（免费面板、多用户 VPS）上，默认 umask 下它们是 644，
 * 同机器的其他用户可以直接读走。写完后立即降到 600。
 * <p>
 * 对不支持 POSIX 权限的平台（Windows）静默跳过，不影响主流程。
 */
final class SecureFiles {

    private static final Set<PosixFilePermission> OWNER_ONLY =
            PosixFilePermissions.fromString("rw-------");

    private SecureFiles() {
    }

    /** 将已存在的文件收紧为仅所有者可读写。失败不抛异常。 */
    static void lockDown(Path file) {
        try {
            if (file != null && Files.exists(file)) {
                Files.setPosixFilePermissions(file, OWNER_ONLY);
            }
        } catch (UnsupportedOperationException | IOException ignored) {
            // 非 POSIX 文件系统：无权限概念，无需处理
        }
    }

    /** 写入文件并立即收紧权限。 */
    static void writePrivate(Path file, String content) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, content, java.nio.charset.StandardCharsets.UTF_8);
        lockDown(file);
    }
}
