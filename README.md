# TgEnhance

Telegram 客户端的本地增强模块，基于 **LSPosed / Xposed**，不改动 Telegram 安装包本体，运行时挂载。

所有代码在 GitHub Actions 上云端编译，本地无需 Android SDK。

---

## 功能

### 多账号

| 项 | 说明 |
|---|---|
| 账号上限提升 | Telegram 原生限制为免费 3 个、会员 5 个账号。开启后可自行设置 3 – 16 个 |

实现上需要三处同时处理，只改一处无效（详见下方「实现要点」）。

### 界面与主题

| 项 | 说明 |
|---|---|
| 使用系统字体 | 把 Telegram 内嵌的 Roboto 替换为系统字体，代码块仍保留等宽 |
| 隐藏 Stories | 收起聊天列表顶部的 Stories 环与相关入口 |

### 网络

| 项 | 说明 |
|---|---|
| 阻止代理连通性探测 | Telegram 添加代理前会先发一次**不走代理**的探测请求，可被恶意代理链接用来套取真实出口 IP。开启后跳过该探测 |

### 隐私与本地增强

| 项 | 说明 |
|---|---|
| 隐藏「正在输入 / 录音中」 | 不再向对方发送实时输入状态 |
| 防撤回 | 拦截服务器下发的删除请求，对方撤回的消息保留在你的记录里 |

---

## 风险提示

带副作用的功能在设置界面**开启时**会弹窗说明并要求确认，不会静默生效：

- **防撤回**：副作用是你自己也删不掉「为所有人删除」的消息；同时保留他人撤回内容可能涉及隐私与取证合规，请仅用于个人设备上的正当用途。
- **隐藏正在输入**：对方将完全看不到你的输入状态，会影响沟通预期。
- **账号上限提升**：会扩容 Telegram 内部账号实例数组，少数版本上超出原生上限的账号可能不稳定，建议先保持默认值 6。

---

## 合规边界

本模块只做**本地体验增强**，明确不做以下事情：

- **不去除赞助消息 / 广告** —— 这会直接削减 Telegram 的广告收入
- **不绕过 Telegram Premium 付费能力** —— 不伪造会员状态
- **不做批量群发、自动化推广**等可能被滥用的能力

---

## 实现要点

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

所有 Hook 点均取自 Telegram 官方源码（`DrKLO/Telegram`），而非猜测：

| Hook 点 | 源码位置 |
|---|---|
| `UserConfig.getMaxAccountCount()` | `UserConfig.java` |
| 各 `getInstance(int)` / `Instance[]` | `AccountInstance.java` 等 |
| `AndroidUtilities.getTypeface(String)` | `AndroidUtilities.java:2393` |
| `MessagesController.sendTyping(...)` | `MessagesController.java:11381` |
| `MessagesController.deleteMessages(...)` | `MessagesController.java:9307+` |
| `ConnectionsManager.checkProxy(...)` | `ConnectionsManager.java:739` |

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

## 诊断与版本适配

Hook 点依赖 Telegram 的类名与方法签名，这些会随版本变动而失效。为让适配不依赖反复试错，模块提供**两层可观测性**：

**1. 挂载期探测** —— 启动时主动探测所有关键类与方法是否存在，输出签名命中情况与各功能组的挂载结果。

**2. 运行期触发计数** —— 仅仅「挂上了」不等于「被调用了」。版本不匹配时最隐蔽的失败形态是：类名签名全对、挂载日志一切正常，但实际调用点早已改走别的路径，模块看起来工作正常却毫无效果。因此模块会在启动 **45 秒后**输出每个 Hook 点的触发次数，**计数为 0 即代表该功能未真正生效**，无需依赖现象描述。

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

---

## 签名说明

仓库中的 `keystore/tgenhance.p12` 是**公开的自签名密钥**，用途仅是保证 CI 每次构建的签名一致，从而支持覆盖升级。

密码为 `tgenhance`。它没有任何保密价值，也不代表任何组织身份 —— **请勿用于正式发布或分发场景**。若要正式分发，请自行生成密钥并改用 CI Secrets 注入。

---

## 免责声明

本项目仅供个人学习与自用。使用本模块产生的任何后果由使用者自行承担。请遵守所在地区的法律法规以及 Telegram 的服务条款。
