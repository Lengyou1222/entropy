// File: app/src/main/java/com/entropy/hardware/CpuTier.kt
//
// CPU 等级：把"我这颗 U 算什么水平"落到五档 —— 夯 / 顶级 / 人上人 / NPC / 拉完了。
//
// 依据只有本机真读得到的两样东西，而且判定来源会跟着结果一起显示：
//  1) 型号表：ro.soc.model / Build.SOC_MODEL 里的型号代号，按公开的 CPU 相对强弱人工归档。
//     这张表是人写的，会过时，也会漏 —— 所以命中与否都写出来，不装作是查出来的权威结论。
//  2) 频率兜底：表里没有的型号，用最高簇频率 + 核数换算一个粗分，标成"频率估算"。
//     只看频率会高估暖枭（同为 3.0GHz，架构差一代），所以卡片上同时把"估算"两个字印出来，
//     谁看了都知道这不是跑分。
//
// 分数量纲只用来排这五档的阶梯，不当跑分显示，也不参与任何调度决策。
// 判不出来的机器（模拟器、读不到 cpufreq）返回 NONE 并给原因，硬猜一档比"不知道"更糟。
//
// 同一张 Models 还是悬浮控制台「CPU 排行」那页的榜单本身（见 CpuRanker.ladder）：
// 评级和榜单必须是同一份数据，否则会出现"卡片说我在夯档，榜上却找不到自己"这种自相矛盾。
package com.entropy.hardware

import android.os.Build
import kotlin.math.roundToInt

/**
 * 五档，按序号从强到弱排。
 *
 * 顺序就是用户给的这把梯子（夯 → 顶级 → 人上人 → NPC → 拉完了），
 * ordinal 越大越弱；阶梯 UI 直接按 entries 画，不再自己排一遍。
 */
enum class CpuTier(val label: String, val blurb: String) {
    HANG("夯", "当代第一梯队：抬频有余量，温控压下来也不至于先掉帧"),
    TOP("顶级", "次旗舰与上一代旗舰：帧率稳，继续抬频的收益已经不大"),
    HUMAN("人上人", "中端偏上：够用，长时间顶满会先撞温度墙"),
    NPC("NPC", "主流中端：温控一压就先让帧率，调度能帮的有限"),
    LAST("拉完了", "老平台或入门平台：先降渲染分辨率，再谈调度"),;

    companion object {
        /** 分数 → 档位。阈值按型号表里的实际分布取，不是等分五段 */
        fun of(score: Int): CpuTier = when {
            score >= 86 -> HANG
            score >= 72 -> TOP
            score >= 52 -> HUMAN
            score >= 34 -> NPC
            else -> LAST
        }
    }
}

/** 一次评级结果 */
data class CpuRanking(
    /** null = 判不出来（模拟器、没有 cpufreq 节点），此时 score/tierName 都不该显示 */
    val tier: CpuTier?,
    val score: Int,
    /** 显示用的型号名，命中表时是表里的名字，没命中就回落到原始型号串 */
    val chipName: String,
    /** 判定来源，直接印在卡片上 */
    val basis: Basis,
    /** basis == NONE 时说明为什么判不出来 */
    val reason: String,
) {
    enum class Basis { MODEL, ESTIMATED, NONE }

    /** 阶梯上的位置：0 是最强那一档。没有结论时给 -1，UI 不画游标 */
    val index: Int get() = tier?.ordinal ?: -1
}

/** 榜单上的一行 = 一颗移动平台。score 是表内自评的相对强弱，不是跑分 */
data class Ranked(val name: String, val score: Int, val tier: CpuTier)

/** 画出来的一行：名次是在"把本机算进去"的那串里数出来的，所以它可能是全榜连续编号 */
data class RankRow(val rank: Int, val chip: Ranked, val mine: Boolean, val estimated: Boolean)

/** 一个档位连同它榜上那些行；空的档不会出现在结果里 */
data class TierBlock(val tier: CpuTier, val rows: List<RankRow>)

object CpuRanker {

    /** 型号表里的一行。key 一律去掉空格小写写，和 haystack 同一套归一化规则 */
    private data class Model(val key: String, val name: String, val score: Int)

