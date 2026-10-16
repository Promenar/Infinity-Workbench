package com.promenar.nexara.buildlogic;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** 仅使用 Gradle 与 JDK，从纯矢量母版生成各变体的应用图标。 */
public abstract class GenerateLauncherIcons extends DefaultTask {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String SVG = "http://www.w3.org/2000/svg";
    private static final String HEADER = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n";
    private static final Set<String> RESERVED = Set.of("ic_launcher", "ic_launcher_round",
            "ic_launcher_foreground", "ic_launcher_background", "ic_launcher_monochrome",
            "nexara_launcher_foreground", "nexara_launcher_background");

    @InputFile @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getMasterSvg();

    @InputFiles @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSourceRoots();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    public GenerateLauncherIcons() {
        setGroup("build setup");
        setDescription("从折叠 N 母版生成日夜、自适应和单色主题应用图标。");
        // 每次检查来源目录，避免新增覆盖被增量缓存掩盖。
        getOutputs().upToDateWhen(task -> false);
    }

    @TaskAction
    public void generate() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        for (File source : getSourceRoots()) {
            if (!source.exists()) continue;
            List<Path> files;
            try (Stream<Path> walk = Files.walk(source.toPath())) {
                files = walk.filter(Files::isRegularFile).collect(Collectors.toList());
            }
            for (Path file : files) validateSource(factory, file);
        }

        Element root = factory.newDocumentBuilder().parse(getMasterSvg().get().getAsFile()).getDocumentElement();
        require("svg".equals(root.getTagName()) && SVG.equals(root.getNamespaceURI()) &&
                "0 0 108 108".equals(root.getAttribute("viewBox")), "母版必须使用 108 × 108 SVG 画布");
        attributes(root, Set.of("xmlns", "width", "height", "viewBox"));
        Element logo = null;
        for (Element child : children(root)) {
            if ("g".equals(child.getTagName())) {
                require(logo == null && "brand-logo".equals(child.getAttribute("id")), "母版必须包含唯一 brand-logo 分组");
                attributes(child, Set.of("id", "fill"));
                require("#000000".equals(child.getAttribute("fill")), "母版标志必须为纯黑");
                logo = child;
            } else {
                require(("title".equals(child.getTagName()) || "desc".equals(child.getTagName())) &&
                        children(child).isEmpty(), "母版仅允许根级说明与直接路径，禁止嵌套视口或隐藏路径");
                attributes(child, Set.of());
            }
        }
        require(logo != null, "母版必须包含唯一 brand-logo 分组");
        List<Element> pathElements = children(logo);
        require(pathElements.size() == 3, "折叠 N 母版必须包含三个折面");
        List<String> paths = new ArrayList<>();
        for (Element path : pathElements) {
            require("path".equals(path.getTagName()) && SVG.equals(path.getNamespaceURI()) && children(path).isEmpty(),
                    "母版仅允许分组中的三个直接路径，禁止嵌套视口");
            attributes(path, Set.of("id", "d", "fill"));
            String data = path.getAttribute("d");
            require(data.matches("[MLCQZ0-9 .,+\\-]+") && data.trim().endsWith("Z") &&
                    (!path.hasAttribute("fill") || "#000000".equals(path.getAttribute("fill"))), "母版仅允许纯黑闭合贝塞尔路径");
            paths.add(data);
        }

