# OpenMinis Sandbox 120

基于 [OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis) 的个人 Android 定制版，保留多模型聊天、工具调用、Linux 沙盒、终端、子代理和备份能力。此仓库发布 `1.14.6-sandbox120` 的源码与安装包，不是上游官方发行版。

## 安装

在本仓库 [Releases](https://github.com/cormellswallie-alt/openminis-sandbox120/releases) 下载 `MinisApp-1.14.6-sandbox120-coexist-arm64-v8a.apk`。设备需为 ARM64，Android 8.0 / API 26 或更高。

- 版本：`1.14.6-sandbox120`，versionCode `34`
- 包名：`com.openminis.perf120`
- APK 大小：42,251,270 字节
- APK SHA-256：`796bd1769e7f73f77c02f2ee1aab8841122528cc4e48ca30e24b9f13dc4432da`
- 签名证书 SHA-256：`af29344161194d6b1e4c1785766c4a2dd74d04c2128acdd9c664a5e9001990e4`

该安装包沿用此前 `perf120` 共存版的专用签名，可覆盖同包名、同签名的旧版。与官方版及旧 `com.openminis.s14` 的数据目录独立；迁移数据请使用应用的备份/导入功能。源码自行构建的临时签名包不能覆盖本发行 APK。

## 本版改动

- 页面退出、暂停、切换会话或宿主脱离时清理复制选择菜单和手柄，阻止旧页面的迟到显示回调。
- 终端直接设置初始工作目录，移除读取路径上的固定 300ms 等待及自动 `cd/clear` 输入。
- 沙盒命令释放进程预算后立即唤醒等待队列，保留周期检查、取消、超时和预算保护。
- 大输出直接处理字符数组，减少临时字符串复制；复用不可变的终端 ASCII 解析事件。
- 修复沙盒 Git 的 `core.createObject=copy` 错误，初始化时幂等迁移配置，减少 shell 启动时的重复写入。

此前已实现任务恢复、网络请求重试、Token 单价与消费估算、压缩撤销/恢复、状态卡片过渡和最高 120 Hz 的前台刷新率请求。

云端 Release 编译和专项回归通过：**109 个测试类、897 项测试，0 失败、0 错误、0 跳过**。下载后已核对 APK 完整性、签名、版本、ARM64 和 Baseline Profile。验证摘要见 [docs/release-1.14.6.json](docs/release-1.14.6.json)。这是专项测试集合，尚未完成本版真机交互验收；最高 120 Hz 请求不代表稳定 120 FPS，局部性能基准不代表下载带宽或纯计算吞吐的整体提升。

## 构建

请阅读 [BUILDING.md](BUILDING.md)。Android 工程在 `src/android`，使用 JDK 17、Gradle Wrapper 8.11.1、SDK platform 36 和 Build Tools 35.0.0。

本仓库包含用于还原 ARM64 预构建资源的 `ci/native` 分卷。使用这些资源时执行：

```sh
export ANDROID_HOME=/path/to/android-sdk
python3 scripts/ci/prepare_android.py
CI_SIGNED=false CI_RUN_TESTS=true bash scripts/ci/build_android.sh
```

公开构建模板位于 `ci/workflow-templates/android-build.yml`。本次发布授权令牌缺少 GitHub `workflow` 权限，因此模板尚未安装到 `.github/workflows/`，公开 Actions 暂未启用。具备相应权限的维护者可安装该模板，执行临时签名构建和回归测试。公开仓库不保存专用签名密钥或其 Secrets；正式发行 APK 由原签名构建产生后上传 Releases。

首次构建需要联网下载依赖。手机 ARM64 环境可能需要为 aapt2 指定正确架构；桌面基准独立位于 `scripts/benchmarks`。修改原生实现后应重新构建对应库，复用分卷不会包含新 native 代码。

## 来源与许可

Android 定制基于上游 1.14。此仓库采用独立快照历史，Android 主源码、测试、Gradle 配置及 native 分卷与已验证发行源码一致；保留了依赖源码和许可证。原开发环境、用户聊天数据、私有签名和内部交接记录不属于此公开源码快照。

许可证见 [LICENSE](LICENSE)（GPLv3）与 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。各第三方组件的具体条款以其随附许可证为准。
