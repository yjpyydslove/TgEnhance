# TgEnhance

Telegram 客户端的本地增强模块，基于 **LSPosed / Xposed**，不改动 Telegram 安装包本体，运行时挂载。

所有代码在 GitHub Actions 上云端编译，本地无需 Android SDK。

---

## 功能

按模块设置界面里的分组顺序排列，每一项都能单独开关。

### 多账号

| 项 | 说明 |
|---|---|
| 账号上限提升 | Telegram 原生限制为免费 3 个、会员 5 个账号。开启后可自行设置 3 – 16 个 |

实现上需要三处同时处理，只改一处无效（详见下方「实现要点」）。

### 界面与主题

| 项 | 说明 |
|---|---|
| 在 Telegram 设置里添加入口 | 在 Telegram 自己的设置页底部加一行「模块设置」，点击直接跳回本模块 —— 桌面图标隐藏后尤其有用 |
| 使用系统字体 | 把 Telegram 内嵌的 Roboto 替换为系统字体，代码块仍保留等宽 |
| 隐藏 Stories | 收起聊天列表顶部的 Stories 环与相关入口 |

### 网络

| 项 | 说明 |
|---|---|
| 阻止代理连通性探测 | Telegram 添加代理前会先发一次**不走代理**的探测请求，可被恶意代理链接用来套取真实出口 IP。开启后跳过该探测 |
| 阻止媒体自动下载 | 收到的媒体不再自动下载，只在手动点击时才下 —— 省流量 |
| 禁用自动播放 | 聊天里的 GIF 与视频不再自动播放，省流量也更省电 |

### 隐私与本地增强

| 项 | 说明 |
|---|---|
| 隐藏「正在输入 / 录音中」 | 不再向对方发送实时输入状态 |
| 防撤回 | 拦截服务器下发的删除请求，对方撤回的消息保留在你的记录里 |
| 不上报已读回执 | 读完消息不向服务器发送已读位置，对方看不到你的已读状态 |
| 隐藏在线状态 | 不上报「我在线」，对方看到你一直离线 |
| 隐藏对方的在线状态 | 反过来：对方明明在线，你这边也只显示「最近上线」 |
| 隐藏对方的最后上线时间 | 对方的状态整体显示为「很久以前」，不透露最后上线于何时 |
| 隐藏手机号 | 资料页里的手机号只保留末 4 位 |

### 广告屏蔽

| 项 | 说明 |
|---|---|
| 屏蔽赞助消息 | 聊天列表与频道内的推广内容不再请求、不再显示 |

默认关闭，开启前会说明「这会减少 Telegram 从你这里获得的广告收入」——
这是本模块唯一一项与收入相关的功能，因此刻意做得显眼。

### 高级功能

| 项 | 说明 |
|---|---|
| 强制平板布局 | 让手机也用上 Telegram 的平板界面（左右分栏） |
| 关闭更新提示 | 不再提示有新版本可用 |

这一组在设置界面里**默认收起** —— 普通用户用不上，误开后也较难自己排查。

### 反检测

| 项 | 说明 |
|---|---|
| 隐藏模块痕迹 | 让宿主应用看不出自己跑在 Xposed 上（详见下表） |
| 隐藏模块桌面图标 | 桌面不再显示本模块图标，Telegram 不受影响 |

「隐藏模块痕迹」覆盖的探测面：

| 探测手法 | 处理 |
|---|---|
| `Class.forName` / `loadClass` 查框架类 | 直接抛 `ClassNotFoundException` |
| `Throwable` / `Thread.getStackTrace()` 取栈 | 剔除框架帧后再返回 |
| `Thread.getAllStackTraces()` 全线程扫栈 | 逐线程剔除框架帧 |
| `printStackTrace` 输出里的框架帧 | 在取栈处剔除（它不经过 `getStackTrace`） |
| `getPackageInfo` 查模块包名 | 抛 `NameNotFoundException` |
| `getApplicationInfo` 读模块 meta-data | 同上 —— meta-data 是「这是 Xposed 模块」最直接的证据 |
| `getInstalledPackages` / `getInstalledApplications` 列全部应用 | 从结果里滤掉模块条目 |
| `Method.getModifiers()` 找 native 标志 | 只对被本模块 hook 过的方法修正 |

