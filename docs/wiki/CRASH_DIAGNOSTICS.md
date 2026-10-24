# 崩溃诊断采集

## 系统接口

- Android 11 起，通过 `ActivityManager.getHistoricalProcessExitReasons` 查询本应用的历史退出记录。
- Android 12 起，对 Native 崩溃读取 `ApplicationExitInfo.getTraceInputStream`，按官方 tombstone protobuf 格式解码，并保留原始二进制。系统使用全局循环缓冲，回溯可能被覆盖或不提供。
- Android 11 起，通过 `ActivityManager.setProcessStateSummary` 记录进程当前状态，重启后从对应退出记录的 `getProcessStateSummary` 读取。摘要使用带版本标记的二进制格式，最多 127 字节，低于系统 128 字节限制。提交在后台合并，每两秒最多尝试一次；系统仍可能限流，因此摘要不是完整操作轨迹。

官方参考：

- [ActivityManager.setProcessStateSummary](https://developer.android.com/reference/android/app/ActivityManager#setProcessStateSummary(byte[]))
- [ApplicationExitInfo.getTraceInputStream](https://developer.android.com/reference/android/app/ApplicationExitInfo#getTraceInputStream())
- [NDK 原生崩溃调试](https://developer.android.com/ndk/guides/debug)

## 状态与日志边界

本地基础诊断独立于远程统计和崩溃上报开关，仅记录固定页面类别、事件类别、启动阶段、前后台状态，以及版本名、版本号、提交标记、状态更新时间和当时的进程运行时长。不记录账号、视频标识、标题、搜索词或设置值；未知页面和事件使用 `other`。字符串按字段限制长度；提交标记不足时可能显示 `local`，不能据此推定精确的代码版本。

状态更新时间表示最后一次状态变化，并非退出时间。两次变化之间的状态会合并，突发退出时最后一次变化可能尚未提交。进程摘要只供诊断，不用于恢复界面。

历史退出快照读取退出时间前十分钟内已持久化的基础及增强日志，若窗口内存在启动标记，则从最近一次启动开始，最多保留最后 120 条；多行异常作为一条保留。未落盘、已滚动清理或设备时间变化造成的缺口不能还原。重启后的内存日志单独展示，不能当作崩溃前操作。旧版本没有状态摘要时，采集版本和退出前版本不得混同。

## 原生回溯与导出

文本摘要包含系统构建指纹、原生崩溃时间、进程运行时长、内存页大小、线程数、崩溃线程的完整已解码栈，以及主线程和渲染线程各最多 120 帧。栈帧保留函数内偏移和库 Build ID，便于匹配符号；其他线程仅是并发快照，不能直接认定为触发源。

ART JIT 内联栈只能证明崩溃发生在编译器中，原始 tombstone 未必包含编译目标方法或崩溃前系统日志，增加摘要不能保证还原这些信息。

原始 `.pb` 保存在应用私有目录 `files/logs/last_crash_trace.pb`，采集上限 4 MiB，超过时明确标记截断。只有选择「导出并附原始回溯」时才通过系统分享附带，普通导出不附带。原始附件可能包含设备路径等信息；文本继续使用现有脱敏流程。

Android 17 的现有 `ProfilingManager` 触发采集继续受增强诊断开关和系统配额约束，不能保证每次崩溃都生成性能附件，也不能将未提供附件解读为没有异常。