    private val Models = listOf(
        // 命中规则是「haystack 里包含这条 key」，取第一条命中的。
        // 所以带后缀的变体必须排在裸型号之前：SM8550-AB 里也含 "sm8550"，
        // 裸 key 排前面就会把变体说成公版。
        // 厂商代号（pineapple / kalama / waipio / lahaina / hi3690）走的是
        // ro.board.platform 那一路：API 31 以下的机器没有 ro.soc.model，只认得出代号。
        // ---- 第一梯队 ---- //
        Model("sm8850", "骁龙 8 Elite Gen 5", 100),
        Model("mt6993", "天玑 9500", 98),
        Model("sm8750", "骁龙 8 至尊版", 96),
        Model("mt6991", "天玑 9400", 95),
        Model("mt6989t", "天玑 9300+", 88),
        Model("pineapple", "骁龙 8 Gen 3", 88),
        Model("sm8650", "骁龙 8 Gen 3", 88),
        Model("mt6989", "天玑 9300", 86),
        // ---- 顶级 ---- //
        Model("sm8550-ab", "骁龙 8 Gen 2 for Galaxy", 84),
        Model("sm8550p", "骁龙 8 Gen 2 Leading", 84),
        Model("kalama", "骁龙 8 Gen 2", 82),
        Model("sm8550", "骁龙 8 Gen 2", 82),
        Model("mt6985t", "天玑 9200+", 80),
        Model("sm8635", "骁龙 8s Gen 3", 80),
        Model("sm8735", "骁龙 8s Gen 4", 78),
        Model("mt6985", "天玑 9200", 78),
        Model("exynos2500", "Exynos 2500", 78),
        Model("sm8475", "骁龙 8+ Gen 1", 76),
        Model("mt6983t", "天玑 9000+", 76),
        Model("exynos2400", "Exynos 2400", 74),
        Model("mt6983", "天玑 9000", 74),
        Model("kirin9020", "麒麟 9020", 72),
        Model("tensorg4", "Tensor G4", 72),
        Model("tensorg3", "Tensor G3", 70),
        // ---- 人上人 ---- //
        Model("waipio", "骁龙 8 Gen 1", 68),
        Model("sm8450", "骁龙 8 Gen 1", 68),
        Model("kirin9000s", "麒麟 9000S", 68),
        Model("kirin9010", "麒麟 9010", 68),
        Model("sm7750", "骁龙 7+ Gen 3", 62),
        Model("kirin9000", "麒麟 9000", 62),
        Model("sm8350-ac", "骁龙 888+", 60),
        Model("lahaina", "骁龙 888", 58),
        Model("sm8350", "骁龙 888", 58),
        Model("exynos2200", "Exynos 2200", 56),
        Model("sm7650", "骁龙 7+ Gen 2", 56),
        Model("mt6895", "天玑 8100", 52),
        Model("gs201", "Tensor G2", 52),
        Model("tensorg2", "Tensor G2", 52),
        // ---- NPC ---- //
        Model("gs101", "Tensor G1", 40),
        Model("whitechapel", "Tensor G1", 40),
        Model("sm7350", "骁龙 780G", 48),
        Model("sm8250-ac", "骁龙 870", 50),
        Model("sm8250", "骁龙 865", 48),
        Model("sm6450", "骁龙 7 Gen 1", 46),
        Model("sm7325", "骁龙 778G", 44),
        Model("exynos1480", "Exynos 1480", 44),
        Model("mt6877", "天玑 930", 42),
        Model("exynos1380", "Exynos 1380", 42),
        Model("mt6893", "天玑 8000", 46),
        Model("kirin990", "麒麟 990", 44),
        Model("hi3690", "麒麟 990", 44),
        Model("sm6375", "骁龙 695", 40),
        Model("mt6873", "天玑 920", 40),
        Model("mt6853", "天玑 800", 38),
        Model("mt6833", "天玑 800U", 38),
        // ---- 拉完了 ---- //
        Model("sm8150-ac", "骁龙 855+", 36),
        Model("sm8150", "骁龙 855", 34),
        Model("kirin980", "麒麟 980", 30),
        Model("hi3680", "麒麟 980", 30),
        Model("mt6832", "天玑 700", 24),
        Model("sdm845", "骁龙 845", 26),
        Model("sdm710", "骁龙 710", 22),
        Model("exynos9810", "Exynos 9810", 20),
        Model("mt6769", "Helio G85 系", 20),
        Model("sm4250", "骁龙 480", 18),
        Model("sm6115", "骁龙 662", 16),
        Model("mt6765", "Helio P35 系", 12),
        Model("kirin710", "麒麟 710", 10),
        Model("mt6762", "Helio P22 系", 10),
        Model("exynos850", "Exynos 850", 8),
    )

    /**
     * 完整榜单：就是这张型号表本身，按分数从高到低，同一颗 U 的多个代号并成一行。
     *
     * 并行是按显示名（"Tensor G1" 有 gs101 和 whitechapel 两条 key；麒麟 990 有
     * kirin990 与 hi3690）。不并的话榜上会出现两颗同名不同分数的 U，名次也就能数出两种结果。
     * 排序用稳定排序，同分数保持表内顺序，所以表里那条"变体在前"的约定同样管住榜单。
     */
    val ladder: List<Ranked> by lazy {
        Models.sortedByDescending { it.score }
            .distinctBy { it.name }
            .map { Ranked(it.name, it.score, CpuTier.of(it.score)) }
    }

