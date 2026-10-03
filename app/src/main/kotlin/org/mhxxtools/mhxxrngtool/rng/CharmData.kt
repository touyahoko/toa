package org.mhxxtools.mhxxrngtool.rng

// ---------------------------------------------------------------------------
// 言語テーブル (set_ja 相当)
// ---------------------------------------------------------------------------

// HTML統合ツール準拠の正式スキル名（略称パディングは廃止）
val SKILL_NAMES: List<String> = listOf(
    "毒", "麻痺", "睡眠", "気絶", "聴覚保護", "風圧", "耐震", "だるま", "耐暑", "耐寒",
    "寒冷適応", "炎熱適応", "盗み無効", "対防御DOWN", "狂撃耐性", "細菌学", "裂傷", "攻撃", "防御", "体力",
    "火耐性", "水耐性", "雷耐性", "氷耐性", "龍耐性", "属性耐性", "火属性攻撃", "水属性攻撃", "雷属性攻撃", "氷属性攻撃",
    "龍属性攻撃", "属性攻撃", "特殊攻撃", "研ぎ師", "匠", "斬れ味", "剣術", "研磨術", "鈍器", "抜刀会心",
    "抜刀減気", "納刀", "納刀研磨", "刃鱗", "装填速度", "反動", "精密射撃", "通常弾強化", "貫通弾強化", "散弾強化",
    "重撃弾強化", "通常弾追加", "貫通弾追加", "散弾追加", "榴弾追加", "拡散弾追加", "毒瓶追加", "麻痺瓶追加", "睡眠瓶追加", "強撃瓶追加",
    "属強瓶追加", "接撃瓶追加", "減気瓶追加", "爆破瓶追加", "速射", "射法", "装填数", "変則射撃", "弾薬節約", "達人",
    "痛撃", "連撃", "特殊会心", "属性会心", "会心強化", "裏会心", "溜め短縮", "スタミナ", "体術", "気力回復",
    "走行継続", "回避性能", "回避距離", "泡沫", "ガード性能", "ガード強化", "KO", "減気攻撃", "笛", "砲術",
    "重撃", "爆弾強化", "本気", "闘魂", "無傷", "チャンス", "龍気", "底力", "逆境", "逆上",
    "窮地", "根性", "気配", "采配", "号令", "乗り", "跳躍", "無心", "我慢", "SP延長",
    "千里眼", "観察眼", "狩人", "運搬", "加護", "英雄の盾", "回復量", "回復速度", "効果持続", "広域",
    "腹減り", "食いしん坊", "食事", "節食", "肉食", "茸食", "野草知識", "調合成功率", "調合数", "高速設置",
    "採取", "ハチミツ", "護石王", "気まぐれ", "運気", "剥ぎ取り", "捕獲", "ベルナ", "ここっと", "ポッケ",
    "ユクモ", "龍識船", "飛行酒場", "紅兜", "大雪主", "矛砕", "岩穿", "紫毒姫", "宝纏", "白疾風",
    "隻眼", "黒炎王", "金雷公", "荒鉤爪", "燼滅刃", "朧隠", "鎧裂", "天眼", "青電主", "銀嶺",
    "鏖魔", "真・紅兜", "真・大雪主", "真・矛砕", "真・岩穿", "真・紫毒姫", "真・宝纏", "真・白疾風", "真・隻眼", "真・黒炎王",
    "真・金雷公", "真・荒鉤爪", "真・燼滅刃", "真・朧隠", "真・鎧裂", "真・天眼", "真・青電主", "真・銀嶺", "真・鏖魔", "北辰納豆流",
    "斬術", "食欲", "職工", "剛腕", "祈願", "裏稼業", "刀匠", "射手", "状態耐性", "怒",
    "回避術", "居合", "頑強", "剛撃", "盾持", "潔癖", "増幅", "護石収集", "強欲", "対鋼龍",
    "対霞龍", "対炎龍", "胴系統倍加", "秘術", "護石強化"
)

val ORIGIN_NAMES: List<String> = listOf("マカ", "炭鉱")
val KIND_NAMES: List<String> = listOf("風化したお守り", "古びたお守り", "光るお守り", "なぞのお守り")

val RARITY_COLORS: Map<Int, Long> = mapOf(
    1 to 0xFF808080L, 2 to 0xFF8080FFL, 3 to 0xFFC0C000L, 4 to 0xFFC080C0L,
    5 to 0xFF80C080L, 6 to 0xFF4040C0L, 7 to 0xFFC04040L, 8 to 0xFF80C0C0L,
    9 to 0xFFFFC080L, 10 to 0xFFC040C0L
)

