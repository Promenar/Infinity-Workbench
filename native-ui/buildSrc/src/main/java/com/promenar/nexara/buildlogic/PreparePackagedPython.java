package com.promenar.nexara.buildlogic;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ArchiveOperations;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 把官方 CPython Android 嵌入包整理成可放进 jniLibs 的打包库：
 * libpython 与依赖库原样保留，标准库纯 Python 部分存为不压缩 zip（libpystdlib.so），
 * 原生扩展改名为 libpyext_<模块>.so，并用 NDK clang 编译可执行启动器 libnxpython.so。
 * 安装后全部位于 nativeLibraryDir，运行时不解压、不从数据目录加载代码。
 */
public abstract class PreparePackagedPython extends DefaultTask {
    /** zip 条目使用固定本地时间，保证同一输入在任意时区得到相同字节。 */
    private static final LocalDateTime ZIP_TIME = LocalDateTime.of(1980, 2, 1, 0, 0);

    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getArchive();

    @Input
    public abstract Property<String> getAbi();

    /** clang 目标三元组，例如 aarch64-linux-android31。 */
    @Input
    public abstract Property<String> getClangTarget();

    /** 主次版本，例如 3.14。 */
    @Input
    public abstract Property<String> getPythonMajorMinor();

    /** 不进入标准库 zip 的顶层目录。 */
    @Input
    public abstract ListProperty<String> getExcludedStdlibDirectories();

    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getLauncherSource();

    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getSiteCustomize();

    /** NDK 目录随机器不同，只以版本号参与增量判断。 */
    @Internal
    public abstract DirectoryProperty getNdkDirectory();

