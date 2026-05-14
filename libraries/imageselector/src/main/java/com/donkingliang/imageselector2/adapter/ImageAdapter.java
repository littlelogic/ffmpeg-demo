package com.donkingliang.imageselector2.adapter;

import android.content.Context;
import androidx.recyclerview.widget.RecyclerView;

import android.text.format.DateUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.VideoDecoder;
import com.bumptech.glide.request.RequestOptions;
import com.donkingliang.imageselector.R;
import com.donkingliang.imageselector2.ImageSelectorActivity;
import com.donkingliang.imageselector2.entry.Media;
import com.donkingliang.imageselector2.utils.VersionUtils;

import com.xyq.libffplayer.ui.MediaInfoDialogHelper;

import java.util.ArrayList;

public class ImageAdapter extends RecyclerView.Adapter<ImageAdapter.ViewHolder> {


    ImageSelectorActivity imageSelectorActivity;
    private Context mContext;
    private ArrayList<Media> mImages;
    private LayoutInflater mInflater;

    //保存选中的图片
    private ArrayList<Media> mSelectImages = new ArrayList<>();
    private OnImageSelectListener mSelectListener;
    private OnItemClickListener mItemClickListener;
    private int mMaxCount;
    private boolean isSingle;
    private boolean isViewImage;

    private static final int TYPE_CAMERA = 1;
    private static final int TYPE_IMAGE = 2;

    private boolean useCamera;

    private boolean isAndroidQ = VersionUtils.isAndroidQ();

    /**
     * @param maxCount    图片的最大选择数量，小于等于0时，不限数量，isSingle为false时才有用。
     * @param isSingle    是否单选
     * @param isViewImage 是否点击放大图片查看
     */
    public ImageAdapter(ImageSelectorActivity imageSelectorActivity_, int maxCount, boolean isSingle, boolean isViewImage) {
        mContext = imageSelectorActivity_;
        imageSelectorActivity = imageSelectorActivity_;
        this.mInflater = LayoutInflater.from(mContext);
        mMaxCount = maxCount;
        this.isSingle = isSingle;
        this.isViewImage = isViewImage;
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        if (viewType == TYPE_IMAGE) {
            View view = mInflater.inflate(R.layout.adapter_images_item, parent, false);
            return new ViewHolder(view);
        } else {
            View view = mInflater.inflate(R.layout.adapter_camera, parent, false);
            return new ViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(final ViewHolder holder, int position) {
        if (getItemViewType(position) == TYPE_IMAGE) {
            final Media image = getImage(position);

            Log.i("qq","-231130pw4-SelectAdapter-onBindViewHolder-TYPE_IMAGE"
                    +" isAndroidQ:"+isAndroidQ
                    +" image.getUri():"+image.getUri()
                    +" image.getPath():"+image.getPath()
            );
            isAndroidQ = false;
            if (false) {
                Glide.with(mContext).load(isAndroidQ ? image.getUri() : image.getPath())
//                    .apply(new RequestOptions().diskCacheStrategy(DiskCacheStrategy.NONE))
                        .apply(new RequestOptions().diskCacheStrategy(DiskCacheStrategy.RESOURCE))
                        .into(holder.ivImage);
            }
            {
                RequestOptions lRequestBuilder = new RequestOptions().diskCacheStrategy(DiskCacheStrategy.RESOURCE);
//                lRequestBuilder.set(VideoDecoder.TARGET_FRAME, 1L);
                lRequestBuilder.set(VideoDecoder.TARGET_FRAME, 0L);
                lRequestBuilder.set(VideoDecoder.TARGET_FRAME, -1L);
                ///lRequestBuilder.getOptions().set()
                Glide.with(mContext).load(isAndroidQ ? image.getUri() : image.getPath())
//                    .apply(new RequestOptions().diskCacheStrategy(DiskCacheStrategy.NONE))
                        .apply(lRequestBuilder)
                        .into(holder.ivImage);
            }


            setItemSelect(holder, mSelectImages.contains(image));

            holder.ivGif.setVisibility(image.isGif() ? View.VISIBLE : View.GONE);
            holder.video_during.setVisibility(image.isVideo() ? View.VISIBLE : View.GONE);
            if (image.isVideo()) {
                holder.video_during.setVisibility(View.VISIBLE);
                long time = image.getDuring();
                //todo 时间转换可以优化
                if (time < 1000) {
                    holder.video_during.setText(DateUtils.formatElapsedTime(1));
                } else {
                    holder.video_during.setText(DateUtils.formatElapsedTime(time / 1000));
                }
            } else {
                 holder.video_during.setVisibility(View.GONE);
            }

            if (holder.btnMediaInfo != null) {
                if (image.isVideo()) {
                    holder.btnMediaInfo.setVisibility(View.VISIBLE);
                    holder.btnMediaInfo.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            String path = image.getPath();
                            if (path != null && !path.isEmpty()) {
                                MediaInfoDialogHelper.asyncShow(imageSelectorActivity, path);
                            } else {
                                Toast.makeText(mContext, "无法读取视频路径", Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                } else {
                    holder.btnMediaInfo.setVisibility(View.GONE);
                    holder.btnMediaInfo.setOnClickListener(null);
                }
            }

            //点击选中/取消选中图片
            holder.ivSelectIcon.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    checkedImage(holder, image);
                }
            });

//            holder.itemView.setOnClickListener(new View.OnClickListener() {
//                @Override
//                public void onClick(View v) {
//                    if (isViewImage) {
//                        if (mItemClickListener != null) {
//                            int p = holder.getAdapterPosition();
//                            mItemClickListener.OnItemClick(image, useCamera ? p - 1 : p);
//                        }
//                    } else {
//                        checkedImage(holder, image);
//                    }
//                }
//            });
            holder.maskTextView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    checkedMedia(holder, image);
                }
            });

