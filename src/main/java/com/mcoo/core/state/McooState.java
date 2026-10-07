package com.mcoo.core.state;

import com.mojang.serialization.Codec;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 全 mod **唯一**的存档。
 *
 * <p><b>为什么是一个存档而不是每个功能一个：</b>
 * 每个功能自己定存档结构，等于每加一个字段就要改一次 Codec；
 * 而 {@code SavedDataType} 的 Codec 是<b>读盘时</b>要用的，改坏了旧存档就读不出来。
 * 把结构自由度下放给功能、core 只当一个「按功能分区的字符串仓库」，
 * core 就不需要因为功能演进而改格式 —— 这是稳定性优先的取舍。
 *
 * <p><b>分区规则（对应 {@code AGENTS.md} §3.4）</b>：
 * 每个功能只能碰自己 id 命名空间下的键。删掉一个功能，
 * 它那块数据就成了孤儿字符串，不影响任何人，也不用清理。
 *
 * <p><b>值一律用字符串存</b>：功能自己的结构（列表、嵌套对象）由功能
 * 序列化成 JSON 文本存进来，core <b>永远不解析它</b>。
 * 这样 core 不需要跟着功能的字段变化而改，这是刻意的，不是偷懒。
 *
 * <p><b>线程安全</b>：用 {@link ConcurrentHashMap}。主线程读写为主，
 * 但异步任务（比如给离线玩家补发礼物）也可能碰它。
 */
public final class McooState extends SavedData {

    /** 功能 id → 该功能自己序列化的数据文本。 */
    private final Map<String, String> sections;

    /**
     * 整份存档的编解码器。26.2 的 {@code SavedData} <b>没有</b> save/load 方法，
     * 存盘全靠这个 codec，所以它写错 = 存档读不出来。
     *
     * <p><b>为什么顶层直接就是 sections map，中间不套一层字段</b> ——
     * 这坑非常隐蔽，源码 javadoc 查不到，只能看类签名：
     * {@code Codec.fieldOf(name)} 返回的是 {@code MapCodec}，而
     * {@code MapCodec} <b>不是 {@code Codec} 的子类型</b>，
     * 它只实现 {@code MapEncoder} / {@code MapDecoder}，是
     * {@code RecordCodecBuilder} 的内部构件：
     * <pre>
     * public abstract class MapCodec&lt;A&gt; extends CompressorHolder
     *         implements MapDecoder&lt;A&gt;, MapEncoder&lt;A&gt; { }
     * </pre>
     * 所以 {@code SECTIONS.fieldOf("x").orElse(...).xmap(...)} 这种写法
     * 必然编译失败，而且报错是「不存在类型变量 S 的实例」这种看不出所以然的。
     *
     * <p>而 {@code Codec.unboundedMap(...)} 返回的 {@code UnboundedMapCodec}
     * <b>确实是 {@code Codec<Map<K,V>>}</b>，可以直接 xmap。
     *
     * <p>顶层不套字段反而更干净：存档文件本身已经叫 {@code mcoo:main}、
     * 独占一个文件，再包一层 {@code "sections"} 是多余的。
     */
    private static final Codec<Map<String, String>> SECTIONS =
            Codec.unboundedMap(Codec.STRING, Codec.STRING);

    public static final Codec<McooState> CODEC =
            SECTIONS.xmap(McooState::new, state -> state.sections);

    /** 存档 id。改了等于换一个存档文件，老数据不会丢但也不会被读到。 */
    public static final String SAVE_ID = "mcoo:main";

    public McooState() {
        this(Map.of());
    }

    private McooState(Map<String, String> sections) {
        this.sections = new ConcurrentHashMap<>(sections);
    }

    /**
     * 读某个功能分区的原始文本；没有则返回空串。
     *
     * <p>返回空串而不是 null，是为了让调用方少写一次判空。
     * 空串对功能来说就是「没有数据」，和「数据是空」可以自行区分。
     */
    public String read(String featureId, String key) {
        String whole = sections.get(featureId);
        if (whole == null || whole.isEmpty()) {
            return "";
        }
        // 分区内是 key=value 每行一条，简单到不需要引入 JSON 解析器。
        for (String line : whole.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0 && line.substring(0, eq).equals(key)) {
                return line.substring(eq + 1);
            }
        }
        return "";
    }

    /** 写某个功能分区的一格。value 为 null 视为删除该格。 */
    public void write(String featureId, String key, String value) {
        // 每次写都重建整个分区文本。分区数据量很小（每个功能几十行），
        // 这点开销可以忽略，换来的是不用担心并发把内容写花。
        Map<String, String> entries = new LinkedHashMap<>();
        String whole = sections.get(featureId);
        if (whole != null && !whole.isEmpty()) {
            for (String line : whole.split("\n")) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    entries.put(line.substring(0, eq), line.substring(eq + 1));
                }
            }
        }
        if (value == null) {
            entries.remove(key);
        } else {
            // 换行和等号会破坏行格式，直接拒绝而不是悄悄存坏数据。
            if (value.indexOf('\n') >= 0 || value.indexOf('=') >= 0) {
                throw new IllegalArgumentException(
                        "存档值不能包含换行或等号（功能 " + featureId + " 的键 " + key + "）");
            }
            entries.put(key, value);
        }

        if (entries.isEmpty()) {
            sections.remove(featureId);
        } else {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : entries.entrySet()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            sections.put(featureId, sb.toString());
        }

        // 关键：标脏，否则世界存档时不会把我们写进去。
        setDirty();
    }

    /** 读整数；没有或格式不对都返回 {@code fallback}，绝不抛异常。 */
    public int readInt(String featureId, String key, int fallback) {
        String raw = read(featureId, key);
        if (raw.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            // 存档被人手改坏了是常事，当成"没有值"比崩服务器强。
            return fallback;
        }
    }

    public long readLong(String featureId, String key, long fallback) {
        String raw = read(featureId, key);
        if (raw.isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * 读布尔值；没有值、或存档里的值不是合法的 {@code true/false} 时退回默认值。
     *
     * <p><b>这里不能直接用 {@link Boolean#parseBoolean}</b>：
     * 它对任何非 "true" 的字符串都返回 {@code false}。于是玩家把存档手改成
     * {@code claimed=yes} 时，这里会安静地返回 {@code false} 而不是默认值 ——
     * 跟 {@link #readInt} / {@link #readLong} 的行为不一致，
     * 而且这个 bug 极难被发现（功能只是「偶尔少算一天奖励」）。
     */
    public boolean readBoolean(String featureId, String key, boolean fallback) {
        String raw = read(featureId, key);
        if ("true".equals(raw)) {
            return true;
        }
        if ("false".equals(raw)) {
            return false;
        }
        return fallback;
    }

    public void writeInt(String featureId, String key, int value) {
        write(featureId, key, Integer.toString(value));
    }

    public void writeLong(String featureId, String key, long value) {
        write(featureId, key, Long.toString(value));
    }

    public void writeBoolean(String featureId, String key, boolean value) {
        write(featureId, key, Boolean.toString(value));
    }

    /** 已经存在的功能分区 id。只读，不解析内容。 */
    public Set<String> featureIds() {
        return Collections.unmodifiableSet(sections.keySet());
    }

    /** 存档里一共有多少个功能分区。用于诊断存档有没有被写坏。 */
    public int sectionCount() {
        return sections.size();
    }

    @Override
    public String toString() {
        return "McooState[分区=" + sections.size() + ", 已修改=" + isDirty() + "]";
    }
}