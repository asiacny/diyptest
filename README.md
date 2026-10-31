# DIYP-Next IPTV Player

全新现代化的 Android IPTV 直播播放器，基于 Google AndroidX Media3 (ExoPlayer) 核心构建，1:1 复刻经典 DIYP 影音界面与 EPG 解析机制。

- **包名**：`com.diyp.next`（独立包名，可与老版本共存，不互相覆盖）
- **适用平台**：Android TV 机顶盒、车机（Android 7.1.2+ 极致优化）、Android 手机/平板
- **构建输出**：自动拆分 `arm64-v8a`、`armeabi-v7a` 及 `universal` 多架构安装包
- **教程与高级技巧**：请查阅 [USER_MANUAL.md](./USER_MANUAL.md)

---

## 快速推送并编译

```powershell
cd c:\Users\haha\Desktop\hdp\123
git init
git branch -m main
git add .
git commit -m "feat: Initial commit for DIYP-Next"
git remote add origin <你的GitHub仓库地址>
git push -u origin main
```
推送至 GitHub 后，GitHub Actions 会全自动编译并输出已签名的 Release APK 供直接下载安装。
