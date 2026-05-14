package com.donkingliang.imageselector2.entry;

import android.net.Uri;
import android.os.Parcel;

public class VideoMedia extends Media{

    public VideoMedia(String path, Uri uri, long addTime_, long modifyTime, String name, String mimeType, long during_) {
        super(path, uri, addTime_, modifyTime, name, mimeType, during_);
        isVideo = true;
    }


}
