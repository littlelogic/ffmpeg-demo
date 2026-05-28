package com.xyq.ffmpegdemo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.MyHorizontalScrollView
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.badlogic.utils.ALog
import com.badlogic.utils.Tools
import com.donkingliang.imageselector2.ImageSelectorActivity
import com.donkingliang.imageselector2.entry.RequestConfig
import com.donkingliang.imageselector2.model.ImageModel
import com.donkingliang.imageselector2.utils.ImageSelector
import com.xyq.ffmpegdemo.adapter.ThumbnailAdapter
import com.xyq.ffmpegdemo.databinding.ActivityMainBinding
import com.xyq.ffmpegdemo.timeline.TimelineConfig
import com.xyq.ffmpegdemo.entity.Thumbnail
import com.xyq.ffmpegdemo.player.IMediaPlayer
import com.xyq.ffmpegdemo.player.IMediaPlayerStatusListener
import com.xyq.ffmpegdemo.player.MyPlayer
import com.xyq.ffmpegdemo.player.PlayerConfig
import com.xyq.ffmpegdemo.viewmodel.PlayViewModel
import com.xyq.ffmpegdemo.viewmodel.VideoThumbnailViewModel
import com.xyq.libffplayer.ui.MediaInfoDialogHelper
import com.xyq.libffplayer.utils.FFMpegUtils
import com.xyq.libmediapicker.MediaPickerActivity
import com.xyq.libmediapicker.PickerConfig
import com.xyq.libmediapicker.entity.Media
import com.xyq.librender.filter.GreyFilter
import com.xyq.libutils.CommonUtils
import com.xyq.libutils.FileUtils
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var mBinding: ActivityMainBinding
    private lateinit var mPlayer: IMediaPlayer
    private lateinit var mMediaFilePath: String
    private var mIsVideo = true

    private var mVideoPathForThumbnail = ""
    private var mHasPermission = false
    private var mIsSeeking = false
    private var mTrackUserScrolling = false
    private var mIsExporting = false
    private var mDuration = -1.0
    private var mThumbnailAdapter: ThumbnailAdapter? = null

    private val mTimelineConfig = TimelineConfig()

    private lateinit var mVideoThumbnailViewModel: VideoThumbnailViewModel
    private lateinit var mPlayViewModel: PlayViewModel

    private var mExecutors = Executors.newFixedThreadPool(2)

    private val mLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == PickerConfig.RESULT_CODE) {
                Log.i(TAG, "select media file done")
                val select = result.data?.getParcelableArrayListExtra<Media>(PickerConfig.EXTRA_RESULT)
                if (select.isNullOrEmpty()) return@registerForActivityResult
                val media = select[0]
                mMediaFilePath = media.path
                mIsVideo = media.isVideo() or media.isGif()
            }
        }

    private val permissionLauncher = registerForActivityResult<Array<String>, Map<String, Boolean>>(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        for ((key, value) in result) {
            println("MainActivity- key= $key and value= $value")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Tools.setApplication(this.application)
        ALog.setMark(Tools.isApkDebugable(application))

        Log.i(TAG, "onCreate: ")
        mBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(mBinding.root)
        mPlayer = MyPlayer(applicationContext, mBinding.glSurfaceView)

        initViews()
        initViewModels()

        mHasPermission = checkPermission()

        // preload video thumbnail
        mMediaFilePath = getDemoVideoPath()
//        mMediaFilePath = "/sdcard/11_media/2-h265.mp4";
        mMediaFilePath = "/sdcard/11_media/dongbeikanjia1212.mp4";
        checkPermissionAndRunNext(kotlinx.coroutines.Runnable {
            fetchVideoThumbnail(mMediaFilePath)
        })

        val text = CommonUtils.generateTextBitmap("雪月清的随笔", 16f, applicationContext)
        mBinding.ivWatermark.setImageBitmap(text)

    }


    fun loadVideo(){

        if (false) {
            val intent = Intent(this, MediaPickerActivity::class.java)
            intent.putExtra(PickerConfig.SELECT_MODE, PickerConfig.PICKER_IMAGE_VIDEO)
            intent.putExtra(PickerConfig.MAX_SELECT_COUNT, 1)
            mLauncher.launch(intent)
        }



        val config = RequestConfig()
        config.type = ImageModel.Type.Video
        config.maxSelectCount = 1
        config.minSelectCount = 1
        val intent = Intent(this@MainActivity, ImageSelectorActivity::class.java)
        intent.putExtra(ImageSelector.KEY_CONFIG, config)
        mLauncher.launch(intent)


        /*this@MainActivity.startActivityForResult(intent, requestCode)
        ImageSelectorActivity
            .openActivity(this@MainActivity, RequestCodeCode_GifActivity, config)

        this@MainActivity.startActivity(
            Intent(
                this@MainActivity,
                ImageSelectorActivity::class.java
            )
        )*/
    }

    private fun checkPermissionAndRunNext(run: Runnable?) {
        var havePermission = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PermissionChecker.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_VIDEO
            ) == PermissionChecker.PERMISSION_GRANTED)
        ) {
            println("MainActivity-checkPermissionAndRunNex- Full access on Android 13 (API level 33) or higher")
            havePermission = true
        } else if (Build.VERSION.SDK_INT >= 34 &&
            ContextCompat.checkSelfPermission(
                this,
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
            ) == PermissionChecker.PERMISSION_GRANTED
        ) {
            println("MainActivity-checkPermissionAndRunNex- Partial access on Android 14 (API level 34) or higher")
            havePermission = true
        } else if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PermissionChecker.PERMISSION_GRANTED
        ) {
            println("MainActivity-checkPermissionAndRunNex- Full access up to Android 12 (API level 32)")
            havePermission = true
        } else {
            println("MainActivity-checkPermissionAndRunNex- Access denied")
            havePermission = false
        }
        println("MainActivity-checkPermissionAndRunNext havePermission："+havePermission)
        if (havePermission) {
            run?.run()
        } else {
            if (Build.VERSION.SDK_INT >= 34) {
                println("MainActivity-checkPermissionAndRunNext sdk：>= 34")
                permissionLauncher.launch(
                    arrayOf<String>(
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO
                    )
                )
            } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.TIRAMISU) {
                println("MainActivity-checkPermissionAndRunNext sdk == 33")
                permissionLauncher.launch(
                    arrayOf<String>(
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO
                    )
                )
            } else {
                println("MainActivity-checkPermissionAndRunNext sdk：== else")
                permissionLauncher.launch(arrayOf<String>(Manifest.permission.READ_EXTERNAL_STORAGE))
            }
        }
    }

    var onResume_play_mark = false

    override fun onResume() {
        Log.i(TAG, "onResume: filepath: $mMediaFilePath, isVideo: $mIsVideo")
        super.onResume()
        /*if (mHasPermission) {
            checkMediaFileValid(mMediaFilePath, mIsVideo)
            startPlay(mMediaFilePath, mIsVideo)
        }*/

        if (!onResume_play_mark) {
//            onResume_play_mark = true
            ///读取缩略图触发的问题
            checkPermissionAndRunNext(kotlinx.coroutines.Runnable {
                checkMediaFileValid(mMediaFilePath, mIsVideo)
                startPlay(mMediaFilePath, mIsVideo)
            })
        }

    }

    override fun onPause() {
        Log.i(TAG, "onPause: ")
        super.onPause()
        stopPlay()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy: ")
        mIsExporting = false
        super.onDestroy()
        mPlayer.release()
        mExecutors.shutdown()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1000 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            mHasPermission = true
            Log.i(TAG, "onRequestPermissionsResult:")
        }
    }

    private fun checkPermission(): Boolean {
        Log.i(TAG, "checkPermission: ")
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ), 1000
            )
            return false
        }
        return true
    }

    fun getScreenSize(context: Context): IntArray {
        val resources = context.getResources()
        val dm = resources.getDisplayMetrics()
        val density1 = dm.density
        val width = dm.widthPixels
        val height = dm.heightPixels
        return intArrayOf(width, height)
    }

    private fun initViews() {
        Log.i(TAG, "initViews: ")

        val resources = this.getResources()
        val dm = resources.getDisplayMetrics()
        val width = dm.widthPixels
        val height = dm.heightPixels
//        mBinding.trackHeader.layoutParams.width = width / 2
//        mBinding.trackTailer.layoutParams.width = width / 2
//
//        mBinding.trackHeader.layoutParams.width = 0
//        mBinding.trackTailer.layoutParams.width = 0
        mBinding.trackScrollView.layoutParams.width = width
        mBinding.timeScaleView.setOutParentView(mBinding.trackScrollView)
        mBinding.videoThumbSliderView.setOutParentView(mBinding.trackScrollView)


        mTimelineConfig.majorTickSpacingPx = TimelineConfig.majorTickSpacingPx(dm.density)
        mTimelineConfig.pxPerSecond = TimelineConfig.defaultPxPerSecond(mTimelineConfig.majorTickSpacingPx)
        mBinding.timeScaleView.setTimelineConfig(mTimelineConfig)
        mBinding.videoThumbSliderView.setTimelineConfig(mTimelineConfig)

        setupTrackScrollView(width / 2)

        mBinding.trackScrollView.setOnTouchDownEventListener {
            ALog.i("MainActivity-trackScrollView-onTouchDownEventListener")
            mPlayViewModel.updatePlayState(false)
        }
        mBinding.trackScrollView.addOnScrollListener(object : MyHorizontalScrollView.OnScrollListener() {
            override fun onScrollStateChanged(view: MyHorizontalScrollView, newState: Int) {
                mTrackUserScrolling = newState != MyHorizontalScrollView.SCROLL_STATE_IDLE
                when (newState) {
                    MyHorizontalScrollView.SCROLL_STATE_IDLE -> {
                        // fling 结束 / 用户离手且没启动惯性
                        ALog.i("MainActivity-trackScrollView-onScrollStateChanged-SCROLL_STATE_IDLE"
                                    + " fling 结束 / 用户离手且没启动惯性"
                        )
                    }
                    MyHorizontalScrollView.SCROLL_STATE_DRAGGING -> {
                        // 用户手指正在拖动
                        ALog.i("MainActivity-trackScrollView-onScrollStateChanged-SCROLL_STATE_DRAGGING"
                                + " 用户手指正在拖动"
                        )
                    }
                    MyHorizontalScrollView.SCROLL_STATE_SETTLING -> {
                        // fling / springBack / smoothScrollBy 中
                        ALog.i("MainActivity-trackScrollView-onScrollStateChanged-SCROLL_STATE_SETTLING"
                                + " fling / springBack / smoothScrollBy 中"
                        )
                    }
                }
            }

            override fun onScrolled(view: MyHorizontalScrollView, dx: Int, dy: Int) {
                updateTimelineScrollOffset()
                if (mTrackUserScrolling && mIsVideo && mDuration > 0) {
                    val curTime = getPlayheadTimeSec()
                    val seconds = curTime.toInt()
                    val finalTime = seconds + ((curTime - seconds) * 30).toInt() / 30.0

                    ALog.i("-260527seek-MainActivity-trackScrollView-onScrollStateChanged-onScrolled"
                            + " curTime:" + curTime
                            + " finalTime:" + finalTime
                    )
                    mPlayer.seek(finalTime)
                }
            }
        })



        val layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        mBinding.videoThumbnails.layoutManager = layoutManager
        mThumbnailAdapter = ThumbnailAdapter(this, ArrayList())
        mBinding.videoThumbnails.adapter = mThumbnailAdapter

        mBinding.seekBar.isEnabled = false
        mBinding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && mDuration > 0) {
                    val timestamp = progress / 100f * mDuration
                    mBinding.tvTimer.text = CommonUtils.getTimeDesc(timestamp.toInt())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                Log.e(TAG, "onStartTrackingTouch: ")
                mIsSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let {
                    val timestamp = seekBar.progress / 100f * mDuration
                    Log.e(TAG, "onStopTrackingTouch: progress: ${seekBar.progress}, timestamp: $timestamp")
                    mPlayer.seek(timestamp)
                }
                mIsSeeking = false
            }
        })

        mBinding.btnPlayer.setOnClickListener {
            val start = mPlayViewModel.isPlaying()
            mPlayViewModel.updatePlayState(!start)
        }

        mBinding.btnAudio.setOnClickListener {
            val isMute = mPlayViewModel.isMute()
            mPlayViewModel.updateMuteState(!isMute)
        }

        mBinding.btnImport.setOnClickListener {
            loadVideo()
        }

        mBinding.btnExport.setOnClickListener {
            if (mIsExporting) {
                Log.e(TAG, "exportGif doing")
                return@setOnClickListener
            }
            mIsExporting = true

            mExecutors.submit {
                if (mIsVideo) {
                    exportGif()
                } else {
                    exportImage()
                }
            }
        }

        mBinding.btnMediaInfo.setOnClickListener {
            showMediaInfoDialog()
        }
    }

    private fun initViewModels() {
        Log.i(TAG, "initViewModels: ")
        // video thumbnail
        mVideoThumbnailViewModel = ViewModelProvider(this)[VideoThumbnailViewModel::class.java]
        mVideoThumbnailViewModel.getData().observe(this) { model ->
            Log.i(TAG, "Receive VideoThumbnailViewModel: $model")
            if (model.isValid()) {
                model.getBitmap()?.let {
                    mThumbnailAdapter?.addData(Thumbnail(it, model.index))
                }
            }
        }

        mPlayViewModel = PlayViewModel(mPlayer as MyPlayer)
        mPlayViewModel.getPlayState().observe(this) {
            mBinding.btnPlayer.background = ResourcesCompat.getDrawable(resources, it, null)
        }
        mPlayViewModel.getMuteState().observe(this) {
            mBinding.btnAudio.background = ResourcesCompat.getDrawable(resources, it, null)
        }
    }

    private fun showOrHideVideoModeView(show: Boolean) {
        val visible = if (show) View.VISIBLE else View.INVISIBLE
        mBinding.audioVisualizeView.visibility = visible
        mBinding.tvTimer.visibility = visible
        mBinding.videoThumbnails.visibility = visible
        mBinding.btnPlayer.visibility = visible
        mBinding.btnAudio.visibility = visible
        mBinding.seekBar.visibility = visible
        mBinding.trackScrollView.visibility = visible
    }

    var playPath = ""

    private fun startPlay(path: String, isVideo: Boolean) {
        playPath = path
        showOrHideVideoModeView(isVideo)
        if (isVideo) {
            fetchVideoThumbnail(path)
        }

        mBinding.seekBar.isEnabled = true
        mBinding.seekBar.progress = 0

        mPlayViewModel.updateMuteState(false)
        mPlayViewModel.updatePlayState(true)

        mExecutors.submit {
            Log.i(TAG, "startPlay: start")
            val config = PlayerConfig().apply {
                decodeConfig = PlayerConfig.DecodeConfig.USE_FF_HW_DECODER
            }
            if (path.isEmpty()) {
                Toast.makeText(this@MainActivity,"Path cannot be empty",Toast.LENGTH_SHORT).show()
                return@submit
            }

            // 检查文件是否存在
            val file = File(path)
            if (!file.exists()) {
                Toast.makeText(this@MainActivity,"File not found: $path",Toast.LENGTH_SHORT).show()
                return@submit
            }
            if (!file.isFile) {
                Toast.makeText(this@MainActivity,"Path is not a file: $path",Toast.LENGTH_SHORT).show()
                return@submit
            }

            mPlayer.prepare(path, config, isVideo)
            val defaultGreyVal = if (isVideo) 0.5f else 0.0f
            (mPlayer as MyPlayer).updateFilterEffect(GreyFilter.VAL_PROGRESS, defaultGreyVal)
            mDuration = mPlayer.getDuration()
            Log.i(TAG, "startPlay: duration: $mDuration")

            runOnUiThread {
                if (isVideo && mDuration > 0) {
                    setupTimeline(mDuration)
                }
            }

            mPlayer.setListener(object : IMediaPlayerStatusListener {

                override fun onProgress(timestamp: Double) {
                    runOnUiThread {
                        if (!mIsSeeking) {
                            mBinding.seekBar.progress = ((timestamp / mDuration) * 100).toInt()
                            mBinding.tvTimer.text = CommonUtils.getTimeDesc(timestamp.toInt())
                            if (mIsVideo && mDuration > 0 && !mTrackUserScrolling
                                && !mBinding.trackScrollView.isPinchScaling
                            ) {
                                scrollTrackToTimeSec(timestamp)
                            }
                        }
                    }
                }

                override fun onComplete() {
                    runOnUiThread {
                        mBinding.seekBar.progress = 100
                        mPlayViewModel.updatePlayState(false)
                    }
                }

                override fun onFftAudioDataArrived(data: FloatArray) {
                    mBinding.audioVisualizeView.setFftAudioData(data)
                }

            })

            mPlayer.start()
        }
    }

    private fun setupTrackScrollView(headerWidthPx: Int) {
        mBinding.trackScrollView.setTimelineConfig(mTimelineConfig)
        mBinding.trackScrollView.setTrackHeaderWidthPx(headerWidthPx)
        mBinding.trackScrollView.setDurationSec(mDuration)
        mBinding.trackScrollView.setScaleEnabled(mIsVideo && mDuration > 0)
        mBinding.trackScrollView.setOnTimelineScaleListener { _, _ ->
            applyTimelineConfigToViews()
            applyTimelineLayout()
            updateTimelineScrollOffset()
        }
    }

    private fun setupTimeline(duration: Double) {
        mTimelineConfig.durationSec = duration
        if (mTimelineConfig.pxPerSecond <= 0) {
            mTimelineConfig.pxPerSecond =
                TimelineConfig.defaultPxPerSecond(mTimelineConfig.majorTickSpacingPx)
        }
        mBinding.trackScrollView.setDurationSec(duration)
        mBinding.trackScrollView.setScaleEnabled(mIsVideo && duration > 0)
        applyTimelineConfigToViews()
        applyTimelineLayout()
        updateTimelineScrollOffset()
    }

    private fun applyTimelineConfigToViews() {
        mBinding.timeScaleView.setTimelineConfig(mTimelineConfig)
        mBinding.videoThumbSliderView.setTimelineConfig(mTimelineConfig)
    }

    private fun applyTimelineLayout() {
        val contentW = mTimelineConfig.contentWidthPx.coerceAtLeast(1)
        mBinding.trackContent.layoutParams.width = contentW + mBinding.trackScrollView.width
        mBinding.trackContent.requestLayout()
        mBinding.trackContentView.requestLayout()
    }

    /**
     * 计算 timeline 在视口中的可见区间（本地坐标）。
     * 视口 [scrollX, scrollX+viewportW] 与 timeline [headerW, headerW+contentWidthPx] 求交；
     * 滑到开头左侧可能是 header，滑到尽头右侧可能是 tailer，可见宽会小于 viewportW。
     */
    private fun computeTimelineVisibleRange(
        scrollX: Int,
        viewportW: Int,
        headerW: Int,
        contentWidthPx: Int,
    ): Pair<Int, Int> {
        if (contentWidthPx <= 0 || viewportW <= 0) return 0 to 0
//        val visibleLeft = scrollX
//        val visibleRight = scrollX + mBinding.trackScrollView.width
//        return visibleLeft to visibleRight

        val timelineStart = headerW
        val timelineEnd = headerW + contentWidthPx
        val viewStart = scrollX
        val viewEnd = scrollX + viewportW
        if (viewEnd <= timelineStart || viewStart >= timelineEnd) {
            return 0 to 0
        }
        val intersectStart = maxOf(viewStart, timelineStart)
        val intersectEnd = minOf(viewEnd, timelineEnd)
        val visibleLeft = intersectStart - timelineStart
        val visibleRight = intersectEnd - timelineStart
        return visibleLeft to visibleRight
    }

    private fun updateTimelineScrollOffset() {
//        val headerW = trackHeaderWidthPx()
        val headerW = mBinding.trackScrollView.width/2
        val scrollX = mBinding.trackScrollView.scrollX
        val viewportW = mBinding.trackScrollView.width
        val contentWidthPx = mTimelineConfig.contentWidthPx.coerceAtLeast(0)
        val (visibleLeft, visibleRight) = computeTimelineVisibleRange(
            scrollX, viewportW, headerW, contentWidthPx,
        )
        val visibleTimelineW = (visibleRight - visibleLeft).coerceAtLeast(0)
        ALog.i("MainActivity-updateTimelineScrollOffset-"
                + " scrollX:" + scrollX
                + " viewportW:" + viewportW
                + " headerW:" + headerW
                + " contentWidthPx:" + contentWidthPx
                + " visibleLeft:" + visibleLeft
                + " visibleRight:" + visibleRight
                + " visibleTimelineW:" + visibleTimelineW
        )
        mBinding.timeScaleView.setVisibleRange(visibleLeft, visibleRight)
        mBinding.videoThumbSliderView.setContentScrollX(visibleLeft)
        mBinding.videoThumbSliderView.setViewportWidthPx(visibleTimelineW)
    }

    private fun getPlayheadTimeSec(): Double = mBinding.trackScrollView.getPlayheadTimeSec()

    private fun scrollTrackToTimeSec(timeSec: Double) {
        mBinding.trackScrollView.scrollToTimeSec(timeSec)
        updateTimelineScrollOffset()
    }

    private fun fetchVideoThumbnail(path: String) {
        if (mVideoPathForThumbnail == path) {
            Log.i(TAG, "fetchVideoThumbnail: has fetch")
            return
        }
        mVideoPathForThumbnail = path

        val width = CommonUtils.getScreenWidth(this) / 5
        mExecutors.submit {
            mVideoThumbnailViewModel.loadThumbnail(path, width, 0, 5, false) {
                runOnUiThread {
                    mVideoThumbnailViewModel.getData().value = it
                }
                return@loadThumbnail true
            }
        }
    }

    private fun stopPlay() {
        mPlayer.stop()
        Log.i(TAG, "stopPlay: done")
    }

    private fun getDemoVideoPath(): String {
        val path = "oceans.mp4"
        val videoPath = cacheDir.absolutePath + "/$path"
        FileUtils.copyFile2Path(assets.open(path), videoPath)
        return videoPath
    }

    private fun checkMediaFileValid(path: String, isVideo: Boolean) {
        if (FileUtils.fileExists(path)) {
            mMediaFilePath = path
            mIsVideo = isVideo
        } else {
            Log.e(TAG, "checkMediaFileValid: $path is not exists")
            mMediaFilePath = getDemoVideoPath()
            mIsVideo = true
            runOnUiThread {
                Toast.makeText(this, "文件不存在! 使用默认视频", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun exportGif() {
        val start = System.currentTimeMillis()
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        val gifPath = "${dir}/Camera/export_${System.currentTimeMillis()}.gif"

        Log.i(TAG, "exportGif start: $gifPath")
        FFMpegUtils.exportGif(mMediaFilePath, gifPath)
        val consume = System.currentTimeMillis() - start
        Log.i(TAG, "exportGif end, consume: ${consume}ms")

        runOnUiThread {
            mIsExporting = false
            Toast.makeText(this, "export gif consume: ${consume}ms", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportImage() {
        val start = System.currentTimeMillis()
        (mPlayer as MyPlayer).getCurrentImage { frameBuffer ->
            if (frameBuffer != null) {
                val displayName = "export_image_${System.currentTimeMillis()}"
                FileUtils.saveBitmapToLocal(contentResolver, frameBuffer.toBitmap(), displayName)
            } else {
                Log.e(TAG, "exportImage: failed")
            }
            val consume = System.currentTimeMillis() - start
            runOnUiThread {
                mIsExporting = false
                Toast.makeText(this, "export image consume: ${consume}ms", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_MOVE) {
            (mPlayer as MyPlayer).updateFilterEffect(GreyFilter.VAL_PROGRESS, event.x / mBinding.glSurfaceView.width)
        }
        return super.onTouchEvent(event)
    }

    /**
     * 显示媒体信息对话框
     */
    private fun showMediaInfoDialog() {
        if (false) {
            (mPlayer as MyPlayer).getMediaInfo()?.let {
                MediaInfoDialogHelper.syncShow(this, it)
            }
        } else {
            MediaInfoDialogHelper.asyncShow(this, playPath)
        }
    }
}