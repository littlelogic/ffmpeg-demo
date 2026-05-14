package com.donkingliang.imageselector2;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.MediaStore;
import android.provider.Settings;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.os.EnvironmentCompat;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager.widget.ViewPager;

import android.text.format.DateUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.VideoDecoder;
import com.bumptech.glide.request.RequestOptions;
import com.donkingliang.imageselector.R;
import com.donkingliang.imageselector2.adapter.FolderAdapter;
import com.donkingliang.imageselector2.entry.Folder;
import com.donkingliang.imageselector2.entry.Media;
import com.donkingliang.imageselector2.entry.RequestConfig;
import com.donkingliang.imageselector2.model.ImageModel;
import com.donkingliang.imageselector2.utils.ImageSelector;
import com.donkingliang.imageselector2.utils.ImageUtil;
import com.donkingliang.imageselector2.utils.UriUtils;
import com.donkingliang.imageselector2.utils.VersionUtils;
import com.donkingliang.imageselector2.view.CutFrameLayout;
import com.donkingliang.imageselector2.view.SelectItemDecoration;
import com.ogaclejapan.smarttablayout.SmartTabLayoutExtend;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class ImageSelectorActivity extends AppCompatActivity {

    /**
     * 启动图片选择器
     *
     * @param activity
     * @param requestCode
     * @param config
     */
    public static void openActivity(Activity activity, int requestCode, RequestConfig config) {
        Intent intent = new Intent(activity, ImageSelectorActivity.class);
        intent.putExtra(ImageSelector.KEY_CONFIG, config);
        activity.startActivityForResult(intent, requestCode);
    }

/**/    /**
     * 启动图片选择器
     *
     * @param fragment
     * @param requestCode
     * @param config
     */
    public static void openActivity(androidx.fragment.app.Fragment fragment, int requestCode, RequestConfig config) {
        Intent intent = new Intent(fragment.getActivity(), ImageSelectorActivity.class);
        intent.putExtra(ImageSelector.KEY_CONFIG, config);
        fragment.startActivityForResult(intent, requestCode);
    }

    /**
     * 启动图片选择器
     *
     * @param fragment
     * @param requestCode
     * @param config
     */
    public static void openActivity(android.app.Fragment fragment, int requestCode, RequestConfig config) {
        Intent intent = new Intent(fragment.getActivity(), ImageSelectorActivity.class);
        intent.putExtra(ImageSelector.KEY_CONFIG, config);
        fragment.startActivityForResult(intent, requestCode);
    }

    private TextView tvTime;
    private TextView tvFolderName;
    private TextView tvConfirm;
    private TextView tvPreview;
    private FrameLayout btnConfirm;
    private FrameLayout btnPreview;

    private CutFrameLayout folderOutFl;
    private ImageView folderExpandIv;
    private RecyclerView rvFolder;

    private RecyclerView selectRv;
    private SelectItemDecoration selectItemDecoration;
    private SelectAdapter selectAdapter;
    TextView select_num;
    TextView select_hint;
    TextView sure;
    float sure_unable = 0.3f;

    private ArrayList<Folder> mFolders;
    private Folder mFolder;
    private boolean applyLoadImage = false;
    private boolean applyCamera = false;
    private static final int PERMISSION_WRITE_EXTERNAL_REQUEST_CODE = 0x00000011;
    private static final int PERMISSION_CAMERA_REQUEST_CODE = 0x00000012;

    private static final int CAMERA_REQUEST_CODE = 0x00000010;
    private Uri mCameraUri;
    private String mCameraImagePath;
    private long mTakeTime;

    private boolean isOpenFolder;
    private boolean isShowTime;
    private boolean isInitFolder;
    private boolean isSingle;
    private boolean canPreview = true;
    private int mMaxCount;
    private int mMinCount = 1;

    private boolean useCamera = true;
    private boolean onlyTakePhoto = false;
    ImageModel.Type type = ImageModel.Type.All;

    private Handler mHideHandler = new Handler();
    private Runnable mHide = new Runnable() {
        @Override
        public void run() {
            hideTime();
        }
    };

    //用于接收从外面传进来的已选择的图片列表。当用户原来已经有选择过图片，现在重新打开选择器，允许用
    // 户把先前选过的图片传进来，并把这些图片默认为选中状态。
    private ArrayList<String> mSelectedImages;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        RequestConfig config = intent.getParcelableExtra(ImageSelector.KEY_CONFIG);
        if (config == null) {
            config = new RequestConfig();
            config.useCamera = false;
            config.canPreview = false;
            config.maxSelectCount = 9;
            config.minSelectCount = 1;
            config.type = ImageModel.Type.All;
        }
        mMinCount = config.minSelectCount;
        mMaxCount = config.maxSelectCount;
        canPreview = config.canPreview;
        useCamera = config.useCamera;
        mSelectedImages = config.selected;
        onlyTakePhoto = config.onlyTakePhoto;
        type = config.type;
        {
            isSingle = (mMinCount == mMaxCount && mMinCount == 1);
        }
        if (onlyTakePhoto) {
            // 仅拍照
            checkPermissionAndCamera();
        } else {
            setContentView(R.layout.activity_image_select);
            ///setStatusBarTransparent();
            setStatusBarColor();
            initView();
            initListener();
            initImageList();
            checkPermissionAndLoadImages();
            hideFolderList();
            setSelectImageCount(0);
        }
    }

    /**
     * 修改状态栏颜色
     */
    private void setStatusBarColor() {
        if (VersionUtils.isAndroidL()) {
            Window window = getWindow();
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(Color.parseColor("#0D0D0D"));
        }
    }

    private void setStatusBarTransparent() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Window window = this.getWindow();
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(Color.TRANSPARENT);
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                this.getWindow().setStatusBarColor(0xFF_FF_CB_00);
            }
        }
    }

    private ViewPager typeViewPager;
    private TypePageAdapter typePageAdapter;
    private SmartTabLayoutExtend tabLayout;
    protected ArrayList<String> typeList;

    @SuppressLint("SetTextI18n")
    private void initView() {
        folderOutFl = findViewById(R.id.rv_folder_out);
        folderExpandIv = findViewById(R.id.album_open_img);
        rvFolder = findViewById(R.id.rv_folder);
        tvConfirm = findViewById(R.id.tv_confirm);
        tvPreview = findViewById(R.id.tv_preview);
        btnConfirm = findViewById(R.id.btn_confirm);
        btnPreview = findViewById(R.id.btn_preview);
        tvFolderName = findViewById(R.id.tv_folder_name);
        tvTime = findViewById(R.id.tv_time);

        tabLayout = findViewById(R.id.tabLayout);
        typeViewPager = findViewById(R.id.viewPager);
        typeList = new ArrayList<>();
        Resources resources = this.getResources();

        /*typeList.add(resources.getString(R.string.tab_title_all));
        typeList.add(resources.getString(R.string.tab_title_video));
        typeList.add(resources.getString(R.string.tab_title_image));*/
        if ((type.value() & ImageModel.Type.Image.value()) == ImageModel.Type.Image.value() ) {
            typeList.add(resources.getString(R.string.tab_title_image));
        }
        if ((type.value() & ImageModel.Type.Video.value()) == ImageModel.Type.Video.value() ) {
            typeList.add(resources.getString(R.string.tab_title_video));
        }
        if ((type.value() & ImageModel.Type.All.value()) == ImageModel.Type.All.value() ) {
            typeList.add(resources.getString(R.string.tab_title_all));
        }

        typePageAdapter = new TypePageAdapter(this, this.getSupportFragmentManager(), typeList);
        typeViewPager.setAdapter(typePageAdapter);
        typePageAdapter.notifyDataSetChanged();
        tabLayout.setViewPager(typeViewPager);
        tabLayout.setIndicatorWidth(8);

        typeViewPager.setPageMargin(0);
        typeViewPager.setPageMarginDrawable(new ColorDrawable(getResources().getColor(android.R.color.holo_green_dark)));
        typeViewPager.setOffscreenPageLimit(2);

        selectRv = findViewById(R.id.select_recyclerView);
        select_num = findViewById(R.id.select_num);
        select_hint = findViewById(R.id.select_hint);
        if (mMinCount == 1) {
            ////"（单次最多添加" + mMinCount + "段素材）"
            select_hint.setText(this.getString(R.string.select_hint_maximum_0) + mMaxCount +
                    this.getString(R.string.select_hint_maximum_1));
        } else {
            /// "最少" + minSelectNum + "素材，最多" + maxSelectNum + "素材"
            select_hint.setText(this.getString(R.string.select_hint_minimum_0) + mMinCount +
                    this.getString(R.string.select_hint_minimum_1) + mMaxCount +this.getString(R.string.select_hint_minimum_2));
        }
        select_num.setText(0+"/"+mMaxCount);

        sure = findViewById(R.id.sure);
        sure.setAlpha(sure_unable);
        sure.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (sure.getAlpha() >= 1f) {
                    confirm();
                }
            }
        });
        if (selectItemDecoration == null) {
            int sideMargin = ImageUtil.dip2px(this, 12);
            int itemMargin = ImageUtil.dip2px(this, 8);
            selectItemDecoration = new SelectItemDecoration(sideMargin, itemMargin);
        }
        selectRv.removeItemDecoration(selectItemDecoration);
        selectRv.addItemDecoration(selectItemDecoration);
        selectRv.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false));
        selectAdapter = new SelectAdapter(this, mMaxCount, isSingle);
        selectRv.setAdapter(selectAdapter);

    }


    private void refreshSelectRv(){
        selectAdapter.refresh(mMedias);
        selectRv.setVisibility
                ( mMedias.size() > 0 ? View.VISIBLE : View.GONE);
    }


    private void initListener() {
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        btnPreview.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
//                ArrayList<Media> images = new ArrayList<>();
//                images.addAll(mAdapter.getSelectImages());
//                toPreviewActivity(images, 0);
            }
        });

        btnConfirm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirm();
            }
        });

        findViewById(R.id.btn_folder).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isInitFolder) {
                    if (isOpenFolder) {
                        closeFolder();
                    } else {
                        openFolder();
                    }
                }
            }
        });


