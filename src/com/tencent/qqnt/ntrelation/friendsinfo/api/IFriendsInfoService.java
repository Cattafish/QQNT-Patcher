package com.tencent.qqnt.ntrelation.friendsinfo.api;
import com.tencent.mobileqq.qroute.QRouteApi;
public interface IFriendsInfoService extends QRouteApi {
    String getNickWithUid(String uid, String defaultValue);
    String getRemarkWithUid(String uid, String defaultValue);
}
