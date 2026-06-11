package com.xiaozhi.aiservice.data.remote.mcp

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * 常用 app 包名映射表（中文/英文名 → 包名）
 *
 * 覆盖主流应用，让 LLM 只需传"微信"就能找到 com.tencent.mm。
 * 查不到的走设备已装应用模糊匹配 → 都没有就跳应用商店按名搜索。
 */
private val APP_PACKAGE_MAP: Map<String, String> = mapOf(
    // ===== 社交 =====
    "微信" to "com.tencent.mm",
    "wechat" to "com.tencent.mm",
    "weixin" to "com.tencent.mm",
    "qq" to "com.tencent.mobileqq",
    "企业微信" to "com.tencent.wework",
    "wework" to "com.tencent.wework",
    "钉钉" to "com.alibaba.android.rimet",
    "dingding" to "com.alibaba.android.rimet",
    "dingtalk" to "com.alibaba.android.rimet",
    "飞书" to "com.ss.android.lark",
    "lark" to "com.ss.android.lark",
    "feishu" to "com.ss.android.lark",
    "微博" to "com.sina.weibo",
    "weibo" to "com.sina.weibo",

    // ===== 电商 =====
    "淘宝" to "com.taobao.taobao",
    "taobao" to "com.taobao.taobao",
    "天猫" to "com.tmall.wireless",
    "tmall" to "com.tmall.wireless",
    "京东" to "com.jingdong.app.mall",
    "jd" to "com.jingdong.app.mall",
    "jingdong" to "com.jingdong.app.mall",
    "拼多多" to "com.xunmeng.pinduoduo",
    "pdd" to "com.xunmeng.pinduoduo",
    "pinduoduo" to "com.xunmeng.pinduoduo",
    "唯品会" to "com.achievo.vipshop",
    "vipshop" to "com.achievo.vipshop",
    "得物" to "com.shizhuang.duapp",
    "闲鱼" to "com.taobao.idlefish",
    "xianyu" to "com.taobao.idlefish",

    // ===== 外卖/出行 =====
    "美团" to "com.sankuai.meituan",
    "meituan" to "com.sankuai.meituan",
    "饿了么" to "me.ele.android",
    "eleme" to "me.ele.android",
    "大众点评" to "com.dianping.v1",
    "dianping" to "com.dianping.v1",
    "滴滴" to "com.sdu.dididaida",
    "didi" to "com.sdu.dididaida",
    "高德地图" to "com.autonavi.minimap",
    "高德" to "com.autonavi.minimap",
    "amap" to "com.autonavi.minimap",
    "百度地图" to "com.baidu.BaiduMap",
    "腾讯地图" to "com.tencent.map",

    // ===== 短视频/内容 =====
    "抖音" to "com.ss.android.ugc.aweme",
    "douyin" to "com.ss.android.ugc.aweme",
    "tiktok" to "com.ss.android.ugc.aweme",
    "快手" to "com.smile.gifmaker",
    "kuaishou" to "com.smile.gifmaker",
    "小红书" to "com.xingin.xhs",
    "xhs" to "com.xingin.xhs",
    "rednote" to "com.xingin.xhs",
    "哔哩哔哩" to "tv.danmaku.bilibili",
    "bilibili" to "tv.danmaku.bilibili",
    "b站" to "tv.danmaku.bilibili",
    "知乎" to "com.zhihu.android",
    "zhihu" to "com.zhihu.android",
    "今日头条" to "com.ss.android.article.news",
    "toutiao" to "com.ss.android.article.news",
    "西瓜视频" to "com.ss.android.article.video",
    "腾讯视频" to "com.tencent.qqlive",
    "爱奇艺" to "com.qiyi.video",
    "iqiyi" to "com.qiyi.video",
    "优酷" to "com.youku.phone",
    "youku" to "com.youku.phone",
    "芒果tv" to "com.hunantv.imgo.activity",

    // ===== 音乐/音频 =====
    "网易云音乐" to "com.netease.cloudmusic",
    "云音乐" to "com.netease.cloudmusic",
    "网易云" to "com.netease.cloudmusic",
    "qq音乐" to "com.tencent.qqmusic",
    "酷狗" to "com.kugou.android",
    "kugou" to "com.kugou.android",
    "酷我" to "cn.kuwo.player",
    "汽水音乐" to "com.luna.music",
    "喜马拉雅" to "com.ximalaya.ting.android",
    "ximalaya" to "com.ximalaya.ting.android",
    "得到" to "com.luojilab.player",
    "小宇宙" to "com.podcast.cosmos",
    "荔枝" to "com.litchi.app",

    // ===== 支付/金融 =====
    "支付宝" to "com.eg.android.AlipayGphone",
    "alipay" to "com.eg.android.AlipayGphone",
    "微信支付" to "com.tencent.mm",
    "云闪付" to "com.unionpay",
    "工商银行" to "com.icbc",
    "icbc" to "com.icbc",
    "建设银行" to "com.chinamworld.bocmbci.ccb",
    "ccb" to "com.chinamworld.bocmbci.ccb",
    "中国银行" to "com.chinamworld.bocmbci.ccb",
    "农业银行" to "com.android.bankabc",
    "招商银行" to "cmb.pb",
    "交通银行" to "com.bankcomm.Bankcomm",
    "平安银行" to "com.pingan.mobile.bank",
    "同花顺" to "com.hexin.plat.android",
    "东方财富" to "com.eastmoney.android.berlin",

    // ===== 工具/系统 =====
    "设置" to "com.android.settings",
    "相机" to "com.android.camera",
    "相册" to "com.android.gallery3d",
    "文件管理" to "com.android.documentsui",
    "计算器" to "com.android.calculator2",
    "时钟" to "com.android.deskclock",
    "闹钟" to "com.android.deskclock",
    "日历" to "com.android.calendar",
    "录音机" to "com.android.soundrecorder",
    "浏览器" to "com.android.browser",
    "下载" to "com.android.providers.downloads.ui",
    "应用商店" to "com.oppo.market",
    "oppo商店" to "com.oppo.market",
    "小米商店" to "com.xiaomi.market",
    "华为商店" to "com.huawei.appmarket",
    "google play" to "com.android.vending",
    "play商店" to "com.android.vending",

    // ===== 办公/学习 =====
    "wps" to "cn.wps.moffice_eng",
    "wps office" to "cn.wps.moffice_eng",
    "金山文档" to "cn.wps.yun",
    "腾讯文档" to "com.tencent.docs",
    "石墨文档" to "com.shimo.shimowendang",
    "notion" to "notion.id",
    "obsidian" to "md.obsidian",
    "印象笔记" to "com.yinxiang",
    "有道云笔记" to "com.youdao.note",
    "onenote" to "com.microsoft.office.onenote",
    "邮件" to "com.android.email",
    "gmail" to "com.google.android.gm",
    "outlook" to "com.microsoft.office.outlook",
    "百度网盘" to "com.baidu.netdisk",
    "阿里云盘" to "com.alicloud.databox",
    "腾讯微云" to "com.tencent.weiyun",
    "keep" to "com.gotokeep.keep",
    "微信读书" to "com.tencent.weread",
    "豆瓣" to "com.douban.book.reader",
    "douban" to "com.douban.book.reader",
    "扇贝" to "com.shanbay.words",

    // ===== 出行/旅游 =====
    "12306" to "com.MobileTicket",
    "铁路12306" to "com.MobileTicket",
    "携程" to "ctrip.android.view",
    "ctrip" to "ctrip.android.view",
    "飞猪" to "com.taobao.trip",
    "去哪儿" to "com.Qunar",
    "qunar" to "com.Qunar",
    "同程" to "com.tongcheng.android",
    "航旅纵横" to "com.umetrip.android.msky.app",

    // ===== 通讯/输入法 =====
    "电话" to "com.android.dialer",
    "短信" to "com.android.mms",
    "通讯录" to "com.android.contacts",
    "搜狗输入法" to "com.sohu.inputmethod.sogou",
    "百度输入法" to "com.baidu.input",
    "讯飞输入法" to "com.iflytek.inputmethod",

    // ===== 健康/生活 =====
    "美柚" to "com.melody.melody",
    "keep" to "com.gotokeep.keep",
    "小米运动" to "com.xiaomi.hm.health",
    "华为运动健康" to "com.huawei.health",
    "qq邮箱" to "com.tencent.androidqqmail",
    "网易邮箱" to "com.netease.mail",

    // ===== 直播 =====
    "斗鱼" to "air.tv.douyu.android",
    "douyu" to "air.tv.douyu.android",
    "虎牙" to "com.duowan.duyuhd",
    "huya" to "com.duowan.duyuhd",
    "yy" to "com.yy.mobile.yaolai",
    "映客" to "com.meelive.ingkee",

    // ===== 游戏相关 =====
    "王者荣耀" to "com.tencent.tmgp.sgame",
    "和平精英" to "com.tencent.tmgp.pubgmhd",
    "原神" to "com.miHoYo.Yuanshen",
    "崩坏" to "com.miHoYo.honkaiimpact3rd",
    "我的世界" to "com.mojang.minecraftpe",
    "迷你世界" to "com.minitech.miniworld",
)