开启「隐藏模块痕迹」后，**自检报告里会出现一行反检测验证结果** ——
用上述同样的手法反过来探测自己，有任何一项没挡住都会点名列出来。
不需要你去猜它到底有没有生效。

只处理**本模块自己的痕迹**，不伪造 `Build` 属性、不隐藏 root、不动 `/proc/self/maps` ——
那属于全局环境改造，不该由一个 Telegram 增强模块代劳。
「抹除 native 标志」也只针对本模块 hook 过的方法，不做全量过滤 ——
全量过滤会把 Telegram 自己真正的 native 方法也伪装成 Java 方法，反而制造出新的异常特征。

---

## 配置即时生效

v2.0.0 起，所有 Hook 都**常驻挂载**，开关移到回调里实时读取。配合 Telegram
主界面 `onResume` 时的配置重载，**改完设置切回 Telegram 就生效，不用重启**。

只有少数依赖启动期状态的项例外（例如账号数组已经分配过的容量），这类改动
仍需重启 Telegram。

---

## 运行状态

判断功能是否**真的生效**，不必再去翻 LSPosed 日志 —— 设置界面底部有「运行状态」卡片：

```
报告时间 21:45:03
（Telegram 每次切换前后台都会刷新）

● 账号上限查询  已触发 12 次
● 配置热更新  已触发 4 次
○ 拦截撤回  未触发
...
```

原理：hook 端（Telegram 进程）把触发计数通过**显式广播**回传给设置界面，
令牌校验来源后落盘展示。之所以用广播，是因为两个进程不同 uid ——
模块私有目录 Telegram 没有权限，而 LSPosed 暴露的 `XSharedPreferences` 是只读的。

**怎么读这张表**

| 现象 | 含义 |
|---|---|
| 有数据、多数项已触发 | 模块工作正常 |
| 某功能开着但该项长期「未触发」 | 操作时没走到该路径，或 Hook 点已随版本失效 |
| 完全收不到报告 | LSPosed 里模块未启用 / 作用域没勾 Telegram |

「未触发」只说明这段时间没出现过对应动作（例如没人撤回消息），**不等于功能失效**。

---

## 风险提示

带副作用的功能在设置界面**开启时**会弹窗说明并要求确认，不会静默生效：

- **防撤回**：副作用是你自己也删不掉「为所有人删除」的消息；同时保留他人撤回内容可能涉及隐私与取证合规，请仅用于个人设备上的正当用途。
- **隐藏正在输入**：对方将完全看不到你的输入状态，会影响沟通预期。
- **不上报已读回执**：对方永远看不到你读过消息，群里的已读人数也不会增加；服务器侧仍认为这些消息未读，换设备或重新登录时未读标记可能重新出现，频道/群组未读计数会在服务器侧累积。
- **账号上限提升**：会扩容 Telegram 内部账号实例数组，少数版本上超出原生上限的账号可能不稳定，建议先保持默认值 6。

---

## 合规边界

本模块的定位是**本地体验增强**。其中有一项涉及收入，单独说明。

### 广告屏蔽（唯一一项与收入相关的功能）

- **默认关闭**，开启前弹窗说明「这会减少 Telegram 从你这里获得的广告收入」
- 实现方式是**客户端不再请求**赞助内容（`getSponsoredMessages` 直接短路），
  不是伪装、也不是劫持响应
- 只处理聊天列表与频道内的赞助消息，不做视频贴片等其他广告形式

这一项的存在感是刻意做明显的：它不是「顺手帮你屏蔽掉」，
而是「你明确知道并选择之后才生效」。

### 明确不做的事

- **不伪造 Telegram Premium** —— 不伪造会员状态、不本地解锁会员功能
- **不绕过内容保存 / 转发限制** —— 属于绕过内容保护
- **不阻止阅后即焚媒体被删除** —— 保留他人以为会消失的内容，伦理上不该由本模块代劳
- **不做批量群发、自动化推广**等可能被滥用的能力

