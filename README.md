# 火狐下载目录修改（LSPosed 模块）

把 Firefox（火狐浏览器）的下载保存目录从固定的 `/sdcard/Download` 重定向到任意自定义文件夹。

## 原理

Firefox 下载有三条路径，本模块分别挂钩：

1. **系统下载管理器（主路径，所有 Android 版本）**：Firefox 通过 `AndroidDownloadManager` 调用
   `android.app.DownloadManager.Request#setDestinationInExternalPublicDir(dirType, fileName)` 保存文件。
   模块把 `dirType`（默认 `Download`）替换成你配置的目录名。
2. **内置下载服务（Android 10+ 私密下载）**：走 `AbstractFetchDownloadService`，向
   `MediaStore.Downloads` 插入记录。模块在 `ContentResolver.insert` 时补写
   `RELATIVE_PATH`，把文件重定向到自定义目录。
3. **旧版/低版本路径**：拦截 `Environment.getExternalStoragePublicDirectory("Download")`，返回自定义目录。

作用域限定为 Firefox 相关包名，不影响其他应用。

## 使用

> ⚠️ 重要：模块**不会**在 Firefox 的“设置”里添加任何选项（Firefox 本来就没有下载目录设置）。
> 下载目录是在**模块自己的设置页**里配置的，Firefox 里的下载会被静默重定向到该目录。

1. 在 GitHub Actions 里构建（或本地 `./gradlew assembleRelease` 后自行签名），得到
   `firefox-downloaddir-module.apk`。
2. 安装到已装有 **LSPosed** 的设备。
3. 打开 LSPosed 管理器 → 模块 → 启用「火狐下载目录修改」→ 勾选作用域里的 **Firefox**（这是最关键的一步）。
4. 桌面打开模块图标（橙色下载箭头），填写目标目录并保存：
   - 目录名**相对于 `/storage/emulated/0`**，例如：`Movies`、`Pictures`、`Download/Firefox`。
   - 恢复默认请填写 `Download`。
   - 页面会显示一行状态文字：绿色「配置通道正常」表示 LSPosed 新偏好机制已生效；
     红色「警告」说明 Firefox 读不到配置（见下方排查）。
5. 完全结束 Firefox 进程（从最近任务划掉）并重新打开，之后的下载会保存到新目录。

> 提示：`/sdcard` 就是 `/storage/emulated/0`。不需要给目录加前缀，也支持多级路径。
> 要求 **LSPosed 版本 ≥ 1.8.x（API 93）**。写文件的是系统 DownloadProvider/MediaProvider，
> 会自动创建目录，因此不需要额外存储权限。

## 排查

- **点模块图标闪退/没反应**：正常情况下设置页是纯系统控件、不依赖任何 Xposed 库。
  若仍打不开，把 logcat 报错（`adb logcat` 或 LSPosed 日志里的异常）发出来。
- **设置页显示「警告：LSPosed 新偏好机制未生效」**：说明模块没有以“新偏好机制”方式被加载。
  请确认：① 在 LSPosed 管理器里已启用本模块并重启生效；② LSPosed 版本 ≥ 1.8.x（API 93）。
  升级/调整后重新打开设置页，状态应变绿。
- **模块不生效**：先在 LSPosed 管理器日志里搜 `FirefoxDownloadDir`。
  如果看到 `hooks installed ... folder=...`，说明模块已加载；
  如果没有，说明模块未启用或作用域没勾选 Firefox。
- 改了目录不生效：确认保存后把 Firefox 进程完全杀掉再打开。
- 下载后文件找不到：目录名务必相对 `/storage/emulated/0`，例如填 `Movies` 而不是 `/sdcard/Movies`（填绝对路径也会被自动转成相对目录名）。

## 目录结构

```
app/src/main/
├── assets/xposed_init          # LSPosed 入口声明
├── AndroidManifest.xml
├── java/com/firefoxdl/mod/
│   ├── XposedEntry.java        # 挂钩逻辑
│   └── SettingsActivity.java   # 设置页
└── res/values/                 # 字符串与默认作用域
.github/workflows/build.yml     # GitHub Actions 自动构建+签名
```

## 说明

- 依赖 Xposed API 82，仅 `compileOnly`，不会打入 APK。
- 若你的 Firefox 包名不在 `xposed_scope`/`FIREFOX_PACKAGES` 列表中，在 LSPosed 里手动勾选即可。
- 私密浏览（无痕）下载同样会被重定向（Android 10+ 走 MediaStore 分支）。