    /**
     * 这个分数排在第几（1 起）。
     *
     * 按"压过多少人"算而不是查表：型号表里没有的 U 也要能落位，
     * 频率估算出来的分数就是它在这串数字里的位置。表外估算的名次精度当然不如命中，
     * 所以 UI 要把"估算"两个字一起印出来，别让一个插出来的名次看起来像查出来的。
     */
    fun place(score: Int): Int = ladder.count { it.score > score } + 1

    /**
     * 把一次评级钉进完整榜单：按档分组、连续数名次、标本机那一行。
     *
     * 命中型号表时"本机"就是表里那一行，名次是数出来的；
     * 只有频率估算时，先按 place() 把它插进该在的位置再数 —— 名次因此带着估算的精度，
     * 所以 estimated 要一路传到 UI 上，让它把"估算"两个字印出来。
     * 判不出等级（NONE）不插行：榜照排，本机那栏写没结论的原因。
     */
    fun board(r: CpuRanking): List<TierBlock> {
        val mine = r.tier
        val estimated = mine != null && r.basis == CpuRanking.Basis.ESTIMATED
        val chips = ladder.toMutableList()
        if (estimated && mine != null)
            chips.add((place(r.score) - 1).coerceIn(0, chips.size), Ranked(r.chipName, r.score, mine))
        val hit = r.basis == CpuRanking.Basis.MODEL
        val byTier = LinkedHashMap<CpuTier, MutableList<RankRow>>()
        chips.forEachIndexed { i, c ->
            val isMine = c.name == r.chipName && (hit || estimated)
            byTier.getOrPut(c.tier) { mutableListOf() }
                .add(RankRow(i + 1, c, isMine, estimated && isMine))
        }
        return CpuTier.entries.mapNotNull { t -> byTier[t]?.let { TierBlock(t, it) } }
    }

    /**
     * 喂给型号表的串：SoC 型号 + 机型名 + 品牌/硬件/设备名。
     * 空格一律抹掉 —— 高通写 "SM8650"、三星写 "Exynos 2500"、`ro.product.model` 里还可能
     * 再夹一层厂商前缀，归一化之后一张表就能全覆盖，不用每个型号养两条 key。
     */
    private fun haystack(p: DeviceProfile): String =
        listOf(p.socModel, p.model, Build.BRAND, Build.MODEL, Build.HARDWARE, Build.DEVICE)
            .joinToString(" ").lowercase().replace(" ", "")

    /**
     * 评一次级。
     *
     * 虚拟设备先单独拦一道：ranchu/goldfish 这类底子上，cpufreq 节点存在但读出来的是
     * 宿主机频率，拿它评一档纯属误导 —— 直接判 NONE，让 UI 说实话。
     */
    fun rank(p: DeviceProfile): CpuRanking {
        val hay = haystack(p)
        if (isVirtual(p))
            return CpuRanking(null, 0, p.socModel.ifEmpty { "未取到" }, CpuRanking.Basis.NONE,
                "这台机器没有真实 SoC（虚拟平台），不猜等级")
        Models.firstOrNull { hay.contains(it.key) }?.let { m ->
            return CpuRanking(CpuTier.of(m.score), m.score, m.name, CpuRanking.Basis.MODEL, "")
        }
        val topGhz = p.cpuClusters.maxOfOrNull { it.maxKhz }?.div(1_000_000.0) ?: 0.0
        if (topGhz <= 0.0)
            return CpuRanking(null, 0, p.socModel.ifEmpty { "未取到" }, CpuRanking.Basis.NONE,
                "型号表里没有这颗 U，也读不到 cpufreq 频率上限，不猜等级")
        val cores = p.cpuClusters.sumOf { it.cores.count() }
        // 3.6GHz 顶到 90 分、1.4GHz 落到 10 分，核数只补到 +8：
        // 频率是这里唯一真读得到的信号，权重要压在它身上，核数只是修正项
        val freqScore = ((topGhz - 1.4) / (3.6 - 1.4) * 80.0 + 10.0).roundToInt()
            .coerceIn(5, 92)
        val score = (freqScore + if (cores >= 8) 8 else if (cores >= 6) 4 else 0).coerceIn(1, 100)
        return CpuRanking(CpuTier.of(score), score,
            p.socModel.ifEmpty { "未识别型号" }, CpuRanking.Basis.ESTIMATED, "")
    }

    private fun isVirtual(p: DeviceProfile): Boolean {
        val hay = haystack(p)
        return listOf("ranchu", "goldfish", "vbox86", "nx", "cuttlefish", "android_x86")
            .any { hay.contains(it) } || p.family == SocFamily.UNKNOWN && p.cpuClusters.isEmpty()
    }
}
