# OpenMinis Sandbox 120

基于 [OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis) 的个人 Android 定制版，保留多模型聊天、工具调用、Linux 沙盒、终端、子代理和备份能力。此仓库发布 `1.14.7-shellspeed120` 的源码与安装包，不是上游官方发行版。

## 安装

在本仓库 [Releases](https://github.com/cormellswallie-alt/openminis-sandbox120/releases) 下载 `MinisApp-1.14.7-shellspeed120-coexist-arm64-v8a.apk`。设备需为 ARM64，Android 8.0 / API 26 或更高。

- 版本：`1.14.7-shellspeed120`，versionCode `35`
- 包名：`com.openminis.perf120`
- APK 大小：42,263,118 字节
- APK SHA-256：`abab95e33f71078e04b35c6a24d9678c67f005abf394a3699403a751846a9ead`
- 签名证书 SHA-256：`af29344161194d6b1e4c1785766c4a2dd74d04c2128acdd9c664a5e9001990e4`

该安装包沿用此前 `perf120` 共存版的专用签名，可覆盖同包名、同签名的旧版。与官方版及旧 `com.openminis.s14` 的数据目录独立；迁移数据请使用应用的备份/导入功能。源码自行构建的临时签名包不能覆盖本发行 APK。

## 本版改动

- shell 工具以解码分块更新输出，无换行文本也能显示；最近 50 行/16 Ki 字符的预览按 50ms 合并，完整最终结果仍走原路径。
- 工具预览独立于模型长文本节流，避免二次等待；预览跟随执行任务取消并验证目标状态，防止停止后复活。
- 订阅实时输出的 Python 工具命令默认无缓冲，保留用户环境及 CLI 覆盖。终端保持正常 TTY 缓冲，避免大量小 print 的吞吐退步。
- 退出状态目录由后台维护线程低频清理，保护活跃命令与目录代次，避免短命令等待完整扫描。
- 终端复用不可变 ASCII cell、避免同尺寸备用屏重建，分片输入零复制，并按软时间预算让出主线程。
- 并发工具状态事务统一处理；用户终端环境不再污染全局沙盒默认值。

此前的复制选择浮窗退出清理、终端去固定启动等待、预算释放唤醒、Git 配置修复、任务恢复、Token 计价和压缩撤销等功能保留。

云端 Release 编译和专项回归通过：**114 个测试类、939 项测试，0 失败、0 错误、0 跳过**。下载后已核对 APK 完整性、签名、版本、ARM64 和 Baseline Profile。验证摘要见 [docs/release-1.14.7.json](docs/release-1.14.7.json)。

本版尚未完成真机端到端交互验收。输出处理的局部基准显示收益，但不能据此承诺 Python 纯计算、下载带宽或所有命令整体加速。共享 Python 字节码缓存与持久解释器未启用；公开基准位于 `scripts/benchmarks`。

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