            if (image.index_select > 0) {
                holder.maskTextView.setVisibility(View.VISIBLE);
                holder.maskTextView.setText(String.valueOf(image.index_select));
                holder.maskTextView.setAlpha(1);
            } else {
                holder.maskTextView.setVisibility(View.VISIBLE);
                holder.maskTextView.setAlpha(0);
            }

        } else if (getItemViewType(position) == TYPE_CAMERA) {
            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mItemClickListener != null) {
                        mItemClickListener.OnCameraClick();
                    }
                }
            });
        }
    }

    @Override
    public int getItemViewType(int position) {
        if (useCamera && position == 0) {
            return TYPE_CAMERA;
        } else {
            return TYPE_IMAGE;
        }
    }

    private void checkedImage(ViewHolder holder, Media image) {
        if (mSelectImages.contains(image)) {
            //如果图片已经选中，就取消选中
            unSelectImage(image);
            setItemSelect(holder, false);
        } else if (isSingle) {
            //如果是单选，就先清空已经选中的图片，再选中当前图片
            clearImageSelect();
            selectImage(image);
            setItemSelect(holder, true);
        } else if (mMaxCount <= 0 || mSelectImages.size() < mMaxCount) {
            //如果不限制图片的选中数量，或者图片的选中数量
            // 还没有达到最大限制，就直接选中当前图片。
            selectImage(image);
            setItemSelect(holder, true);
        }
    }

    private void checkedMedia(ViewHolder holder, Media image) {
        if (image.index_select <= 0) {
            imageSelectorActivity.addMedia(image);
        } else {
            imageSelectorActivity.removeMedia(image);
        }
    }

    /**
     * 选中图片
     *
     * @param image
     */
    private void selectImage(Media image) {
        mSelectImages.add(image);
        if (mSelectListener != null) {
            mSelectListener.OnImageSelect(image, true, mSelectImages.size());
        }
    }

    /**
     * 取消选中图片
     *
     * @param image
     */
    private void unSelectImage(Media image) {
        mSelectImages.remove(image);
        if (mSelectListener != null) {
            mSelectListener.OnImageSelect(image, false, mSelectImages.size());
        }
    }


    @Override
    public int getItemCount() {
        return useCamera ? getImageCount() + 1 : getImageCount();
    }

    private int getImageCount() {
        return mImages == null ? 0 : mImages.size();
    }

    public ArrayList<Media> getData() {
        return mImages;
    }

    public void refresh(ArrayList<Media> data, boolean useCamera) {
        mImages = data;
        this.useCamera = useCamera;
        notifyDataSetChanged();
    }

    private Media getImage(int position) {
        return mImages.get(useCamera ? position - 1 : position);
    }

    public Media getFirstVisibleImage(int firstVisibleItem) {
        if (mImages != null && !mImages.isEmpty()) {
            if (useCamera) {
                return mImages.get(firstVisibleItem > 0 ? firstVisibleItem - 1 : 0);
            } else {
                return mImages.get(firstVisibleItem < 0 ? 0 : firstVisibleItem);
            }
        }
        return null;
    }

    /**
     * 设置图片选中和未选中的效果
     */
    private void setItemSelect(ViewHolder holder, boolean isSelect) {
        if (isSelect) {
            holder.ivSelectIcon.setImageResource(R.drawable.icon_image_select);
            holder.ivMasking.setAlpha(0.5f);
        } else {
            holder.ivSelectIcon.setImageResource(R.drawable.icon_image_un_select);
            holder.ivMasking.setAlpha(0.2f);
        }
    }

    private void clearImageSelect() {
        if (mImages != null && mSelectImages.size() == 1) {
            int index = mImages.indexOf(mSelectImages.get(0));
            mSelectImages.clear();
            if (index != -1) {
                notifyItemChanged(useCamera ? index + 1 : index);
            }
        }
    }

    public void setSelectedImages(ArrayList<String> selected) {
        if (mImages != null && selected != null) {
            for (String path : selected) {
                if (isFull()) {
                    return;
                }
                for (Media image : mImages) {
                    if (path.equals(image.getPath())) {
                        if (!mSelectImages.contains(image)) {
                            mSelectImages.add(image);
                        }
                        break;
                    }
                }
            }
            notifyDataSetChanged();
        }
    }


    private boolean isFull() {
        if (isSingle && mSelectImages.size() == 1) {
            return true;
        } else if (mMaxCount > 0 && mSelectImages.size() == mMaxCount) {
            return true;
        } else {
            return false;
        }
    }

    public ArrayList<Media> getSelectImages() {
        return mSelectImages;
    }

    public void setOnImageSelectListener(OnImageSelectListener listener) {
        this.mSelectListener = listener;
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.mItemClickListener = listener;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {

        ImageView ivImage;
        ImageView ivSelectIcon;
        ImageView ivMasking;
        ImageView ivGif;
        ImageView ivCamera;
        ImageView btnMediaInfo;
        TextView video_during;
        TextView maskTextView;
        public ViewHolder(View itemView) {
            super(itemView);
            ivImage = itemView.findViewById(R.id.iv_image);
            ivSelectIcon = itemView.findViewById(R.id.iv_select);
            ivMasking = itemView.findViewById(R.id.iv_masking);
            ivGif = itemView.findViewById(R.id.iv_gif);

            ivCamera = itemView.findViewById(R.id.iv_camera);
            btnMediaInfo = itemView.findViewById(R.id.btn_media_info);
            video_during = itemView.findViewById(R.id.video_during);
            maskTextView = itemView.findViewById(R.id.maskTextView);
        }
    }

    public interface OnImageSelectListener {
        void OnImageSelect(Media image, boolean isSelect, int selectCount);
    }

    public interface OnItemClickListener {
        void OnItemClick(Media image, int position);

        void OnCameraClick();
    }
}