---

## 设置界面

界面按 **Telegram 官方客户端**的观感重做，不是通用的 Material 设置页：

| 元素 | 对齐官方的做法 |
|---|---|
| 配色 | 浅色取官方 Day 主题主色 `#3390EC`；深色跟随官方 Night 主题的紫色 `#8774E1` |
| 分组 | 12dp 圆角卡片，左右各留 16dp；分组标题用主色，位于卡片上方 |
| 分割线 | 从文字左侧 16dp 起（不是通栏），颜色是 6% 前景色而非实色灰 |
| 开关 | 自绘控件 `TgSwitch`：滑块直径 28dp **大于**轨道高度 22dp，带投影，支持点击切换与横向拖拽 |
| 滑条 | 4dp 细轨道 + 圆形滑块，两端按滑块半径内缩 |
| 对话框 | 16dp 圆角卡片，按钮用主色 |

开关之所以不用系统 `Switch`：它的 thumb / track 尺寸关系在所有 ROM 上是固定的，改不出 TG 「滑块外扩」的效果，且不同厂商 ROM 的默认外观差异极大。

深色模式跟随系统，切换时界面会自动重建以刷新取色。

功能变多之后，顶部固定了一个搜索框（按标题与说明即时过滤，命中的分组之外整组隐去），
点击分组标题可以折叠该组 —— 折叠只收起卡片、保留标题，方便点回去展开。
「高级功能」与「诊断」两组**默认收起**（日用版的定位是装上就忘掉它），
点开一次后会被记下来，之后一直保持展开。
搜索与「只看已开启」时无视折叠状态 —— 否则会出现「搜到了结果、却因为它恰好在收起的分组里而看不见」。

首次打开设置页会弹一次引导，把「日用配置」真正写进配置文件
（详见 N1 的版本说明：默认值只写在注册表里的话，hook 端读到的仍是「未设置」= 关闭）。

**入口不止桌面图标一个。**开启「在 Telegram 设置里添加入口」后，
Telegram 自己的设置页最下面会多出一行「模块设置」，点一下就直接跳回本模块。
这一行是**额外**加的，不占用也不改动 Telegram 原有的任何条目 ——
所以就算哪天这项功能在你的客户端上失效，也只是这一行不出现，设置页本身一切照旧。

---

## 实现要点

### 功能注册表（v3.0.0）

「配置 key / 界面标题 / 说明文案 / 风险等级 / 关闭全部功能是否包含它」原本分散在
`Prefs`、`SettingsActivity`、`HookStats.expect` 三处，新增一个功能要改五六个文件，
漏一处就会出两种典型故障：**开关点了没反应**（key 没进配置清单）、
或**界面直接显示 `privacy.foo` 这种原始 key**。

现在集中声明在 `core/FeatureSpec.kt` 的一张表里：

```kotlin
FeatureSpec(
    key = Prefs.BLOCK_READ_RECEIPT,
    group = FeatureGroup.PRIVACY,
    title = "不上报已读回执",
    summary = "读完消息不向服务器发送已读位置，对方看不到你的已读状态。",
    risk = RiskLevel.HIGH,
    riskMessage = "开启后…",
)
```

界面、内存快照的 key 清单、导入配置时的风险确认名单、「关闭全部功能」的范围
全部由它派生，**结构上不可能不一致**。

新增功能只需两步：注册表加一条 + 在对应 `*Hooks` 里加 hook 点。

### 为什么不能只改 `MAX_ACCOUNT_COUNT`

Telegram 的账号体系是一组同构单例：

```java
private static volatile Xxx[] Instance = new Xxx[MAX_ACCOUNT_COUNT];
public static Xxx getInstance(int num) { ... Instance[num] ... }
```

而

```java
public final static int MAX_ACCOUNT_COUNT = 4;
```

是**编译期常量**，会被内联进每一处引用 —— 反射改字段值对方法体里早已写死的 `4` 毫无作用。

真正的上限由两处共同决定：

1. UI 层 `UserConfig.getMaxAccountCount()`（免费 3 / 会员 5）
2. 内存层各 `Instance[]` 数组的容量

