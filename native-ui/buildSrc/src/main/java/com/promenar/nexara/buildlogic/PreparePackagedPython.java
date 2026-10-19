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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 把官方 CPython Android 嵌入包整理成可放进 jniLibs 的打包库：
 * libpython 与依赖库原样保留，标准库纯 Python 部分存为不压缩 zip（libpystdlib.so），
 * 原生扩展改名为 libpyext_<模块>.so，并用 NDK clang 编译可执行启动器 libnxpython.so。
 * 启用预编译时标准库 zip 只放哈希 pyc（无法编译的文件保留源码），见 {@link PythonBytecode}。
 * 安装后全部位于 nativeLibraryDir，运行时不解压、不从数据目录加载代码。
 */
public abstract class PreparePackagedPython extends DefaultTask {
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

    /** 是否预编译标准库字节码。 */
    @Input
    public abstract Property<Boolean> getCompileBytecode();

    /** 官方包的 PYC_MAGIC_NUMBER；与包内头文件不一致时构建失败。 */
    @Input
    public abstract Property<Integer> getBytecodeMagic();

    /** 宿主解释器（命令名或路径）；产物只取决于 magic，路径不参与增量判断。 */
    @Internal
    public abstract Property<String> getHostPython();

    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getBytecodeCompiler();

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
        PythonBytecode.verifyArchiveMagic(prefix, version, getBytecodeMagic().get());
        if (Files.exists(stdlib.resolve("sitecustomize.py"))) {
            throw new GradleException("官方标准库已包含 sitecustomize.py，需评估后再覆盖");
        }
        Files.copy(getSiteCustomize().get().getAsFile().toPath(), stdlib.resolve("sitecustomize.py"));
        boolean compiled = getCompileBytecode().get();
        if (compiled) {
            getLogger().info(PythonBytecode.compileTree(getExecOperations(), getHostPython().get(),
                    getBytecodeCompiler().get().getAsFile(), getBytecodeMagic().get(), stdlib));
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
        writeStdlibZip(stdlib, compiled, abiDir.resolve("libpystdlib.so"));
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
     * 标准库存为 STORED zip（见 {@link StoredZip}）；条目按路径排序，目录条目保留，供命名空间包识别。
     * [sourceless] 时已有同目录 pyc 的 .py 不入 zip。
     */
    private static void writeStdlibZip(Path stdlib, boolean sourceless, Path target) throws IOException {
        List<Path> entries = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(stdlib)) {
            walk.filter(path -> !path.equals(stdlib))
                    .filter(path -> {
                        String top = stdlib.relativize(path).getName(0).toString();
                        return !top.equals("lib-dynload") && !top.equals("site-packages");
                    })
                    .filter(path -> !sourceless || !path.toString().endsWith(".py")
                            || !Files.isRegularFile(path.resolveSibling(path.getFileName() + "c")))
                    .forEach(entries::add);
        }
        entries.sort((left, right) -> entryName(stdlib, left).compareTo(entryName(stdlib, right)));
        try (StoredZip zip = new StoredZip(target)) {
            for (Path path : entries) {
                if (Files.isDirectory(path)) {
                    zip.directory(entryName(stdlib, path));
                } else {
                    zip.put(entryName(stdlib, path), Files.readAllBytes(path));
                }
            }
        }
    }

    private static String entryName(Path root, Path path) {
        return root.relativize(path).toString().replace(File.separatorChar, '/');
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
