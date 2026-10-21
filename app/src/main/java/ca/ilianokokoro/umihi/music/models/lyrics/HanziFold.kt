package ca.ilianokokoro.umihi.music.models.lyrics

import android.os.Build

/**
 * Folds Traditional Chinese to Simplified.
 *
 * The domestic lyric catalogues are keyed on Simplified, but the metadata that reaches us from
 * YouTube Music often keeps the Traditional spelling - the track shows up as `煙花易冷` when the
 * song is `烟花易冷`. That breaks the lookup twice over: QQ Music and NetEase answer the
 * Traditional keyword with an empty page, and even when a source does return something, the
 * character-by-character comparison in [LyricsMatch] scores it zero, so the track ends up with no
 * lyrics while every individual step still looks like it worked.
 *
 * ICU ships the conversion on Android 10 and later. The table below is only the fallback for
 * older releases, and only carries the characters that actually show up in song titles and
 * artist names.
 */
internal object HanziFold {
    private val icu by lazy(LazyThreadSafetyMode.NONE) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Not available before API 29, and absent from the JVM unit tests, hence runCatching.
            runCatching { android.icu.text.Transliterator.getInstance("Traditional-Simplified") }
                .getOrNull()
        } else {
            null
        }
    }

    private val table: Map<Char, Char> by lazy(LazyThreadSafetyMode.NONE) {
        TRADITIONAL.zip(SIMPLIFIED).toMap()
    }

    fun fold(value: String): String {
        if (value.isEmpty()) return value
        icu?.let { transliterator ->
            runCatching { transliterator.transliterate(value) }.getOrNull()?.let { return it }
        }
        if (value.none { it in table }) return value
        return buildString(value.length) {
            for (character in value) append(table[character] ?: character)
        }
    }

    private val TRADITIONAL =
        "乾來倆倉個們倫偉側傑備傳傷僅價儀儂億優儲兒內兩冊凍凱別則創劃劇劉劍劑勁動務勞勢勳匯區協厭厲參叢員" +
        "問啟單嗎嘆嘗噓噸嚇嚨嚮囈圍園圖團執塵墊壓壞壟壯壽夠夢夾奧奪妝媽嬌孫學實寫寶將專尋對導屆屬島嶺嶽巔" +
        "師帳帶幫幹庫廟廠廢廣張彈後徑從徹恆恥悅愛態慶憶懷懸戀戰戲戶掃掛採揚換揮損搖搗撥撫擇擊擋擔據擠擬擴" +
        "擺擾攜敗敵數斷於昇時晉暫曆曉書會東棄楊極樂樓標樣樹橋機橫檔檢檯歐歡歲歸殘殺毀氣氫淚淨淵湯準溝溫滅" +
        "滯漢潔濃濕濟濱灑灘灣為無煉煙煩燈燒營牆猶獎獨獲獵獻現瑤瑩瑪環瓊產畢畫當疊痺瘋療癡發盜盞盧瞭矚確碼" +
        "礎禍禮禱種積穩窮竄筆節範築簡籃粵糧紀約紅紋純紙級終結給經綠緊練總織繞繪繼續羅習聲聽臟臺與舊華萬葉" +
        "蕭蘇蘭處號蝦蟲衛衝補裝製複見規親覺觀訂計訓記訪設許診註評詞試詩話該認語誤說誰課調談請論諷謀講謝證" +
        "識譚譜議護讀變讓讚豬貓貝負財貢貧貨販責貴買費貼資賈賓賜賞賠賣質賴贊趕趙躍車軌軍軒軟轉轟辦辭農迴這" +
        "過遙遞遠適選邁還邊郵鄉鄧鄭醜醫釀釋針釣鈴銀鋒錢錯鍵鍾鐘鐵長門閃閉開閒間閱闆關陣陰陳陽隊階際隨險隱" +
        "雖雙雜雞離難雲電霧靈靜響頁頂項順須預頭題顏願類風飄飛飯飲養餘館馬馮駕騎騙驕驗驚骯髒體髮鬆鬍鬢鬥鬧" +
        "魚魯鮮鳥鳳鳴鴨鴻鵲鷹麗麥麵麼黃點黨齊齋齒齡龍龜"

    private val SIMPLIFIED =
        "干来俩仓个们伦伟侧杰备传伤仅价仪侬亿优储儿内两册冻凯别则创划剧刘剑剂劲动务劳势勋汇区协厌厉参丛员" +
        "问启单吗叹尝嘘吨吓咙向呓围园图团执尘垫压坏垄壮寿够梦夹奥夺妆妈娇孙学实写宝将专寻对导届属岛岭岳巅" +
        "师帐带帮干库庙厂废广张弹后径从彻恒耻悦爱态庆忆怀悬恋战戏户扫挂采扬换挥损摇捣拨抚择击挡担据挤拟扩" +
        "摆扰携败敌数断于升时晋暂历晓书会东弃杨极乐楼标样树桥机横档检台欧欢岁归残杀毁气氢泪净渊汤准沟温灭" +
        "滞汉洁浓湿济滨洒滩湾为无炼烟烦灯烧营墙犹奖独获猎献现瑶莹玛环琼产毕画当叠痹疯疗痴发盗盏卢了瞩确码" +
        "础祸礼祷种积稳穷窜笔节范筑简篮粤粮纪约红纹纯纸级终结给经绿紧练总织绕绘继续罗习声听脏台与旧华万叶" +
        "萧苏兰处号虾虫卫冲补装制复见规亲觉观订计训记访设许诊注评词试诗话该认语误说谁课调谈请论讽谋讲谢证" +
        "识谭谱议护读变让赞猪猫贝负财贡贫货贩责贵买费贴资贾宾赐赏赔卖质赖赞赶赵跃车轨军轩软转轰办辞农回这" +
        "过遥递远适选迈还边邮乡邓郑丑医酿释针钓铃银锋钱错键钟钟铁长门闪闭开闲间阅板关阵阴陈阳队阶际随险隐" +
        "虽双杂鸡离难云电雾灵静响页顶项顺须预头题颜愿类风飘飞饭饮养余馆马冯驾骑骗骄验惊肮脏体发松胡鬓斗闹" +
        "鱼鲁鲜鸟凤鸣鸭鸿鹊鹰丽麦面么黄点党齐斋齿龄龙龟"
}
