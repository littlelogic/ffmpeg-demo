package com.donkingliang.imageselector2.entry;

import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

/**
 *图片实体类
 */
public class Media implements Parcelable {


    public int index_select = 0;//从1开始
    ////----
    protected boolean isVideo;
    private String path;
    private long addTime;
    private long modifyTime;
    private long during;
    private String name;
    private String mimeType;
    private Uri uri;

    Media(String path, Uri uri, long addTime_, long modifyTime_, String name, String mimeType,long during_ ) {
        this.path = path;
        this.addTime = addTime_;
        this.modifyTime = modifyTime_;
        this.during = during_;
        this.name = name;
        this.mimeType = mimeType;
        this.uri = uri;
    }

    public long getDuring(){
        return during;
    }

    public boolean isVideo(){
        return isVideo;
    }

    public Uri getUri() {
        return uri;
    }

    public void setUri(Uri uri) {
        this.uri = uri;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public long getAddTime() {
        return addTime;
    }

    public void setAddTime(long addTime) {
        this.addTime = addTime;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public boolean isGif(){
        return "image/gif".equals(mimeType);
    }


    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(this.path);
        dest.writeLong(this.addTime);
        dest.writeString(this.name);
        dest.writeString(this.mimeType);
        dest.writeParcelable(this.uri, flags);
    }

    protected Media(Parcel in) {
        this.path = in.readString();
        this.addTime = in.readLong();
        this.name = in.readString();
        this.mimeType = in.readString();
        this.uri = in.readParcelable(Uri.class.getClassLoader());
    }

    public static final Creator<Media> CREATOR = new Creator<Media>() {
        @Override
        public Media createFromParcel(Parcel source) {
            return new Media(source);
        }

        @Override
        public Media[] newArray(int size) {
            return new Media[size];
        }
    };
}