/**
 * 解析 app_name → package_name
 *
 * 优先级：
 * 1. 用户显式传的 package_name（精确时优先）
 * 2. 内置常用 app 映射表
 * 3. 设备已装应用按应用名模糊匹配（loadLabel 包含 appName）
 * 4. 返回 null → 调用方走商店搜索
 */
internal fun resolvePackageName(
    ctx: Context,
    appName: String,
    explicitPkg: String?
): String? {
    val pm = ctx.packageManager

    // 1. 用户显式传了包名
    if (explicitPkg != null) {
        return try {
            pm.getPackageInfo(explicitPkg, 0)
            explicitPkg
        } catch (_: PackageManager.NameNotFoundException) {
            // 设备上没装 — 仍返回这个包名，让调用方跳商店
            explicitPkg
        }
    }

    // 2. 内置映射表（精确匹配 + 小写匹配）
    val exact = APP_PACKAGE_MAP[appName]
    if (exact != null) {
        return try {
            pm.getPackageInfo(exact, 0)
            exact
        } catch (_: PackageManager.NameNotFoundException) {
            // 设备没装该包名 — 仍返回，让调用方跳商店
            exact
        }
    }
    val lower = APP_PACKAGE_MAP[appName.lowercase()]
    if (lower != null) return lower

    // 3. 设备已装应用按应用名模糊匹配（用 queryIntentActivities+MAIN/LAUNCHER 配合 manifest <queries> 解锁 Android 11+ visibility）
    val nameLower = appName.lowercase()
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolveInfos = pm.queryIntentActivities(launcherIntent, 0)
    val exactMatch = resolveInfos.firstOrNull {
        pm.getApplicationLabel(it.activityInfo.applicationInfo).toString().lowercase() == nameLower
            || it.activityInfo.loadLabel(pm).toString().lowercase() == nameLower
    }
    if (exactMatch != null) return exactMatch.activityInfo.packageName

    val containsMatch = resolveInfos.firstOrNull { ri ->
        val label = ri.activityInfo.loadLabel(pm).toString()
        label.contains(appName, ignoreCase = true) || appName.contains(label, ignoreCase = true)
    }
    if (containsMatch != null) return containsMatch.activityInfo.packageName

    return null
}
