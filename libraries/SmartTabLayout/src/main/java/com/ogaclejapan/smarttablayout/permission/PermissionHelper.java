package com.ogaclejapan.smarttablayout.permission;

import android.Manifest;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;

import java.util.List;

public class PermissionHelper {


    static enum Type {
        // 相机权限
        CAMERA,
        // 麦克风权限
        AUDIO,
        // 相册权限
        STORAGE,
    }


    /*public static void requestPermission(
            FragmentActivity activity,
            Type permissionType,
            Runnable granted
            ){
        if (false) {
            if (PermissionX.isGranted(activity, Manifest.permission.CAMERA)) {
                // 相机权限已授予
            } else {
                // 相机权限未授予，需要申请
            }
        }
        List<String> permissionsStr = null;


        PermissionX.init(activity).permissions(permissionsStr)
                .onExplainRequestReason(new ExplainReasonCallback() {
                    @Override
                    public void onExplainReason(ExplainScope scope, List<String> deniedList) {
                        /// 当用户拒绝过权限时，会回调此方法，你可以在这里向用户解释为什么需要这些权限
                        scope.showRequestReasonDialog(deniedList, "需要相机权限来拍照", "确定", "取消");
                    }
                })
                .onExplainRequestReason(new ExplainReasonCallbackWithBeforeParam() {
                    @Override
                    public void onExplainReason(ExplainScope scope, List<String> deniedList, boolean beforeRequest) {

                    }
                })
                .onForwardToSettings(new ForwardToSettingsCallback() {
                    @Override
                    public void onForwardToSettings(ForwardScope scope, List<String> deniedList) {
                        /// 当用户拒绝权限且选择"不再询问"时，会回调此方法，你可以引导用户去设置页开启权限
                        scope.showForwardToSettingsDialog(deniedList, "请在设置中开启以下权限", "确定", "取消");
                    }
                })
                .request(new RequestCallback() {
                    @Override
                    public void onResult(boolean allGranted, List<String> grantedList, List<String> deniedList) {
                        if (allGranted) {
//                            Toast.makeText(FileExplorerActivity.this, "All permissions are granted", Toast.LENGTH_LONG).show();
                            if(granted != null) {
                                granted.run();
                            }
                        } else {
//                            Toast.makeText(FileExplorerActivity.this, "These permissions are denied: $deniedList:"+deniedList, Toast.LENGTH_LONG).show();
                        }
                    }
                });

    }*/


}