因此本模块三管齐下：接管 `getMaxAccountCount()`、对每个管理器类的 `getInstance(int)` 挂前置钩子按需扩容数组、重写被常量写死循环上界的统计方法。

扩容采用「按需」而非「启动即扩容」：类以 `Class.forName(name, false, loader)` 加载（**不触发静态初始化**），只有真的用到第 N 个账号时才动数组 —— 避免启动期强行拉起整条 Manager 链。

### Hook 点依据

所有 Hook 点均取自 Telegram 官方源码（`DrKLO/Telegram`），而非猜测。
下表是**逐条核对过**的结果（v N1.14）：把项目里每个 `HookFinder.match`
的过滤条件，与源码里的真实签名比对了一遍。

| Hook 点 | 源码位置 | 过滤条件 | 核对 |
|---|---|---|---|
| `UserConfig.getMaxAccountCount()` | `UserConfig.java:124` | 返回 `int` | ✅ |
| `UserConfig.getActivatedAccountsCount()` | `UserConfig.java:101` | 返回 `int` | ✅ |
| `UserConfig.hasPremiumOnAccounts()` | `UserConfig.java:115` | 返回 `boolean` | ✅ |
| 各 `getInstance(int)` / `Instance[]` | `AccountInstance.java` 等 | — | — |
| `MessagesController.sendTyping(...)` | `MessagesController.java:11381 / 11385` | 返回 `boolean`，参数 ≥3 | ✅ |
| `MessagesController.deleteMessages(...)` | `MessagesController.java:9307+`（4 个重载） | 参数 ≥2 | ✅ |
| `MessagesController.completeReadTask(ReadTask)` | `MessagesController.java:14559`（**private**） | — | ✅ |
| `MessagesController.updateTimerProc()` | `MessagesController.java:10520` | — | ✅ |
| `MessagesController.getSponsoredMessages(long)` | `MessagesController.java:21612` | — | ✅ |
| `ConnectionsManager.checkProxy(...)` | `ConnectionsManager.java:739` | — | ✅ |
| `AndroidUtilities.getTypeface(String)` | `AndroidUtilities.java:2393` | — | ✅ |
| `AndroidUtilities.isTabletForce()` / `isTabletInternal()` | `AndroidUtilities.java:2948 / 2952` | 返回 `boolean` | ✅ |
| `SharedConfig.isAutoplayVideo()` / `isAutoplayGifs()` | `SharedConfig.java:740 / 744` | 返回 `boolean` | ✅ |
| `SharedConfig.isAppUpdateAvailable()` | `SharedConfig.java:777` | 返回 `boolean` | ✅ |
| `DownloadController.canDownloadMedia(...)` | `DownloadController.java:609 / 627` | — | ✅ |
| `StoriesController.has*()` | `StoriesController.java:254 / 287 / 1076 / 1342 / 1754` | 返回 `boolean` | ✅ |
| `LocaleController.formatUserStatus(...)` | `LocaleController.java:2888` | — | — |
| `PhoneFormat.format(String)` | `PhoneFormat.java:187` | 返回 `String`，1 参 | ✅ |

**两处值得单独记下的：**

- `ConnectionsManager.checkProxy(...)` 返回 **`long`**，所以回调里写的是
  `param.result = 0L`。若写成 `0`（`Int`）会抛 `ClassCastException` ——
  这类错误只在真机上炸，静态检查看不出来。
- `MessagesController.completeReadTask(ReadTask)` 是 **private** 方法。
  项目的 `hookAllByName` 按名字挂载所有重载、**不看可见性**，所以能挂上；
  但按「public 方法」去找会一直找不到它。

**没打勾的两行**是这次没逐条核的（`AccountInstance` 那组是数组扩容、
`formatUserStatus` 项目本身没加过滤条件），它们的存在性已由挂载期自检覆盖。

---

## 构建

推送到 `main` 自动编译并上传 artifact；打 `v*` 标签会额外发布 Release。

```bash
git tag v1.0.0 && git push origin v1.0.0
```

