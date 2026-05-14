/*
 * Copyright (C) 2014 nohana, Inc.
 * Copyright 2017 Zhihu Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an &quot;AS IS&quot; BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.donkingliang.imageselector2.model;

import androidx.collection.ArraySet;

import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * MIME Type enumeration to restrict selectable media on the selection activity. Matisse only supports images and
 * videos.
 * <p>
 * Good example of mime types Android supports:
 * https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/media/java/android/media/MediaFile.java
 */
@SuppressWarnings("unused")
public enum MimeType {

    // ============== images ==============
    JPG("image/jpg"),
    JPEG("image/jpeg"),
    PNG("image/png"),
    GIF("image/gif"),
    HEIF("image/heif"),
    HEIC("image/heic"),
//    BMP("image/x-ms-bmp"),
//    WEBP("image/webp"),

    // ============== videos ==============
//    MPEG("video/mpeg"),
    MP4("video/mp4"),
    QUICKTIME("video/quicktime"),
    //    THREEGPP("video/3gpp", arraySetOf(
//            "3gp",
//            "3gpp"
//    )),
//    THREEGPP2("video/3gpp2", arraySetOf(
//            "3g2",
//            "3gpp2"
//    )),
    MKV("video/x-matroska"),

    WEBM("video/webm"),
    //    TS("video/mp2ts", arraySetOf(
//            "ts"
//    )),
    AVI("video/avi"),

    WMV("video/x-ms-wmv");

    private final String mMimeTypeName;

    MimeType(String mimeTypeName) {
        mMimeTypeName = mimeTypeName;
    }

    public static Set<MimeType> ofAll() {
        return EnumSet.allOf(MimeType.class);
    }

    public static Set<MimeType> of(MimeType type, MimeType... rest) {
        return EnumSet.of(type, rest);
    }

    public static Set<MimeType> ofImage() {
        return EnumSet.of(JPEG, PNG, GIF);
    }

    public static Set<MimeType> ofVideo() {
        return EnumSet.of(MP4, QUICKTIME, WEBM, AVI, WMV);
    }

    public static boolean isImage(String mimeType) {
        if (mimeType == null) return false;
        return mimeType.equals(MimeType.JPG.toString())
                || mimeType.equals(MimeType.JPEG.toString())
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


    public static boolean isHEIF(String mimeType) {
        if (mimeType == null) return false;
        return mimeType.equals(MimeType.HEIC.toString())
                || mimeType.equals(MimeType.HEIF.toString());
    }

    public static boolean isGif(String mimeType) {
        if (mimeType == null) return false;
        return mimeType.equals(MimeType.GIF.toString());
    }

    private static Set<String> arraySetOf(String... suffixes) {
        return new ArraySet<>(Arrays.asList(suffixes));
    }

    @Override
    public String toString() {
        return mMimeTypeName;
    }

    public static String guessMimeTypeWithImageFile(String fileName){
        String mimeType = null;
        try {
            File file = new File(fileName);
            //打开文件输入流
            FileInputStream inputStream = new FileInputStream(file);
            int byteToRead = 16;
            byte[] buffer = new byte[byteToRead];
            int len = inputStream.read(buffer);
            if(len == byteToRead){
                if((buffer[0] & 0xff) == 0xff){
//                    ALog.d("[zsu]", "Mime type jpeg");
                    mimeType = MimeType.JPEG.toString();
                }else if((buffer[0] & 0x89) == 0x89){
//                    ALog.d("[zsu]", "Mime type png");
                    mimeType = MimeType.PNG.toString();
                }else {
                    switch ((int) buffer[0]) {
                        case 0x47: {
//                            ALog.d("[zsu]", "Mime type gif");
                            mimeType = MimeType.GIF.toString();
                            break;
                        }
                        case 0x49:
                        case 0x4D: {
//                            ALog.d("[zsu]", "Mime type tiff");
                            break;
                        }
                        case 0x00: {
                            String metaStr = new String(buffer, 4, 8);
                            if (metaStr.equalsIgnoreCase("ftypheic") ||
                                    metaStr.equalsIgnoreCase("ftypheix") ||
                                    metaStr.equalsIgnoreCase("ftyphevc") ||
                                    metaStr.equalsIgnoreCase("ftyphevx")) {
//                                ALog.d("[zsu]", "Mime type heic");
                                mimeType = MimeType.HEIF.toString();
                            }
                            break;
                        }
                    }
                }
            }
            //关闭输入流
            inputStream.close();
        }catch (Exception ex){
            ex.printStackTrace();
        }
        return mimeType;
    }
//    public boolean checkType(ContentResolver resolver, Uri uri) {
//        MimeTypeMap map = MimeTypeMap.getSingleton();
//        if (uri == null) {
//            return false;
//        }
//        String type = map.getExtensionFromMimeType(resolver.getType(uri));
//        String path = null;
//        // lazy load the path and prevent resolve for multiple times
//        boolean pathParsed = false;
//        for (String extension : mExtensions) {
//            if (extension.equals(type)) {
//                return true;
//            }
//            if (!pathParsed) {
//                // we only resolve the path for one time
//                path = PhotoMetadataUtils.getPath(resolver, uri);
//                if (!TextUtils.isEmpty(path)) {
//                    path = path.toLowerCase(Locale.US);
//                }
//                pathParsed = true;
//            }
//            if (path != null && path.endsWith(extension)) {
//                return true;
//            }
//        }
//        return false;
//    }
}