//        rvImage.addOnScrollListener(new RecyclerView.OnScrollListener() {
//            @Override
//            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
//                super.onScrollStateChanged(recyclerView, newState);
//                changeTime();
//            }
//
//            @Override
//            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
//                super.onScrolled(recyclerView, dx, dy);
//                changeTime();
//            }
//        });
    }

    /**
     * 初始化图片列表
     */
    private void initImageList() {
//        // 判断屏幕方向
//        Configuration configuration = getResources().getConfiguration();
//        if (configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
//            mLayoutManager = new GridLayoutManager(this, 3);
//        } else {
//            mLayoutManager = new GridLayoutManager(this, 5);
//        }

//        rvImage.setLayoutManager(mLayoutManager);
//        mAdapter = new SelectAdapter(this, mMaxCount, isSingle, canPreview);
//        rvImage.setAdapter(mAdapter);
//        ((SimpleItemAnimator) rvImage.getItemAnimator()).setSupportsChangeAnimations(false);
        if (mFolders != null && !mFolders.isEmpty()) {
            setFolder(mFolders.get(0));
        }
//        mAdapter.setOnImageSelectListener(new SelectAdapter.OnImageSelectListener() {
//            @Override
//            public void OnImageSelect(Media image, boolean isSelect, int selectCount) {
//                setSelectImageCount(selectCount);
//            }
//        });
//        mAdapter.setOnItemClickListener(new SelectAdapter.OnItemClickListener() {
//            @Override
//            public void OnItemClick(Media image, int position) {
//                toPreviewActivity(mAdapter.getData(), position);
//            }
//
//            @Override
//            public void OnCameraClick() {
//                checkPermissionAndCamera();
//            }
//        });
    }

    /**
     * 初始化图片文件夹列表
     */
    private void initFolderList() {
        if (mFolders != null && !mFolders.isEmpty()) {
            isInitFolder = true;
            rvFolder.setLayoutManager(new LinearLayoutManager(ImageSelectorActivity.this));
            FolderAdapter adapter = new FolderAdapter(ImageSelectorActivity.this, mFolders);
            adapter.setOnFolderSelectListener(new FolderAdapter.OnFolderSelectListener() {
                @Override
                public void OnFolderSelect(Folder folder) {
                    setFolder(folder);
                    closeFolder();
                }
            });
            rvFolder.setAdapter(adapter);
        }
    }



    /**
     * 设置选中的文件夹，同时刷新图片列表
     *
     * @param folder
     */
    private void setFolder(Folder folder) {
        if (folder != null && !folder.equals(mFolder)) {
            mFolder = folder;
            tvFolderName.setText(folder.getName());
            typePageAdapter.refresh(folder,folder.isUseCamera());
        }
    }

    public void setSelectImageCount(int count) {
        if (count == 0) {
            btnConfirm.setEnabled(false);
            btnPreview.setEnabled(false);
            tvConfirm.setText(R.string.selector_send);
            tvPreview.setText(R.string.selector_preview);
        } else {
            btnConfirm.setEnabled(true);
            btnPreview.setEnabled(true);
            tvPreview.setText(getString(R.string.selector_preview) + "(" + count + ")");
            if (isSingle) {
                tvConfirm.setText(R.string.selector_send);
            } else if (mMaxCount > 0) {
                tvConfirm.setText(getString(R.string.selector_send) + "(" + count + "/" + mMaxCount + ")");
            } else {
                tvConfirm.setText(getString(R.string.selector_send) + "(" + count + ")");
            }
        }
    }

    ///====================================

    private void hideFolderList_old() {
        rvFolder.post(new Runnable() {
            @Override
            public void run() {
                rvFolder.setTranslationY(rvFolder.getHeight());
                rvFolder.setVisibility(View.GONE);
                rvFolder.setBackgroundColor(Color.WHITE);
            }
        });
    }

    private void hideFolderList() {
        folderOutFl.post(new Runnable() {
            @Override
            public void run() {
                folderOutFl.setToTopDisappear(null);
                folderOutFl.setVisibility(View.GONE);
                ///folderOutFl.setBackgroundColor(Color.WHITE);
            }
        });
    }

    private void openFolder_old() {
        if (!isOpenFolder) {
            ObjectAnimator animator = ObjectAnimator.ofFloat(rvFolder, "translationY",
                    rvFolder.getHeight(), 0).setDuration(300);
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationStart(Animator animation) {
                    super.onAnimationStart(animation);
                    rvFolder.setVisibility(View.VISIBLE);
                }
            });
            animator.start();
            isOpenFolder = true;
        }
    }

    private void openFolder() {
        if (!isOpenFolder) {
            folderOutFl.setToTopDisappear(true);
            folderOutFl.setVisibility(View.VISIBLE);
            ValueAnimator animator = ValueAnimator.ofFloat(0f,1f)
                    .setDuration(300);
            animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    float value = (float) animation.getAnimatedValue();
                    Log.i("2l","--ImageSelectorActivity-openFolder-onAnimationUpdate-value:"+value);
                    folderOutFl.setDisappearPer(value);
                    folderOutFl.invalidate();
                }
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    folderOutFl.setToTopDisappear(null);
                }
            });
            animator.start();
            isOpenFolder = true;

            folderExpandIv.animate().rotation(-180).setDuration(300).start();
        }
    }

    private void closeFolder_old() {
        if (isOpenFolder) {
            folderOutFl.setToTopDisappear(true);
            ObjectAnimator animator = ObjectAnimator.ofFloat(rvFolder, "translationY",
                    0, rvFolder.getHeight()).setDuration(300);
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    rvFolder.setVisibility(View.GONE);
                }
            });
            animator.start();
            isOpenFolder = false;
        }
    }

    private void closeFolder() {
        if (isOpenFolder) {
            folderOutFl.setToTopDisappear(true);
            ValueAnimator animator = ValueAnimator.ofFloat(1f,0f)
                    .setDuration(300);
            animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    float value = (float) animation.getAnimatedValue();
                    folderOutFl.setDisappearPer(value);
                    folderOutFl.invalidate();
                }
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    folderOutFl.setToTopDisappear(null);
                    folderOutFl.setVisibility(View.GONE);
                }
            });
            animator.start();
            isOpenFolder = false;
            folderExpandIv.animate().rotation(0).setDuration(300).start();
        }
    }

    ///====================================

    /**
     * 隐藏时间条
     */
    private void hideTime() {
        if (isShowTime) {
            ObjectAnimator.ofFloat(tvTime, "alpha", 1, 0).setDuration(300).start();
            isShowTime = false;
        }
    }

    ///------------------

    private ArrayList<Media> mMedias = new ArrayList<>();
    ArrayList<Media> mMedias_temp = new ArrayList<>(mMaxCount);

    public void addMedia(Media media){
        if (mMedias.size() >= mMaxCount) {
            if (isSingle) {
                mMedias.get(0).index_select = 0;
                mMedias.clear();
            } else {
                Toast.makeText(this, this.getString(R.string.failMaxedCount), Toast.LENGTH_SHORT).show();
                return;
            }
        }
        mMedias.add(media);
        media.index_select = mMedias.size();
        updateDate();
    }

    public void removeMedia(Media media){
        int index = 1;
        mMedias_temp.clear();
        for (Media item : mMedias) {
            if (item == media) {
                media.index_select = 0;
            } else {
                item.index_select = index++;
                mMedias_temp.add(item);
            }
        }
        mMedias.clear();
        mMedias.addAll(mMedias_temp);
        updateDate();
    }

    @SuppressLint("SetTextI18n")
    private void updateDate() {
        ///typePageAdapter.notifyDataSetChanged();
        typePageAdapter.notifyAllChildPager();
        refreshSelectRv();
        select_num.setText(mMedias.size()+"/"+mMaxCount);
        sure.setAlpha(mMedias.size() >= mMinCount ? 1f : sure_unable);
    }

    ///------------------


    /**
     * 显示时间条
     */
    private void showTime() {
        if (!isShowTime) {
            ObjectAnimator.ofFloat(tvTime, "alpha", 0, 1).setDuration(300).start();
            isShowTime = true;
        }
    }

    /**
     * 改变时间条显示的时间（显示图片列表中的第一个可见图片的时间）
     */
    private void changeTime() {
//        int firstVisibleItem = getFirstVisibleItem();
//        Media image = mAdapter.getFirstVisibleImage(firstVisibleItem);
//        if (image != null) {
//            String time = DateUtils.getImageTime(this, image.getAddTime());
//            tvTime.setText(time);
//            showTime();
//            mHideHandler.removeCallbacks(mHide);
//            mHideHandler.postDelayed(mHide, 1500);
//        }
    }


    private void confirm() {
        ArrayList<String> images = new ArrayList<>();
        for (Media image : mMedias) {
            images.add(image.getPath());
        }
        saveImageAndFinish(images, false);
    }

    private void saveImageAndFinish(final ArrayList<String> images, final boolean isCameraImage) {
        //点击确定，把选中的图片通过Intent传给上一个Activity。
        setResult(images, isCameraImage);
        finish();
    }

    private void setResult(ArrayList<String> images, boolean isCameraImage) {
        Intent intent = new Intent();
        intent.putStringArrayListExtra(ImageSelector.SELECT_RESULT, images);
        intent.putExtra(ImageSelector.IS_CAMERA_IMAGE, isCameraImage);
        setResult(RESULT_OK, intent);
    }

    private void toPreviewActivity(ArrayList<Media> images, int position) {
//        if (images != null && !images.isEmpty()) {
//            for (Media item : images) {
//                Log.i("qq","-231130pw4-ImageSelectorActivity-toPreviewActivity-"
//                        +" item.getUri():"+item.getUri()
//                        +" item.getPath():"+item.getPath()
//                );
//            }
//            PreviewActivity.openActivity(this, images,
//                    mAdapter.getSelectImages(), isSingle, mMaxCount, position);
//        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (applyLoadImage) {
            applyLoadImage = false;
            checkPermissionAndLoadImages();
        }
        if (applyCamera) {
            applyCamera = false;
            checkPermissionAndCamera();
        }
    }

    /**
     * 处理图片预览页返回的结果
     *
     * @param requestCode
     * @param resultCode
     * @param data
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == ImageSelector.RESULT_CODE) {
//            if (data != null && data.getBooleanExtra(ImageSelector.IS_CONFIRM, false)) {
//                //如果用户在预览页点击了确定，就直接把用户选中的图片返回给用户。
//                confirm();
//            } else {
//                //否则，就刷新当前页面。
//                mAdapter.notifyDataSetChanged();
//                setSelectImageCount(mAdapter.getSelectImages().size());
//            }
        } else if (requestCode == CAMERA_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                ArrayList<String> images = new ArrayList<>();
                Uri savePictureUri = null;
                if (VersionUtils.isAndroidQ()) {
                    savePictureUri = mCameraUri;
                    images.add(UriUtils.getPathForUri(this, mCameraUri));
                } else {
                    savePictureUri = Uri.fromFile(new File(mCameraImagePath));
                    images.add(mCameraImagePath);
                }
                ImageUtil.savePicture(this,savePictureUri,mTakeTime);
                saveImageAndFinish(images, true);
            } else {
                if (onlyTakePhoto) {
                    finish();
                }
            }
        }
    }

    /**
     * 横竖屏切换处理
     *
     * @param newConfig
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        /*if (mLayoutManager != null && mAdapter != null) {
            //切换为竖屏
            if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
                mLayoutManager.setSpanCount(3);
            }
            //切换为横屏
            else if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                mLayoutManager.setSpanCount(5);
            }
            mAdapter.notifyDataSetChanged();
        }*/
    }

    /**
     * 检查权限并加载SD卡里的图片。
     */
    private void checkPermissionAndLoadImages() {
        if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
//            Toast.makeText(this, "没有图片", Toast.LENGTH_LONG).show();
            return;
        }
        int hasWriteExternalPermission = ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (true|| hasWriteExternalPermission == PackageManager.PERMISSION_GRANTED) {
            //有权限，加载图片。
            loadImageForSDCard();
        } else {
            //没有权限，申请权限。
            ActivityCompat.requestPermissions(ImageSelectorActivity.this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_WRITE_EXTERNAL_REQUEST_CODE);
        }
    }

    /**
     * 检查权限并拍照。
     */
    private void checkPermissionAndCamera() {
        int hasCameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA);
        int hasWriteExternalPermission = ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (hasCameraPermission == PackageManager.PERMISSION_GRANTED
                && hasWriteExternalPermission == PackageManager.PERMISSION_GRANTED) {
            //有调起相机拍照。
            openCamera();
        } else {
            //没有权限，申请权限。
            ActivityCompat.requestPermissions(ImageSelectorActivity.this,
                    new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_CAMERA_REQUEST_CODE);
        }
    }

    /**
     * 处理权限申请的回调。
     *
     * @param requestCode
     * @param permissions
     * @param grantResults
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERMISSION_WRITE_EXTERNAL_REQUEST_CODE) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                //允许权限，加载图片。
                loadImageForSDCard();
            } else {
                //拒绝权限，弹出提示框。
                showExceptionDialog(true);
            }
        } else if (requestCode == PERMISSION_CAMERA_REQUEST_CODE) {
            if (grantResults.length > 1
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                //允许权限，有调起相机拍照。
                openCamera();
            } else {
                //拒绝权限，弹出提示框。
                showExceptionDialog(false);
            }
        }
    }

    /**
     * 发生没有权限等异常时，显示一个提示dialog.
     */
    private void showExceptionDialog(final boolean applyLoad) {
        new AlertDialog.Builder(this)
                .setCancelable(false)
                .setTitle(R.string.selector_hint)
                .setMessage(R.string.selector_permissions_hint)
                .setNegativeButton(R.string.selector_cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.cancel();
                        finish();
                    }
                }).setPositiveButton(R.string.selector_confirm, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
                startAppSettings();
                if (applyLoad) {
                    applyLoadImage = true;
                } else {
                    applyCamera = true;
                }
            }
        }).show();
    }

    /**
     * 从SDCard加载图片。
     */
    private void loadImageForSDCard() {
        ImageModel.loadImageForSDCard(this,type, new ImageModel.DataCallback() {
            @Override
            public void onSuccess(ArrayList<Folder> folders) {
                mFolders = folders;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (mFolders != null && !mFolders.isEmpty()) {
                            initFolderList();
                            mFolders.get(0).setUseCamera(useCamera);
                            setFolder(mFolders.get(0));
                            {


                            }
                        }
                    }
                });
            }
        });
    }

    /**
     * 调起相机拍照
     */
    private void openCamera() {
        Intent captureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (captureIntent.resolveActivity(getPackageManager()) != null) {
            File photoFile = null;
            Uri photoUri = null;

            if (VersionUtils.isAndroidQ()) {
                photoUri = createImagePathUri();
            } else {
                try {
                    photoFile = createImageFile();
                } catch (IOException e) {
                    e.printStackTrace();
                }

                if (photoFile != null) {
                    mCameraImagePath = photoFile.getAbsolutePath();
                    if (VersionUtils.isAndroidN()) {
                        //通过FileProvider创建一个content类型的Uri
                        photoUri = FileProvider.getUriForFile(this, getPackageName() + ".imageSelectorProvider", photoFile);
                    } else {
                        photoUri = Uri.fromFile(photoFile);
                    }
                }
            }

            mCameraUri = photoUri;
            if (photoUri != null) {
                captureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
                captureIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                startActivityForResult(captureIntent, CAMERA_REQUEST_CODE);
                mTakeTime = System.currentTimeMillis();
            }
        }
    }

    /**
     * 创建一条图片地址uri,用于保存拍照后的照片
     *
     * @return 图片的uri
     */
    public Uri createImagePathUri() {
        String status = Environment.getExternalStorageState();
        SimpleDateFormat timeFormatter = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
        long time = System.currentTimeMillis();
        String imageName = timeFormatter.format(new Date(time));
        // ContentValues是我们希望这条记录被创建时包含的数据信息
        ContentValues values = new ContentValues(2);
        values.put(MediaStore.Images.Media.DISPLAY_NAME, imageName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        // 判断是否有SD卡,优先使用SD卡存储,当没有SD卡时使用手机存储
        if (status.equals(Environment.MEDIA_MOUNTED)) {
            return getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        } else {
            return getContentResolver().insert(MediaStore.Images.Media.INTERNAL_CONTENT_URI, values);
        }
    }

    private File createImageFile() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String imageFileName = String.format("JPEG_%s.jpg", timeStamp);
        File storageDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES);
        if (!storageDir.exists()) {
            storageDir.mkdir();
        }
        File tempFile = new File(storageDir, imageFileName);
        if (!Environment.MEDIA_MOUNTED.equals(EnvironmentCompat.getStorageState(tempFile))) {
            return null;
        }
        return tempFile;
    }

    /**
     * 启动应用的设置
     */
    private void startAppSettings() {
        Intent intent = new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN && isOpenFolder) {
            closeFolder();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    public class TypePageAdapter extends FragmentStatePagerAdapter {

        private ArrayList<String> typeList;

        public TypePageAdapter(Context mContext_, FragmentManager fm, ArrayList<String> typeList_) {
            super(fm);
            typeList = typeList_;
        }

        @Override
        public CharSequence getPageTitle(int position) {
            try {
                return typeList.get(position);
            } catch (Exception e) {
                e.printStackTrace();
            }
            return "";
        }

        @Override
        public void setPrimaryItem(ViewGroup container, int position, Object object) {
            super.setPrimaryItem(container, position, object);
        }

        @Override
        public Fragment getItem(int index) {
//            ALog.i(ALog.Tag2, "MainPageAdapter-getItem-index->" + index);
//            return getItemByIndex(index);
            ///return new TextFragment(index+"") ;
            return getItemByIndex(index) ;
        }

        public void setFolder(Folder folder){


        }




        public void refresh(Folder folder, boolean useCamera) {
            if (allMediaPagerShowFragment != null) {
                allMediaPagerShowFragment.refresh(folder.getMedias(),useCamera);
            } else {
                medias = folder.getMedias();
            }
            if (videoMediaPagerShowFragment != null) {
                videoMediaPagerShowFragment.refresh(folder.getVideoMedias(),useCamera);
            } else {
                videoMedias = folder.getVideoMedias();
            }
            if (imageMediaPagerShowFragment != null) {
                imageMediaPagerShowFragment.refresh(folder.getImageMedias(),useCamera);
            } else {
                imageMedias = folder.getImageMedias();
            }
        }


        private ArrayList<Media> medias;
        private ArrayList<Media> videoMedias ;
        private ArrayList<Media> imageMedias;
        MediaPagerShowFragment allMediaPagerShowFragment;
        MediaPagerShowFragment videoMediaPagerShowFragment;
        MediaPagerShowFragment imageMediaPagerShowFragment;

        MediaPagerShowFragment mediaPagerShowFragment_0;
        MediaPagerShowFragment mediaPagerShowFragment_1;
        MediaPagerShowFragment mediaPagerShowFragment_2;

        public MediaPagerShowFragment getItemByIndex(int index) {
            if (type == ImageModel.Type.Video) {
                MediaPagerShowFragment itemFragment = null;
                if (mediaPagerShowFragment_0 == null) {
                    itemFragment = mediaPagerShowFragment_0 = videoMediaPagerShowFragment = new MediaPagerShowFragment();
                    itemFragment.setData(
                            ImageSelectorActivity.this,isSingle,canPreview,mMaxCount);
                    if (videoMedias != null) {
                        itemFragment.refresh(videoMedias,useCamera);
                    }
                }
                return itemFragment;
            } else if (type == ImageModel.Type.Image) {
                MediaPagerShowFragment itemFragment = null;
                if (mediaPagerShowFragment_0 == null) {
                    itemFragment = mediaPagerShowFragment_0 = imageMediaPagerShowFragment = new MediaPagerShowFragment();
                    itemFragment.setData(
                            ImageSelectorActivity.this,isSingle,canPreview,mMaxCount);
                    if (imageMedias != null) {
                        itemFragment.refresh(imageMedias,useCamera);
                    }
                }
                return itemFragment;
            } else {
                return getItemByIndexDo(index);
            }
        }


        public MediaPagerShowFragment getItemByIndexDo(int index) {
            MediaPagerShowFragment itemFragment = null;
            if (index == 0) {
                if (mediaPagerShowFragment_0 == null) {
                    itemFragment = mediaPagerShowFragment_0 = allMediaPagerShowFragment = new MediaPagerShowFragment();
                    if (medias != null) {
                        itemFragment.refresh(medias,useCamera);
                    }
                }
            } else if (index == 1) {
                if (mediaPagerShowFragment_1 == null) {
                    itemFragment = mediaPagerShowFragment_1 = videoMediaPagerShowFragment = new MediaPagerShowFragment();
                    if (videoMedias != null) {
                        itemFragment.refresh(videoMedias,useCamera);
                    }
                }
            } else if (index == 2) {
                if (mediaPagerShowFragment_2 == null) {
                    itemFragment = mediaPagerShowFragment_2 = imageMediaPagerShowFragment = new MediaPagerShowFragment();
                    if (imageMedias != null) {
                        itemFragment.refresh(imageMedias,useCamera);
                    }
                }
            }
            itemFragment.setData(
                    ImageSelectorActivity.this,isSingle,canPreview,mMaxCount);

            return itemFragment;
        }

        @Override
        public int getCount() {
            return typeList.size();
        }

        /*@Override
        public long getItemId(int position) {
            return position;
        }*/

        @Override
        public void notifyDataSetChanged() {
            super.notifyDataSetChanged();
        }

        public void notifyAllChildPager() {
            if (mediaPagerShowFragment_0 != null) {
                mediaPagerShowFragment_0.notifyDataSetChanged();
            }
            if (mediaPagerShowFragment_1 != null) {
                mediaPagerShowFragment_1.notifyDataSetChanged();
            }
            if (mediaPagerShowFragment_2 != null) {
                mediaPagerShowFragment_2.notifyDataSetChanged();
            }
        }

        @Override
        public int getItemPosition(Object object) {
            return super.getItemPosition(object);
        }
    }

    public class SelectAdapter extends RecyclerView.Adapter<SelectAdapter.ViewHolder> {


        ImageSelectorActivity imageSelectorActivity;
        private Context mContext;
        private ArrayList<Media> mImages;
        private LayoutInflater mInflater;

        //保存选中的图片
        private ArrayList<Media> mSelectImages = new ArrayList<>();
        private int mMaxCount;
        private boolean isSingle;

        private static final int TYPE_IMAGE = 2;
        private boolean isAndroidQ = VersionUtils.isAndroidQ();


        public SelectAdapter(ImageSelectorActivity imageSelectorActivity_, int maxCount, boolean isSingle) {
            mContext = imageSelectorActivity_;
            imageSelectorActivity = imageSelectorActivity_;
            this.mInflater = LayoutInflater.from(mContext);
            mMaxCount = maxCount;
            this.isSingle = isSingle;
        }

        @Override
        public SelectAdapter.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = mInflater.inflate(R.layout.adapter_select_item, parent, false);
            return new SelectAdapter.ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(final SelectAdapter.ViewHolder holder, int position) {
            holder.delete.setTag(position);
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


            holder.ivGif.setVisibility(image.isGif() ? View.VISIBLE : View.GONE);
            holder.video_during.setVisibility(image.isVideo() ? View.VISIBLE : View.GONE);
            if (image.isVideo()) {
                holder.video_during.setVisibility(View.VISIBLE);
                long time = image.getDuring();
                //todo 时间转换可以优化
                if (time < 1000) {
                    holder.video_during.setText(android.text.format.DateUtils.formatElapsedTime(1));
                } else {
                    holder.video_during.setText(DateUtils.formatElapsedTime(time / 1000));
                }
            } else {
                holder.video_during.setVisibility(View.GONE);
            }
        }

        @Override
        public int getItemCount() {
            return mImages == null ? 0 : mImages.size();
        }

        public ArrayList<Media> getData() {
            return mImages;
        }

        public void refresh(ArrayList<Media> data) {
            mImages = data;
            notifyDataSetChanged();
        }

        private Media getImage(int position) {
            return mImages.get(position);
        }
        View.OnClickListener deleteClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int position = (Integer) v.getTag();
                if (position >= 0 && position < mImages.size()) {
                    Media lMedia = mImages.get(position);
                    removeMedia(lMedia);
                }
            }
        };

        class ViewHolder extends RecyclerView.ViewHolder {

            ImageView ivImage;
            ImageView ivGif;
            TextView video_during;
            ImageView delete;

            public ViewHolder(View itemView) {
                super(itemView);
                ivImage = itemView.findViewById(R.id.iv_image);
                ivGif = itemView.findViewById(R.id.iv_gif);
                video_during = itemView.findViewById(R.id.video_during);
                delete = itemView.findViewById(R.id.delete);
                delete.setOnClickListener(deleteClickListener);
            }
        }

    }

}