版本号在 `gradle.properties` 的 `VERSION_NAME` / `VERSION_CODE` 统一维护。

---

## 安装

1. 设备需已 root 并安装 LSPosed（Zygisk 版）
2. 安装 Release 中的 APK
3. 在 LSPosed 管理器中启用本模块，作用域勾选 Telegram
4. 打开模块图标进入设置界面调整功能
5. 从最近任务中划掉 Telegram 再重新打开（无需重启手机）

---

## 快速上手

装好之后**不需要逐项配置**：首次打开设置界面会弹一次引导，
选「应用日常配置」即可，之后正常用 Telegram 就行。

想自己调整时再往下看。

### 日用配置包含什么

| 分组 | 项 | 说明 |
|---|---|---|
| 多账号 | 启用多账号上限提升 | 上限提到 6，可在该组内用滑条调 |
| 界面与主题 | 启用界面定制 | 该组的总开关，具体项自行决定 |
| 界面与主题 | 在 Telegram 设置里添加入口 | Telegram 设置页底部多一行「模块设置」，点了跳回本模块 |
| 网络 | 启用网络增强 | 同上 |
| 高级功能 | 关闭更新提示 | 不再弹新版本提示 |
| 高级功能 | 详细日志 | 排查问题时才有用 |
| 高级功能 | 隐藏模块痕迹 | 挡掉宿主对 Xposed 的探测 |
| 诊断 | 输出诊断日志 | 挂载自检，反馈问题时用得上 |

**不包含**：隐私与本地增强整组、广告屏蔽整组，
以及「使用系统字体」「隐藏 Stories」这类纯外观偏好。

### 想逐项验证 Hook 是否生效

1. 打开 LSPosed 管理器 → 日志，搜索 `TgEnhance`，
   确认出现 `RESULT [界面] ...` 挂载日志。
2. 随便用一用 Telegram，回到模块设置界面看「运行状态」卡片 ——
   这里会列出每个 Hook 点被触发了多少次。
3. 出现「首次触发」说明该 Hook **确实作用在活跃路径上**。
   只有挂载日志、始终没有首次触发，则说明 Hook 点已随版本变动失效。

**日志里三类关键字：**

| 关键字 | 含义 |
|---|---|
| `RESULT [诊断]` | 挂载期探测：目标类 / 方法是否存在 |
| `RESULT [首次触发]` | 运行期：该 Hook 第一次被真正调用 |
| `RESULT [统计]` | 启动 90 秒后的汇总计数 |

---

## 反馈问题

提供以下四项基本可以一次定位：

1. **日志**：LSPosed 管理器 → 日志，搜索 `TgEnhance`，从 `===== TgEnhance =====` 起整段复制
2. **Telegram 版本号**：日志第一行的 `已挂载到 xxx (版本)` 里就有
3. **模块版本**：设置界面底部
4. **具体现象**：哪个开关、做了什么操作、期望什么、实际什么

有了 1 和 2，Hook 点要不要重新定位就是确定的事，不必靠猜。

---

## 诊断与版本适配

Hook 点依赖 Telegram 的类名与方法签名，这些会随版本变动而失效。为让适配不依赖反复试错，模块提供**两层可观测性**：

**1. 挂载期探测** —— 启动时主动探测所有关键类与方法是否存在，输出签名命中情况与各功能组的挂载结果。

**2. 运行期触发计数** —— 仅仅「挂上了」不等于「被调用了」。版本不匹配时最隐蔽的失败形态是：类名签名全对、挂载日志一切正常，但实际调用点早已改走别的路径，模块看起来工作正常却毫无效果。因此模块会在每个 Hook 点**第一次被触及时立即输出一条 `RESULT [首次触发]`**，并在启动 **90 秒后**输出汇总计数，**计数为 0 即代表该功能未真正生效**，无需依赖现象描述。

查看方式：**LSPosed 管理器 → 日志**，搜索关键字 `TgEnhance`。

反馈问题时把 `诊断报告` 与 `Hook 触发统计` 两段日志一起贴出来，即可精确定位需要修正的 Hook 点。

### 已知的 Hook 点失效风险

