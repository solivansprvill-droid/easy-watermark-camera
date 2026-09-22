# 水印相机 (Watermark Camera)

> 自用、无广告、无弹窗、本地优先的安卓水印相机 —— 参考 [lxsfful/simple-watermark-camera](https://github.com/lxsfful/simple-watermark-camera) 功能集重制，纯 Java 实现（`android.jar` 零第三方依赖，手工构建链编译，无需 Gradle）。

## 功能

- 📷 **硬件最大分辨率拍摄**：选取设备支持的最大画幅，JPEG 质量 98，长边上限 3200px（编辑器内存安全）
- 🕐 **马克排版水印**：大字时间（`HH:mm`）+ `星期X / yyyy-MM-dd` + 字段图标行（📍 详细地址 / 🌐 经纬度 / 🏔️ 海拔）
- 🛡 **真实性验证行**：水印含「水印相机已验证照片真实性」
- 🔐 **防伪编号**：每次拍摄生成 13 位随机码，水印右下角显示
- ✂️ **图片编辑（修原图）**：拍摄后可进入编辑器对无水印原图进行 **裁剪**（四角拖拽 + 自由/1:1/4:3/16:9 比例）、**滤镜**（原图/黑白/复古/冷色/暖色/鲜艳）、**加字**（输入文字拖动定位），确认后自动重新合成水印
- 📐 **短边自适应比例**：以 `min(width, height)` 为缩放基准，横竖屏水印占比一致、不截断
- 🔠 **三档字号 / 四档透明度**：设置面板点击循环切换，SharedPreferences 持久化
- 🏠 **详细地址解析**：Geocoder 优先（中文门牌/街道），Nominatim 兜底，自动清洗"中国"前缀
- 🏔️ **无效海拔过滤**：海拔 ≤1.0m 时不显示
- 📝 **EXIF GPS 元数据写入**：经纬度 + 海拔写入 JPEG EXIF（可在设置中关闭）
- 💾 **双版本保存**：默认同时保存「水印版」（`Pictures/水印相机/Watermarked/`）与「无水印原图」（`Original/`），可在设置中关闭
- 🔒 **隐私本地优先**：照片 100% 本地处理，不上传图片，无广告、无会员、无弹窗

## 规格

- 包名 `com.victory.easywatermark`，minSdk 29（Android 10+），targetSdk 34
- Camera1 API + LocationManager + Geocoder/Nominatim + MediaStore + ExifInterface + Canvas/ColorMatrix
- 零第三方依赖、零 Gradle，手工构建链：

```bash
aapt2 compile --dir res -o compiled.zip
aapt2 link -o app.apk -I <android.jar> --manifest AndroidManifest.xml \
  --min-sdk-version 29 --target-sdk-version 34 --java gen compiled.zip
javac -encoding UTF-8 -source 1.8 -target 1.8 \
  -bootclasspath <android.jar> -classpath <android.jar> -d classes \
  gen/**/R.java java/**/*.java
d8 --release --lib <android.jar> --output dexout classes/**/*.class
# classes.dex（必须使用标准名）加入 apk → zipalign -p 4 → apksigner sign
```

## 安装

`apk/` 下最新 APK 传到安卓手机直接安装（需允许未知来源）。调试签名，仅供自用/测试。

## 目录结构

```
├── android/          # 原生安卓源码（Java）
├── pwa/              # H5 PWA 网页版（HTTPS 部署后可用）
└── apk/              # 编译产物
```

## 版本历史

- **v1.2**：图片编辑器（裁剪/滤镜/加字修原图）；水印新增真实性验证行与防伪编号；移除天气；更名「水印相机」
- **v1.1**：修复 v1.0 安装失败（dex 文件名不规范）
- **v1.0**：首个版本

## 致谢

- [lxsfful/simple-watermark-camera](https://github.com/lxsfful/simple-watermark-camera) — 功能设计参考
- [马克水印相机](https://www.markj.com/) — 水印排版风格参考
