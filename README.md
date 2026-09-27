# AutoJs7

基于 [AutoJs6](https://github.com/SuperMonster003/AutoJs6) 二次开发的 Android JavaScript 自动化工具。本分支的重点是让 AI 客户端通过 MCP 连接手机，使用无障碍服务、脚本引擎及设备工具完成自动化任务。

> 仓库名为 AutoJs7；当前安装包和应用内名称仍沿用 **AutoJs6**，并非另一个独立安装的应用。

[下载最新版本](https://github.com/SiwuXue/AutoJs7/releases/latest) · [查看构建状态](https://github.com/SiwuXue/AutoJs7/actions/workflows/android.yml) · [MCP 配套 Skill](https://github.com/SiwuXue/autojs7-mcp-skill)

## 新功能：让 AI 客户端操作 Android

应用内提供带 Bearer Token 鉴权的 MCP 服务。兼容 Streamable HTTP 的客户端可连接 `http://<设备 IP>:7788/mcp`；同时保留旧版 SSE 接入端点。当前源码注册了 **45 个工具**，主要包括：

| 能力 | 可做什么 | 工具示例 |
| --- | --- | --- |
| 界面观察与操作 | 读取控件树、查找控件、点击与滑动 | `dump_ui_tree`、`ui_find`、`ui_action`、`gesture` |
| 图像与文字识别 | 截图、OCR、找图与找色，辅助处理游戏或画布界面 | `screenshot`、`ocr_recognize`、`find_image`、`find_color` |
| 脚本自动化 | 列出、运行或停止手机上的脚本 | `list_scripts`、`run_script`、`run_file`、`stop_script` |
| 应用与设备 | 查询设备/前台应用、启动应用、读取通知和日志 | `get_device_info`、`launch_app`、`read_notifications`、`read_log` |
| 文件与高级操作 | 读写文件、执行 Shell 或 Shizuku 命令、安装应用 | `file_read`、`file_write`、`run_shell`、`shizuku`、`install_app` |

工具的实际参数以客户端取得的 `tools/list` 结果为准。MCP 可复用 AutoJs6 原有的 JavaScript 自动化能力；这不是新的脚本语言或单独的云端服务。

本分支也改进了 MCP 的运行稳定性：限制并发连接及请求大小，停止服务时关闭活动连接；Shell 超时后尝试终止进程树，Shizuku 调用未结束时拒绝重叠调用。设置页和悬浮菜单的 MCP 开关状态、运行通知中的会话数量也已修正。详见 [版本说明](https://github.com/SiwuXue/AutoJs7/releases/tag/v6.7.1-mcp.1)。

## 下载与连接

1. 从 [Releases](https://github.com/SiwuXue/AutoJs7/releases/latest) 下载适合设备的 APK：`arm64-v8a`、`armeabi-v7a` 或 `universal`。Android 最低版本为 7.0（API 24）。发布页提供 `SHA256SUMS.txt` 供校验。
2. 安装并开启应用所需的无障碍服务。在“设置 → 开发者选项”启用 MCP 服务；也可以通过悬浮菜单启停。
3. 如客户端运行在另一台设备上，再开启“允许局域网访问”。在应用内查看实际地址与访问令牌；默认只监听 `127.0.0.1`，不对局域网开放。
4. 在 MCP 客户端选择 **Streamable HTTP**，填入应用显示的 `/mcp` 地址，并配置请求头 `Authorization: Bearer <访问令牌>`。不要将真实令牌写入公开仓库、截图或聊天消息。

通知中的会话数指近一小时内已初始化且仍有效的 MCP 会话，不等于 TCP 连接数；只打开地址或做一次未完成初始化的探测，仍可能显示 0。连接和工具调用示例可参考 [配套 Skill 仓库](https://github.com/SiwuXue/autojs7-mcp-skill)。

> 如果设备上原来安装的是不同签名的调试包，Android 不允许直接覆盖安装正式包。请先备份脚本和配置，再卸载旧包并安装本版。

## 安全提示

MCP 客户端持有令牌后可调用全部已注册工具，其中包括脚本、Shell、文件写入和应用安装等高权限能力；工具的风险标记不是服务端权限拦截。局域网端点使用 HTTP，不提供传输加密。请只在可信网络中启用局域网访问，不要把端口直接暴露到公网；令牌泄露后应在应用内重置。

## 源码与致谢

本项目沿用 AutoJs6 的主体代码与 JavaScript 自动化功能，在此基础上增加 MCP 服务和相关稳定性改进。原项目文档及更完整的功能说明请看 [AutoJs6 上游仓库](https://github.com/SuperMonster003/AutoJs6)。感谢上游作者和贡献者。

构建与发布流程见 [Android GitHub Actions](.github/workflows/android.yml)。项目许可证见 [LICENSE](LICENSE)。