按可靠性从高到低排列，便于出问题时优先怀疑：

| 功能 | Hook 点 | 风险 |
|---|---|---|
| 多账号 | `UserConfig.getMaxAccountCount()` + `Instance[]` 扩容 | 低（结构稳定） |
| 系统字体 | `AndroidUtilities.getTypeface()` | 低 |
| 阻止代理探测 | `ConnectionsManager.checkProxy()` | 低 |
| 隐藏输入状态 | `MessagesController.sendTyping()` | 中（重载可能增减） |
| 防撤回 | `MessagesController.deleteMessages()` | 中（重载有 4 层嵌套） |
| 隐藏 Stories | `StoriesController` 布尔查询 | 较高（类名/方法变动较频繁） |

`隐私` 两项默认关闭，且开启时弹窗确认；`Stories` 若日志显示 `ui.stories` 计数为 0，说明该类已变动，需要重新定位。

**官方改名之后会怎样。** 每个 Hook 点都有精确名单 + 特征兜底两层，
行为在 N1.3 之后是明确的：

- 精确名单有命中 → 用精确的，**不涉及任何其他方法**。
- 精确名单落空、有特征 → 兜底接住，并在自检里标为「靠特征命中」，
  提示这个名字已经和官方源码对不上。
- 精确名单落空、**没有特征**（强制平板、关闭更新提示、隐藏手机号这三项）→
  不做任何 hook，改为在「运行状态」里显示「当前客户端不支持」。
  这是有意为之：**宁可功能失效，也不要乱 hook 一批无关方法。**
  界面错乱比功能不可用难排查得多。

---

## 签名说明

**密钥不再随仓库公开**（v N2.2 起）。

最初把自签名密钥连同密码一起提交进仓库，是为了让 CI 每次构建的签名一致、
从而支持覆盖升级。但它有个当时没想周全的副作用：

> 任何人拿到那个密钥，都能签一个**篡改过的 APK** ——
> 而因为签名相同，它可以**覆盖升级**已装的版本。

一个人自己用时问题不大；一旦有人从仓库下载，这个风险就从理论变成了现实。
所以现在改成：

| | 做法 |
|---|---|
| 密钥文件 | 由 GitHub Secret `KEYSTORE_BASE64` 注入，CI 里解码回 `keystore/tgenhance.p12` |
| 密钥密码 | 由 Secret `KEYSTORE_PASSWORD` 注入，走环境变量 |
| 仓库里 | 只有 `.gitignore` 里的一行 `keystore/`，**没有任何密钥材料** |

workflow 里对 Secret 做了**存在性检查**：没配就明确报错退出，
而不是悄悄退回 debug 签名 —— 那样会产出一个无法覆盖升级的包，
问题要到用户安装时才发现。

**自己在本机构建**：把 p12 放到 `keystore/tgenhance.p12`、设好
`KEYSTORE_PASSWORD` 环境变量即可（没设时回退到一个占位密码，仅本机可用）。

> ✅ **签名没有变化，不需要卸载重装。** 换的是密钥的**打包格式**，
> 证书本身沿用同一对，指纹一致（`5A:97:5B:0E:…:2D:E8:2D:E3`）——
> 之前装的版本**仍可覆盖升级**。
>
> （最初以为换密钥必然改签名，写成了「需要先卸载再装」；实际核对指纹后
> 发现并没有变，这里改正。）

---

## 版本历史

完整记录见 **[CHANGELOG.md](CHANGELOG.md)**（从 v1.x 到当前版本，
每版做了什么、为什么这么做）。

