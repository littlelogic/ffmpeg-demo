package com.donkingliang.imageselector2.entry;


import com.donkingliang.imageselector2.utils.StringUtils;

import java.util.ArrayList;

/**
 * 图片文件夹实体类
 */
public class Folder {

    private boolean useCamera; // 是否可以调用相机拍照。只有“全部”文件夹才可以拍照
    private String name;
    private ArrayList<Media> medias = new ArrayList<>();
//    private ArrayList<ImageMedia> imageMedias = new ArrayList<>();
//    private ArrayList<VideoMedia> videoMedias = new ArrayList<>();

    private ArrayList<Media> videoMedias = new ArrayList<>();
    private ArrayList<Media> imageMedias = new ArrayList<>();

//    public Folder(String name) {
//        this.name = name;
//    }

//    public Folder(String name, ArrayList<Media> images) {
//        this.name = name;
//        this.images = images;
//    }

    private String path;
    private int id;

    public Folder(String name, int id, String path ) {
        this.name = name;
        this.id = id;
        this.path = path;
    }

//    public Folder(Folder lFolder) {
//        this.useCamera = lFolder.useCamera;
//        this.name = lFolder.name;
//        this.id = lFolder.id;
//        this.path = lFolder.path;
//        this.medias = lFolder.medias;
//        this.imageMedias = lFolder.imageMedias;
//        this.videoMedias = lFolder.videoMedias;
//    }




    public Folder(String name, int id, String path, ArrayList<Media> medias,
                  ArrayList<Media> videoMedias_, ArrayList<Media> imageMedias_) {
        this.name = name;
        this.id = id;
        this.path = path;
        this.medias = medias;
        this.videoMedias = videoMedias_;
        this.imageMedias = imageMedias_;
    }

    public boolean isUseCamera() {
        return useCamera;
    }

    public void setUseCamera(boolean useCamera) {
        this.useCamera = useCamera;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ArrayList<Media> getMedias() {
        return medias;
    }

    public ArrayList<Media> getImageMedias() {
        return imageMedias;
//        return medias;
    }

    public ArrayList<Media> getVideoMedias() {
        return videoMedias;
//        return medias;
    }

    public void addMedia(Media image) {
        /*if (image != null && StringUtils.isNotEmptyString(image.getPath())) {
            medias.add(image);
        }*/
        medias.add(image);
    }

    public void addImage(ImageMedia image) {
        imageMedias.add(image);
    }

    public void addVideo(VideoMedia image) {
        videoMedias.add(image);
    }



    @Override
    public String toString() {
        return "Folder{" +
                "name='" + name + '\'' +
                ", images=" + medias +
                '}';
    }
}
