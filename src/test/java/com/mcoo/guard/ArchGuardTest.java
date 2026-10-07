package com.mcoo.guard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 架构安全闸：靠扫描 import 强制三条边界规则。
 *
 * <p><b>规则（违反 = 构建失败）</b>
 * <ol>
 *   <li><b>{@code core} 不得 import {@code feature}</b>。<br>
 *       core 是地基。一旦它认识某个具体功能，就再也删不掉那个功能了。</li>
 *   <li><b>{@code feature/X} 不得 import {@code feature/Y}（X≠Y）</b>。<br>
 *       功能之间必须互不认识，否则删掉 Y 就会连带 X 编译失败，
 *       "删一个功能目录其余照常"这个验收标准直接作废。</li>
 *   <li><b>{@code feature} 不得直接 import 存档包</b><br>
 *       （{@code net.minecraft.world.level.saveddata.**}）。<br>
 *       存档是"坏档"唯一的入口，必须经 {@code core} 的 McooState。
 *       这里刻意<b>不</b>禁止 feature 引用 ItemStack 之类的普通数据类型 ——
 *       送礼功能天生要处理 ItemStack，禁了就是自欺欺人。</li>
 * </ol>
 *
 * <p><b>本测试最重要的部分是 {@code src/test/resources/guard-fixtures/} 里
 * 那批故意违规的样本。</b>没有它们，一个永远绿的守卫等于没有守卫：
 * 你无法区分"真的没违规"和"根本没在检查"。所以下面每个
 * "能抓到 XXX" 的测试，都必须对着样本跑通才算数。
 */
class ArchGuardTest {

    /** 直接 import 存档类会让功能绕开 McooState，是坏档风险。 */
    private static final String SAVED_DATA_ROOT = "net.minecraft.world.level.saveddata";

    private static Path mainSourceDir() {
        String dir = System.getProperty("mcoo.mainSrcDir");
        if (dir == null) {
            fail("系统属性 mcoo.mainSrcDir 未设置 —— build.gradle 的 test 任务里配了吗？");
        }
        return Paths.get(dir);
    }

    private static Path fixtureDir() {
        String dir = System.getProperty("mcoo.fixtureDir");
        if (dir == null) {
            fail("系统属性 mcoo.fixtureDir 未设置 —— build.gradle 的 test 任务里配了吗？");
        }
        return Paths.get(dir);
    }

    private static List<String> violationsIn(Path root) throws IOException {
        List<String> violations = new ArrayList<>();
        for (SourceIndex.SourceFile file : SourceIndex.scan(root)) {
            String ownerFeature = SourceIndex.featureIdOf(file.packageName());
            boolean isCore = SourceIndex.isCore(file.packageName());

            for (String imported : file.imports()) {
                String problem = check(isCore, ownerFeature, imported);
                if (problem != null) {
                    violations.add(file.displayName() + " -> " + imported + " ： " + problem);
                }
            }
        }
        return violations;
    }

    /** 单条规则的判定。返回 null 表示没问题。 */
    private static String check(boolean isCore, String ownerFeature, String imported) {
        // 规则 1：core 不得依赖 feature
        if (isCore && SourceIndex.isUnder(imported, SourceIndex.FEATURE_ROOT)) {
            return "规则1 违规：core 不得 import feature";
        }

        // 规则 2：功能之间不得互相依赖
        if (ownerFeature != null && SourceIndex.isUnder(imported, SourceIndex.FEATURE_ROOT)) {
            String importedFeature = SourceIndex.featureIdOf(imported);
            if (importedFeature == null) {
                // import 的是 com.mcoo.feature 包本身（比如通配 import 到根包）
                return "规则2 违规：功能之间不得互相 import";
            }
            if (!importedFeature.equals(ownerFeature)) {
                return "规则2 违规：功能 " + ownerFeature + " 不得 import 功能 " + importedFeature;
            }
        }

        // 规则 3：功能不得直接碰存档
        if (ownerFeature != null && SourceIndex.isUnder(imported, SAVED_DATA_ROOT)) {
            return "规则3 违规：功能不得直接 import 存档包，存档只能走 core 的 McooState";
        }

        return null;
    }