    @Input
    public String getNdkRevision() throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(getNdkDirectory().get().file("source.properties").getAsFile().toPath())) {
            properties.load(input);
        }
        return properties.getProperty("Pkg.Revision", "unknown");
    }

    /** 输出根目录，内含 <abi>/ 子目录，可直接作为 jniLibs 源目录。 */
    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    @Inject protected abstract ArchiveOperations getArchiveOperations();
    @Inject protected abstract FileSystemOperations getFileSystemOperations();
    @Inject protected abstract ExecOperations getExecOperations();

    public PreparePackagedPython() {
        setGroup("build setup");
        setDescription("把官方 CPython Android 包整理为 jniLibs 打包库并编译启动器。");
    }

    @TaskAction
    public void prepare() throws IOException {
        String version = getPythonMajorMinor().get();
        String stdlibName = "python" + version;
        File work = new File(getTemporaryDir(), "prefix");
        getFileSystemOperations().delete(spec -> spec.delete(work));
        List<String> excluded = getExcludedStdlibDirectories().get();
        getFileSystemOperations().copy(spec -> {
            spec.from(getArchiveOperations().tarTree(getArchiveOperations().gzip(getArchive().get().getAsFile())));
            spec.include("prefix/include/**", "prefix/lib/libpython" + version + ".so", "prefix/lib/lib*_python.so",
                    "prefix/lib/" + stdlibName + "/**");
            for (String directory : excluded) spec.exclude("prefix/lib/" + stdlibName + "/" + directory + "/**");
            spec.exclude("**/__pycache__/**");
            spec.into(work.getParentFile());
        });
        Path prefix = work.toPath();
        Path lib = prefix.resolve("lib");
        Path stdlib = lib.resolve(stdlibName);
        if (!Files.isDirectory(stdlib.resolve("encodings"))) {
            throw new GradleException("Python 包结构不符：缺少 lib/" + stdlibName + "/encodings");
        }

        File output = getOutputDirectory().get().getAsFile();
        getFileSystemOperations().delete(spec -> spec.delete(output));
        Path abiDir = output.toPath().resolve(getAbi().get());
        Files.createDirectories(abiDir);

        try (Stream<Path> libraries = Files.list(lib)) {
            for (Path library : libraries.filter(Files::isRegularFile).collect(Collectors.toList())) {
                Files.copy(library, abiDir.resolve(library.getFileName()));
            }
        }
        copyExtensions(stdlib.resolve("lib-dynload"), abiDir);
        writeStdlibZip(stdlib, getSiteCustomize().get().getAsFile().toPath(), abiDir.resolve("libpystdlib.so"));
        compileLauncher(prefix, abiDir);
        getFileSystemOperations().delete(spec -> spec.delete(work));
    }

    /** 扩展模块按模块名改名，由 sitecustomize 中的 meta_path finder 加载。 */
    private static void copyExtensions(Path dynload, Path abiDir) throws IOException {
        Set<String> modules = new HashSet<>();
        try (Stream<Path> files = Files.list(dynload)) {
            for (Path extension : files.filter(path -> path.getFileName().toString().endsWith(".so")).sorted().collect(Collectors.toList())) {
                String name = extension.getFileName().toString();
                String module = name.substring(0, name.indexOf('.'));
                if (!modules.add(module)) throw new GradleException("扩展模块重名：" + module);
                Files.copy(extension, abiDir.resolve("libpyext_" + module + ".so"));
            }
        }
        if (modules.isEmpty()) throw new GradleException("Python 包结构不符：lib-dynload 中没有扩展模块");
    }

    /**
     * 标准库存为 STORED zip：zlib 本身是扩展模块，解释器启动早期尚不可用，压缩条目无法读取。
     * 条目按路径排序并使用固定时间；目录条目保留，供命名空间包识别。
     */
    private static void writeStdlibZip(Path stdlib, Path siteCustomize, Path target) throws IOException {
        List<Path> entries = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(stdlib)) {
            walk.filter(path -> !path.equals(stdlib))
                    .filter(path -> {
                        String top = stdlib.relativize(path).getName(0).toString();
                        return !top.equals("lib-dynload") && !top.equals("site-packages");
                    })
                    .forEach(entries::add);
        }
        entries.sort((left, right) -> entryName(stdlib, left).compareTo(entryName(stdlib, right)));
        if (Files.exists(stdlib.resolve("sitecustomize.py"))) {
            throw new GradleException("官方标准库已包含 sitecustomize.py，需评估后再覆盖");
        }
        try (OutputStream file = Files.newOutputStream(target); ZipOutputStream zip = new ZipOutputStream(file, StandardCharsets.UTF_8)) {
            zip.setMethod(ZipOutputStream.STORED);
            for (Path path : entries) {
                if (Files.isDirectory(path)) {
                    putStored(zip, entryName(stdlib, path) + "/", new byte[0]);
                } else {
                    putStored(zip, entryName(stdlib, path), Files.readAllBytes(path));
                }
            }
            putStored(zip, "sitecustomize.py", Files.readAllBytes(siteCustomize));
        }
    }

    private static String entryName(Path root, Path path) {
        return root.relativize(path).toString().replace(File.separatorChar, '/');
    }

    private static void putStored(ZipOutputStream zip, String name, byte[] content) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(content);
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(content.length);
        entry.setCompressedSize(content.length);
        entry.setCrc(crc.getValue());
        entry.setTimeLocal(ZIP_TIME);
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }

    private void compileLauncher(Path prefix, Path abiDir) {
        File clang = clang(getNdkDirectory().get().getAsFile());
        String version = getPythonMajorMinor().get();
        getExecOperations().exec(spec -> {
            spec.setExecutable(clang);
            spec.args("--target=" + getClangTarget().get(), "-O2", "-fPIE", "-pie",
                    "-I" + prefix.resolve("include").resolve("python" + version),
                    "-L" + abiDir, "-lpython" + version,
                    "-Wl,-rpath,$ORIGIN", "-Wl,-z,max-page-size=16384",
                    "-o", abiDir.resolve("libnxpython.so").toString(),
                    getLauncherSource().get().getAsFile().getPath());
        });
    }

    /** NDK 只附带当前主机的一套预编译工具链。 */
    private static File clang(File ndk) {
        File[] hosts = new File(ndk, "toolchains/llvm/prebuilt").listFiles(File::isDirectory);
        if (hosts == null || hosts.length != 1) throw new GradleException("无法确定 NDK 主机工具链目录：" + ndk);
        File bin = new File(hosts[0], "bin");
        File clang = new File(bin, "clang");
        if (!clang.isFile()) clang = new File(bin, "clang.exe");
        if (!clang.isFile()) throw new GradleException("NDK 中缺少 clang：" + bin);
        return clang;
    }
}
