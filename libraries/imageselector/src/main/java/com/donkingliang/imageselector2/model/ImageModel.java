package com.donkingliang.imageselector2.model;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;
import android.webkit.MimeTypeMap;

import com.donkingliang.imageselector.R;
import com.donkingliang.imageselector2.entry.Folder;
import com.donkingliang.imageselector2.entry.ImageMedia;
import com.donkingliang.imageselector2.entry.Media;
import com.donkingliang.imageselector2.entry.VideoMedia;
import com.donkingliang.imageselector2.utils.ImageUtil;
import com.donkingliang.imageselector2.utils.StringUtils;
import com.donkingliang.imageselector2.utils.UriUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

import androidx.core.content.ContextCompat;

public class ImageModel {

    public enum Type {
        All(3),
        Video(2),
        Image(1);

        int value;

        Type (int value){
            this.value = value;
        }

        public int value(){
            return value;
        }

        public static Type value(int value){
            if (value == Video.value) {
                return Video;
            } else if (value == Image.value) {
                return Image;
            } else /*if (value == All.value()) */{
                return All;
            }
        }
    }

    /**
     * 缓存图片
     */
    private static ArrayList<Folder> cacheImageList = null;
    private static boolean isNeedCache = false;
    private static PhotoContentObserver observer;

    /**
     * 预加载图片
     *
     * @param context
     */
    public static void preloadAndRegisterContentObserver(final Context context) {
        isNeedCache = true;
        if (observer == null) {
            observer = new PhotoContentObserver(context.getApplicationContext());
            context.getApplicationContext().getContentResolver().registerContentObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer);
        }
        preload(context);
    }

    private static void preload(final Context context) {
        int hasWriteExternalPermission = ContextCompat.checkSelfPermission(context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (hasWriteExternalPermission == PackageManager.PERMISSION_GRANTED) {
            //有权限，加载图片。
            loadImageForSDCard(context, true, Type.All,null);
        }
    }

    /**
     * 清空缓存
     */
    public static void clearCache(Context context) {
        isNeedCache = false;
        if (observer != null) {
            context.getApplicationContext().getContentResolver().unregisterContentObserver(observer);
            observer = null;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                synchronized (ImageModel.class) {
                    if (cacheImageList != null) {
                        cacheImageList.clear();
                        cacheImageList = null;
                    }
                }
            }
        }).start();
    }

    /**
     * 从SDCard加载图片
     *
     * @param context
     * @param callback
     */
    public static void loadImageForSDCard(final Context context, final ImageModel.Type type,final DataCallback callback) {
        loadImageForSDCard(context, false, type,callback);
    }

    /**
     * 从SDCard加载图片
     *
     * @param context
     * @param isPreload 是否是预加载
     * @param callback
     */
    private static void loadImageForSDCard(final Context context, final boolean isPreload,final ImageModel.Type type, final DataCallback callback) {
        //由于扫描图片是耗时的操作，所以要在子线程处理。
        new Thread(new Runnable() {
            @Override
            public void run() {
                synchronized (ImageModel.class) {
                    String imageCacheDir = ImageUtil.getImageCacheDir(context);
                    ArrayList<Folder> folders = null;
                    if (cacheImageList == null || isPreload) {
                        ArrayList<Folder> folders_post = new ArrayList<>();
                        ArrayList<Media> imageList = loadImage(context,type,folders_post);
                        if (false) {
                            Collections.sort(imageList, new Comparator<Media>() {
                                @Override
                                public int compare(Media image, Media t1) {
                                    if (image.getAddTime() > t1.getAddTime()) {
                                        return 1;
                                    } else if (image.getAddTime() < t1.getAddTime()) {
                                        return -1;
                                    } else {
                                        return 0;
                                    }
                                }
                            });
                        }
                        ArrayList<Media> images = new ArrayList<>();

                        Log.i("q2","-231016p3w-ImageModel-loadImageForSDCard-31");
                        if (false) {
                            for (Media image : imageList) {
                                // 过滤不存在或未下载完成的图片
                                boolean exists = !"downloading".equals(getExtensionName(image.getPath())) && checkImgExists(image.getPath());
                                //过滤剪切保存的图片；
                                boolean isCutImage = ImageUtil.isCutImage(imageCacheDir, image.getPath());
                                if (!isCutImage && exists) {
                                    images.add(image);
                                }
                            }
                        }
                        Log.i("q2","-231016p3w-ImageModel-loadImageForSDCard-41");
                        if (false) {
                            Collections.reverse(images);
                            folders = splitFolder(context, images);
                        }
                        folders = folders_post;
                        Log.i("q2","-231016p3w-ImageModel-loadImageForSDCard-44");
                        if (isNeedCache) {
                            cacheImageList = folders;
                        }
                    } else {
                        folders = cacheImageList;
                    }

                    if (callback != null) {
                        callback.onSuccess(folders);
                    }
                }
            }
        }).start();
    }

    private static final Uri QUERY_URI = MediaStore.Files.getContentUri("external");


    private static final String[] PROJECTION = {
            MediaStore.Files.FileColumns._ID,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,

            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.Video.VideoColumns.DURATION
    };

    private static final String SELECTION =
            "(" + MediaStore.Files.FileColumns.MEDIA_TYPE + "=?"
                    + " OR "
                    + MediaStore.Files.FileColumns.MEDIA_TYPE + "=?)"
                    + " AND " + MediaStore.MediaColumns.SIZE + ">0";
    private static final String SELECTION_B =
            "(" + MediaStore.Files.FileColumns.MEDIA_TYPE + "=?"
                    + " OR "
                    + MediaStore.Files.FileColumns.MEDIA_TYPE + "=?)"
                    + " AND " + MediaStore.MediaColumns.SIZE + ">10240";


    private static final String[] SELECTION_ARGS = {
            String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE),
            String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO),
    };
    private static final String[] SELECTION_ARGS_IMAGE = {
            String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE)
    };
    private static final String[] SELECTION_ARGS_VIDEO = {
            String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
    };

    private static final String BUCKET_ORDER_BY = "datetaken DESC";
    private static final String ADDED_ORDER_BY = MediaStore.Images.Media.DATE_ADDED + " DESC";

    /**
     * 从SDCard加载图片
     *
     * @param context
     * @return
     */
    private static synchronized ArrayList<Media> loadImage(Context context,ImageModel.Type type, ArrayList<Folder> folders) {
        Log.i("q2","-231016p3w-ImageModel-loadImage-01");
        //扫描图片
        ContentResolver mContentResolver = context.getContentResolver();
        Cursor mCursor = null;
        String[] select_mark = SELECTION_ARGS;
        if (type == Type.Image) {
            select_mark = SELECTION_ARGS_IMAGE;
        } else if (type == Type.Video) {
            select_mark = SELECTION_ARGS_VIDEO;
        } else {
            select_mark = SELECTION_ARGS;
        }
        if (false) {
            Uri mImageUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            mCursor = mContentResolver.query(mImageUri, new String[]{
                            MediaStore.Images.Media._ID,
                            MediaStore.Images.Media.DATA,
                            MediaStore.Images.Media.DISPLAY_NAME,
                            MediaStore.Images.Media.DATE_ADDED,
                            MediaStore.Images.Media.MIME_TYPE,
                            MediaStore.Images.Media.SIZE},
                    MediaStore.MediaColumns.SIZE + ">0",
                    null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC");
        }
        mCursor = mContentResolver.query(
                QUERY_URI,
                PROJECTION,
                SELECTION,
                select_mark,
                ADDED_ORDER_BY);

        HashMap<Integer,Folder>  albumList = new HashMap<>();
        ////SparseArray<Folder> albumList = new SparseArray<>();
        ArrayList<Media> mediasAll = new ArrayList<>(mCursor.getCount());
        ArrayList<Media> videosAll = new ArrayList<>(mCursor.getCount()/2);
        ArrayList<Media> imagesAll = new ArrayList<>(mCursor.getCount()/2);
        String imageCacheDir = ImageUtil.getImageCacheDir(context);
        Log.i("q2","-231016p3w-ImageModel-loadImage-02-"
                + " mCursor.getCount():"+mCursor.getCount()
                + " imageCacheDir:"+imageCacheDir
        );
        //读取扫描到的图片
        if (mCursor != null) {
            Log.i("q2","-231016p3w-ImageModel-loadImage-31- mCursor.getPosition():"+ mCursor.getPosition());
            mCursor.moveToFirst();
            int bucketId_last = -1213908;
            Folder album = null;
            /**
             * MediaStore.Images.Media.BUCKET_ID, // 直接包含该图片文件的文件夹ID，防止在不同下的文件夹重名
             * MediaStore.Images.Media.BUCKET_DISPLAY_NAME, // 直接包含该图片文件的文件夹名
             * MediaStore.Images.Media.DISPLAY_NAME, // 图片文件名
             * MediaStore.Images.Media.DATA, // 图片绝对路径
             */
            ///-MediaStore.Video.Media._ID
            // todo 和 PROJECTION 相对应
            int index__ID = mCursor.getColumnIndex(MediaStore.MediaColumns._ID);
            int index_MIME_TYPE = mCursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE);

            int index_SIZE = mCursor.getColumnIndex(MediaStore.MediaColumns.SIZE);
            int index_BUCKET_ID = mCursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_ID);
            int index_DISPLAY_NAME = mCursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);

            int index_DATA = mCursor.getColumnIndex(MediaStore.MediaColumns.DATA);
            int index_DATE_ADDED = mCursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED);
            int index_DATE_MODIFIED = mCursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED);
            int index_WIDTH = mCursor.getColumnIndex(MediaStore.MediaColumns.WIDTH);
            int index_HEIGHT = mCursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT);
            int index_DURATION = mCursor.getColumnIndex(MediaStore.MediaColumns.DURATION);
            Log.i("q2","-231016p3w-ImageModel-loadImage-32- mCursor.getPosition():"+ mCursor.getPosition());
            mCursor.moveToPrevious();
            Log.i("q2","-231016p3w-ImageModel-loadImage-33- mCursor.getPosition():"+ mCursor.getPosition());
            while (mCursor.moveToNext()) {
                String path = mCursor.getString(index_DATA);
                {
                    // 过滤不存在或未下载完成的图片
                    ///boolean exists = !"downloading".equals(getExtensionName(path)) && checkImgExists(path);
                    ///todo  文件读取，多了耗时 checkImgExists(path)
                    boolean exists = !"downloading".equals(getExtensionName(path));
                    //过滤剪切保存的图片；
                    boolean isCutImage = ImageUtil.isCutImage(imageCacheDir, path);
                    if (isCutImage || !exists) {
                        continue;
                    }
                }
                // 获取图片的路径
                long id = mCursor.getLong(index__ID);
                //获取图片名称
                String name = mCursor.getString(index_DISPLAY_NAME);
                //获取图片时间
                long time = mCursor.getLong(index_DATE_ADDED);
                /*if (String.valueOf(time).length() < 13) {
                    time *= 1000;
                }*/

                /*if (index_DATE_MODIFIED == -1) {
                    Log.i("q2","-231016p3w-ImageModel-loadImage-39- "
                            +" index_DATE_MODIFIED:"+ index_DATE_MODIFIED
                            +" path:"+ path
                            +" name:"+ name
                            +" time:"+ time
                    );
                }*/

                long time_modify = mCursor.getLong(index_DATE_MODIFIED);
                long during = mCursor.getLong(index_DURATION);

                //获取图片类型
                String mimeType = mCursor.getString(index_MIME_TYPE);
                boolean isVideo = false;
                if (mimeType == null) {
                    Log.e("q2","-231016p3w-ImageModel-loadImage-64-error mimeType:"+ mimeType);
                    isVideo = checkVideo(path);
                } else if (mimeType.startsWith("image/")) {
                    isVideo = false;
                } else if (mimeType.startsWith("video/")) {
                    isVideo = true;
                } else {
                    Log.e("q2","-231016p3w-ImageModel-loadImage-65-error mimeType:"+ mimeType);
                    isVideo = checkVideo(path);
                }
                //获取图片uri
                Uri uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI.buildUpon()
                        .appendPath(String.valueOf(id)).build();
                ///------------
                int bucketId = mCursor.getInt(index_BUCKET_ID);
                if (bucketId != bucketId_last) {
                    //todo 这个地方改成map，效率会如何 ？
                    album = albumList.get(bucketId);
                    if (album == null) {
                        bucketId_last = bucketId;
                        String lFolderName = getFolderName(path);
                        album = new Folder(lFolderName,bucketId, path);
                        ///albumList.append(bucketId, album);
                        albumList.put(bucketId,album);
                        folders.add(album);
                    }
                }

                if (isVideo) {
                    VideoMedia videoMedia = new VideoMedia(path,uri, time,time_modify, name, mimeType,during);
                    album.addVideo(videoMedia);
                    album.addMedia(videoMedia);//---
                    videosAll.add(videoMedia);///---
                    mediasAll.add(videoMedia);//---
                } else {
                    ImageMedia imageMedia = new ImageMedia(path,uri, time,time_modify, name, mimeType,during);
                    album.addImage(imageMedia);
                    album.addMedia(imageMedia);//---
                    imagesAll.add(imageMedia);//---
                    mediasAll.add(imageMedia);//---
                }
            }
            mCursor.close();
            folders.add(0,new Folder(context.getString(R.string.selector_all_image),-11,"/", mediasAll,videosAll,imagesAll));
        }
        Log.i("q2","-231016p3w-ImageModel-loadImage-82");
        return mediasAll;
    }

    /**
     * 检查图片是否存在。ContentResolver查询处理的数据有可能文件路径并不存在。
     *
     * @param filePath
     * @return
     */
    private static boolean checkImgExists(String filePath) {
        return new File(filePath).exists();
        ////return true;
    }


        public static boolean isImage(String mimeType) {
        if (mimeType == null) return false;
        return mimeType.equals("image/jpg")
                || mimeType.equals("image/jpeg")
                || mimeType.equals(MimeType.GIF.toString())
                || mimeType.equals(MimeType.PNG.toString());
    }

    public static boolean isVideo(String mimeType) {
        if (mimeType == null)
            return false;
        return mimeType.equals(MimeType.MP4.toString())
                || mimeType.equals(MimeType.QUICKTIME.toString())
                || mimeType.equals(MimeType.WEBM.toString())
                || mimeType.equals(MimeType.AVI.toString())
                || mimeType.equals(MimeType.WMV.toString())
                || mimeType.equals(MimeType.MKV.toString());
    }

    //获取文件的mimetype
    public String getMimeType(File file){
        String suffix = getSuffix(file);
        if (suffix == null) {
            return "file/*";
        }
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(suffix);
        if (type != null && !type.isEmpty()) {
            return type;
        }
        return "file/*";
    }

    public static boolean checkVideo(String path){
        if (path == null) {
            return false;
        }
        String suffix = null;
        try {
            suffix = path.substring(path.lastIndexOf(".") + 1);
        } catch (Exception e) {
            e.printStackTrace();
        }
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(suffix);
        if (type != null && !type.isEmpty()) {
            return  !isImage(type);
        }
        return false;
    }

    private String getSuffix(File file) {
        if (file == null || !file.exists() || file.isDirectory()) {
            return null;
        }
        String fileName = file.getName();
        if (fileName.equals("") || fileName.endsWith(".")) {
            return null;
        }
        int index = fileName.lastIndexOf(".");
        if (index != -1) {
            return fileName.substring(index + 1).toLowerCase(Locale.US);
        } else {
            return null;
        }
    }

    private static String getPathForAndroidQ(Context context, long id) {
        return UriUtils.getPathForUri(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI.buildUpon()
                .appendPath(String.valueOf(id)).build());
    }

    /**
     * 把图片按文件夹拆分，第一个文件夹保存所有的图片
     *
     * @param images
     * @return
     */
    private static ArrayList<Folder> splitFolder(Context context, ArrayList<Media> images) {
        Log.i("q2","-231016p3w-ImageModel-splitFolder-10");

        ArrayList<Folder> folders = new ArrayList<>();
        folders.add(new Folder(context.getString(R.string.selector_all_image),-11,"/", images
                ,new ArrayList<Media>(),new ArrayList<Media>()));

        if (images != null && !images.isEmpty()) {
            Log.i("q2","-231016p3w-ImageModel-splitFolder-images.size():"+images.size());
            int size = images.size();
            for (int i = 0; i < size; i++) {
                String path = images.get(i).getPath();
                String name = getFolderName(path);
                if (StringUtils.isNotEmptyString(name)) {
                    Folder folder = getFolder(name, folders);
                    folder.addMedia(images.get(i));
                }
            }
        }
        Log.i("q2","-231016p3w-ImageModel-splitFolder-89");
        return folders;
    }

    /**
     * Java文件操作 获取文件扩展名
     */
    public static String getExtensionName(String filename) {
        if (filename != null && filename.length() > 0) {
            int dot = filename.lastIndexOf('.');
            if (dot > -1 && dot < filename.length() - 1) {
                return filename.substring(dot + 1);
            }
        }
        return "";
    }

    /**
     * 根据图片路径，获取图片文件夹名称
     *
     * @param path
     * @return
     */
    private static String getFolderName(String path) {
        if (StringUtils.isNotEmptyString(path)) {
            String[] strings = path.split(File.separator);
            if (strings.length >= 2) {
                return strings[strings.length - 2];
            }
        }
        return "";
    }

    private static Folder getFolder(String name, List<Folder> folders) {
        if (!folders.isEmpty()) {
            int size = folders.size();
            for (int i = 0; i < size; i++) {
                Folder folder = folders.get(i);
                if (name.equals(folder.getName())) {
                    return folder;
                }
            }
        }
        Folder newFolder = new Folder(name,11,"00");
        folders.add(newFolder);
        return newFolder;
    }

    public interface DataCallback {
        void onSuccess(ArrayList<Folder> folders);
    }

    private static class PhotoContentObserver extends ContentObserver {

        private Context context;

        public PhotoContentObserver(Context appContext) {
            super(null);
            context = appContext;
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            preload(context);
        }
    }
}
