package com.mcoo.guard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 源码扫描工具：把 {@code .java} 文件里的 {@code package} 和 {@code import} 抽出来。
 *
 * <p><b>刻意不用任何 Java 解析库</b>，也不用正则去"理解"代码 ——
 * 只做行级的 package/import 抽取。理由：
 * <ul>
 *   <li>安全闸必须在"依赖解析不了"时也能跑（Gradle 配置坏了、编译环境有问题），
 *       而基于 AST 的方案需要先编译，先编译就意味着闸门要等编译通过才生效。</li>
 *   <li>引入解析库会带来它自己的一堆传递依赖，与"依赖越少越好"冲突。</li>
 *   <li>行级抽取对 {@code import} 这种一行一条的语法已经够用，
 *       真正会骗过它的是把 import 拆行写，而那样 Java 编译器也不接受。</li>
 * </ul>
 *
 * <p>唯一的已知盲区：字符串字面量里出现 {@code "import com.mcoo.feature.x;"}
 * 会被误判。实际代码里不会出现这种写法，测试样本也不会。
 */
final class SourceIndex {

    /** 一个被扫描到的 Java 文件。 */
    record SourceFile(Path path, String relativePath, String packageName, List<String> imports) {

        /** 相对路径里去掉包名部分，便于人读懂报错。 */
        String displayName() {
            return relativePath.replace('\\', '/');
        }
    }

    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;");

    /**
     * import 匹配。允许静态 import（{@code static}）和结尾的 {@code .*} 通配。
     * 只认行首，避免匹配到行尾注释或字符串里的内容。
     */
    private static final Pattern IMPORT_PATTERN =
            Pattern.compile("^\\s*import\\s+(static\\s+)?([\\w.*]+)\\s*;");

    /** 本项目自身的根包。 */
    static final String PROJECT_ROOT = "com.mcoo";

    /** 功能包前缀。 */
    static final String FEATURE_ROOT = "com.mcoo.feature";

    /** core 包前缀。 */
    static final String CORE_ROOT = "com.mcoo.core";

    private SourceIndex() {
    }

    /** 递归扫描目录下所有 {@code .java} 文件。 */
    static List<SourceFile> scan(Path root) throws IOException {
        if (root == null || !Files.isDirectory(root)) {
            throw new IOException("要扫描的目录不存在: " + root);
        }
        List<SourceFile> result = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
            for (Path file : files) {
                result.add(read(file, root));
            }
        }
        return result;
    }

    private static SourceFile read(Path file, Path root) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

        String packageName = "";
        List<String> imports = new ArrayList<>();

        for (String line : lines) {
            Matcher pkg = PACKAGE_PATTERN.matcher(line);
            if (pkg.find()) {
                packageName = pkg.group(1);
                continue;
            }
            Matcher imp = IMPORT_PATTERN.matcher(line);
            if (imp.find()) {
                imports.add(imp.group(2));
            }
        }

        String relative = root.relativize(file).toString();
        return new SourceFile(file, relative, packageName, imports);
    }

    /**
     * 判断某个包属于哪个功能（属于 {@code com.mcoo.feature.<名字>} 时返回名字，否则返回 null）。
     *
     * <p>只匹配两层：{@code com.mcoo.feature.gift} 和更深的一层子包都算 gift。
     */
    static String featureIdOf(String packageName) {
        if (packageName == null || !packageName.startsWith(FEATURE_ROOT + ".")) {
            return null;
        }
        String rest = packageName.substring(FEATURE_ROOT.length() + 1);
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }

    static boolean isCore(String packageName) {
        return packageName != null
                && (packageName.equals(CORE_ROOT) || packageName.startsWith(CORE_ROOT + "."));
    }

    /**
     * 判断被 import 的包 {@code imported} 是否落在 {@code prefix} 之下。
     *
     * <p>同时处理通配 import：{@code com.mcoo.feature.*} 会被判为落在
     * {@code com.mcoo.feature} 之下（这是对的 —— 通配本来就意味着全都要）。
     */
    static boolean isUnder(String imported, String prefix) {
        if (imported == null || prefix == null) {
            return false;
        }
        if (imported.equals(prefix) || imported.startsWith(prefix + ".")) {
            return true;
        }
        // prefix 本身带通配，例如 "net.minecraft.world.level.saveddata.*"
        if (prefix.endsWith(".*")) {
            String bare = prefix.substring(0, prefix.length() - 2);
            return imported.equals(bare) || imported.startsWith(bare + ".");
        }
        return false;
    }
}