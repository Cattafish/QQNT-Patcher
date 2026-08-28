package com.tencent.relation.common.api;
import com.tencent.mobileqq.qroute.QRouteApi;
public interface IRelationNTUinAndUidApi extends QRouteApi {
    String getUinFromUid(String uid);
    String getUidFromUin(String uin);
}
