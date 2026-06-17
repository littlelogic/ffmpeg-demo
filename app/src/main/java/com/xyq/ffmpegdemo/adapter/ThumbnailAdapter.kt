package com.xyq.ffmpegdemo.adapter

import android.content.Context
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.xyq.ffmpegdemo.R
import com.xyq.ffmpegdemo.entity.Thumbnail

class ThumbnailAdapter(
    private val context: Context,
    private var thumbnails: ArrayList<Thumbnail>
): RecyclerView.Adapter<ThumbnailAdapter.MyViewHolder>() {

    companion object {
        private const val TAG = "ThumbnailAdapter"
    }


    /**
     * 获得屏幕高度
     *
     * @param context
     * @return
     */
    fun getScreenHeight(context: Context): Int {
        val wm = context
            .getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val outMetrics = DisplayMetrics()
        wm.getDefaultDisplay().getMetrics(outMetrics)
        return outMetrics.heightPixels
    }

    /**
     * 获得屏幕宽度
     *
     * @param context
     * @return
     */
    fun getScreenWidth(context: Context): Int {
        val wm = context
            .getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val outMetrics = DisplayMetrics()
        wm.getDefaultDisplay().getMetrics(outMetrics)
        return outMetrics.widthPixels
    }

    /**
     * dp转px
     *
     * @param context
     * @param dpVal
     * @return
     */
    fun dp2px(context: Context, dpVal: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dpVal, context.getResources().getDisplayMetrics()
        ).toInt()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MyViewHolder {
        val width = getScreenWidth(context) / 5
        val height = dp2px(context, 60f)
        return MyViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.thumbnail_item, parent, false), width, height)
    }

    override fun getItemCount(): Int {
        return thumbnails.size
    }

    override fun onBindViewHolder(holder: MyViewHolder, position: Int) {
        val model = thumbnails[position]
        holder.ivThumb.setImageBitmap(model.bitmap)
    }

    fun addData(thumbnail: Thumbnail) {
        Log.i(TAG, "addData: ${thumbnail.index}, size: ${thumbnails.size}")
        if (thumbnails.size <= thumbnail.index) {
            thumbnails.add(thumbnail)
        } else {
            thumbnails[thumbnail.index] = thumbnail
        }
        notifyItemChanged(thumbnail.index)
    }

    class MyViewHolder(view: View, w: Int, h: Int) : RecyclerView.ViewHolder(view) {
        val ivThumb: ImageView

        init {
            ivThumb = view.findViewById(R.id.iv_thumbnail)
            ivThumb.layoutParams = FrameLayout.LayoutParams(w, h)
        }
    }
}