当前版本见 `gradle.properties` 里的 `VERSION_NAME`；
历史版本与 APK 下载见
[Releases](https://github.com/yjpyydslove/TgEnhance/releases)。

---

## 常见问题

**「运行状态」里某项显示「未触发」，是坏了吗？**
不一定。它只代表这段时间没出现过对应动作（例如没人撤回消息）。
真正要怀疑的是：你明确做了对应操作（比如自己删了一条「为所有人删除」的消息），
计数仍然是 0 —— 那才是 Hook 点随版本失效。

**自检显示某个 Hook 点没匹配上？**
先看它给出的「相关项」候选，多半是官方改了方法名，一眼就能对上。

**支持哪些客户端？**
只要加载 `org.telegram.messenger.UserConfig` 的客户端都支持，包括改过包名的第三方 fork。
Telegram X 是另一套架构，不支持。

**能隐藏 root 吗？**
不能，而且**不建议**在本模块里做。

root 隐藏是**全局需求** —— 银行、支付类应用都会检测，而本模块只在 Telegram
进程内运行。在这里做只能骗过 Telegram 一个应用，其它应用照样检测得到。

正确做法是用系统层的隐藏方案：KernelSU / Magisk 生态里的 **Shamiko**、
**Zygisk Assistant** 之类。它们在 Zygote 层就把 root 痕迹卸掉，
覆盖全部应用，也比在单个 App 里 hook 文件读取彻底得多。

本模块的边界是**只处理自己的痕迹**（见「反检测」一节）：不伪造 `Build` 属性、
不隐藏 root、不动 `/proc/self/maps`。那些属于全局环境改造，出错会波及整机，
不该由一个 Telegram 增强模块代劳。

**能解除 +86 风控、或免费解锁 Premium 吗？**
技术上做不到，本模块也不做。

这两类判断都在 Telegram **服务器侧**：区号相关的账号限制、账号信誉、
会员权益，全部由后端根据你的账号状态决定。客户端只是个渲染层 ——
把提示文字改掉，服务器该拒绝还是会拒绝，只是让错误更难理解。

至于免费解锁 Premium，那属于绕过付费，是本项目明确不碰的范围
（见「合规边界」）。本模块只做本地行为调整与显示层面的增强。

**怎么反馈问题？**
设置界面 →「运行状态」→「分享诊断报告」，把导出的文本发出来即可，
已包含版本、配置与自检结果。

## 参考与致谢

本项目是**独立实现**，不是任何项目的分支，也没有复制它们的代码。
功能方向上参考了以下开源模块（均为 GPL-3.0）：

| 项目 | 作者 | 本项目参考了什么 |
|---|---|---|
| [TeleVip-LSPosed](https://github.com/mustafa1dev/TeleVip-Lsposed) | mustafa1dev | 隐私功能的分类方式（已读 / 输入状态 / 在线状态），以及「多客户端适配」这件事必须认真做 |
| [Telegami](https://github.com/aoya111/Telegami) | aoya111 | 同为 Kotlin 重写，印证了针对 fork 客户端做类路径探测的必要性 |
| [NoAdsTelegram](https://modules.lsposed.org/module/ai.noads.NoAdsTelegram) | — | 「按类别分组设置项」的界面组织方式 |

### 与它们刻意划开的边界

| 它们做的事 | 本模块 |
|---|---|
| 伪造 Telegram Premium、本地解锁会员功能 | **不做** —— 不伪造会员状态 |
| 去除赞助消息 / 广告 | 做，但**默认关闭**、开启前说明影响 —— 本模块唯一一项与收入相关的功能 |
| 绕过内容保存 / 转发限制 | **不做** —— 属于绕过内容保护 |
| 阻止阅后即焚媒体被删除 | **不做** —— 保留他人以为会消失的内容，伦理上不该由本模块代劳 |
| 隐藏手机号 | 做，但用**遮罩**保留末 4 位，而不是直接抹掉 |
| 隐藏 Stories / 输入状态 / 在线状态、防撤回 | 做 |

如果只想要「破解会员」这类功能，请看上面那几个项目 ——
本模块的定位是**本地体验增强**，不涉及任何商业化能力的绕过。

另外：这些模块都只适配 README 里列出的**特定客户端版本**，换版本就可能失效。
本模块在这方面的做法不同 —— 用候选类路径与特征匹配来扩大适配面，
并在自检里明确告知「哪些功能在当前客户端不可用」，而不是让用户自己试。

---

## 免责声明

本项目仅供个人学习与自用。使用本模块产生的任何后果由使用者自行承担。请遵守所在地区的法律法规以及 Telegram 的服务条款。