    // ================================================================
    //  正向：真实源码必须干净
    // ================================================================

    @Test
    @DisplayName("真实源码零违规")
    void 真实源码零违规() throws IOException {
        List<String> violations = violationsIn(mainSourceDir());
        if (!violations.isEmpty()) {
            fail("src/main/java 存在 " + violations.size() + " 处架构违规：\n  "
                    + String.join("\n  ", violations));
        }
    }

    // ================================================================
    //  反向：必须能抓到违规（用故意违规的样本证明守卫不是摆设）
    // ================================================================

    @Test
    @DisplayName("能抓到：core import feature")
    void 能抓到_core导入_feature() throws IOException {
        assertFixtureCaught("规则1 违规：core 不得 import feature");
    }

    @Test
    @DisplayName("能抓到：功能之间互相 import")
    void 能抓到_功能之间互相_import() throws IOException {
        assertFixtureCaught("规则2 违规");
    }

    @Test
    @DisplayName("能抓到：功能直接 import 存档包")
    void 能抓到_功能直接_import_存档包() throws IOException {
        assertFixtureCaught("规则3 违规");
    }

    @Test
    @DisplayName("能抓到：通配符 import 也不能绕过规则")
    void 能抓到_通配符_import() throws IOException {
        assertFixtureCaught("规则2 违规");
    }

    /** 要求样本目录里至少被抓到一条含 {@code expectedFragment} 的违规。 */
    private void assertFixtureCaught(String expectedFragment) throws IOException {
        Path fixtures = fixtureDir();
        assertTrue(Files.isDirectory(fixtures),
                "违规样本目录不存在：" + fixtures
                        + "\n没有这些样本，下面的反向测试就只是在测一个空转的检查器。");

        List<String> violations = violationsIn(fixtures);
        assertFalse(violations.isEmpty(),
                "违规样本目录里一个违规都没抓到 —— 守卫本身失效了，这比代码违规更严重");

        boolean matched = violations.stream().anyMatch(v -> v.contains(expectedFragment));
        assertTrue(matched,
                "样本目录里没有抓到含 \"" + expectedFragment + "\" 的违规。\n实际抓到：\n  "
                        + String.join("\n  ", violations));
    }

    // ================================================================
    //  工具类自身的自检
    // ================================================================

    @Test
    @DisplayName("包归属判定：core / feature / 通配前缀")
    void 包归属判定() {
        assertTrue(SourceIndex.isCore("com.mcoo.core"), "core 根包");
        assertTrue(SourceIndex.isCore("com.mcoo.core.feature"), "core 子包");
        assertFalse(SourceIndex.isCore("com.mcoo.feature.daily"), "feature 不是 core");

        assertTrue("gift".equals(SourceIndex.featureIdOf("com.mcoo.feature.gift")), "功能 id");
        assertTrue("gift".equals(SourceIndex.featureIdOf("com.mcoo.feature.gift.gui")),
                "功能子包仍算同一个功能");

        assertTrue(SourceIndex.isUnder("com.mcoo.feature.daily.X", "com.mcoo.feature"),
                "feature 之下");
        assertTrue(SourceIndex.isUnder("com.mcoo.feature", "com.mcoo.feature"),
                "正好等于前缀");
        assertFalse(SourceIndex.isUnder("com.mcoo.core.feature.X", "com.mcoo.feature"),
                "core.feature 不该被当成 feature 包");
    }

    @Test
    @DisplayName("干净源码样本本身不该报违规（防止守卫见谁咬谁）")
    void 干净样本不报违规() throws IOException {
        Path clean = fixtureDir().resolve("clean");
        assertTrue(Files.isDirectory(clean), "缺少干净样本目录: " + clean);
        List<String> violations = violationsIn(clean);
        if (!violations.isEmpty()) {
            fail("干净样本被误报了，守卫会误伤正常代码：\n  "
                    + String.join("\n  ", violations));
        }
    }
}