// ---------------------------------------------------------------------------
// KindTable – お守り種類ごとのテーブル
// ---------------------------------------------------------------------------
data class KindTable(
    val index: Int,
    val label: String,
    val skill1: IntArray,
    val sp1: Array<IntArray>,       // [lo, hi]
    val skill2: IntArray,
    val sp2: Array<IntArray>,       // [lo, hi]
    val slotvalue: Array<IntArray>, // [v0, v1, v2]
    val th: Int
)

val KIND_TABLES: Map<Int, KindTable> = mapOf(
    // ────────────────── 風化したお守り ──────────────────
    0 to KindTable(
        index = 0, label = "風化したお守り",
        skill1 = intArrayOf(
            4,5,10,11,14,15,25,31,32,35,36,37,38,39,40,41,42,44,45,47,
            48,49,50,64,65,66,68,70,71,72,73,76,77,78,79,80,81,82,83,84,
            85,86,87,90,92,93,94,95,97,99,100,101,106,107,108,109,114,115,116,122,123,132
        ),
        sp1 = arrayOf(
            intArrayOf(3,7),intArrayOf(5,10),intArrayOf(3,7),intArrayOf(3,7),intArrayOf(3,7),intArrayOf(5,10),intArrayOf(3,7),intArrayOf(3,7),intArrayOf(3,7),intArrayOf(3,7),
            intArrayOf(3,7),intArrayOf(1,5),intArrayOf(2,6),intArrayOf(1,5),intArrayOf(1,5),intArrayOf(5,10),intArrayOf(5,10),intArrayOf(3,7),intArrayOf(2,6),intArrayOf(2,6),
            intArrayOf(2,6),intArrayOf(2,6),intArrayOf(2,6),intArrayOf(1,5),intArrayOf(1,5),intArrayOf(1,5),intArrayOf(3,7),intArrayOf(2,6),intArrayOf(1,5),intArrayOf(2,6),
            intArrayOf(2,6),intArrayOf(2,6),intArrayOf(2,6),intArrayOf(3,7),intArrayOf(3,7),intArrayOf(2,6),intArrayOf(2,6),intArrayOf(2,6),intArrayOf(1,5),intArrayOf(3,7),
            intArrayOf(3,7),intArrayOf(5,10),intArrayOf(5,10),intArrayOf(2,6),intArrayOf(2,6),intArrayOf(1,5),intArrayOf(1,5),intArrayOf(1,5),intArrayOf(2,6),intArrayOf(2,6),
            intArrayOf(2,6),intArrayOf(1,5),intArrayOf(2,6),intArrayOf(1,5),intArrayOf(3,7),intArrayOf(3,7),intArrayOf(3,7),intArrayOf(1,5),intArrayOf(3,7),intArrayOf(2,6),
            intArrayOf(3,7),intArrayOf(3,7)
        ),
        skill2 = intArrayOf(
            4,5,17,18,25,26,27,28,29,30,32,33,34,35,36,37,39,40,41,43,
            44,45,47,48,49,50,64,65,66,68,69,70,71,74,75,76,77,78,79,80,
            81,82,83,84,85,86,87,88,89,90,91,92,93,94,95,96,97,99,100,101,
            105,106,107,108,109,114,115,116,119,122,123,125,132,134,135,136,161,162,163,164,
            165,166,167,168,169,170,171,172,173,174,175,176,177,178
        ),
        sp2 = arrayOf(
            intArrayOf(3,5),intArrayOf(5,7),intArrayOf(7,10),intArrayOf(5,13),intArrayOf(5,7),intArrayOf(5,13),intArrayOf(5,13),intArrayOf(5,13),intArrayOf(5,13),intArrayOf(5,13),
            intArrayOf(5,7),intArrayOf(7,10),intArrayOf(3,5),intArrayOf(5,7),intArrayOf(5,7),intArrayOf(3,5),intArrayOf(5,5),intArrayOf(2,8),intArrayOf(5,7),intArrayOf(3,3),
            intArrayOf(5,7),intArrayOf(5,7),intArrayOf(3,5),intArrayOf(3,5),intArrayOf(3,5),intArrayOf(3,5),intArrayOf(3,5),intArrayOf(3,5),intArrayOf(3,5),intArrayOf(5,7),
            intArrayOf(7,10),intArrayOf(3,5),intArrayOf(1,3),intArrayOf(3,5),intArrayOf(3,3),intArrayOf(3,5),intArrayOf(3,5),intArrayOf(3,5),intArrayOf(3,5),intArrayOf(3,5),
            intArrayOf(3,5),intArrayOf(3,5),intArrayOf(1,3),intArrayOf(3,5),intArrayOf(3,5),intArrayOf(7,10),intArrayOf(7,10),intArrayOf(5,10),intArrayOf(5,10),intArrayOf(3,5),
            intArrayOf(5,10),intArrayOf(3,5),intArrayOf(1,3),intArrayOf(1,3),intArrayOf(1,3),intArrayOf(3,3),intArrayOf(3,5),intArrayOf(3,5),intArrayOf(3,5),intArrayOf(1,3),
            intArrayOf(7,10),intArrayOf(3,5),intArrayOf(1,3),intArrayOf(5,7),intArrayOf(5,7),intArrayOf(7,10),intArrayOf(1,3),intArrayOf(3,5),intArrayOf(5,12),intArrayOf(3,5),
            intArrayOf(5,7),intArrayOf(3,5),intArrayOf(7,10),intArrayOf(5,7),intArrayOf(3,5),intArrayOf(5,7),intArrayOf(3,3),intArrayOf(3,3),intArrayOf(3,3),intArrayOf(3,3),
            intArrayOf(3,3),intArrayOf(3,3),intArrayOf(3,3),intArrayOf(3,3),intArrayOf(3,3),intArrayOf(3,3),intArrayOf(3,3),intArrayOf(3,3),intArrayOf(3,3),intArrayOf(3,3),
            intArrayOf(3,3),intArrayOf(3,3),intArrayOf(3,3),intArrayOf(3,3)
        ),
        slotvalue = arrayOf(
            intArrayOf(100,100,100),intArrayOf(3,53,88),intArrayOf(5,55,89),intArrayOf(7,57,89),intArrayOf(13,58,89),
            intArrayOf(16,60,90),intArrayOf(22,62,90),intArrayOf(30,66,90),intArrayOf(38,68,91),intArrayOf(50,72,91),
            intArrayOf(55,75,92),intArrayOf(59,77,92),intArrayOf(64,81,94),intArrayOf(67,83,94),intArrayOf(71,86,96),
            intArrayOf(74,88,96),intArrayOf(79,91,98),intArrayOf(82,92,98),intArrayOf(86,94,99),intArrayOf(90,96,99)
        ),
        th = 15
    ),
    // ────────────────── 古びたお守り ──────────────────
    1 to KindTable(
        index = 1, label = "古びたお守り",
        skill1 = intArrayOf(
            4,5,10,11,14,15,25,26,27,28,29,30,31,32,35,36,38,41,42,44,
            45,47,48,49,50,65,68,70,72,73,76,77,78,79,81,82,84,85,86,87,
            90,92,97,99,100,103,104,106,108,109,114,116,122,123,124,132
        ),
        sp1 = arrayOf(
            intArrayOf(1,5),intArrayOf(1,5),intArrayOf(1,5),intArrayOf(1,5),intArrayOf(1,5),intArrayOf(1,8),intArrayOf(1,5),intArrayOf(1,7),intArrayOf(1,7),intArrayOf(1,7),
            intArrayOf(1,7),intArrayOf(1,7),intArrayOf(1,5),intArrayOf(1,6),intArrayOf(1,5),intArrayOf(1,5),intArrayOf(1,6),intArrayOf(1,6),intArrayOf(1,6),intArrayOf(1,6),
            intArrayOf(1,5),intArrayOf(1,5),intArrayOf(1,5),intArrayOf(1,5),intArrayOf(1,5),intArrayOf(1,3),intArrayOf(1,5),intArrayOf(1,5),intArrayOf(1,5),intArrayOf(1,5),
            intArrayOf(1,5),intArrayOf(1,5),intArrayOf(1,6),intArrayOf(1,6),intArrayOf(1,6),intArrayOf(1,6),intArrayOf(1,6),intArrayOf(1,6),intArrayOf(1,6),intArrayOf(1,6),
            intArrayOf(1,5),intArrayOf(1,6),intArrayOf(1,6),intArrayOf(1,5),intArrayOf(1,5),intArrayOf(1,7),intArrayOf(1,7),intArrayOf(1,5),intArrayOf(1,5),intArrayOf(1,6),
            intArrayOf(1,7),intArrayOf(1,6),intArrayOf(1,5),intArrayOf(1,5),intArrayOf(1,5),intArrayOf(1,7)
        ),
        skill2 = intArrayOf(
            3,4,5,17,18,19,20,21,22,23,24,25,26,27,28,29,30,32,33,34,
            35,36,37,39,40,41,42,44,45,47,48,49,50,64,65,66,68,69,70,71,
            74,76,77,78,79,80,81,82,83,84,85,86,87,88,89,90,91,92,93,94,
            95,97,99,100,101,103,104,105,106,107,108,109,110,114,115,116,117,119,120,122,
            123,124,125,132,134,135,136,143,144,145,146,147,148,149,150,151,152,153,154,155,
            156,157,158,159,160
        ),
        sp2 = arrayOf(
            intArrayOf(10,13),intArrayOf(3,3),intArrayOf(10,3),intArrayOf(10,10),intArrayOf(10,10),intArrayOf(10,13),intArrayOf(10,13),intArrayOf(10,13),intArrayOf(10,13),intArrayOf(10,13),
            intArrayOf(10,13),intArrayOf(3,3),intArrayOf(10,13),intArrayOf(10,13),intArrayOf(10,13),intArrayOf(10,13),intArrayOf(10,13),intArrayOf(10,4),intArrayOf(10,8),intArrayOf(5,5),
            intArrayOf(3,3),intArrayOf(3,3),intArrayOf(3,3),intArrayOf(3,3),intArrayOf(5,8),intArrayOf(10,4),intArrayOf(3,3),intArrayOf(3,4),intArrayOf(3,3),intArrayOf(3,3),
            intArrayOf(3,3),intArrayOf(3,3),intArrayOf(3,3),intArrayOf(3,3),intArrayOf(5,5),intArrayOf(3,3),intArrayOf(5,5),intArrayOf(10,10),intArrayOf(3,3),intArrayOf(3,3),
            intArrayOf(3,3),intArrayOf(3,3),intArrayOf(3,3),intArrayOf(3,4),intArrayOf(3,4),intArrayOf(5,5),intArrayOf(3,4),intArrayOf(3,4),intArrayOf(3,3),intArrayOf(3,4),
            intArrayOf(3,4),intArrayOf(3,4),intArrayOf(3,4),intArrayOf(10,10),intArrayOf(10,10),intArrayOf(3,3),intArrayOf(10,10),intArrayOf(3,4),intArrayOf(3,3),intArrayOf(3,3),
            intArrayOf(3,3),intArrayOf(3,4),intArrayOf(5,5),intArrayOf(5,5),intArrayOf(3,3),intArrayOf(10,10),intArrayOf(10,10),intArrayOf(5,5),intArrayOf(5,5),intArrayOf(3,3),
            intArrayOf(5,5),intArrayOf(3,3),intArrayOf(10,12),intArrayOf(10,9),intArrayOf(3,3),intArrayOf(3,4),intArrayOf(10,12),intArrayOf(10,12),intArrayOf(10,10),intArrayOf(3,3),
            intArrayOf(5,5),intArrayOf(5,5),intArrayOf(3,3),intArrayOf(8,10),intArrayOf(3,3),intArrayOf(3,3),intArrayOf(3,3),intArrayOf(3,3),intArrayOf(3,3),intArrayOf(3,3),
            intArrayOf(3,3),intArrayOf(3,3),intArrayOf(3,3),intArrayOf(3,3),intArrayOf(3,3),intArrayOf(3,3),intArrayOf(3,3),intArrayOf(3,3),intArrayOf(3,3),intArrayOf(3,3),
            intArrayOf(3,3),intArrayOf(3,3),intArrayOf(3,3),intArrayOf(3,3),intArrayOf(3,3)
        ),
        slotvalue = arrayOf(
            intArrayOf(8,58,88),intArrayOf(9,59,88),intArrayOf(16,61,89),intArrayOf(17,62,89),intArrayOf(23,63,89),
            intArrayOf(25,65,90),intArrayOf(31,66,90),intArrayOf(38,68,90),intArrayOf(45,71,91),intArrayOf(58,76,91),
            intArrayOf(63,79,92),intArrayOf(66,80,92),intArrayOf(71,83,94),intArrayOf(74,84,94),intArrayOf(78,87,96),
            intArrayOf(82,90,96),intArrayOf(86,93,98),intArrayOf(88,94,98),intArrayOf(91,96,99),intArrayOf(94,97,99)
        ),
        th = 25
    ),
    // ────────────────── 光るお守り ──────────────────
    2 to KindTable(
        index = 2, label = "光るお守り",
        skill1 = intArrayOf(
            0,1,2,3,5,6,7,13,17,18,19,20,21,22,23,24,26,27,28,29,
            30,32,33,38,41,44,46,51,52,53,54,55,62,63,68,69,72,73,78,79,
            81,84,85,86,87,88,89,91,97,98,99,100,103,104,106,108,109,110,113,114,
            116,117,119,120,122,123,124,126,129,131,132
        ),
        sp1 = arrayOf(
            intArrayOf(1,5),intArrayOf(1,5),intArrayOf(1,5),intArrayOf(1,8),intArrayOf(1,4),intArrayOf(1,7),intArrayOf(1,7),intArrayOf(1,7),intArrayOf(1,4),intArrayOf(1,4),
            intArrayOf(1,8),intArrayOf(1,6),intArrayOf(1,6),intArrayOf(1,6),intArrayOf(1,6),intArrayOf(1,6),intArrayOf(1,7),intArrayOf(1,7),intArrayOf(1,7),intArrayOf(1,7),
            intArrayOf(1,7),intArrayOf(1,4),intArrayOf(1,4),intArrayOf(1,4),intArrayOf(1,6),intArrayOf(1,4),intArrayOf(1,6),intArrayOf(1,6),intArrayOf(1,10),intArrayOf(1,10),
            intArrayOf(1,10),intArrayOf(1,10),intArrayOf(1,10),intArrayOf(1,10),intArrayOf(1,3),intArrayOf(1,4),intArrayOf(1,3),intArrayOf(1,3),intArrayOf(1,6),intArrayOf(1,6),
            intArrayOf(1,6),intArrayOf(1,6),intArrayOf(1,4),intArrayOf(1,6),intArrayOf(1,6),intArrayOf(1,6),intArrayOf(1,6),intArrayOf(1,6),intArrayOf(1,6),intArrayOf(1,5),
            intArrayOf(1,3),intArrayOf(1,3),intArrayOf(1,5),intArrayOf(1,5),intArrayOf(1,3),intArrayOf(1,3),intArrayOf(1,6),intArrayOf(1,8),intArrayOf(1,8),intArrayOf(1,7),
            intArrayOf(1,6),intArrayOf(1,7),intArrayOf(1,8),intArrayOf(1,8),intArrayOf(1,4),intArrayOf(1,3),intArrayOf(1,3),intArrayOf(1,8),intArrayOf(1,8),intArrayOf(1,8),
            intArrayOf(1,3)
        ),
        skill2 = intArrayOf(
            0,1,2,3,6,7,8,9,12,13,14,15,16,17,18,19,20,21,22,23,
            24,32,40,46,51,52,53,54,55,56,57,58,59,60,61,62,63,65,67,68,
            69,72,73,88,89,91,98,99,100,102,103,104,105,106,108,110,111,112,113,117,
            118,119,120,121,123,124,126,127,128,129,130,131,132,133
        ),
        sp2 = arrayOf(
            intArrayOf(10,7),intArrayOf(10,7),intArrayOf(10,7),intArrayOf(10,10),intArrayOf(10,8),intArrayOf(10,8),intArrayOf(10,10),intArrayOf(10,10),intArrayOf(10,10),intArrayOf(10,8),
            intArrayOf(5,5),intArrayOf(5,5),intArrayOf(10,10),intArrayOf(7,7),intArrayOf(7,7),intArrayOf(10,10),intArrayOf(10,10),intArrayOf(10,10),intArrayOf(10,10),intArrayOf(10,10),
            intArrayOf(10,10),intArrayOf(4,4),intArrayOf(5,5),intArrayOf(10,10),intArrayOf(8,8),intArrayOf(10,10),intArrayOf(10,10),intArrayOf(10,10),intArrayOf(10,10),intArrayOf(10,10),
            intArrayOf(10,10),intArrayOf(10,10),intArrayOf(10,12),intArrayOf(10,12),intArrayOf(10,10),intArrayOf(10,10),intArrayOf(10,10),intArrayOf(3,3),intArrayOf(10,10),intArrayOf(5,5),
            intArrayOf(7,7),intArrayOf(5,5),intArrayOf(5,5),intArrayOf(8,8),intArrayOf(8,8),intArrayOf(8,8),intArrayOf(5,5),intArrayOf(5,5),intArrayOf(5,5),intArrayOf(10,10),
            intArrayOf(7,7),intArrayOf(7,7),intArrayOf(8,8),intArrayOf(5,5),intArrayOf(5,5),intArrayOf(10,10),intArrayOf(10,10),intArrayOf(10,10),intArrayOf(10,10),intArrayOf(4,4),
            intArrayOf(10,10),intArrayOf(10,10),intArrayOf(10,10),intArrayOf(10,13),intArrayOf(5,5),intArrayOf(5,5),intArrayOf(10,10),intArrayOf(10,13),intArrayOf(10,10),intArrayOf(10,10),
            intArrayOf(10,13),intArrayOf(10,10),intArrayOf(5,5),intArrayOf(10,13)
        ),
        slotvalue = arrayOf(
            intArrayOf(2,72,100),intArrayOf(9,74,100),intArrayOf(16,76,100),intArrayOf(23,78,100),intArrayOf(30,80,100),
            intArrayOf(37,82,100),intArrayOf(44,84,100),intArrayOf(51,86,100),intArrayOf(58,88,100),intArrayOf(75,90,100),
            intArrayOf(83,92,100),intArrayOf(87,95,100),intArrayOf(90,97,100),intArrayOf(92,98,100),intArrayOf(94,99,100),
            intArrayOf(95,99,100),intArrayOf(97,100,100),intArrayOf(98,100,100),intArrayOf(99,100,100),intArrayOf(99,100,100)
        ),
        th = 35
    ),
    // ────────────────── なぞのお守り ──────────────────
    3 to KindTable(
        index = 3, label = "なぞのお守り",
        skill1 = intArrayOf(
            0,1,2,3,6,7,8,9,12,13,14,16,17,18,19,20,21,22,23,24,
            46,51,52,53,54,55,56,57,58,59,60,61,62,63,67,69,88,89,91,98,
            102,103,104,105,110,111,112,113,118,119,120,121,126,127,128,129,130,131,133
        ),
        sp1 = arrayOf(
            intArrayOf(1,5),intArrayOf(1,5),intArrayOf(1,5),intArrayOf(1,8),intArrayOf(1,7),intArrayOf(1,7),intArrayOf(1,10),intArrayOf(1,10),intArrayOf(1,10),intArrayOf(1,7),
            intArrayOf(1,3),intArrayOf(1,5),intArrayOf(1,4),intArrayOf(1,4),intArrayOf(1,8),intArrayOf(1,6),intArrayOf(1,6),intArrayOf(1,6),intArrayOf(1,6),intArrayOf(1,6),
            intArrayOf(1,6),intArrayOf(1,8),intArrayOf(1,8),intArrayOf(1,8),intArrayOf(1,8),intArrayOf(1,8),intArrayOf(1,8),intArrayOf(1,8),intArrayOf(1,8),intArrayOf(1,8),
            intArrayOf(1,8),intArrayOf(1,8),intArrayOf(1,8),intArrayOf(1,8),intArrayOf(1,5),intArrayOf(1,4),intArrayOf(1,6),intArrayOf(1,6),intArrayOf(1,6),intArrayOf(1,4),
            intArrayOf(1,8),intArrayOf(1,3),intArrayOf(1,3),intArrayOf(1,10),intArrayOf(1,8),intArrayOf(1,8),intArrayOf(1,8),intArrayOf(1,8),intArrayOf(1,8),intArrayOf(1,8),
            intArrayOf(1,8),intArrayOf(1,10),intArrayOf(1,8),intArrayOf(1,10),intArrayOf(1,8),intArrayOf(1,8),intArrayOf(1,10),intArrayOf(1,8),intArrayOf(1,10)
        ),
        skill2 = intArrayOf(0),
        sp2 = arrayOf(intArrayOf(10,7)),
        slotvalue = arrayOf(
            intArrayOf(55,100,100),intArrayOf(60,100,100),intArrayOf(65,100,100),intArrayOf(70,100,100),intArrayOf(75,100,100),
            intArrayOf(80,100,100),intArrayOf(85,100,100),intArrayOf(90,100,100),intArrayOf(95,100,100),intArrayOf(99,100,100),
            intArrayOf(100,100,100),intArrayOf(100,100,100),intArrayOf(100,100,100),intArrayOf(100,100,100),intArrayOf(100,100,100),
            intArrayOf(100,100,100),intArrayOf(100,100,100),intArrayOf(100,100,100),intArrayOf(100,100,100),intArrayOf(100,100,100)
        ),
        th = 100
    )
)
