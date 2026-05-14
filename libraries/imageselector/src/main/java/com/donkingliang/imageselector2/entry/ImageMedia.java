package com.donkingliang.imageselector2.entry;

import android.net.Uri;

public class ImageMedia extends Media{


    public ImageMedia(String path, Uri uri, long addTime_, long modifyTime, String name, String mimeType, long during_) {
        super(path, uri, addTime_, modifyTime, name, mimeType, during_);
        isVideo = false;
    }


}
