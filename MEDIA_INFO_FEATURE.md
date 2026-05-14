# 媒体信息查看功能

## 功能描述
在主页面顶部中央添加了一个"媒体信息"按钮，点击后会弹出对话框显示当前加载的媒体文件的详细信息。

## 实现细节

### 1. UI 更改
**文件**: `app/src/main/res/layout/activity_main.xml`
- 在顶部中央添加了一个按钮 `btn_media_info`
- 该按钮与左上角的"导出"按钮和右上角的"导入"按钮水平平齐
- 使用 ConstraintLayout 的中心约束使其居中显示

**文件**: `app/src/main/res/values/strings.xml`
- 添加了字符串资源 `res_media_info`，值为"媒体信息"

### 2. 数据层修改
**文件**: `app/src/main/java/com/xyq/ffmpegdemo/player/MyPlayer.kt`
- 添加了 `mMediaInfo` 属性来保存当前加载的媒体信息
- 在 `prepare()` 方法中调用 `MediaInfo(mProxy!!.getMediaInfo())` 并保存结果
- 添加了 `getMediaInfo(): MediaInfo?` 方法来提供媒体信息的访问接口

### 3. 交互层修改
**文件**: `app/src/main/java/com/xyq/ffmpegdemo/MainActivity.kt`
- 在 `initViews()` 方法中为 `btn_media_info` 添加点击监听器
- 实现了 `showMediaInfoDialog()` 方法来展示媒体信息的对话框

## 显示的信息

### 视频信息
- 编码格式（Video Codec）
- 分辨率（分宽 × 高）
- 帧率（FPS）
- 宽高比（DAR）
- 时长（单位：秒）
- 旋转角度（度数）
- 是否开启硬件加速

### 音频信息
- 编码格式（Audio Codec）
- 采样率（Hz）
- 声道数
- 样本格式

## 使用步骤
1. 在主界面加载一个媒体文件
2. 点击顶部中央的"媒体信息"按钮
3. 在弹出的对话框中查看媒体的详细信息
4. 点击"确定"按钮关闭对话框

## 代码修改总结

### MyPlayer.kt
```kotlin
// 添加媒体信息属性
private var mMediaInfo: MediaInfo? = null

// 在prepare()方法中保存媒体信息
mMediaInfo = MediaInfo(mProxy!!.getMediaInfo())

// 提供getter方法
fun getMediaInfo(): MediaInfo? {
    return mMediaInfo
}
```

### MainActivity.kt
```kotlin
// 在initViews()中添加按钮点击事件
mBinding.btnMediaInfo.setOnClickListener {
    showMediaInfoDialog()
}

// 实现展示对话框的方法
private fun showMediaInfoDialog() {
    val mediaInfo = (mPlayer as MyPlayer).getMediaInfo()
    if (mediaInfo == null) {
        Toast.makeText(this, "媒体信息未加载", Toast.LENGTH_SHORT).show()
        return
    }
    
    // 构造显示信息
    // 使用AlertDialog显示
}
```

## 编译验证
已通过 `./gradlew app:compileDebugKotlin` 验证，Kotlin 代码编译成功，无语法错误。