        Map<String, String> files = new LinkedHashMap<>();
        files.put("drawable/ic_launcher_foreground.xml", vector(paths, "@color/nexara_launcher_foreground", false));
        files.put("drawable/ic_launcher_monochrome.xml", vector(paths, "#000000", false));
        files.put("drawable/ic_launcher_background.xml", HEADER + "<shape xmlns:android=\"" + ANDROID +
                "\" android:shape=\"rectangle\"><solid android:color=\"@color/nexara_launcher_background\" /></shape>\n");
        files.put("values/launcher_icon_colors.xml", colors("#FFFFFF", "#000000"));
        files.put("values-night/launcher_icon_colors.xml", colors("#000000", "#FFFFFF"));
        for (String name : List.of("ic_launcher", "ic_launcher_round")) {
            files.put("mipmap-anydpi/" + name + ".xml", vector(paths, "@color/nexara_launcher_foreground", true));
            for (int api : List.of(26, 33)) {
                files.put("mipmap-anydpi-v" + api + "/" + name + ".xml", HEADER +
                        "<adaptive-icon xmlns:android=\"" + ANDROID + "\">\n" +
                        "    <background android:drawable=\"@drawable/ic_launcher_background\" />\n" +
                        "    <foreground android:drawable=\"@drawable/ic_launcher_foreground\" />\n" +
                        (api >= 33 ? "    <monochrome android:drawable=\"@drawable/ic_launcher_monochrome\" />\n" : "") +
                        "</adaptive-icon>\n");
            }
        }
        Path output = getOutputDirectory().get().getAsFile().toPath();
        Files.createDirectories(output);
        try (Stream<Path> walk = Files.walk(output)) {
            for (Path file : walk.filter(Files::isRegularFile).collect(Collectors.toList())) {
                if (!files.containsKey(output.relativize(file).toString().replace(File.separatorChar, '/'))) Files.delete(file);
            }
        }
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path target = output.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue(), StandardCharsets.UTF_8);
        }
    }

    private static void validateSource(DocumentBuilderFactory factory, Path file) throws Exception {
        String name = file.getFileName().toString();
        require(!(name.startsWith("ic_launcher") || name.equals("launcher_icon_colors.xml")),
                "图标资源必须由母版生成，请移除来源目录中的覆盖：" + name);
        if (name.endsWith(".xml") && file.getParent().getFileName().toString().startsWith("values")) {
            Element values = factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
            for (Element value : children(values)) require(!RESERVED.contains(value.getAttribute("name")),
                    "图标资源必须由母版生成，请移除 values 中的覆盖：" + name);
        }
        if (!name.equals("AndroidManifest.xml")) return;
        Element manifest = factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
        for (String tag : List.of("application", "activity", "activity-alias")) {
            var nodes = manifest.getElementsByTagName(tag);
            for (int i = 0; i < nodes.getLength(); i++) {
                Element element = (Element) nodes.item(i);
                for (String attr : List.of("icon", "roundIcon")) {
                    String expected = "@mipmap/" + (attr.equals("icon") ? "ic_launcher" : "ic_launcher_round");
                    require(!element.hasAttributeNS(ANDROID, attr) || element.getAttributeNS(ANDROID, attr).equals(expected),
                            "Manifest 图标引用必须统一：" + tag + " 的 " + attr + " 应为 " + expected);
                }
            }
        }
    }

    private static void attributes(Element element, Set<String> allowed) {
        for (int i = 0; i < element.getAttributes().getLength(); i++) {
            require(allowed.contains(element.getAttributes().item(i).getNodeName()), "母版仅允许无变换、无位图的纯色路径");
        }
    }

    private static List<Element> children(Element parent) {
        List<Element> result = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element) result.add((Element) child);
        }
        return result;
    }

    private static String vector(List<String> paths, String fill, boolean background) {
        StringBuilder xml = new StringBuilder(HEADER).append("<vector xmlns:android=\"").append(ANDROID)
                .append("\" android:width=\"108dp\" android:height=\"108dp\" android:viewportWidth=\"108\" android:viewportHeight=\"108\">\n");
        if (background) xml.append("    <path android:fillColor=\"@color/nexara_launcher_background\" android:pathData=\"M0 0 H108 V108 H0 Z\" />\n");
        for (String path : paths) xml.append("    <path android:fillColor=\"").append(fill).append("\" android:pathData=\"").append(path).append("\" />\n");
        return xml.append("</vector>\n").toString();
    }

    private static String colors(String background, String foreground) {
        return HEADER + "<resources><color name=\"nexara_launcher_background\">" + background +
                "</color><color name=\"nexara_launcher_foreground\">" + foreground + "</color></resources>\n";
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
